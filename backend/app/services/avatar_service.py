from sqlalchemy.orm import Session
from typing import Optional

from app.models.couple_relation import CoupleRelation
from app.models.avatar import AiAvatar
from app.repositories import avatar_repo, couple_repo


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def get_avatar(db: Session, user_id: int) -> AiAvatar:
    relation = _check_relation(db, user_id)
    avatar = avatar_repo.get_or_create_avatar(db, relation.id)
    db.commit()
    return avatar


def update_avatar(db: Session, user_id: int, data: dict) -> AiAvatar:
    relation = _check_relation(db, user_id)
    avatar = avatar_repo.get_or_create_avatar(db, relation.id)
    avatar = avatar_repo.update_avatar(db, avatar, data)
    db.commit()
    db.refresh(avatar)
    return avatar


def get_assets(db: Session, user_id: int) -> list:
    _check_relation(db, user_id)
    assets = avatar_repo.list_assets(db)
    return assets


def set_voice_style(db: Session, user_id: int, voice_style: str) -> AiAvatar:
    relation = _check_relation(db, user_id)
    avatar = avatar_repo.get_or_create_avatar(db, relation.id)
    # P-B §4.4：用户手选一次即写 manual（UI 顶部「由画像自动选择」提示随之消失）。
    # 但只有**值真的变了**才算手选——客户端保存捏脸时会把当前语气原样回传，
    # 无条件写 manual 会让「只改脸」的保存把自动来源误标掉。
    # 画像驱动的自动重算是 P-D，本轮不碰。
    updates: dict = {"voice_style": voice_style}
    if avatar.voice_style != voice_style:
        updates["voice_style_source"] = "manual"
    avatar = avatar_repo.update_avatar(db, avatar, updates)
    db.commit()
    db.refresh(avatar)
    return avatar
