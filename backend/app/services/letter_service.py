from sqlalchemy import and_, or_
from sqlalchemy.orm import Session
from typing import Optional, List, Dict
from datetime import datetime, timedelta

from app.models.letter import Letter
from app.models.couple_relation import CoupleRelation
from app.core.features import FROZEN_LETTER_TYPES
from app.repositories import letter_repo, couple_repo


CALM_PERIOD_HOURS = 2
SINGLE_MODE_ALLOWED_TYPES = {"normal", "unsaid"}

#: 契约 §2.5-2：存量未解锁未来信在通知/首页卡上的 title 替身——
#: 保留字段本身（JSON 只增不减），但绝不带真实标题。
LOCKED_LETTER_TITLE = "未解锁的信"


def _future_letter_locked(letter: Letter, now: Optional[datetime] = None) -> bool:
    """存量未来信是否处于未解锁状态（与具体查看者无关）。

    契约 §2.5-2：解锁门**只看** `letter_type` + `unlock_time`，不依赖
    status / is_private 等巧合字段。`unlock_time is NULL` 视为「从未排期」
    同样锁定——历史客户端从不传 unlock_time，条件落空即全量泄漏，
    正是本次要堵的洞（未来信创建已冻结，此类行永远不会解锁）。
    """
    if letter.letter_type != "future":
        return False
    if letter.unlock_time is None:
        return True
    return letter.unlock_time > (now or datetime.utcnow())


def is_locked_future(letter: Letter, user_id: int, now: Optional[datetime] = None) -> bool:
    """接收方视角的存量未来信锁：仅拦截收件方；发件方看自己的信不受限。"""
    if letter.receiver_id != user_id:
        return False
    return _future_letter_locked(letter, now)


def locked_future_clause(now: Optional[datetime] = None):
    """`is_locked_future` 的 SQL 同义条款（首页等集合查询用，规则同源）。"""
    ts = now or datetime.utcnow()
    return and_(
        Letter.letter_type == "future",
        or_(Letter.unlock_time.is_(None), Letter.unlock_time > ts),
    )


def _notification_title(letter: Letter) -> str:
    """通知可用标题：未解锁未来信解锁前不带真实 title（契约 §2.5-2）。"""
    if _future_letter_locked(letter):
        return LOCKED_LETTER_TITLE
    return letter.title or "无题"


def check_letter_type_allowed(letter_type) -> None:
    """按类型冻结（契约 §2.5-1）：future / private 一律 10006。

    只拒这两类——现有前端还会发 unsaid / calm，信件主体必须保留。
    服务层用 `ValueError("10006")` 传码，由 API 层翻译成冻结错误。
    """
    if letter_type in FROZEN_LETTER_TYPES:
        raise ValueError("10006")


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
    # 契约 §2.5-1：冻结创建 future / private（在任何其他分支之前，单身模式同理）
    check_letter_type_allowed(data.get("letter_type", "normal"))

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

    # 契约 §2.5-2：锁定行在 SQL 层就从 count 与分页中剔除——
    # 否则 total 含被 Python 层隐藏的行，total / 页数口径与实际返回行数不一致。
    # SQL 条款与 `is_locked_future` 同源同刻（共用 now），双层过滤互为兜底。
    now = datetime.utcnow()
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
        exclude_locked_for=user_id,
        now=now,
    )

    filtered = []
    for letter in items:
        # 契约 §2.5-2：存量未解锁 future 信对接收方在列表中整条隐藏
        # （标题/内容都不给；发件方与已解锁不受影响）
        if is_locked_future(letter, user_id, now):
            continue
        if letter.is_private and letter.receiver_id == user_id:
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

    # 契约 §2.5-2：存量未解锁 future 信——接收方 detail 在 unlock_time 前
    # 无条件拒绝（只看 letter_type + unlock_time，不依赖 status 等巧合字段）
    if is_locked_future(letter, user_id):
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
    # 契约 §2.5-1：请求体把类型改成 future/private 时拒绝（改回正常类型不受限）
    check_letter_type_allowed(data.get("letter_type"))
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
    # 契约 §2.5-2：与 detail 同语义同错误码——接收方在 unlock 前不得经
    # 删除路径探测/操作未解锁 future 信（错误处理路径同 detail：60002）
    if is_locked_future(letter, user_id):
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
        # 契约 §2.5-2：锁定行**剔除而非整批拒绝**——与 list 语义一致
        # （列表里这些行本来就不返回，批删自然也不动它们）。
        # 返回类型仍是 int「成功删除的非锁定行数」，与既有返回契约不冲突，
        # 只是锁定行不计入 deleted_count（客户端本就看不到这些行）。
        if is_locked_future(letter, user_id):
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
    # 契约 §2.5-1：草稿本身的类型是 future/private 时拒绝发送
    check_letter_type_allowed(letter.letter_type)
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

    # 通知收件人（契约 §2.5-2：存量未解锁 future 信解锁前通知不带真实 title；
    # schedule_notify 兼容线程池调用——旧的事件循环探测分支在线程池里恒静默失败）
    if letter.receiver_id:
        from app.services.notification_service import (
            notify_letter_received,
            schedule_notify,
        )
        schedule_notify(
            notify_letter_received(
                letter.receiver_id, user_id, letter.id, _notification_title(letter)
            ),
            "letter_received",
        )

    return letter


def toggle_favorite(db: Session, user_id: int, letter_id: int) -> Letter:
    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.sender_id != user_id and letter.receiver_id != user_id:
        raise ValueError("60002")
    # 契约 §2.5-2：favorite 响应体是完整 LetterOut（title+content 全文），
    # 与 detail 同语义同错误码——接收方不得借收藏动作拿到未解锁 future 信
    if is_locked_future(letter, user_id):
        raise ValueError("60002")

    letter.is_favorite = not letter.is_favorite
    db.commit()
    db.refresh(letter)
    return letter


def list_inbox(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    return list_letters(db=db, user_id=user_id, inbox=True, page=page, page_size=page_size)


def list_drafts(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> dict:
    return list_letters(db=db, user_id=user_id, drafts=True, page=page, page_size=page_size)
