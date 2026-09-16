from sqlalchemy import BigInteger, ForeignKey, Index, String
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import BigIntPKMixin, TimestampMixin, Base


class NotificationEmailLog(BigIntPKMixin, TimestampMixin, Base):
    """邮件通知发送台账。

    存在的唯一理由是限流：同一用户同一类事件要做冷却（30 分钟内同类只发一封），
    全站还要有每日总量上限。这两个判断都需要知道「过去发过什么、什么时候发的」，
    光靠进程内计数在重启 / 多 worker 下会失真，所以落库。

    只存事件类型，不存邮件正文 —— 台账里不该留任何可被翻出的内容线索。
    """

    __tablename__ = "notification_email_log"
    __table_args__ = (
        Index("ix_notif_email_user_type", "user_id", "event_type", "created_at"),
    )

    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), nullable=False
    )
    event_type: Mapped[str] = mapped_column(String(40), nullable=False)
