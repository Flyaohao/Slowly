"""校验在用模型是否真的支持「思考流」。

为什么需要这个脚本：
    客户端「深度思考」面板依赖 OpenAI 兼容接口的 delta.reasoning_content。
    但这不是所有模型都有的能力，而且线上配的模型可能因为额度、下线、
    改名等原因静默失效（HTTP 403 / 404 都不会在本机表现出来）。
    部署前跑一遍，能避免「代码没问题、上线就不吐字」这类事故。

实测背景（2026-09-14）：
    qwen3.7-flash   正常，reasoning_content 1840 字 + content 49 字
    deepseek-v4-pro  HTTP 403 免费额度耗尽 —— 线上 .env 里原本配的是它

运行：
    python tests/test_model_reasoning.py
"""

import json
import os
import sys

import httpx

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core import config  # noqa: E402

PROMPT = "用一句话解释：为什么天空是蓝色的？"
TIMEOUT = 180


def stream_once(model):
    """流式请求一次，统计思考与正文各下发多少字。

    返回 (reasoning_chars, content_chars, error)。
    """
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": PROMPT}],
        "stream": True,
        "max_tokens": 60,
    }
    headers = {
        "Authorization": f"Bearer {config.AI_API_KEY}",
        "Content-Type": "application/json",
    }
    reasoning = 0
    content = 0
    try:
        with httpx.stream(
            "POST",
            f"{config.AI_BASE_URL.rstrip('/')}/chat/completions",
            json=payload,
            headers=headers,
            timeout=TIMEOUT,
        ) as resp:
            if resp.status_code != 200:
                resp.read()
                return 0, 0, f"HTTP {resp.status_code}: {resp.text[:200]}"
            for line in resp.iter_lines():
                if not line or not line.startswith("data: "):
                    continue
                data = line[6:]
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except json.JSONDecodeError:
                    continue
                delta = (obj.get("choices") or [{}])[0].get("delta") or {}
                if delta.get("reasoning_content"):
                    reasoning += len(delta["reasoning_content"])
                if delta.get("content"):
                    content += len(delta["content"])
    except Exception as e:  # noqa: BLE001
        return 0, 0, f"{type(e).__name__}: {e}"
    return reasoning, content, None


def main():
    main_model = config.AI_MODEL
    print(f"主模型 = {main_model}")
    print(f"降级链 = {getattr(config, 'AI_FALLBACK_MODELS', '(见 llm_client.FALLBACK_MODELS)')}")

    failures = []

    print("\n[1] 主模型必须支持思考流")
    reasoning, content, err = stream_once(main_model)
    if err:
        print(f"    FAIL  {main_model} 调用失败：{err}")
        failures.append(f"{main_model} 不可用")
    elif reasoning == 0:
        print(f"    FAIL  {main_model} 能出正文（{content} 字）但没有 reasoning_content")
        print("          → 客户端「深度思考」面板会一直空着，需换模型或改 UI 降级")
        failures.append(f"{main_model} 无思考流")
    else:
        print(f"    PASS  思考 {reasoning} 字 / 正文 {content} 字")

    print("\n[2] 降级链上的模型应至少可用（有额度、名字没写错）")
    for model in ["qwen-plus", "qwen-turbo"]:
        reasoning, content, err = stream_once(model)
        if err:
            print(f"    WARN  {model}：{err}")
        else:
            flag = "有思考流" if reasoning else "无思考流（非推理模型，正常）"
            print(f"    OK    {model}：思考 {reasoning} 字 / 正文 {content} 字，{flag}")

    print()
    if failures:
        print("结果：不通过 —— " + "；".join(failures))
        return 1
    print("结果：通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
