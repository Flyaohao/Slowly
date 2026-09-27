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

import contextlib
import json
import logging
import re
import threading
import time
from typing import Any, Dict, Iterator, List, Optional, Tuple, Type

import httpx
from pydantic import BaseModel, ValidationError

from app.core.config import (
    AI_API_KEY,
    AI_BASE_URL,
    AI_ENABLE_THINKING,
    AI_MODEL,
    AI_THINKING_BUDGET,
)
from app.schemas.ai_output import build_tool_schema, get_output_model

logger = logging.getLogger("couple.llm")

#: 主模型额度耗尽或限流时，依次尝试的备用模型
FALLBACK_MODELS: List[str] = ["qwen-plus", "deepseek-v3", "qwen-turbo"]

#: 单次请求超时。qwen3.7-flash 这类推理模型会先产出思考内容，
#: 实测单次结构化调用约 30s，长 prompt 更久，故留足余量。
_REQUEST_TIMEOUT = 120.0
_TOOL_NAME = "submit_structured_answer"

#: 流式增量的两种类型标签，见 `LlmClient.stream_events`
_KIND_THINKING = "thinking"  #: 模型的推理过程（reasoning_content），只给用户看，不落正文
_KIND_CONTENT = "content"    #: 面向用户的正文

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


@contextlib.contextmanager
def _cancel_scope(
    cancel_event: Optional[threading.Event], resp: httpx.Response
) -> Iterator[None]:
    """在 `cancel_event` 被置位时立刻关闭底层 HTTP 响应。

    **为什么不能只在 for 循环里判断标志位**：`resp.iter_lines()` 是阻塞读取，
    模型思考期间可能长时间没有新字节，循环根本转不到下一圈，标志位判了也白判。
    真正能立刻解除阻塞的只有 `resp.close()`——它让读取端抛错返回。
    因此这里单开一个守护线程盯着信号，而不是污染主读取循环。
    """
    if cancel_event is None:
        yield
        return

    finished = threading.Event()

    def _watch() -> None:
        # 轮询而非 wait()：既要能被 cancel_event 唤醒，也要能在正常结束时退出。
        while not finished.is_set():
            if cancel_event.wait(timeout=0.2):
                try:
                    resp.close()
                except Exception:  # noqa: BLE001 —— 关闭失败无关紧要，读取端自会报错
                    logger.debug("[LLM] 取消时关闭响应失败", exc_info=True)
                return

    watcher = threading.Thread(target=_watch, daemon=True, name="llm-cancel")
    watcher.start()
    try:
        yield
    finally:
        finished.set()


