import logging
import threading
from typing import Optional, List, Dict
from datetime import datetime

from sqlalchemy.orm import Session
from sqlalchemy import and_

from app.models.ai import AiMemory
from app.services.llm_client import llm, LlmError
from app.services.prompt_builder import MEMORY_DISTILL_PROMPT

logger = logging.getLogger("couple.memory")

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
    return _to_dict(memory)


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


def get_memory_context(db: Session, user_id: int, relation_id: int, limit: int = 10) -> str:
    memories = (
        db.query(AiMemory)
        .filter(
            and_(
                AiMemory.user_id == user_id,
                AiMemory.relation_id == relation_id,
            )
        )
        .order_by(AiMemory.created_at.desc())
        .limit(limit)
        .all()
    )
    if not memories:
        return ""
    lines = [f"- [{m.memory_type}] {m.memory_text}" for m in memories]
    return "## AI 记忆\n" + "\n".join(lines)


def update_visibility(db: Session, memory_id: int, user_id: int, visibility: str) -> dict:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    memory.visibility = visibility
    db.commit()
    return _to_dict(memory)


def delete_memory(db: Session, memory_id: int, user_id: int) -> None:
    memory = db.query(AiMemory).filter(AiMemory.id == memory_id).first()
    if not memory or memory.user_id != user_id:
        raise ValueError("50002")
    db.delete(memory)
    db.commit()


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

def distill_and_save(
    db: Session,
    user_id: int,
    relation_id: int,
    scene_key: str,
    user_input: str,
    assistant_text: str,
) -> Optional[dict]:
    """从一轮对话里抽取一条长期记忆并落库。返回新记忆，未写库则返回 None。

    调用方需传入可用的 `db`（流式链路请用 `distill_in_background`，
    因为那时请求级会话已经销毁）。
    """
    text = (user_input or "").strip()
    if len(text) < _MIN_INPUT_LEN:
        return None

    try:
        result = llm.invoke_structured(
            [
                {"role": "system", "content": MEMORY_DISTILL_PROMPT},
                {
                    "role": "user",
                    "content": "场景：%s\n\n用户说：%s\n\nAI 回复：%s"
                    % (scene_key, user_input, assistant_text or ""),
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
        duplicated = (
            db.query(AiMemory)
            .filter(
                and_(
                    AiMemory.user_id == user_id,
                    AiMemory.relation_id == relation_id,
                    AiMemory.memory_text == memory_text,
                )
            )
            .first()
        )
        if duplicated:
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
) -> None:
    """后台线程里沉淀记忆：开独立会话，不阻塞也不影响对话响应。

    必须在**请求级会话之外**调用（流式链路的响应体是在请求会话销毁之后
    才被消费的），因此这里自己开 SessionLocal。
    """
    if not llm.api_key:
        return None
    if len((user_input or "").strip()) < _MIN_INPUT_LEN:
        return None

    def _worker():
        from app.core.database import SessionLocal

        db = SessionLocal()
        try:
            distill_and_save(
                db, user_id, relation_id, scene_key, user_input, assistant_text
            )
        except Exception:
            logger.exception("[MEMORY] 后台沉淀异常 user=%s", user_id)
        finally:
            db.close()

    threading.Thread(
        target=_worker, name="ai-memory-distill", daemon=True
    ).start()
    return None
