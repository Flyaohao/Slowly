from sqlalchemy.orm import Session
from typing import Optional, List, Dict
from datetime import datetime, timedelta

from app.models.letter import Letter
from app.models.couple_relation import CoupleRelation
from app.repositories import letter_repo, couple_repo


CALM_PERIOD_HOURS = 2
SINGLE_MODE_ALLOWED_TYPES = {"normal", "unsaid"}


def _get_partner_id(db: Session, relation_id: int, user_id: int) -> Optional[int]:
    relation = db.query(CoupleRelation).filter(CoupleRelation.id == relation_id).first()
    if not relation:
        return None
    return relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id


def _check_relation(db: Session, user_id: int) -> CoupleRelation:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")
    return relation


def create_letter(db: Session, user_id: int, data: dict) -> Letter:
    relation = couple_repo.get_active_relation_by_user(db, user_id)

    if relation:
        partner_id = _get_partner_id(db, relation.id, user_id)
        if not partner_id:
            raise ValueError("30005")
        receiver_id = data.get("receiver_id") or partner_id
        if receiver_id != partner_id:
            raise ValueError("60002")
        relation_id = relation.id
    else:
        # 单身模式：只允许 normal 和 unsaid 类型
        letter_type = data.get("letter_type", "normal")
        if letter_type not in SINGLE_MODE_ALLOWED_TYPES:
            raise ValueError("60004")
        relation_id = None
        receiver_id = user_id

    send_now = data.pop("send_now", False)
    letter_data = {
        "relation_id": relation_id,
        "sender_id": user_id,
        "receiver_id": receiver_id,
        "title": data.get("title") or "无标题",
        "content": data["content"],
        "letter_type": data.get("letter_type", "normal"),
        "is_private": data.get("is_private", False),
        "send_time": data.get("send_time"),
        "unlock_time": data.get("unlock_time"),
    }

    if send_now and relation_id:
        letter_data["status"] = "sent"
        letter_data["send_time"] = datetime.utcnow()
        if letter_data["letter_type"] == "calm":
            letter_data["unlock_time"] = datetime.utcnow() + timedelta(hours=CALM_PERIOD_HOURS)
    else:
        letter_data["status"] = "draft"

    letter = letter_repo.create_letter(db, letter_data)
    db.commit()
    db.refresh(letter)

    # P0-3：仅 status=sent 时沉淀记忆（草稿还会编辑，先不抽）；
    # 单身信 relation_id=None 挂不上 ai_memory，跳过。后台线程，不阻塞创建。
    if letter.status == "sent" and letter.relation_id:
        from app.services.memory_events import MemoryEvent, distill_event_in_background

        distill_event_in_background(MemoryEvent(
            source="letter",
            source_id=letter.id,
            user_id=user_id,
            relation_id=letter.relation_id,
            content=letter.content,
            occurred_at=datetime.utcnow(),
            extra={"context": f"信件标题：{letter.title}"},
        ))
    return letter


def list_letters(
    db: Session,
    user_id: int,
    letter_type: Optional[str] = None,
    status: Optional[str] = None,
    direction: Optional[str] = None,
    is_favorite: Optional[bool] = None,
    inbox: bool = False,
    drafts: bool = False,
    page: int = 1,
    page_size: int = 20,
) -> dict:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    relation_id = relation.id if relation else None

    items, total = letter_repo.list_letters(
        db=db,
        relation_id=relation_id,
        user_id=user_id,
        letter_type=letter_type,
        status=status,
        direction=direction,
        is_favorite=is_favorite,
        inbox=inbox,
        drafts=drafts,
        page=page,
        page_size=page_size,
    )

    filtered = []
    for letter in items:
        if letter.letter_type == "future" and letter.unlock_time:
            if letter.sender_id == user_id:
                filtered.append(letter)
            elif letter.unlock_time > datetime.utcnow():
                continue
            else:
                filtered.append(letter)
        elif letter.is_private and letter.receiver_id == user_id:
            letter_content_hidden = Letter(
                id=letter.id,
                relation_id=letter.relation_id,
                sender_id=letter.sender_id,
                receiver_id=letter.receiver_id,
                title=letter.title,
                content="[私密信件]",
                letter_type=letter.letter_type,
                status=letter.status,
                send_time=letter.send_time,
                unlock_time=letter.unlock_time,
                is_private=letter.is_private,
                is_favorite=letter.is_favorite,
                created_at=letter.created_at,
                updated_at=letter.updated_at,
            )
            filtered.append(letter_content_hidden)
        else:
            filtered.append(letter)

    return {
        "items": filtered,
        "total": total,
        "page": page,
        "page_size": page_size,
    }


