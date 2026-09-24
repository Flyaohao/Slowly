import logging
import os
import threading
from typing import Optional, List, Dict
from datetime import datetime

from sqlalchemy.orm import Session
from sqlalchemy import and_

from app.models.ai import AiMemory
from app.core.config import AI_MEMORY_MODEL
from app.services.llm_client import llm, LlmClient, LlmError
from app.services.prompt_builder import MEMORY_DISTILL_PROMPT, SESSION_SUMMARY_PROMPT

logger = logging.getLogger("couple.memory")

#: 记忆抽取是每轮对话都要跑一次的后台任务，本质是「二分类 + 一句话抽取」。
#: 用主模型（推理模型）实测单次约 10s / 2000+ token；换轻量模型后判断结果
#: 与主模型一致，但快约 20 倍。故这一条链路单独指定模型，不跟随主模型。
#: 注意：模型只影响速度与成本，输出结构仍由 scene=memory_distill 的
#: Pydantic 模型约束，因此换模型不会改变落库字段契约。
distill_llm = LlmClient(model=AI_MEMORY_MODEL, fallbacks=[])

#: LLM 可能自由发挥，落库前收口到这几类
MEMORY_TYPES = ("偏好", "关系事实", "沟通雷区", "核心诉求")
_DEFAULT_MEMORY_TYPE = "关系事实"

#: 太短的输入没有可沉淀的信息，直接跳过，省一次模型调用
_MIN_INPUT_LEN = 6


def create_memory(
    db: Session,
    user_id: int,
    relation_id: int,
    memory_type: str,
    memory_text: str,
    visibility: str = "private",
) -> dict:
    memory = AiMemory(
        user_id=user_id,
        relation_id=relation_id,
        memory_type=memory_type,
        memory_text=memory_text,
        visibility=visibility,
    )
    db.add(memory)
    db.flush()
    db.commit()
    saved = _to_dict(memory)
    # P0-4：新记忆异步向量化（约束②写入侧）。失败只记日志，不影响落库。
    try:
        from app.services.memory_retrieval import vectorize_memory_async

        vectorize_memory_async(saved)
    except Exception:
        logger.warning("[MEMORY] 向量化挂钩异常（不影响落库）", exc_info=True)
    return saved


def get_memories(
    db: Session,
    user_id: int,
    relation_id: Optional[int] = None,
    memory_type: Optional[str] = None,
) -> List[dict]:
    query = db.query(AiMemory).filter(AiMemory.user_id == user_id)
    if relation_id:
        query = query.filter(AiMemory.relation_id == relation_id)
    if memory_type:
        query = query.filter(AiMemory.memory_type == memory_type)
    memories = query.order_by(AiMemory.created_at.desc()).all()
    return [_to_dict(m) for m in memories]


def get_couple_memories(db: Session, user_id: int, relation_id: int) -> List[dict]:
    memories = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.relation_id == relation_id,
                AiMemory.visibility == "couple",
            )
        )
        .order_by(AiMemory.created_at.desc())
        .all()
    )
    return [_to_dict(m) for m in memories]


def get_memory_context(
    db: Session,
    user_id: int,
    relation_id: int,
    limit: int = 10,
    query: str = "",
) -> str:
    """prompt 注入用的记忆片段。

    P0-4 起改走 `memory_retrieval.retrieve_memory_items`：
      - 传了 query → 向量相关性召回（阈值 0.35），无命中/超时降级近因
      - 未传 query → 近因降级（agent 工具等无 query 调用方行为不变）
    可见性：自己 private + 本关系 couple（约束①），不含对方 private。
    """
    from app.services.memory_retrieval import (
        format_memory_context,
        retrieve_memory_items,
    )

    items = retrieve_memory_items(
        db, user_id, relation_id, query=query or "", limit=max(limit, 10)
    )
    return format_memory_context(items)


def update_visibility(db: Session, memory_id: int, user_id: int, visibility: str) -> dict:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    memory.visibility = visibility
    db.commit()
    # 一致性维护（非修 bug）：当前召回不读 metadata.visibility（走 SQL 双保险），
    # 但同步 chroma 避免日后有人按 metadata 过滤时拿到陈旧值。
    try:
        from app.services.memory_retrieval import _get_collection

        col = _get_collection()
        if col is not None:
            got = col.get(ids=[str(memory_id)])
            if got and got.get("ids"):
                metas = (got.get("metadatas") or [None])[0] or {}
                metas = dict(metas)
                metas["visibility"] = visibility
                col.update(ids=[str(memory_id)], metadatas=[metas])
    except Exception as exc:
        logger.warning("[MEMORY] 同步向量 metadata 失败 id=%s: %s", memory_id, exc)
    return _to_dict(memory)


