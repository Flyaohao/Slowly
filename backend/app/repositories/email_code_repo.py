"""邮箱验证码仓储。"""

from datetime import datetime
from typing import Optional

from sqlalchemy.orm import Session

from app.models.email_verification import EmailVerificationCode


def create_code(db: Session, email: str, code: str, expires_at: datetime, purpose: str = "reset_password") -> None:
    """下发新验证码：旧码全部作废（删掉未使用的），再插入新记录。"""
    db.query(EmailVerificationCode).filter(
        EmailVerificationCode.email == email,
        EmailVerificationCode.used_at.is_(None),
    ).delete()
    record = EmailVerificationCode(
        email=email,
        code=code,
        purpose=purpose,
        expires_at=expires_at,
    )
    db.add(record)
    db.commit()


def get_valid_code(db: Session, email: str, purpose: str = "reset_password") -> Optional[EmailVerificationCode]:
    """取该邮箱当前有效的验证码（未使用且未过期）。"""
    return (
        db.query(EmailVerificationCode)
        .filter(
            EmailVerificationCode.email == email,
            EmailVerificationCode.purpose == purpose,
            EmailVerificationCode.used_at.is_(None),
            EmailVerificationCode.expires_at > datetime.utcnow(),
        )
        .order_by(EmailVerificationCode.id.desc())
        .first()
    )


def mark_used(db: Session, record: EmailVerificationCode) -> None:
    record.used_at = datetime.utcnow()
    db.commit()
