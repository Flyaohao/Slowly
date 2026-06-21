from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.avatar import AiAvatar, AiAvatarAsset


def get_avatar_by_relation_id(db: Session, relation_id: int) -> Optional[AiAvatar]:
    return db.query(AiAvatar).filter(AiAvatar.relation_id == relation_id).first()


def create_avatar(db: Session, data: dict) -> AiAvatar:
    avatar = AiAvatar(**data)
    db.add(avatar)
    db.flush()
    return avatar


def update_avatar(db: Session, avatar: AiAvatar, data: dict) -> AiAvatar:
    for key, value in data.items():
        if value is not None:
            setattr(avatar, key, value)
    db.flush()
    return avatar


def list_assets(db: Session) -> List[AiAvatarAsset]:
    return db.query(AiAvatarAsset).order_by(AiAvatarAsset.asset_type).all()


def get_or_create_avatar(db: Session, relation_id: int) -> AiAvatar:
    avatar = get_avatar_by_relation_id(db, relation_id)
    if not avatar:
        avatar = create_avatar(db, {"relation_id": relation_id})
    return avatar