def delete_memory(db: Session, memory_id: int, user_id: int) -> None:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    db.delete(memory)
    db.commit()
    # 缺陷三：DB 是权威、向量是派生——commit 成功后同步删向量。
    # 向量库异常不能让删除接口失败（只记日志）。
    try:
        from app.services.memory_retrieval import _get_collection

        col = _get_collection()
        if col is not None:
            col.delete(ids=[str(memory_id)])
    except Exception as exc:
        logger.warning("[MEMORY] 同步删向量失败 id=%s: %s", memory_id, exc)


def _to_dict(memory: AiMemory) -> dict:
    return {
        "id": memory.id,
        "user_id": memory.user_id,
        "relation_id": memory.relation_id,
        "memory_type": memory.memory_type,
        "memory_text": memory.memory_text,
        "visibility": memory.visibility,
        "created_at": memory.created_at.isoformat() if memory.created_at else None,
    }


# --------------------------------------------------------------------------
# 记忆沉淀（写入端）
#
# 背景：`create_memory` 此前**全仓零调用**，导致 `AiMemory` 表永远为空、
# `get_memory_context()` 永远返回 ""、客户端「AI 记忆」页点开就是空列表。
# 这一节把写入端接上：一轮对话结束后，由模型判断其中是否含值得长期记住的信息。
#
# 几个刻意的取舍：
# 1. 宁可漏记，不要滥记 —— 靠 `should_remember` 闸门，噪音会通过
#    `get_memory_context()` 进入后续每一轮 prompt，代价比漏记大得多；
# 2. 记忆是锦上添花，**绝不能影响对话主链路** —— 所有异常在此吞掉只记日志；
# 3. 不阻塞响应 —— 放到后台线程 + 独立会话里跑。
# --------------------------------------------------------------------------

def _is_duplicate_memory(
    db: Session, user_id: int, relation_id: int, memory_text: str
) -> bool:
    """按 (user_id, relation_id, memory_text) 完全相等去重。

    从 distill_and_save 内联逻辑抽出：AI 抽取路径与结构化直写路径
    （纪念日 / 量表，不经模型）必须共用这一套，否则重复事件会刷重复记忆。
    """
    return (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.user_id == user_id,
                AiMemory.relation_id == relation_id,
                AiMemory.memory_text == memory_text,
            )
        )
        .first()
        is not None
    )


def save_structured_memory(
    db: Session,
    user_id: int,
    relation_id: int,
    memory_text: str,
    memory_type: Optional[str] = None,
) -> Optional[dict]:
    """不经 AI、直接构造好的记忆落库。复用 `_is_duplicate_memory` 去重。

    供纪念日（名称+日期）、量表（关系模式）这类**确定性文本**使用——
    内容没有歧义，不值得花一次模型调用；但去重与异常吞掉的纪律与 AI 路径一致。
    """
    text = (memory_text or "").strip()
    if not text:
        return None
    mtype = memory_type if memory_type in MEMORY_TYPES else _DEFAULT_MEMORY_TYPE
    try:
        if _is_duplicate_memory(db, user_id, relation_id, text):
            return None
        saved = create_memory(db, user_id, relation_id, mtype, text)
        logger.info("[MEMORY] 结构化落库 user=%s type=%s", user_id, mtype)
        return saved
    except Exception:
        logger.exception("[MEMORY] 结构化记忆落库失败 user=%s", user_id)
        db.rollback()
        return None


def build_distill_user_message(
    scene_key: str,
    user_input: str,
    assistant_text: str = "",
    focus: str = "",
    context: str = "",
) -> str:
    """组装提交给抽取模型的 user 消息。

    - `focus` 为空 → **对话模板，与本函数抽出之前的硬编码逐字节一致**
      （chat 链路零回归的保证，改动四坑 1）
    - `focus` 非空 → 事件模板：场景 + 可选事件描述 + 抽取重点 + 内容。
      信件/纪念馆/双视角等非对话源走这条，避免硬套「AI 回复」空段落
    """
    if not focus:
        return "场景：%s\n\n用户说：%s\n\nAI 回复：%s" % (
            scene_key,
            user_input,
            assistant_text or "",
        )
    parts = ["场景：%s" % scene_key]
    if context:
        parts.append("事件：%s" % context)
    parts.append("抽取重点：%s" % focus)
    parts.append("内容：\n%s" % user_input)
    return "\n\n".join(parts)


