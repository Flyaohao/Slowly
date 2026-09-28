"""统一记忆事件入口（P0-3）。

任何业务产生「用户真实表达」都走这里，业务侧只需构造 :class:`MemoryEvent`
并调用 ``distill_event_in_background``，不需要知道 prompt 怎么拼。

设计约束（任务单 P0-3）：
  - 复用 ``memory_service`` 的闸门 / 20-40 字 / 4 类 / qwen-turbo / 后台线程，
    **不改** MEMORY_DISTILL_PROMPT、should_remember 与既有文案
  - ``focus`` 为空的旧调用路径（chat 链路）逐字节不变
  - 单次输入截断 1200 字，防止信件正文撑爆一轮抽取
  - 纪念日 / 量表走结构化直写，复用 ``save_structured_memory`` 的同一套去重

源覆盖（P-C1 §5 起 7 源）：
  letter / museum / dual / diary → AI 抽取（带 focus）
  anniversary / questionnaire → 结构化直写（不经 AI）
  chat_summary → 不走本模块（memory_service.distill_session_summary_in_background）
  practice → 本轮不做（状态机自相矛盾，backlog 记一笔）

P-C1 §4：distill_event 两段写——
  1) 事件行**无条件先写**（memory_type='事件'，带 occurred_at/source/source_id/importance）；
  2) 蒸馏行（AI 源且 should_remember=True / 结构化源直写）不变。
  异常吞在本函数内，绝不冒泡到业务调用方（记忆是锦上添花）。
"""
import logging
import threading
from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, List, Optional

from sqlalchemy.orm import Session

from app.services.memory_service import (
    EVENT_MEMORY_TYPE,
    _is_duplicate_memory,
    create_memory,
    distill_and_save,
    save_structured_memory,
)
from app.services.user_ai_config_service import AiConfigMissingError
from app.services.user_ai_config_service import resolve as resolve_user_ai_config

logger = logging.getLogger("couple.memory_events")

#: 单次事件内容上限（改动四坑 2）：超长取前 N 字
CONTENT_MAX_LEN = 1200

#: 走 AI 抽取的源 → 抽取重点（改动四坑 1 的 focus 参数）
AI_SOURCE_FOCUS: Dict[str, str] = {
    "letter": "信的核心诉求",
    "museum": "共同记忆锚点",
    "dual": "双方认知差异——同一件事两个人看到的不一样，这是最高价值信号",
    "diary": "日记里的情绪与具体处境",
}

#: 走结构化直写（不经 AI）的源
STRUCTURED_SOURCES = frozenset({"anniversary", "questionnaire"})

#: P-C1 §4：事件行 importance 默认表（集中在一处便于调参）。
#: 2 = 用户手动标星（P-C3 枚举位，本轮不做 UI）。
IMPORTANCE_STAR = 2
IMPORTANCE_BY_SOURCE: Dict[str, int] = {
    "dual": 1,                        # 同一件事两个人认知不同 = 最高价值信号
    "anniversary": 1,                 # 确定性事实，长期有效
    "questionnaire": 1,               # 确定性事实，长期有效
    "letter": 0,                      # 常规内容，靠相似度召回即可
    "museum": 0,
    "diary": 0,
    "chat_summary": 0,
}

#: P-C1 §2：事件行可见性**继承源**（枚举只有 private/couple）。
#: 未列出的源（museum、chat_summary 等）保守取 private——不扩大现有可见面。
VISIBILITY_BY_SOURCE: Dict[str, str] = {
    "letter": "private",
    "diary": "private",
    "dual": "couple",
    "questionnaire": "couple",
    "anniversary": "couple",
}

#: 事件行 memory_text 上限（带 context 前缀防不同事件撞文本撞去重）
EVENT_TEXT_LEN = 200


@dataclass
class MemoryEvent:
    """一次可沉淀为记忆的业务事件。

    source 取值见模块 docstring；source_id 为原记录 id 用于溯源；
    extra 可放 ``context``（事件描述，如「纪念馆新增：第一次看海」）
    及其它源特有字段。
    """

    source: str
    source_id: int
    user_id: int
    relation_id: int
    content: str
    occurred_at: datetime
    extra: dict = field(default_factory=dict)


def _write_event_row(event: MemoryEvent, content: str, db: Session) -> Optional[dict]:
    """P-C1 §4.1：事件行无条件先写。命中去重则跳过；异常吞掉返回 None。

    memory_text = (context + content)[:200]——带 context 前缀，
    防不同事件撞文本撞去重（去重键仍是三元组，_is_duplicate_memory 不改）。
    """
    try:
        context = (event.extra or {}).get("context", "")
        event_text = (context + content)[:EVENT_TEXT_LEN]
        if not event_text.strip():
            return None
        if _is_duplicate_memory(db, event.user_id, event.relation_id, event_text):
            return None
        return create_memory(
            db,
            event.user_id,
            event.relation_id,
            EVENT_MEMORY_TYPE,
            event_text,
            visibility=VISIBILITY_BY_SOURCE.get(event.source, "private"),
            occurred_at=event.occurred_at,
            source=event.source,
            source_id=event.source_id,
            importance=IMPORTANCE_BY_SOURCE.get(event.source, 0),
        )
    except Exception:
        logger.exception(
            "[MEMORY_EVENTS] 事件行写入失败 source=%s id=%s",
            event.source,
            event.source_id,
        )
        try:
            db.rollback()
        except Exception:
            pass
        return None


