"""基于 LangChain 的 Prompt 组装层

定位
----
与 `prompt_builder.build_prompt()` **保持完全一致的变量契约**
（user_profile / partner_profile / conflict_pattern / history / rag_context /
memory_context / user_input），因此两条链路可以逐场景对照、渐进替换。

LangChain 在这里只承担两件事：
    1. Prompt 模板化与角色划分（System 承载人设与画像，Human 承载上下文与输入）
    2. 多轮对话记忆的统一封装

不侵入业务逻辑：数据库访问、路由、鉴权仍由原有分层负责。
"""

from typing import Any, Dict, List, Optional

from langchain_core.prompts import ChatPromptTemplate

from app.services.prompt_builder import SYSTEM_PROMPTS

try:
    from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage

    _HAS_MESSAGES = True
except ImportError:  # pragma: no cover
    _HAS_MESSAGES = False
    BaseMessage = object  # type: ignore

#: 走对话链路（共享同一组变量）的场景
CHAT_SCENES = (
    "private_advisor",
    "partner_translate",
    "expression_rewrite",
    "cold_war",
    "mediation",
    "letter_understand",
)

#: LangChain 消息 type → OpenAI messages role
_ROLE_MAP = {"system": "system", "human": "user", "ai": "assistant"}

#: Human 部分：先给检索到的理论上下文与记忆，再给用户输入
_HUMAN_TEMPLATE = """{rag_context}{memory_context}## 用户输入
{user_input}"""


def build_chat_prompt(scene_key: str) -> ChatPromptTemplate:
    """按场景构造 ChatPromptTemplate。

    与旧实现的分工差异：
        旧版把所有内容拼成**一条**超长 string 交给模型；
        LangChain 版拆成 system / human 两条消息，角色边界更清晰，
        模型对「人设与画像」和「本次输入」的区分更准确。
    """
    if scene_key not in SYSTEM_PROMPTS:
        scene_key = "private_advisor"

    system_template = SYSTEM_PROMPTS[scene_key]
    return ChatPromptTemplate.from_messages([
        ("system", system_template),
        ("human", _HUMAN_TEMPLATE),
    ])


def build_messages(
    scene_key: str,
    user_profile: str,
    partner_profile: str,
    conflict_pattern: str,
    user_input: str,
    history: str = "",
    rag_context: str = "",
    memory_context: str = "",
) -> List[Dict[str, Any]]:
    """产出可直接发给 LLM 的 messages 列表。

    返回 OpenAI 兼容格式：[{"role": "system"|"user", "content": "..."}]
    """
    prompt = build_chat_prompt(scene_key)

    # rag_context / memory_context 需要自带尾部分隔，为空时不留空白
    rag_block = (rag_context + "\n\n") if rag_context else ""
    memory_block = (memory_context + "\n\n") if memory_context else ""
    # history 注入 system，让模型把它当作"已知背景"而非"新输入"
    system_suffix = ("\n\n## 历史对话\n" + history) if history else ""

    formatted = prompt.format_messages(
        user_profile=user_profile,
        partner_profile=partner_profile,
        conflict_pattern=conflict_pattern,
        history=system_suffix,
        rag_context=rag_block,
        memory_context=memory_block,
        user_input=user_input,
    )
    return [
        {"role": _ROLE_MAP.get(m.type, "user"), "content": m.content}
        for m in formatted
    ]


def get_conversation_memory(k: int = 20):
    """构造多轮对话记忆（保留最近 k 轮）。

    说明：当前链路的历史消息来自 MySQL（`ai_chat_message`），
    这里提供 LangChain 原生的窗口记忆封装，用于对比与后续迁移。
    """
    from langchain_core.messages import trim_messages

    return {"trimmer": trim_messages, "max_tokens": None, "k": k}


def history_to_messages(records: List[Dict[str, str]]) -> List[Any]:
    """把数据库中的多轮记录转成 LangChain 消息对象。

    records: [{"role": "user"|"assistant", "content": "..."}]
    """
    if not _HAS_MESSAGES:
        return []

    messages: List[Any] = []
    for item in records:
        content = item.get("content") or ""
        if item.get("role") == "user":
            messages.append(HumanMessage(content=content))
        else:
            messages.append(AIMessage(content=content))
    return messages
