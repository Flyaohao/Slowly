import re
import random
import string
from datetime import datetime, timedelta, timezone
from typing import Optional, Dict

from sqlalchemy.orm import Session

from app.repositories import user_repo
from app.security.password import hash_password, verify_password
from app.security.jwt import (
    create_access_token,
    create_refresh_token,
    decode_token,
)
from app.utils.validators import validate_password_strength

_verification_codes: Dict[str, dict] = {}


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


def forgot_password(db: Session, email: str) -> None:
    user = user_repo.get_user_by_email(db, email)
    if not user:
        return

    code = _generate_code()
    _verification_codes[email] = {
        "code": code,
        "expires_at": datetime.now(timezone.utc) + timedelta(minutes=10),
    }


def reset_password(db: Session, email: str, code: str, new_password: str) -> None:
    record = _verification_codes.get(email)
    if not record:
        raise ValueError("20003")
    if record["code"] != code or datetime.now(timezone.utc) > record["expires_at"]:
        raise ValueError("20003")

    if not validate_password_strength(new_password):
        raise ValueError("10001")

    user = user_repo.get_user_by_email(db, email)
    if not user:
        raise ValueError("20003")

    user.password_hash = hash_password(new_password)
    db.commit()
    _verification_codes.pop(email, None)
