from sqlalchemy.orm import Session
from typing import Optional

from app.models.user import User
from app.models.user_profile import UserProfile


def get_user_by_id(db: Session, user_id: int) -> Optional[User]:
    return db.query(User).filter(User.id == user_id).first()


def get_user_by_email(db: Session, email: str) -> Optional[User]:
    return db.query(User).filter(User.email == email).first()


def create_user(db: Session, email: str, password_hash: str) -> User:
    user = User(email=email, password_hash=password_hash)
    db.add(user)
    db.flush()
    profile = UserProfile(user_id=user.id)
    db.add(profile)
    db.commit()
    db.refresh(user)
    return user


def get_profile_by_user_id(db: Session, user_id: int) -> Optional[UserProfile]:
    return db.query(UserProfile).filter(UserProfile.user_id == user_id).first()


def update_profile(db: Session, user_id: int, data: dict) -> Optional[UserProfile]:
    profile = get_profile_by_user_id(db, user_id)
    if profile is None:
        return None
    for key, value in data.items():
        if value is not None:
            setattr(profile, key, value)
    db.commit()
    db.refresh(profile)
    return profile


def update_avatar(db: Session, user_id: int, avatar_url: str) -> Optional[UserProfile]:
    profile = get_profile_by_user_id(db, user_id)
    if profile is None:
        return None
    profile.avatar_url = avatar_url
    db.commit()
    db.refresh(profile)
    return profile


def update_private_password(db: Session, user_id: int, password_hash: str) -> Optional[UserProfile]:
    profile = get_profile_by_user_id(db, user_id)
    if profile is None:
        return None
    profile.private_password_hash = password_hash
    db.commit()
    db.refresh(profile)
    return profile


def update_has_couple(db: Session, user_id: int, value: bool) -> None:
    user = get_user_by_id(db, user_id)
    if user:
        user.has_couple = value
        db.commit()
