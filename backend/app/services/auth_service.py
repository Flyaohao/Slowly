import re
import random
import string
from datetime import datetime, timedelta
from typing import Optional, Dict

from sqlalchemy.orm import Session

from app.repositories import user_repo
from app.repositories import email_code_repo
from app.services import email_service
from app.core.config import EMAIL_DEV_MODE
from app.security.password import hash_password, verify_password
from app.security.jwt import (
    create_access_token,
    create_refresh_token,
    decode_token,
)
from app.utils.validators import validate_password_strength

def _generate_code() -> str:
    return "".join(random.choices(string.digits, k=6))


def register(db: Session, email: str, password: str) -> int:
    existing = user_repo.get_user_by_email(db, email)
    if existing:
        raise ValueError("20001")

    if not validate_password_strength(password):
        raise ValueError("10001")

    password_hash = hash_password(password)
    user = user_repo.create_user(db, email, password_hash)
    return user.id


def login(db: Session, email: str, password: str) -> dict:
    user = user_repo.get_user_by_email(db, email)
    if not user or not verify_password(password, user.password_hash):
        raise ValueError("20002")

    return {
        "access_token": create_access_token(user.id),
        "refresh_token": create_refresh_token(user.id),
        "token_type": "bearer",
        "mode": "couple" if user.has_couple else "single",
    }


def refresh_access_token(refresh_token_str: str) -> str:
    payload = decode_token(refresh_token_str)
    if not payload or payload.get("type") != "refresh":
        raise ValueError("20005")

    user_id = int(payload["sub"])
    return create_access_token(user_id)


def forgot_password(db: Session, email: str) -> Optional[str]:
    """发起忘记密码：生成验证码 → 落库 → 邮件发送。

    返回值：EMAIL_DEV_MODE 下返回验证码（接口回显 dev_code 便于联调），其余返回 None。
    SMTP 发送失败时抛 ValueError("20004")。
    """
    user = user_repo.get_user_by_email(db, email)
    if not user:
        # 不暴露邮箱是否注册，静默成功
        return None

    code = _generate_code()
    email_code_repo.create_code(
        db, email, code,
        expires_at=datetime.utcnow() + timedelta(minutes=10),
    )
    email_service.send_verification_code(email, code, minutes=10)

    return code if EMAIL_DEV_MODE else None


def reset_password(db: Session, email: str, code: str, new_password: str) -> None:
    record = email_code_repo.get_valid_code(db, email)
    if not record or record.code != code:
        raise ValueError("20003")

    if not validate_password_strength(new_password):
        raise ValueError("10001")

    user = user_repo.get_user_by_email(db, email)
    if not user:
        raise ValueError("20003")

    user.password_hash = hash_password(new_password)
    email_code_repo.mark_used(db, record)
    db.commit()
