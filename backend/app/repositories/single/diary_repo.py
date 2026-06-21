from sqlalchemy.orm import Session
from typing import Optional, List
from datetime import datetime

from app.models.diary_entry import DiaryEntry


def create(
    db: Session,
    user_id: int,
    title: str,
    content: str,
    mood: Optional[str] = None,
    weather: Optional[str] = None,
) -> DiaryEntry:
    entry = DiaryEntry(
        user_id=user_id,
        title=title,
        content=content,
        mood=mood,
        weather=weather,
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


def get_by_id(db: Session, entry_id: int, user_id: int) -> Optional[DiaryEntry]:
    return (
        db.query(DiaryEntry)
        .filter(
            DiaryEntry.id == entry_id,
            DiaryEntry.user_id == user_id,
            DiaryEntry.deleted_at.is_(None),
        )
        .first()
    )


def get_list(
    db: Session,
    user_id: int,
    page: int = 1,
    limit: int = 20,
    filter_type: str = "all",
) -> List[DiaryEntry]:
    query = db.query(DiaryEntry).filter(
        DiaryEntry.user_id == user_id,
        DiaryEntry.deleted_at.is_(None),
    )

    if filter_type == "favorite":
        query = query.filter(DiaryEntry.is_favorite == True)
    elif filter_type == "week":
        from datetime import timedelta
        week_ago = datetime.utcnow() - timedelta(days=7)
        query = query.filter(DiaryEntry.created_at >= week_ago)
    elif filter_type == "month":
        from datetime import timedelta
        month_ago = datetime.utcnow() - timedelta(days=30)
        query = query.filter(DiaryEntry.created_at >= month_ago)

    return (
        query.order_by(DiaryEntry.created_at.desc())
        .offset((page - 1) * limit)
        .limit(limit)
        .all()
    )


def update(
    db: Session,
    entry_id: int,
    user_id: int,
    data: dict,
) -> Optional[DiaryEntry]:
    entry = get_by_id(db, entry_id, user_id)
    if not entry:
        return None
    for key, value in data.items():
        if value is not None and hasattr(entry, key):
            setattr(entry, key, value)
    db.commit()
    db.refresh(entry)
    return entry


def soft_delete(db: Session, entry_id: int, user_id: int) -> bool:
    entry = get_by_id(db, entry_id, user_id)
    if not entry:
        return False
    entry.deleted_at = datetime.utcnow()
    db.commit()
    return True


def toggle_favorite(db: Session, entry_id: int, user_id: int) -> Optional[DiaryEntry]:
    entry = get_by_id(db, entry_id, user_id)
    if not entry:
        return None
    entry.is_favorite = not entry.is_favorite
    db.commit()
    db.refresh(entry)
    return entry


def batch_soft_delete(db: Session, entry_ids: List[int], user_id: int) -> int:
    count = (
        db.query(DiaryEntry)
        .filter(
            DiaryEntry.id.in_(entry_ids),
            DiaryEntry.user_id == user_id,
            DiaryEntry.deleted_at.is_(None),
        )
        .update({"deleted_at": datetime.utcnow()}, synchronize_session=False)
    )
    db.commit()
    return count


def get_recent(db: Session, user_id: int, limit: int = 3) -> List[DiaryEntry]:
    return (
        db.query(DiaryEntry)
        .filter(
            DiaryEntry.user_id == user_id,
            DiaryEntry.deleted_at.is_(None),
        )
        .order_by(DiaryEntry.created_at.desc())
        .limit(limit)
        .all()
    )
