"""LLM 统一客户端

设计原则
--------
**所有大模型调用必须经过这一层，业务代码不得直接发起 HTTP 请求。**

集中在这一层处理的事情：
1. 鉴权与端点拼接（DashScope OpenAI 兼容模式）
2. 超时、重试、模型降级（额度耗尽/限流时自动切换备用模型）
3. 三种调用形态：阻塞 invoke / 流式 stream / 工具调用 invoke_with_tools
4. 结构化输出：通过 Function Calling 承载 Pydantic Schema
5. Token 计量与耗时日志（成本可观测）
"""

from __future__ import annotations

import json
import logging
import re
import time
from typing import Any, Dict, Iterator, List, Optional, Type

import httpx
from pydantic import BaseModel, ValidationError

from app.core.config import AI_API_KEY, AI_BASE_URL, AI_MODEL
from app.schemas.ai_output import build_tool_schema, get_output_model

logger = logging.getLogger("couple.llm")

#: 主模型额度耗尽或限流时，依次尝试的备用模型
FALLBACK_MODELS: List[str] = ["qwen-plus", "deepseek-v3", "qwen-turbo"]

#: 单次请求超时。qwen3.7-flash 这类推理模型会先产出思考内容，
#: 实测单次结构化调用约 30s，长 prompt 更久，故留足余量。
_REQUEST_TIMEOUT = 120.0
_TOOL_NAME = "submit_structured_answer"

#: 命中这些错误码说明是模型侧不可用，应降级到下一个模型
_MODEL_UNAVAILABLE_CODES = (
    "AllocationQuota.FreeTierOnly",
    "Arrearage",
    "Throttling.RateQuota",
    "Throttling.AllocationQuota",
    "ModelNotExist",
    "InvalidParameter.Model",
)


class LlmError(RuntimeError):
    """LLM 调用异常（已完成重试与降级仍然失败）"""


