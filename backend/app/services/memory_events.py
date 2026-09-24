"""统一记忆事件入口（P0-3）。

任何业务产生「用户真实表达」都走这里，业务侧只需构造 :class:`MemoryEvent`
并调用 ``distill_event_in_background``，不需要知道 prompt 怎么拼。

设计约束（任务单 P0-3）：
  - 复用 ``memory_service`` 的闸门 / 20-40 字 / 4 类 / qwen-turbo / 后台线程，
    **不改** MEMORY_DISTILL_PROMPT、should_remember 与既有文案
  - ``focus`` 为空的旧调用路径（chat 链路）逐字节不变
  - 单次输入截断 1200 字，防止信件正文撑爆一轮抽取
  - 纪念日 / 量表走结构化直写，复用 ``save_structured_memory`` 的同一套去重

源覆盖（v1.2 裁决后 5 源）：
  letter / museum / dual  → AI 抽取（带 focus）
  anniversary / questionnaire → 结构化直写（不经 AI）
  diary  → 不做（DiaryEntry 无 relation_id，见任务单差异 1 裁决）
  practice → 本轮不做（真机验证判定 b，见差异 2 裁决与 §0.5.4#2）
"""
import logging
import threading
from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, List, Optional

from sqlalchemy.orm import Session

from app.services.memory_service import (
    distill_and_save,
    save_structured_memory,
)
from app.services.llm_client import llm

logger = logging.getLogger("couple.memory_events")

#: 单次事件内容上限（改动四坑 2）：超长取前 N 字
CONTENT_MAX_LEN = 1200

#: 走 AI 抽取的源 → 抽取重点（改动四坑 1 的 focus 参数）
AI_SOURCE_FOCUS: Dict[str, str] = {
    "letter": "信的核心诉求",
    "museum": "共同记忆锚点",
    "dual": "双方认知差异——同一件事两个人看到的不一样，这是最高价值信号",
}

#: 走结构化直写（不经 AI）的源
STRUCTURED_SOURCES = frozenset({"anniversary", "questionnaire"})


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


def distill_event(event: MemoryEvent, db: Optional[Session] = None) -> List[dict]:
    """统一抽取入口。返回落库的记忆列表（0 或 1 条，不抛异常）。

    - AI 源：截断 content → ``distill_and_save(focus=…, context=…)``
    - 结构化源：截断 content → ``save_structured_memory``（复用同一套去重）

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
        if event.source in STRUCTURED_SOURCES:
            saved = save_structured_memory(
                db, event.user_id, event.relation_id, content
            )
            return [saved] if saved else []

        focus = AI_SOURCE_FOCUS.get(event.source, "")
        context = (event.extra or {}).get("context", "")
        saved = distill_and_save(
            db,
            event.user_id,
            event.relation_id,
            scene_key=event.source,
            user_input=content,
            assistant_text="",
            focus=focus,
            context=context,
        )
        return [saved] if saved else []
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


def distill_event_in_background(event: MemoryEvent) -> None:
    """守护线程版本：异常吞掉只记日志，绝不影响调用方主链路。

    参照 ``memory_service.distill_in_background``：独立 SessionLocal、
    daemon 线程、无返回值。AI 源在缺少 API Key 时直接跳过；
    结构化源不依赖 Key，照常执行。
    """
    if event.source not in STRUCTURED_SOURCES and not llm.api_key:
        return None
    if not (event.content or "").strip():
        return None

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
