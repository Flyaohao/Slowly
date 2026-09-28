"""Agent 端点用户级 key 验证（v5.0 安全口径收口）

背景：`/ai/agent` 此前写死 `agent/executor.py` 的全局 AI_API_KEY，
注册用户可白嫖服务器 key 跑 Agent。收口后与 chat/信/问卷同口径：
必须用触发用户自己的配置，未配置 → 30010，anthropic 协议 → 30011。

验证内容（全程不碰真实 DB、不发真实 LLM 请求，依赖全 mock）：
    1. 用户未配置 AI → 响应 code=30010（AiConfigMissingError 全局处理器）
    2. anthropic 协议用户 → 响应 code=30011（LangChain 走 ChatOpenAI，如实告知）
    3. openai 协议用户 → run_agent 收到用户 key/端点/模型（而非服务器配置）

运行：
    cd backend
    python tests/test_agent_user_key.py
"""

import os
import sys
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi.testclient import TestClient  # noqa: E402

from app.core.dependencies import get_current_user  # noqa: E402
from app.core.database import get_db  # noqa: E402
from app.main import app  # noqa: E402
from app.repositories import couple_repo  # noqa: E402
from app.services import user_ai_config_service as uaicfg  # noqa: E402

USER_ID = 999
RELATION_ID = 7
AGENT_URL = "/api/v1/couple/ai/agent"


def _body_code(resp) -> object:
    """业务码提取：项目口径为业务错误 HTTP 200 + body 顶层 {code,message,data}；
    兼容 detail 包裹形态。"""
    body = resp.json()
    if isinstance(body, dict):
        if "code" in body:
            return body.get("code")
        detail = body.get("detail")
        if isinstance(detail, dict):
            return detail.get("code")
    return None

_captured: dict = {}


def _fake_db():
    yield SimpleNamespace()  # 端点内 repo/service 的 db 参数，行为已全 mock


def _fake_user():
    return SimpleNamespace(id=USER_ID)


def _fake_relation(db, user_id):
    return SimpleNamespace(id=RELATION_ID)


def _fake_run_agent(question, user_id, relation_id=0, history=None,
                    api_key=None, base_url=None, model_name=None):
    """替身：只记录收到的模型配置，不触发 LangChain。"""
    _captured.update(
        question=question, user_id=user_id, relation_id=relation_id,
        api_key=api_key, base_url=base_url, model_name=model_name,
    )
    return {"answer": "ok", "tool_calls": [], "steps": 1, "reasoning": ["AIMessage"]}


def _install_base_overrides():
    app.dependency_overrides[get_current_user] = _fake_user
    app.dependency_overrides[get_db] = _fake_db
    couple_repo.get_active_relation_by_user = _fake_relation
    import app.agent.executor as executor_mod
    executor_mod.run_agent = _fake_run_agent


def _fake_config(provider_type: str, key_cursor: int = 0) -> SimpleNamespace:
    return SimpleNamespace(
        provider_type=provider_type,
        base_url="https://user-endpoint.example/v1",
        model_name="user-model-x",
        key_cursor=key_cursor,
    )


def main() -> int:
    print("=" * 72)
    print("Agent 端点用户级 key 验证")
    print("=" * 72)

    _install_base_overrides()
    client = TestClient(app, raise_server_exceptions=False)
    failures = []

    # ---- 用例 1：未配置 AI → 30010（不得回落服务器 key）----
    print("\n[1] 用户未配置 AI")
    def _missing(db, user_id):
        raise uaicfg.AiConfigMissingError(user_id)
    uaicfg.resolve = _missing
    resp = client.post(AGENT_URL, json={"question": "我们为什么总吵架？"})
    code = _body_code(resp)
    print("  HTTP %s, code=%s" % (resp.status_code, code))
    if code != 30010:
        print("  ❌ 期望 30010，实际 %s" % resp.json())
        failures.append("未配置→30010")
    else:
        print("  ✅ 30010 引导配置，服务器 key 未被使用")

    # ---- 用例 2：anthropic 协议 → 30011 ----
    print("\n[2] anthropic 协议用户")
    uaicfg.resolve = lambda db, user_id: (_fake_config("anthropic"), ["sk-user-a"])
    resp = client.post(AGENT_URL, json={"question": "我们为什么总吵架？"})
    code = _body_code(resp)
    print("  HTTP %s, code=%s" % (resp.status_code, code))
    if code != 30011:
        print("  ❌ 期望 30011，实际 %s" % resp.json())
        failures.append("anthropic→30011")
    else:
        print("  ✅ 30011 明确告知协议不支持")

    # ---- 用例 3：openai 协议 → 用户 key 透传 + 轮换游标生效 ----
    print("\n[3] openai 协议用户（两把 key，游标=1 → 应取第 2 把）")
    uaicfg.resolve = lambda db, user_id: (
        _fake_config("openai", key_cursor=1), ["sk-user-1", "sk-user-2"],
    )
    resp = client.post(AGENT_URL, json={"question": "我们为什么总吵架？"})
    body = resp.json()
    print("  HTTP %s, body=%s" % (resp.status_code, body))
    ok = (
        resp.status_code == 200
        and _captured.get("api_key") == "sk-user-2"
        and _captured.get("base_url") == "https://user-endpoint.example/v1"
        and _captured.get("model_name") == "user-model-x"
        and _captured.get("user_id") == USER_ID
        and _captured.get("relation_id") == RELATION_ID
    )
    if not ok:
        print("  ❌ 透传不符：captured=%s" % _captured)
        failures.append("openai→用户key透传")
    else:
        print("  ✅ 用户 key/端点/模型 透传正确，轮换游标生效")

    # ---- 用例 4（口径自证）：executor 缺省回落仅测试保留，端点必须显式传参 ----
    print("\n[4] 端点源码静态断言")
    src = open("app/api/v1/couple/ai.py", encoding="utf-8").read()
    checks = [
        ("强制解析用户配置", "uaicfg.resolve(db, current_user.id)" in src),
        ("协议白名单", 'provider_type != "openai"' in src),
        ("key 轮换取首把", "uaicfg._rotate(keys" in src),
    ]
    for name, hit in checks:
        print("  %s %s" % ("✅" if hit else "❌", name))
        if not hit:
            failures.append(name)

    print("\n" + "=" * 72)
    if failures:
        print("❌ 失败用例: %s" % failures)
        return 1
    print("✅ 全部通过：/ai/agent 已收口到用户级 key，服务器 AI_API_KEY 不再兜底")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