class LlmClient:
    """大模型统一客户端。模块级单例见文件底部 `llm`。"""

    #: 流式增量类型标签，方便调用方写 `llm.KIND_THINKING`
    KIND_THINKING = _KIND_THINKING
    KIND_CONTENT = _KIND_CONTENT

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        fallbacks: Optional[List[str]] = None,
        enable_thinking: Optional[bool] = None,
        thinking_budget: Optional[int] = None,
    ) -> None:
        self.api_key = api_key or AI_API_KEY
        self.base_url = (base_url or AI_BASE_URL).rstrip("/")
        self.model = model or AI_MODEL
        self.fallbacks = fallbacks if fallbacks is not None else FALLBACK_MODELS
        #: 思考控制。默认值来自环境变量，构造时显式传入可覆盖（测试用）
        self.enable_thinking = (
            AI_ENABLE_THINKING if enable_thinking is None else enable_thinking
        )
        self.thinking_budget = (
            AI_THINKING_BUDGET if thinking_budget is None else thinking_budget
        )

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
        cancel_event: Optional[threading.Event] = None,
    ) -> str:
        """阻塞调用，返回完整文本。

        ``cancel_event`` 置位时立刻关闭在途请求并抛 ``LlmError``（不静默返回空串，
        否则「取消」会被上层误当成模型给出了空回复）。
        """
        payload = {
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        resp = self._request(payload, scene=scene, cancel_event=cancel_event)
        return self._first_text(resp)

    def stream(
        self,
        messages: List[Dict[str, Any]],
        temperature: float = 0.7,
        max_tokens: int = 2000,
        scene: str = "unknown",
    ) -> Iterator[str]:
        """流式调用，**只**产出面向用户的正文增量。

        这是 `stream_events()` 的正文投影，供不关心推理过程的调用方使用。
        需要把模型的思考过程也下推（做"正在深度思考"体验）时用 `stream_events()`。
        """
        for kind, text in self.stream_events(
            messages, temperature=temperature, max_tokens=max_tokens, scene=scene
        ):
            if kind == self.KIND_CONTENT:
                yield text

    def stream_events(
        self,
        messages: List[Dict[str, Any]],
        temperature: float = 0.7,
        max_tokens: int = 2000,
        scene: str = "unknown",
        cancel_event: Optional[threading.Event] = None,
    ) -> Iterator[Tuple[str, str]]:
        """流式调用，逐块产出 `(kind, text)`。

        `cancel_event` 是可选的取消信号：一旦被置位，本方法会**立刻关闭**
        正在读取的 HTTP 响应并安静返回（不抛异常）。这是服务端主动中断的
        落地方式——用户点了「停止生成」之后，不能再让模型把剩下的话说完，
        否则 token 白烧、账单照付。

        `kind` 取 `KIND_THINKING`（模型的推理过程，来自 `reasoning_content`）
        或 `KIND_CONTENT`（面向用户的正文，来自 `content`）。

        **为什么必须分成两个通道**：推理模型（qwen3.7-flash）会先流式产出
        `reasoning_content`，此时 `delta.content` 恒为空串。实测同一次调用中
        推理帧首个约 **0.5s** 抵达，正文首个却要 **18s 以上**——只取 content
        意味着前端要空屏近半分钟。把推理增量单独下推，用户 0.5 秒就能看到
        模型"在想什么"，而正文通道依然保持纯净（推理不会混进落库正文）。

        与 `invoke()` 的差异：
        - 走 `client.stream()`，无法复用 `_request()` 的请求封装，
          因此**模型降级逻辑必须在这里再实现一遍**（曾经漏掉 `model` 字段，
          线上会直接 400 "you must provide a model parameter"）。
        - 只有在**尚未吐出任何增量**时才允许切换模型；一旦有内容发给前端了，
          再换模型会导致前缀重复，此时只能报错。
          注意判据是"推理或正文任一已下发"，推理也算已下发。
        """
        last_error: Optional[str] = None

        for model in self._candidates():
            payload = {
                "messages": messages,
                "model": model,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "stream": True,
                **self._thinking_params(),
            }
            started = time.time()
            thinking_chunks = 0
            content_chunks = 0
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

                        with _cancel_scope(cancel_event, resp):
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

                                reasoning = self._extract_reasoning(event)
                                if reasoning:
                                    thinking_chunks += 1
                                    yield self.KIND_THINKING, reasoning

                                delta = self._extract_delta(event)
                                if delta:
                                    content_chunks += 1
                                    yield self.KIND_CONTENT, delta
            except httpx.HTTPError as exc:
                if cancel_event is not None and cancel_event.is_set():
                    # 调用方主动取消：连接是我们自己关掉的，不是故障。
                    # 安静收尾，不要把「取消」当成异常抛给上层。
                    logger.info("[LLM] 流式已被调用方取消 scene=%s model=%s", scene, model)
                    return
                last_error = "网络异常: %s" % exc
                if thinking_chunks == 0 and content_chunks == 0:
                    logger.warning("[LLM] 流式 %s 请求异常，尝试下一个模型: %s", model, exc)
                    continue
                raise LlmError("流式中断且已推送部分内容: %s" % exc) from exc
            finally:
                logger.info(
                    "[LLM-STREAM] scene=%s model=%s thinking=%d content=%d cost=%.2fs",
                    scene, model, thinking_chunks, content_chunks, time.time() - started,
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
        resp = self._request(payload, scene=scene, no_thinking=True)
        return resp["choices"][0]["message"]

    def invoke_structured(
        self,
        messages: List[Dict[str, Any]],
        output_model: Optional[Type[BaseModel]] = None,
        scene: str = "unknown",
        temperature: float = 0.7,
        max_tokens: int = 2000,
        cancel_event: Optional[threading.Event] = None,
    ) -> BaseModel:
        """结构化调用：返回经 Pydantic 校验的模型实例。

        ``cancel_event`` 语义与 ``stream_events()`` 一致：置位后立刻放弃剩下的
        尝试并抛 ``LlmError``。结构化链路自带「首轮 → 重试 → JSON 兜底」三次
        尝试 × N 个候选模型，客户端一旦断开，没有取消信号就会把这一整套全部
        跑完（单次超时 120s），白烧 token。调用方（流式结构化提取线程）在
        用户断连时置位本事件。
        """
        model_cls = output_model or get_output_model(scene)
        tool = build_tool_schema(model_cls, tool_name=_TOOL_NAME)

        def _cancelled() -> bool:
            return cancel_event is not None and cancel_event.is_set()

        if _cancelled():
            raise LlmError("结构化调用已被调用方取消")

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
            resp = self._request(payload, scene=scene, cancel_event=cancel_event, no_thinking=True)
            message = resp["choices"][0]["message"]
            data = self._extract_tool_arguments(message)
            if data is not None:
                return self._validate(model_cls, data, scene)
        except (LlmError, ValidationError, ValueError, KeyError) as exc:
            logger.warning("[LLM] 结构化首轮失败 scene=%s: %s", scene, exc)

        if _cancelled():
            raise LlmError("结构化调用已被调用方取消")

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
            resp = self._request(payload, scene=scene, cancel_event=cancel_event, no_thinking=True)
            data = self._extract_tool_arguments(resp["choices"][0]["message"])
            if data is not None:
                return self._validate(model_cls, data, scene)
        except (LlmError, ValidationError, ValueError, KeyError) as exc:
            logger.warning("[LLM] 结构化重试失败 scene=%s: %s", scene, exc)

        if _cancelled():
            raise LlmError("结构化调用已被调用方取消")

        # ③ 兜底：要求模型以纯 JSON 文本回复，再手工解析
        try:
            json_messages = self._json_fallback_messages(messages, model_cls)
            text = self.invoke(
                json_messages,
                temperature=0.2,
                max_tokens=max_tokens,
                scene=scene,
                cancel_event=cancel_event,
            )
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

    def _thinking_params(self) -> Dict[str, Any]:
        """思考控制参数，附加到每一次请求体上。

        **为什么值得单独下发**：主模型是推理模型，它先写一段思考再落笔正文，
        而用户的等待时间几乎等于思考时长。用同一封信的解读实测：

        | 配置 | 思考 | 首字正文 | 总耗时 |
        | --- | --- | --- | --- |
        | 不限制 | 9258 字 | 32.9s | 35.9s |
        | thinking_budget=1024 | 3343 字 | 13.4s | 19.3s |
        | enable_thinking=false | 不产生 | 0.9s | 6.1s |

        默认值在 `config.AI_THINKING_BUDGET`，改环境变量即可调，不必改代码。
        备选模型实测会忽略这两个字段（不会 400），所以无条件下发。
        """
        if not self.enable_thinking:
            return {"enable_thinking": False}
        if self.thinking_budget > 0:
            return {"thinking_budget": self.thinking_budget}
        return {}

    def _candidates(self) -> List[str]:
        """待尝试的模型序列：主模型优先，备用模型去重后依次跟上。"""
        models = [self.model]
        for name in self.fallbacks:
            if name not in models:
                models.append(name)
        return models

    def _request(
        self,
        payload: Dict[str, Any],
        scene: str = "unknown",
        cancel_event: Optional[threading.Event] = None,
        no_thinking: bool = False,
    ) -> Dict[str, Any]:
        """带模型降级与重试的底层请求。

        ``cancel_event`` 置位时在**候选模型之间**提前退出：非流式 POST 本身
        无法中断，但可以避免「主模型失败 → 再试 3 个备用模型」把已经无人在等的
        请求继续烧下去（单次超时 120s × 候选数）。

        ``no_thinking=True`` 强制关闭思考（覆盖 `_thinking_params`）：工具调用
        路径（invoke_structured / invoke_with_tools）的产出是工具参数或结构化
        字段，没有「思考面板」可展示——留着思考只会拖慢每一次重试（主模型
        实测单次多花 ~19s）。主聊天流式链路**不受影响**，照常带思考。
        """
        last_error: Optional[str] = None

        for model in self._candidates():
            if cancel_event is not None and cancel_event.is_set():
                raise LlmError("LLM 调用已被调用方取消")
            body = dict(payload, model=model, **self._thinking_params())
            if no_thinking:
                body["enable_thinking"] = False
                body.pop("thinking_budget", None)
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
        """取面向用户的正文增量（`delta.content`）。"""
        try:
            return event["choices"][0].get("delta", {}).get("content") or ""
        except (KeyError, IndexError):
            return ""

    @staticmethod
    def _extract_reasoning(event: Dict[str, Any]) -> str:
        """取推理模型的思考增量（`delta.reasoning_content`）。

        非推理模型的响应里没有这个字段，恒返回空串，因此对普通模型无副作用。
        部分兼容端点也见过 `delta.reasoning` 的写法，一并兼容。
        """
        try:
            delta = event["choices"][0].get("delta") or {}
        except (KeyError, IndexError):
            return ""
        if not isinstance(delta, dict):
            return ""
        return delta.get("reasoning_content") or delta.get("reasoning") or ""

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


#: chat_mode → 客户端实例（P-B §1.3）。按档位缓存 3 个实例，比请求级 clone
#: 简单且无并发风险——思考开关/预算是实例构造参数，不是 invoke 参数。
#: - deep 直接复用模块单例：它读环境变量，与 chat_mode 出现之前的行为
#:   逐参数相同（零回归）；
#: - quick 关思考（enable_thinking=False → 请求体带 enable_thinking=false）；
#: - expert 思考预算 2048。
_CLIENTS: Dict[str, LlmClient] = {
    "quick": LlmClient(enable_thinking=False),
    "deep": llm,
    "expert": LlmClient(enable_thinking=True, thinking_budget=2048),
}


def get_client_for_mode(mode: Optional[str]) -> LlmClient:
    """按 chat_mode 取客户端；未知值回落 deep 单例（与 resolve_chat_mode 同口径）。"""
    return _CLIENTS.get((mode or "").strip().lower(), llm)
