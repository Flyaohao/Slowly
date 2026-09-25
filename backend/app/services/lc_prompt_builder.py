"""基于 LangChain 的 Prompt 组装层（聊天主链路实际使用的实现）

定位
----
本模块是**面向模型的消息（messages）组装唯一实现**：把「人设 + 画像 + 冲突循环 +
历史对话」放进 system，把「RAG 检索结果 + 长期记忆 + 本次输入」放进 human。
`prompt_builder.build_prompt() / build_stream_prompt()` 只是把这里产出的消息
压平成单条字符串的投影，供旧式调用方与单测使用——两者内容同源，不存在第二套拼装逻辑。

LangChain 在这里承担三件事：

1. **模板化与变量校验**：`ChatPromptTemplate` 负责占位符解析，
   缺变量会在组装阶段就报错，而不是把 `{user_profile}` 原样发给模型；
2. **角色划分**：system 承载人设与画像，human 承载本次输入。
   从前是把所有内容拼成一条超长 system 字符串，模型难以区分「背景」与「本次要处理的事」；
3. **多轮记忆的统一封装**（`get_conversation_memory` / `history_to_messages`），
   供后续把历史从「文本拼接」迁移到「消息列表」时使用。

不侵入业务逻辑：数据库访问、路由、鉴权仍由原有分层负责。
"""

from typing import Any, Dict, List, Optional

from langchain_core.prompts import ChatPromptTemplate

from app.services.prompt_builder import (
    STREAM_INSTRUCTION,
    SYSTEM_PROMPTS,
    truncate_at_json_marker,
    with_human_base,
)

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
    "relationship_review",
)

#: LangChain 消息 type → OpenAI messages role
_ROLE_MAP = {"system": "system", "human": "user", "ai": "assistant"}

#: 两组出口共用的模板变量
_SYSTEM_VARS = ("user_profile", "partner_profile", "conflict_pattern", "history")

#: Human 部分：先给检索到的理论上下文与记忆，再给用户输入
_HUMAN_TEMPLATE = """{rag_context}{memory_context}## 用户输入
{user_input}"""


def _render(template: str, variables: Dict[str, Any], role: str = "system") -> str:
    """用 `ChatPromptTemplate` 渲染单条消息，返回纯文本。

    变量缺失会抛 `KeyError` / `ValueError`，由调用方决定是否回退——
    这里不吞异常，因为「模板里出现未知占位符」是需要被看见的信号。
    """
    prompt = ChatPromptTemplate.from_messages([(role, template)])
    return prompt.format_messages(**variables)[0].content


def build_chat_messages(
    scene_key: str,
    user_profile: str,
    partner_profile: str,
    conflict_pattern: str,
    user_input: str,
    history: str = "",
    rag_context: str = "",
    memory_context: str = "",
    mode: str = "structured",
    system_template: Optional[str] = None,
    stream_instruction: str = STREAM_INSTRUCTION,
) -> List[Dict[str, Any]]:
    """产出可直接发给 LLM 的 messages 列表（OpenAI 兼容格式）。

    `mode`：

    - `"structured"`：system 保留模板尾部的 JSON 字段说明，
      配合 Function Calling 回填结构化结果；
    - `"stream"`：在同一处分界点截掉字段说明，换成自然语言输出要求——
      流式链路下用户读的是正文，不该看到 `summary:` 这类字段名。

    `system_template`：由 `prompt_builder.resolve_system_prompt()` 解析出的、
    当前对该场景生效的模板（可能来自 DB，支持版本化与 A/B 分流）。
    传 None 时使用代码内置模板。

    `stream_instruction`：流式尾部指令按 `chat_mode` 分档传入
    （quick/deep/expert → QUICK/STREAM/EXPERT_INSTRUCTION，默认 deep 口径）。
    本层同时无条件追加 L0 人性化基线 `with_human_base`——位置在场景模板
    （含尾部指令）之后、人格（`_append_persona`）之前，两条出口都经过这里。
    """
    base = system_template or SYSTEM_PROMPTS.get(
        scene_key, SYSTEM_PROMPTS["private_advisor"]
    )
    if mode == "stream":
        base = truncate_at_json_marker(base) + "\n\n" + stream_instruction
    base = with_human_base(base)

    profile_vars = {
        "user_profile": user_profile,
        "partner_profile": partner_profile,
        "conflict_pattern": conflict_pattern,
        "history": history,
    }

    try:
        system_text = _render(base, profile_vars)
    except (KeyError, ValueError, IndexError) as exc:
        # DB 模板里可能混进未知占位符（例如把 {dimensions_data} 写进了对话场景）。
        # 这种模板一旦渲染失败就会挡掉整轮对话，回退到内置模板是更划算的选择。
        fallback = SYSTEM_PROMPTS.get(scene_key, SYSTEM_PROMPTS["private_advisor"])
        if mode == "stream":
            fallback = truncate_at_json_marker(fallback) + "\n\n" + stream_instruction
        system_text = _render(with_human_base(fallback), profile_vars)

    # 上下文块自带尾部分隔，为空时不留多余空行
    human_text = _render(
        _HUMAN_TEMPLATE,
        {
            "rag_context": (rag_context + "\n\n") if rag_context else "",
            "memory_context": (memory_context + "\n\n") if memory_context else "",
            "user_input": user_input,
        },
        role="human",
    )

    return [
        {"role": "system", "content": system_text},
        {"role": "user", "content": human_text},
    ]


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
    """`build_chat_messages(mode="structured")` 的别名，保留旧调用方签名。"""
    return build_chat_messages(
        scene_key=scene_key,
        user_profile=user_profile,
        partner_profile=partner_profile,
        conflict_pattern=conflict_pattern,
        user_input=user_input,
        history=history,
        rag_context=rag_context,
        memory_context=memory_context,
        mode="structured",
    )


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