def get_letter(db: Session, user_id: int, letter_id: int) -> Letter:
    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")

    # Check permission: sender or receiver
    if letter.sender_id != user_id and letter.receiver_id != user_id:
        raise ValueError("60002")

    # For non-draft letters with a relation, verify the relation is still active
    if letter.status != "draft" and letter.relation_id is not None:
        relation = couple_repo.get_active_relation_by_user(db, user_id)
        if not relation or letter.relation_id != relation.id:
            raise ValueError("60002")

    # Future letter: receiver can't see before unlock time
    if letter.letter_type == "future" and letter.unlock_time:
        if letter.receiver_id == user_id and letter.unlock_time > datetime.utcnow():
            raise ValueError("60002")

    # Auto-mark as read when receiver views
    if letter.receiver_id == user_id and letter.status == "sent":
        letter.status = "read"
        db.flush()

    return letter


def update_letter(db: Session, user_id: int, letter_id: int, data: dict) -> Letter:
    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.sender_id != user_id:
        raise ValueError("60002")
    if letter.status != "draft":
        raise ValueError("60003")

    letter = letter_repo.update_letter(db, letter, data)
    db.commit()
    db.refresh(letter)
    return letter


def delete_letter(db: Session, user_id: int, letter_id: int) -> None:
    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.sender_id != user_id and letter.receiver_id != user_id:
        raise ValueError("60002")

    letter_repo.soft_delete_letter(db, letter)
    db.commit()


def batch_delete_letters(db: Session, user_id: int, letter_ids: list) -> int:
    deleted = 0
    for letter_id in letter_ids:
        letter = letter_repo.get_letter_by_id(db, letter_id)
        if not letter:
            continue
        if letter.sender_id != user_id and letter.receiver_id != user_id:
            continue
        letter_repo.soft_delete_letter(db, letter)
        deleted += 1
    return deleted


def send_letter(db: Session, user_id: int, letter_id: int) -> Letter:
    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.sender_id != user_id:
        raise ValueError("60002")
    if letter.status != "draft":
        raise ValueError("60003")
    if letter.relation_id is None:
        raise ValueError("30005")

    letter.status = "sent"
    letter.send_time = datetime.utcnow()
    if letter.letter_type == "calm":
        letter.unlock_time = datetime.utcnow() + timedelta(hours=CALM_PERIOD_HOURS)

    db.commit()
    db.refresh(letter)

    # P0-3：draft→sent 时刻沉淀（上方已保证 relation_id 非空）
    from app.services.memory_events import MemoryEvent, distill_event_in_background

    distill_event_in_background(MemoryEvent(
        source="letter",
        source_id=letter.id,
        user_id=user_id,
        relation_id=letter.relation_id,
        content=letter.content,
        occurred_at=datetime.utcnow(),
        extra={"context": f"信件标题：{letter.title}"},
    ))

    # 通知收件人
    if letter.receiver_id:
        try:
            import asyncio
            from app.services.notification_service import notify_letter_received
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.ensure_future(notify_letter_received(
                    letter.receiver_id, user_id, letter.id, letter.title or "无题"
                ))
            else:
                loop.run_until_complete(notify_letter_received(
                    letter.receiver_id, user_id, letter.id, letter.title or "无题"
                ))
        except Exception:
            pass

    return letter


def toggle_favorite(db: Session, user_id: int, letter_id: int) -> Letter:
    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.sender_id != user_id and letter.receiver_id != user_id:
        raise ValueError("60002")

    letter.is_favorite = not letter.is_favorite
    db.commit()
    db.refresh(letter)
    return letter


def list_inbox(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    return list_letters(db=db, user_id=user_id, inbox=True, page=page, page_size=page_size)


def list_drafts(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    return list_letters(db=db, user_id=user_id, drafts=True, page=page, page_size=page_size)