def distill_and_save(
    db: Session,
    user_id: int,
    relation_id: int,
    scene_key: str,
    user_input: str,
    assistant_text: str,
    focus: str = "",
    context: str = "",
) -> Optional[dict]:
    """从一轮对话/一个事件里抽取一条长期记忆并落库。返回新记忆，未写库则返回 None。

    调用方需传入可用的 `db`（流式链路请用 `distill_in_background`，
    因为那时请求级会话已经销毁）。

    `focus` / `context` 为可选（P0-3 改动四）：为空时行为与旧版逐字节一致。
    """
    text = (user_input or "").strip()
    if len(text) < _MIN_INPUT_LEN:
        return None

    try:
        result = distill_llm.invoke_structured(
            [
                {"role": "system", "content": MEMORY_DISTILL_PROMPT},
                {
                    "role": "user",
                    "content": build_distill_user_message(
                        scene_key, user_input, assistant_text,
                        focus=focus, context=context,
                    ),
                },
            ],
            scene="memory_distill",
            temperature=0.2,
            max_tokens=300,
        )
    except LlmError as exc:
        logger.warning("[MEMORY] 记忆抽取失败 scene=%s: %s", scene_key, exc)
        return None
    except Exception:
        logger.exception("[MEMORY] 记忆抽取异常 scene=%s", scene_key)
        return None

    if not getattr(result, "should_remember", False):
        return None

    memory_type = getattr(result, "memory_type", None)
    if memory_type not in MEMORY_TYPES:
        memory_type = _DEFAULT_MEMORY_TYPE

    memory_text = (getattr(result, "memory_text", "") or "").strip()
    if not memory_text:
        return None

    try:
        if _is_duplicate_memory(db, user_id, relation_id, memory_text):
            return None
        saved = create_memory(db, user_id, relation_id, memory_type, memory_text)
        logger.info("[MEMORY] 已沉淀记忆 user=%s type=%s", user_id, memory_type)
        return saved
    except Exception:
        logger.exception("[MEMORY] 记忆落库失败 user=%s", user_id)
        db.rollback()
        return None


def distill_in_background(
    user_id: int,
    relation_id: int,
    scene_key: str,
    user_input: str,
    assistant_text: str,
    focus: str = "",
    context: str = "",
) -> None:
    """后台线程里沉淀记忆：开独立会话，不阻塞也不影响对话响应。

    必须在**请求级会话之外**调用（流式链路的响应体是在请求会话销毁之后
    才被消费的），因此这里自己开 SessionLocal。

    测试隔离：`COUPLE_DISABLE_MEMORY_DISTILL=1` 时直接 return——
    daemon 线程在测试进程退出后才落库，用例 finally 抓不到，会污染开发库。
    默认不设该变量，线上行为零变化。
    """
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return None
    if not llm.api_key:
        return None
    if len((user_input or "").strip()) < _MIN_INPUT_LEN:
        return None

    def _worker():
        from app.core.database import SessionLocal

        db = SessionLocal()
        try:
            distill_and_save(
                db, user_id, relation_id, scene_key, user_input, assistant_text,
                focus=focus, context=context,
            )
        except Exception:
            logger.exception("[MEMORY] 后台沉淀异常 user=%s", user_id)
        finally:
            db.close()

    threading.Thread(
        target=_worker, name="ai-memory-distill", daemon=True
    ).start()
    return None


def distill_session_summary_in_background(
    session_id: int,
    user_id: int,
    relation_id: int,
    scene_key: str,
) -> None:
    """P0-10A 改动七：会话归档后蒸馏 ≤120 字叙事摘要写入 ai_memory。

    - memory_type="session_summary"、visibility="private"（合法枚举仅 private/couple）
    - relation_id 必传（NOT NULL）
    - **不做 embedding**（P0-4 --backfill 负责；本路径直插 AiMemory，不走
      create_memory 的向量化挂钩）
    - 复用 distill_llm（qwen-turbo）与 distill_in_background 同款开关/线程纪律
    - 默认不设开关 → 线上行为不变
    """
    if os.getenv("COUPLE_DISABLE_MEMORY_DISTILL") == "1":
        return None
    if not distill_llm.api_key:
        return None

    def _worker():
        from app.core.database import SessionLocal
        from app.models.ai import AiChatMessage

        db = SessionLocal()
        try:
            msgs = (
                db.query(AiChatMessage)
                .filter(AiChatMessage.session_id == session_id)
                .order_by(AiChatMessage.created_at.asc())
                .all()
            )
            if not msgs:
                return
            conversation = "\n".join(
                "%s: %s" % (m.role, (m.content or "")[:500]) for m in msgs
            )[:6000]
            raw = distill_llm.invoke(
                [
                    {
                        "role": "user",
                        "content": SESSION_SUMMARY_PROMPT.format(
                            conversation=conversation
                        ),
                    }
                ],
                scene="session_summary",
                temperature=0.3,
                max_tokens=200,
            )
            summary = (raw or "").strip()
            if not summary:
                return
            if len(summary) > 120:
                summary = summary[:119] + "…"
            # 直插：绕过 create_memory 的 vectorize 挂钩（约束：本路径不做 embedding）
            # 也不做字面去重——session_summary 每段会话一条，天然不重复
            row = AiMemory(
                user_id=user_id,
                relation_id=relation_id,
                memory_type="session_summary",
                memory_text=summary,
                visibility="private",
            )
            db.add(row)
            db.commit()
            logger.info(
                "[MEMORY] 会话摘要已沉淀 session=%s len=%d",
                session_id,
                len(summary),
            )
        except Exception:
            logger.exception(
                "[MEMORY] 会话摘要蒸馏失败 session=%s", session_id
            )
            db.rollback()
        finally:
            db.close()

    threading.Thread(
        target=_worker, name="session-summary", daemon=True
    ).start()
    return None
