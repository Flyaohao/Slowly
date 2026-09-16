import os
import uuid
from typing import Optional

from sqlalchemy.orm import Session

from app.repositories import user_repo
from app.security.password import hash_password, verify_password
from app.security.jwt import create_private_access_token


def get_profile(db: Session, user_id: int) -> dict:
    user = user_repo.get_user_by_id(db, user_id)
    profile = user_repo.get_profile_by_user_id(db, user_id)
    if not user or not profile:
        raise ValueError("10002")

    return {
        "user_id": user.id,
        "email": user.email,
        "nickname": profile.nickname,
        "avatar_url": profile.avatar_url,
        "gender": profile.gender,
        "birthday": str(profile.birthday) if profile.birthday else None,
        "city": profile.city,
        "signature": profile.signature,
        "love_anniversary": str(profile.love_anniversary) if profile.love_anniversary else None,
        "email_notify_enabled": bool(profile.email_notify_enabled),
    }


def update_profile(db: Session, user_id: int, data: dict) -> dict:
    profile = user_repo.update_profile(db, user_id, data)
    if not profile:
        raise ValueError("10002")
    return get_profile(db, user_id)


def upload_avatar(db: Session, user_id: int, file_content: bytes, filename: str) -> str:
    ext = os.path.splitext(filename)[1].lower()
    if ext not in (".jpg", ".jpeg", ".png", ".webp"):
        raise ValueError("10003")

    upload_dir = "uploads/avatars"
    os.makedirs(upload_dir, exist_ok=True)
    new_filename = f"{uuid.uuid4().hex}{ext}"
    filepath = os.path.join(upload_dir, new_filename)

    with open(filepath, "wb") as f:
        f.write(file_content)

    avatar_url = f"/uploads/avatars/{new_filename}"
    user_repo.update_avatar(db, user_id, avatar_url)
    return avatar_url


def set_private_password(db: Session, user_id: int, password: str) -> None:
    password_hash = hash_password(password)
    user_repo.update_private_password(db, user_id, password_hash)


def get_notification_pref(db: Session, user_id: int) -> dict:
    """读通知偏好。顺带返回邮箱地址与「能不能发」，
    让设置页能直接说明发到哪个邮箱、以及 SMTP 未配置时置灰开关。"""
    from app.services import email_service

    user = user_repo.get_user_by_id(db, user_id)
    profile = user_repo.get_profile_by_user_id(db, user_id)
    if not user or not profile:
        raise ValueError("10002")

    return {
        "email_notify_enabled": bool(profile.email_notify_enabled),
        "email": user.email,
        "email_ready": email_service.smtp_configured(),
    }


def set_notification_pref(db: Session, user_id: int, enabled: bool) -> dict:
    profile = user_repo.update_email_notify(db, user_id, enabled)
    if not profile:
        raise ValueError("10002")
    return get_notification_pref(db, user_id)


def verify_private_password(db: Session, user_id: int, password: str) -> str:
    profile = user_repo.get_profile_by_user_id(db, user_id)
    if not profile or not profile.private_password_hash:
        raise ValueError("10004")

    if not verify_password(password, profile.private_password_hash):
        raise ValueError("10005")

    return create_private_access_token(user_id)
