# -*- coding: utf-8 -*-
"""安全护栏审计事件的落库。

审计是**旁路能力**：写失败只打日志，绝不让主链路跟着失败。
所以 log_event 自带独立会话、吞掉一切异常——调用方（可能正处在
流式生成器、被拦截早退、事务边界尴尬的位置）不需要考虑会话归属。
"""
import json
import logging

from app.core.database import SessionLocal
from app.models.safety_event import SafetyEvent

logger = logging.getLogger("couple.safety")


def log_event(
    user_id,
    scene: str,
    source: str,
    risk_level: str,
    hits,
) -> None:
    """同步写一条安全审计事件。任何失败都不抛出。"""
    db = SessionLocal()
    try:
        db.add(
            SafetyEvent(
                user_id=int(user_id) if user_id else None,
                scene=scene or "chat",
                source=source,
                risk_level=risk_level,
                hit_keywords=json.dumps(list(hits or []), ensure_ascii=False),
            )
        )
        db.commit()
    except Exception as exc:  # noqa: BLE001 —— 审计失败只记日志
        logger.warning("Failed to persist safety event: %s", exc)
        try:
            db.rollback()
        except Exception:  # noqa: BLE001
            pass
    finally:
        db.close()


def get_recent_events(db, limit: int = 50):
    """回查最近的拦截事件（运维/测试用）。"""
    return (
        db.query(SafetyEvent)
        .order_by(SafetyEvent.id.desc())
        .limit(limit)
        .all()
    )
