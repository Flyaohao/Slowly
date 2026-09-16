from datetime import datetime
from typing import Optional

from sqlalchemy.orm import Session

from app.models.notification_email_log import NotificationEmailLog


def count_logs(
    db: Session,
    user_id: int,
    since: datetime,
    event_type: Optional[str] = None,
) -> int:
    """统计某用户在 `since` 之后发过多少封通知邮件。

    event_type 为 None 时统计全部类型（用于每日总量上限），
    传入时只统计该类型（用于同类事件冷却）。
    """
    query = db.query(NotificationEmailLog).filter(
        NotificationEmailLog.user_id == user_id,
        NotificationEmailLog.created_at >= since,
    )
    if event_type is not None:
        query = query.filter(NotificationEmailLog.event_type == event_type)
    return query.count()


def create_log(db: Session, user_id: int, event_type: str) -> NotificationEmailLog:
    log = NotificationEmailLog(user_id=user_id, event_type=event_type)
    db.add(log)
    db.commit()
    db.refresh(log)
    return log