class LlmClient:
    """大模型统一客户端。模块级单例见文件底部 `llm`。"""

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        fallbacks: Optional[List[str]] = None,
    ) -> None:
        self.api_key = api_key or AI_API_KEY
        self.base_url = (base_url or AI_BASE_URL).rstrip("/")
        self.model = model or AI_MODEL
        self.fallbacks = fallbacks if fallbacks is not None else FALLBACK_MODELS

        if not self.api_key:
            logger.warning("未配置 AI_API_KEY，AI 功能将不可用")

    # ------------------------------------------------------------------ #
    # 公开接口
    # ------------------------------------------------------------------ #
    def invoke(
        self,
        messages: List[Dict[str, Any]],
        temperature: float = 0.7,
        max_tokens: int = 2000,
        scene: str = "unknown",
    ) -> str:
        """阻塞调用，返回完整文本。"""
        payload = {
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        resp = self._request(payload, scene=scene)
        return self._first_text(resp)

    def stream(
        self,
        messages: List[Dict[str, Any]],
        temperature: float = 0.7,
        max_tokens: int = 2000,
        scene: str = "unknown",
    ) -> Iterator[str]:
        """流式调用，逐块产出文本增量（供 SSE 使用）。

        与 `invoke()` 的差异：
        - `stream()` 走的是 `client.stream()`，无法复用 `_request()` 的请求封装，
          因此**模型降级逻辑必须在这里再实现一遍**（曾经漏掉 `model` 字段，
          线上会直接 400 "you must provide a model parameter"）。
        - 只有在**尚未吐出任何增量**时才允许切换模型；一旦有内容发给前端了，
          再换模型会导致前缀重复，此时只能报错。
        """
        last_error: Optional[str] = None

        for model in self._candidates():
            payload = {
                "messages": messages,
                "model": model,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "stream": True,
            }
            started = time.time()
            chunks = 0
            try:
                with httpx.Client(timeout=_REQUEST_TIMEOUT) as client:
                    with client.stream(
                        "POST", self._url(), headers=self._headers(), json=payload
                    ) as resp:
                        if resp.status_code != 200:
                            body = resp.read().decode("utf-8", errors="replace")
                            code = self._stream_error_code(body)
                            last_error = "HTTP %s: %s" % (resp.status_code, body[:200])
                            if code in _MODEL_UNAVAILABLE_CODES or resp.status_code in (429, 503):
                                logger.warning(
                                    "[LLM] 流式模型 %s 不可用[%s]，降级到下一个模型", model, code
                                )
                                continue
                            raise LlmError("流式调用失败 HTTP %s: %s" % (resp.status_code, last_error))

                        for line in resp.iter_lines():
                            if not line:
                                continue
                            if line.startswith("data:"):
                                line = line[5:].strip()
                            if line == "[DONE]":
                                break
                            try:
                                event = json.loads(line)
                            except ValueError:
                                continue
                            delta = self._extract_delta(event)
                            if delta:
                                chunks += 1
                                yield delta
            except httpx.HTTPError as exc:
                last_error = "网络异常: %s" % exc
                if chunks == 0:
                    logger.warning("[LLM] 流式 %s 请求异常，尝试下一个模型: %s", model, exc)
                    continue
                raise LlmError("流式中断且已推送部分内容: %s" % exc) from exc
            finally:
                logger.info(
                    "[LLM-STREAM] scene=%s model=%s chunks=%d cost=%.2fs",
                    scene, model, chunks, time.time() - started,
                )
            return

        raise LlmError("所有候选模型均不可用(流式): %s" % last_error)

    def invoke_with_tools(
        self,
        messages: List[Dict[str, Any]],
        tools: List[Dict[str, Any]],
        temperature: float = 0.3,
        max_tokens: int = 2000,
        scene: str = "agent",
    ) -> Dict[str, Any]:
        """工具调用，返回原始 message 结构（含 tool_calls），供 Agent 循环使用。"""
        payload = {
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        resp = self._request(payload, scene=scene)
        return resp["choices"][0]["message"]

    def invoke_structured(
        self,
        messages: List[Dict[str, Any]],
        output_model: Optional[Type[BaseModel]] = None,
        scene: str = "unknown",
        temperature: float = 0.7,
        max_tokens: int = 2000,
    ) -> BaseModel:
        """结构化调用：返回经 Pydantic 校验的模型实例。

        实现路径（Function Calling 承载 Schema）：
            Pydantic 模型 → tools 定义
              → 模型调用 submit_structured_answer 并回填参数
              → JSON 反序列化 → model_validate 强校验

        三级降级：
            ① 正常 tool_calls 解析
            ② 提高约束重试一次（temperature 降到 0.2）
            ③ 退回 prompt-JSON 文本解析
        """
        model_cls = output_model or get_output_model(scene)
        tool = build_tool_schema(model_cls, tool_name=_TOOL_NAME)

        # ① 首轮：明确要求模型必须调用工具
        armed_messages = self._arm_tool_messages(messages)
        try:
            payload = {
                "messages": armed_messages,
                "tools": [tool],
                "tool_choice": {"type": "function", "function": {"name": _TOOL_NAME}},
                "temperature": temperature,
                "max_tokens": max_tokens,
            }
            resp = self._request(payload, scene=scene)
            message = resp["choices"][0]["message"]
            data = self._extract_tool_arguments(message)
            if data is not None:
                return self._validate(model_cls, data, scene)
        except (LlmError, ValidationError, ValueError, KeyError) as exc:
            logger.warning("[LLM] 结构化首轮失败 scene=%s: %s", scene, exc)

        # ② 重试：降低温度，强化指令
        try:
            retry_messages = armed_messages + [{
                "role": "user",
                "content": "上一次未按要求提交。请严格调用 submit_structured_answer 工具，"
                           "并且必须填写所有必填字段。",
            }]
            payload = {
                "messages": retry_messages,
                "tools": [tool],
                "tool_choice": {"type": "function", "function": {"name": _TOOL_NAME}},
                "temperature": 0.2,
                "max_tokens": max_tokens,
            }
            resp = self._request(payload, scene=scene)
            data = self._extract_tool_arguments(resp["choices"][0]["message"])
            if data is not None:
                return self._validate(model_cls, data, scene)
        except (LlmError, ValidationError, ValueError, KeyError) as exc:
            logger.warning("[LLM] 结构化重试失败 scene=%s: %s", scene, exc)

        # ③ 兜底：要求模型以纯 JSON 文本回复，再手工解析
        try:
            json_messages = self._json_fallback_messages(messages, model_cls)
            text = self.invoke(json_messages, temperature=0.2, max_tokens=max_tokens, scene=scene)
            data = self._extract_json_block(text)
            if data is not None:
                return self._validate(model_cls, data, scene)
        except (LlmError, ValidationError, ValueError) as exc:
            logger.error("[LLM] 结构化兜底解析失败 scene=%s: %s", scene, exc)

        raise LlmError("结构化输出解析失败（已尝试 tool_calls → 重试 → JSON 兜底）")

    # ------------------------------------------------------------------ #
    # 内部实现
    # ------------------------------------------------------------------ #
    def _url(self) -> str:
        return "%s/chat/completions" % self.base_url

    def _headers(self) -> Dict[str, str]:
        return {
            "Authorization": "Bearer %s" % self.api_key,
            "Content-Type": "application/json",
        }

    def _candidates(self) -> List[str]:
        """待尝试的模型序列：主模型优先，备用模型去重后依次跟上。"""
        models = [self.model]
        for name in self.fallbacks:
            if name not in models:
                models.append(name)
        return models

    def _request(self, payload: Dict[str, Any], scene: str = "unknown") -> Dict[str, Any]:
        """带模型降级与重试的底层请求。"""
        last_error: Optional[str] = None

        for model in self._candidates():
            body = dict(payload, model=model)
            started = time.time()
            try:
                with httpx.Client(timeout=_REQUEST_TIMEOUT) as client:
                    resp = client.post(self._url(), headers=self._headers(), json=body)
            except httpx.HTTPError as exc:
                last_error = "网络异常: %s" % exc
                logger.warning("[LLM] %s 请求异常，尝试下一个模型: %s", model, exc)
                continue

            elapsed = time.time() - started

            if resp.status_code == 200:
                data = resp.json()
                self._log_usage(scene, model, data, elapsed)
                return data

            # 解析错误体，判断是否值得换模型
            code, message = self._parse_error(resp)
            last_error = "%s (%s)" % (message, code)

            if code in _MODEL_UNAVAILABLE_CODES or resp.status_code in (429, 503):
                logger.warning("[LLM] 模型 %s 不可用[%s]，降级到下一个模型", model, code)
                continue

            # 其它错误（参数错误、鉴权失败等）直接抛出，换模型也没用
            raise LlmError("LLM 调用失败 HTTP %s: %s" % (resp.status_code, last_error))

        raise LlmError("所有候选模型均不可用: %s" % last_error)

    @staticmethod
    def _parse_error(resp: httpx.Response) -> Any:
        try:
            body = resp.json()
            err = body.get("error") or {}
            return err.get("code") or "", err.get("message") or resp.text[:200]
        except Exception:
            return "", resp.text[:200]

    @staticmethod
    def _stream_error_code(body: str) -> str:
        """流式失败时响应体是普通字符串，单独解析出错误码用于判断是否降级。"""
        try:
            err = (json.loads(body).get("error") or {})
            return err.get("code") or ""
        except Exception:
            return ""

    @staticmethod
    def _log_usage(scene: str, model: str, data: Dict[str, Any], elapsed: float) -> None:
        usage = data.get("usage") or {}
        logger.info(
            "[LLM] scene=%s model=%s prompt_tokens=%s completion_tokens=%s total_tokens=%s cost=%.2fs",
            scene, model,
            usage.get("prompt_tokens"), usage.get("completion_tokens"),
            usage.get("total_tokens"), elapsed,
        )

    @staticmethod
    def _first_text(resp: Dict[str, Any]) -> str:
        try:
            return resp["choices"][0]["message"].get("content") or ""
        except (KeyError, IndexError):
            return ""

    @staticmethod
    def _extract_delta(event: Dict[str, Any]) -> str:
        try:
            return event["choices"][0].get("delta", {}).get("content") or ""
        except (KeyError, IndexError):
            return ""

    @staticmethod
    def _arm_tool_messages(messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """在 system 提示中强调必须使用工具提交结构化结果。"""
        hint = (
            "【输出要求】你必须调用 submit_structured_answer 工具来提交最终答案，"
            "不要用普通文本回复。所有必填字段都要填写，内容使用简体中文。"
        )
        armed = list(messages)
        if armed and armed[0].get("role") == "system":
            armed[0] = dict(armed[0], content=armed[0]["content"] + "\n\n" + hint)
        else:
            armed.insert(0, {"role": "system", "content": hint})
        return armed

    @staticmethod
    def _extract_tool_arguments(message: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """从模型返回的 message 中取出工具调用参数。"""
        tool_calls = message.get("tool_calls") or []
        for call in tool_calls:
            function = call.get("function") or {}
            raw = function.get("arguments")
            if not raw:
                continue
            if isinstance(raw, dict):
                return raw
            try:
                return json.loads(raw)
            except ValueError:
                logger.warning("[LLM] tool arguments 非法 JSON: %s", str(raw)[:200])
                continue
        return None

    @staticmethod
    def _json_fallback_messages(
        messages: List[Dict[str, Any]], model_cls: Type[BaseModel]
    ) -> List[Dict[str, Any]]:
        """构造纯 JSON 兜底提示。"""
        schema = json.dumps(model_cls.model_json_schema(), ensure_ascii=False)
        instruction = (
            "请只输出一个 JSON 对象，不要输出任何解释性文字、不要使用 Markdown 代码块。\n"
            "必须符合以下 JSON Schema：\n%s" % schema
        )
        fallback = list(messages)
        if fallback and fallback[0].get("role") == "system":
            fallback[0] = dict(fallback[0], content=fallback[0]["content"] + "\n\n" + instruction)
        else:
            fallback.insert(0, {"role": "system", "content": instruction})
        return fallback

    @staticmethod
    def _extract_json_block(text: str) -> Optional[Dict[str, Any]]:
        """从自由文本中提取第一个 JSON 对象（兼容 ```json 代码块）。"""
        if not text:
            return None
        fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
        candidate = fenced.group(1) if fenced else None
        if candidate is None:
            start = text.find("{")
            end = text.rfind("}")
            if start == -1 or end <= start:
                return None
            candidate = text[start:end + 1]
        try:
            data = json.loads(candidate)
        except ValueError:
            return None
        return data if isinstance(data, dict) else None

    @staticmethod
    def _validate(model_cls: Type[BaseModel], data: Dict[str, Any], scene: str) -> BaseModel:
        try:
            return model_cls.model_validate(data)
        except ValidationError as exc:
            logger.warning("[LLM] Pydantic 校验未通过 scene=%s: %s", scene, exc)
            raise


#: 模块级单例，业务代码 `from app.services.llm_client import llm` 即可使用
llm = LlmClient()
