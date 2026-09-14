"""Agent 执行器

用 LangChain 组装「模型 + 工具」循环，完成 Function Calling 闭环：

    用户提问 → 模型判断是否需要工具 → 选择工具并生成参数
             → 执行工具 → 结果回填上下文 → 模型生成最终答复

版本说明（重要）
----------------
LangChain 1.x **已移除** `AgentExecutor` 与 `create_tool_calling_agent`，
新 API 为 `create_agent`（基于 langgraph），返回编译后的状态图 `CompiledStateGraph`。
旧教程里的写法在当前版本会直接 ImportError。
"""

import logging
from typing import Any, Dict, List, Optional

from langchain.agents import create_agent
from langchain_openai import ChatOpenAI

from app.agent.tools import ALL_TOOLS
from app.core.config import AI_API_KEY, AI_BASE_URL, AI_MODEL

logger = logging.getLogger("couple.agent")

AGENT_SYSTEM_PROMPT = """你是一位专业的亲密关系沟通顾问，服务于一款情侣沟通辅助应用。

你可以调用以下工具来获取信息：
- get_relation_profile：查询用户与伴侣的关系画像（依恋类型、维度分数、冲突循环）
- search_theory：检索心理学理论库（依恋理论 / Gottman 冲突四骑士 / 追逃模式 / 非暴力沟通）
- get_ai_memory：查询此前对话沉淀的记忆

工作原则：
1. 当问题涉及"我是什么类型""我们为什么总是这样吵"时，先调用 get_relation_profile
2. 需要理论支撑时调用 search_theory，不要凭空编造研究结论
3. 综合工具返回的具体数据给出可执行建议，避免空泛安慰
4. 若发现暴力、胁迫、自伤信号，优先提示现实求助渠道
5. 使用简体中文，语气温暖而专业

当前上下文：user_id = {user_id}，relation_id = {relation_id}。
调用工具时请使用上述 ID。
"""

#: 工具调用可能触发的模型轮次上限，防止无限循环
MAX_ITERATIONS = 6

_agent_cache: Dict[Any, Any] = {}
_CACHE_LIMIT = 32


def build_model(temperature: float = 0.3) -> ChatOpenAI:
    """构造指向 DashScope OpenAI 兼容端点的模型实例。"""
    return ChatOpenAI(
        model=AI_MODEL,
        api_key=AI_API_KEY,
        base_url=AI_BASE_URL,
        temperature=temperature,
        timeout=60,
    )


def get_agent(user_id: int, relation_id: int):
    """按 (user_id, relation_id) 构建并缓存 Agent。

    system_prompt 中需要携带这两个 ID（工具调用要用），因此必须按用户区分实例。
    """
    key = (user_id, relation_id)
    if key in _agent_cache:
        return _agent_cache[key]

    if len(_agent_cache) >= _CACHE_LIMIT:
        _agent_cache.clear()

    _agent_cache[key] = create_agent(
        build_model(),
        ALL_TOOLS,
        system_prompt=AGENT_SYSTEM_PROMPT.format(
            user_id=user_id, relation_id=relation_id
        ),
    )
    return _agent_cache[key]


def run_agent(
    user_input: str,
    user_id: int,
    relation_id: int = 0,
    history: Optional[List[Dict[str, str]]] = None,
) -> Dict[str, Any]:
    """执行一次 Agent 对话。

    返回::

        {
          "answer":     最终答复文本,
          "tool_calls": [{"name", "args", "result"}],   # 工具调用轨迹
          "steps":      消息轮次,
          "reasoning":  各轮消息类型（便于前端展示"已查询画像/已检索理论"）
        }
    """
    agent = get_agent(user_id, relation_id)

    messages: List[Dict[str, str]] = list(history or [])
    messages.append({"role": "user", "content": user_input})

    result = agent.invoke(
        {"messages": messages},
        config={"recursion_limit": MAX_ITERATIONS * 2},
    )
    raw = result.get("messages", [])

    answer = ""
    tool_calls: List[Dict[str, Any]] = []
    pending: Dict[Any, Dict[str, Any]] = {}

    for msg in raw:
        for call in (getattr(msg, "tool_calls", None) or []):
            pending[call.get("id")] = {
                "name": call.get("name"),
                "args": call.get("args"),
            }

        if type(msg).__name__ == "ToolMessage":
            call_id = getattr(msg, "tool_call_id", None)
            info = pending.get(call_id, {})
            tool_calls.append({
                "name": info.get("name") or getattr(msg, "name", ""),
                "args": info.get("args"),
                "result": str(msg.content)[:600],
            })
        elif getattr(msg, "content", None) and not getattr(msg, "tool_calls", None):
            answer = str(msg.content)

    logger.info(
        "[AGENT] user=%s relation=%s 工具调用 %d 次: %s",
        user_id, relation_id, len(tool_calls),
        [c["name"] for c in tool_calls] or "无",
    )

    return {
        "answer": answer,
        "tool_calls": tool_calls,
        "steps": len(raw),
        "reasoning": [type(m).__name__ for m in raw],
    }
