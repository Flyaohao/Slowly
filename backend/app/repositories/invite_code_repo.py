from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime, timedelta, timezone
import random
import string

from app.models.invite_code import InviteCode


def _generate_code() -> str:
    return "".join(random.choices(string.ascii_uppercase + string.digits, k=8))


def create_code(db: Session, user_id: int) -> InviteCode:
    """为用户生成恋爱码，同时使旧恋爱码失效"""
    # 使该用户所有未使用的恋爱码失效
    db.query(InviteCode).filter(
        InviteCode.user_id == user_id,
        InviteCode.is_used == False,
    ).update({"is_used": True})

    code = InviteCode(
        user_id=user_id,
        code=_generate_code(),
        is_used=False,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=24),
    )
    db.add(code)
    db.commit()
    db.refresh(code)
    return code


def get_valid_code(db: Session, code_str: str) -> Optional[InviteCode]:
    """获取有效的恋爱码"""
    code = (
        db.query(InviteCode)
        .filter(
            InviteCode.code == code_str,
            InviteCode.is_used == False,
            InviteCode.expires_at > datetime.now(timezone.utc),
        )
        .first()
    )
    return code


def mark_used(db: Session, code_id: int, used_by: int) -> None:
    """标记恋爱码为已使用"""
    code = db.query(InviteCode).filter(InviteCode.id == code_id).first()
    if code:
        code.is_used = True
        code.used_by = used_by
        code.used_at = datetime.now(timezone.utc)
        db.commit()


def get_user_active_code(db: Session, user_id: int) -> Optional[InviteCode]:
    """获取用户当前有效的恋爱码"""
    return (
        db.query(InviteCode)
        .filter(
            InviteCode.user_id == user_id,
            InviteCode.is_used == False,
            InviteCode.expires_at > datetime.now(timezone.utc),
        )
        .first()
    )