def distill_event(event: MemoryEvent, db: Optional[Session] = None) -> List[dict]:
    """统一抽取入口。返回落库的记忆列表（0 或 2 条，不抛异常）。

    P-C1 §4 两段：
      1) 事件行（无条件先写，memory_type='事件'，带三要素 + importance）；
      2) 蒸馏行——AI 源走 ``distill_and_save(focus=…, context=…)``、
         结构化源走 ``save_structured_memory``（复用同一套去重）；
         两路都透传 occurred_at/source/source_id/importance（§3.1）。

    ``db`` 为空时自开 SessionLocal（后台线程路径）；测试可注入桩 db。
    """
    content = (event.content or "")[:CONTENT_MAX_LEN]
    if not content.strip():
        return []

    own_db = db is None
    if own_db:
        from app.core.database import SessionLocal

        db = SessionLocal()
    try:
        saved_rows: List[dict] = []

        # 1) 事件行：无条件先写，异常吞在本层（绝不冒泡到业务调用方）
        event_row = _write_event_row(event, content, db)
        if event_row:
            saved_rows.append(event_row)

        # 2) 蒸馏行（原有路径，三要素透传）
        context = (event.extra or {}).get("context", "")
        common_kwargs = dict(
            occurred_at=event.occurred_at,
            source=event.source,
            source_id=event.source_id,
            importance=IMPORTANCE_BY_SOURCE.get(event.source, 0),
        )
        if event.source in STRUCTURED_SOURCES:
            saved = save_structured_memory(
                db, event.user_id, event.relation_id, content, **common_kwargs
            )
        else:
            focus = AI_SOURCE_FOCUS.get(event.source, "")
            saved = distill_and_save(
                db,
                event.user_id,
                event.relation_id,
                scene_key=event.source,
                user_input=content,
                assistant_text="",
                focus=focus,
                context=context,
                **common_kwargs,
            )
        if saved:
            saved_rows.append(saved)
        return saved_rows
    except Exception:
        # 记忆是锦上添花，任何异常都不能冒泡到业务调用方
        logger.exception(
            "[MEMORY_EVENTS] distill 失败 source=%s id=%s",
            event.source,
            event.source_id,
        )
        return []
    finally:
        if own_db:
            db.close()


def write_diary_memory(
    db: Session, user_id: int, entry, *, background: bool = True
) -> bool:
    """P-C1 §5.1：日记 → 记忆。**取到 active relation 才写，取到才回填
    diary_entry.relation_id；取不到不写（保持单身日记边界）。**

    ``entry`` 为 DiaryEntry 实例；``occurred_at`` 用日记的业务时间
    （created_at），不是记忆入库时间。``background=False`` 供测试同步落库
    （避免 daemon 线程与 finally 删除竞态）。
    """
    from app.repositories.couple_repo import get_active_relation_by_user

    relation = get_active_relation_by_user(db, user_id)
    if relation is None:
        return False

    entry.relation_id = relation.id
    db.commit()

    event = MemoryEvent(
        source="diary",
        source_id=entry.id,
        user_id=user_id,
        relation_id=relation.id,
        content="%s\n%s" % (entry.title or "", entry.content or ""),
        occurred_at=entry.created_at or datetime.now(),
        extra={"context": "日记：%s" % (entry.title or "")},
    )
    if background:
        distill_event_in_background(event)
    else:
        distill_event(event, db=db)
    return True


def distill_event_in_background(event: MemoryEvent) -> None:
    """守护线程版本：异常吞掉只记日志，绝不影响调用方主链路。

    参照 ``memory_service.distill_in_background``：独立 SessionLocal、
    daemon 线程、无返回值。AI 源在缺少 API Key 时直接跳过；
    结构化源不依赖 Key，照常执行。
    """
    if not (event.content or "").strip():
        return None
    # v5.0 D1/D2：AI 源按事件归属用户判定配置；未配置只写事件行（不依赖模型）
    if event.source not in STRUCTURED_SOURCES:
        from app.core.database import SessionLocal

        _probe_db = SessionLocal()
        try:
            resolve_user_ai_config(_probe_db, event.user_id)
        except AiConfigMissingError:
            def _row_only():
                from app.core.database import SessionLocal

                db = SessionLocal()
                try:
                    _write_event_row(event, (event.content or "")[:CONTENT_MAX_LEN], db)
                except Exception:
                    logger.exception(
                        "[MEMORY_EVENTS] 事件行后台写入失败 source=%s", event.source
                    )
                finally:
                    db.close()

            threading.Thread(
                target=_row_only, name="memory-event-row-%s" % event.source, daemon=True
            ).start()
            return None
        finally:
            _probe_db.close()

    def _worker():
        try:
            distill_event(event)
        except Exception:
            logger.exception(
                "[MEMORY_EVENTS] 后台沉淀异常 source=%s id=%s",
                event.source,
                event.source_id,
            )

    threading.Thread(
        target=_worker, name="memory-event-%s" % event.source, daemon=True
    ).start()
    return None
