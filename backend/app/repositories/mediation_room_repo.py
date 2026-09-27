"""共同调解室 · 数据访问层。

职责边界：只做 SQLAlchemy 访问与**原子状态转换**（条件 UPDATE、唯一约束
mutex、租约领取），业务编排在 `mediation_room_service` /
`room_advisor_service` / `room_settlement_service`。

并发原语说明（写法均沿用 ai_task_repo 已验证的模式）：

- 抢占类操作一律「条件 UPDATE + rowcount 判定」，不做「先 SELECT 再改」；
- 唯一约束冲突用 SAVEPOINT（`begin_nested`）隔离，回读必须 `FOR UPDATE`
  （MySQL REPEATABLE-READ 下普通快照读看不见竞争方刚提交的行）；
- 加锁读房间用 `with_for_update()`（SQLite 方言自动忽略，测试基座可用）。
"""

import uuid
from datetime import datetime, timedelta
from typing import List, Optional, Tuple

from sqlalchemy import or_, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models.mediation_room import (
    MediationRoom,
    MediationRoomPending,
    MediationEvent,
    PENDING_TTL_SECONDS,
    RoomMessage,
    ROOM_ACTIVE,
    SETTLE_LEASE_SECONDS,
    SETTLE_MAX_ATTEMPTS,
)


# --------------------------------------------------------------------------- #
# 房间读取
# --------------------------------------------------------------------------- #

def get_room(db: Session, room_id: int) -> Optional[MediationRoom]:
    return db.query(MediationRoom).filter(MediationRoom.id == room_id).first()


def get_room_locked(db: Session, room_id: int) -> Optional[MediationRoom]:
    """加锁读（写回事务 / 状态推进前用）。"""
    return (
        db.query(MediationRoom)
        .filter(MediationRoom.id == room_id)
        .with_for_update()
        .populate_existing()
        .first()
    )


def room_for_user(db: Session, room_id: int, relation_id: int) -> Optional[MediationRoom]:
    """归属校验：房间必须属于当前关系。"""
    return (
        db.query(MediationRoom)
        .filter(MediationRoom.id == room_id, MediationRoom.relation_id == relation_id)
        .first()
    )


def list_rooms(db: Session, relation_id: int) -> List[MediationRoom]:
    return (
        db.query(MediationRoom)
        .filter(MediationRoom.relation_id == relation_id)
        .order_by(MediationRoom.updated_at.desc(), MediationRoom.id.desc())
        .all()
    )


def count_active_rooms(db: Session, relation_id: int) -> int:
    """进行中的房间数（强提醒角标数据源：active/settling/settlement_ready）。"""
    return (
        db.query(MediationRoom.id)
        .filter(
            MediationRoom.relation_id == relation_id,
            MediationRoom.status.in_(
                (ROOM_ACTIVE, "settling", "settlement_ready")
            ),
        )
        .count()
    )


# --------------------------------------------------------------------------- #
# 消息
# --------------------------------------------------------------------------- #

def add_message(
    db: Session,
    room_id: int,
    *,
    sender_type: str,
    content: str,
    sender_user_id: Optional[int] = None,
    thinking: Optional[str] = None,
    risk_level: Optional[str] = None,
    round_no: Optional[int] = None,
) -> RoomMessage:
    """插入消息（**不提交**——由调用方事务统一收口）。"""
    msg = RoomMessage(
        room_id=room_id,
        sender_type=sender_type,
        sender_user_id=sender_user_id,
        content=content,
        thinking=thinking,
        risk_level=risk_level,
        round_no=round_no,
    )
    db.add(msg)
    db.flush()
    return msg


def get_messages(
    db: Session, room_id: int, *, after_id: int = 0, limit: int = 100
) -> List[RoomMessage]:
    query = db.query(RoomMessage).filter(
        RoomMessage.room_id == room_id, RoomMessage.id > after_id
    )
    rows = query.order_by(RoomMessage.id.asc()).limit(limit).all()
    if len(rows) == limit:
        return rows
    # 不足 limit 时可能只是刚好没有更多新消息；再兜底取最新一页（首屏场景）
    return rows


def get_recent_messages(
    db: Session, room_id: int, *, limit: int = 50, before_id: Optional[int] = None
) -> List[RoomMessage]:
    """取最新 limit 条（升序返回），prompt 原文窗口用。"""
    query = db.query(RoomMessage).filter(RoomMessage.room_id == room_id)
    if before_id is not None:
        query = query.filter(RoomMessage.id <= before_id)
    rows = query.order_by(RoomMessage.id.desc()).limit(limit).all()
    return list(reversed(rows))


def count_messages_since(db: Session, room_id: int, message_id: int) -> int:
    return (
        db.query(RoomMessage.id)
        .filter(RoomMessage.room_id == room_id, RoomMessage.id > message_id)
        .count()
    )


# --------------------------------------------------------------------------- #
# pending 生成 mutex（§四 互斥）
# --------------------------------------------------------------------------- #

def try_claim_pending(
    db: Session, room_id: int, user_id: int, kind: str = "advisor_reply"
) -> Optional[MediationRoomPending]:
    """尝试占用房间的 pending 生成位。

    返回占位行（token 在行上）；None = 已被占用（军师正在思考）。
    **不提交**：与调用方的消息插入同一事务——消息落了但占位失败时整体回滚，
    不会出现「消息进了、召唤没成」的错位。
    """
    pending = MediationRoomPending(
        room_id=room_id, kind=kind, token=uuid.uuid4().hex, created_by_user_id=user_id
    )
    try:
        with db.begin_nested():
            db.add(pending)
            db.flush()
    except IntegrityError:
        # SAVEPOINT 已自动回滚本次 INSERT；外层事务（消息插入等）不受影响。
        # 不调 db.rollback()——那会把调用方事务里已完成的工作一并丢掉。
        return None
    return pending


def get_pending(db: Session, room_id: int) -> Optional[MediationRoomPending]:
    return (
        db.query(MediationRoomPending)
        .filter(MediationRoomPending.room_id == room_id)
        .populate_existing()
        .first()
    )


def get_pending_fresh(db: Session, room_id: int) -> Optional[MediationRoomPending]:
    """取未过期的 pending（「军师正在输入」占位 / token 校验用）。"""
    pending = get_pending(db, room_id)
    if pending is None:
        return None
    age = (datetime.utcnow() - pending.created_at).total_seconds()
    if age > PENDING_TTL_SECONDS:
        return None
    return pending


def delete_pending(db: Session, room_id: int) -> None:
    """释放占位（幂等）。**不提交**——随写回事务一起收口；
    失败清理路径自行 commit。"""
    db.query(MediationRoomPending).filter(
        MediationRoomPending.room_id == room_id
    ).delete(synchronize_session=False)


def expire_stale_pending(db: Session) -> int:
    """清理孤儿占位（客户端拿了 token 却一直没开流）。独立 commit。"""
    cutoff = datetime.utcnow() - timedelta(seconds=PENDING_TTL_SECONDS)
    result = db.execute(
        MediationRoomPending.__table__.delete().where(
            MediationRoomPending.created_at < cutoff
        )
    )
    db.commit()
    return int(result.rowcount or 0)


# --------------------------------------------------------------------------- #
# 房间状态推进（全部条件 UPDATE，**不提交**）
# --------------------------------------------------------------------------- #

def advance_after_advisor(db: Session, room: MediationRoom, message_id: int) -> None:
    """军师发言写回的状态推进（与消息 INSERT 同一 commit，room 已加锁读）。

    - round_no += 1；advisor_phase → engaged（召回即在席）
    - 进入「等待双方应答」：作废本轮已按的同意（§五.2）
    - 水位更新
    """
    room.round_no = int(room.round_no or 0) + 1
    room.advisor_phase = "engaged"
    room.waiting_reply = True
    room.agree_user_a = False
    room.agree_user_b = False
    room.last_message_id = message_id
    room.last_message_at = datetime.utcnow()


def advance_after_user_message(db: Session, room: MediationRoom, message_id: int) -> None:
    """用户消息落库后的水位推进（不改变应答状态）。"""
    room.last_message_id = message_id
    room.last_message_at = datetime.utcnow()


def mark_agree(db: Session, room_id: int, role: str) -> bool:
    """按同意；双方都已按 → 军师靠边 + 关闭应答（§五.3）。

    条件 UPDATE + rowcount 判定（waiting_reply 仍为真才允许按）。
    返回 True 表示本次操作生效（含凑齐靠边）。
    """
    col = "agree_user_a" if role == "user_a" else "agree_user_b"
    now = datetime.utcnow()
    result = db.execute(
        update(MediationRoom)
        .where(
            MediationRoom.id == room_id,
            MediationRoom.status == ROOM_ACTIVE,
            MediationRoom.waiting_reply.is_(True),
        )
        .values({col: True, MediationRoom.updated_at: now})
    )
    if result.rowcount != 1:
        return False
    # 凑齐检查：条件推进（双方 agree 均为真 → aside）
    db.execute(
        update(MediationRoom)
        .where(
            MediationRoom.id == room_id,
            MediationRoom.agree_user_a.is_(True),
            MediationRoom.agree_user_b.is_(True),
        )
        .values(
            advisor_phase="aside",
            waiting_reply=False,
            updated_at=now,
        )
    )
    return True


def reset_agrees(db: Session, room_id: int) -> None:
    """本轮同意作废（任一方补充时，§五.2）。**不提交**。"""
    db.execute(
        update(MediationRoom)
        .where(MediationRoom.id == room_id)
        .values(agree_user_a=False, agree_user_b=False)
    )


def set_end_vote(db: Session, room_id: int, role: str, voted: bool) -> Optional[bool]:
    """结束调解投票（可反悔，D-CLOSING）。

    返回 None = 操作无效（房间不在 active）；True = 双方票已凑齐（房间转
    settling）；False = 投票已记录、尚未凑齐。
    """
    col = "end_vote_user_a" if role == "user_a" else "end_vote_user_b"
    now = datetime.utcnow()
    result = db.execute(
        update(MediationRoom)
        .where(MediationRoom.id == room_id, MediationRoom.status == ROOM_ACTIVE)
        .values({col: voted, MediationRoom.updated_at: now})
    )
    if result.rowcount != 1:
        return None
    room = get_room(db, room_id)
    if room and room.end_vote_user_a and room.end_vote_user_b:
        db.execute(
            update(MediationRoom)
            .where(MediationRoom.id == room_id)
            .values(status="settling", updated_at=now)
        )
        return True
    return False


def settlement_confirm(db: Session, room_id: int, role: str) -> Optional[bool]:
    """调解书确认。None=无效；True=双方已确认（房间结算）；False=已记录待对方。"""
    col = "confirm_user_a" if role == "user_a" else "confirm_user_b"
    now = datetime.utcnow()
    result = db.execute(
        update(MediationRoom)
        .where(
            MediationRoom.id == room_id,
            MediationRoom.status == "settlement_ready",
        )
        .values({col: True, MediationRoom.updated_at: now})
    )
    if result.rowcount != 1:
        return None
    room = get_room(db, room_id)
    if room and room.confirm_user_a and room.confirm_user_b:
        db.execute(
            update(MediationRoom)
            .where(MediationRoom.id == room_id)
            .values(status="settled", settled_at=now, updated_at=now)
        )
        # 同步事件行的结算时间（观察素材）
        db.execute(
            update(MediationEvent)
            .where(MediationEvent.room_id == room_id)
            .values(settled_at=now)
        )
        return True
    return False


# --------------------------------------------------------------------------- #
# 结算租约（worker 领取，模式同 ai_task_repo.claim_next_task）
# --------------------------------------------------------------------------- #

def claim_settlement(db: Session, worker_id: str) -> Optional[MediationRoom]:
    """原子领取一个待结算房间。领取即 attempt+=1 并 commit。

    领取条件：status=settling、尝试未耗尽、租约过期/为空、退避窗口已过。
    """
    now = datetime.utcnow()
    candidates = (
        db.query(MediationRoom.id)
        .filter(
            MediationRoom.status == "settling",
            MediationRoom.settle_attempt < SETTLE_MAX_ATTEMPTS,
            or_(
                MediationRoom.settle_locked_until.is_(None),
                MediationRoom.settle_locked_until < now,
            ),
            or_(
                MediationRoom.settle_next_retry_at.is_(None),
                MediationRoom.settle_next_retry_at <= now,
            ),
        )
        .order_by(MediationRoom.id.asc())
        .limit(5)
        .all()
    )
    for (room_id,) in candidates:
        result = db.execute(
            update(MediationRoom)
            .where(
                MediationRoom.id == room_id,
                MediationRoom.status == "settling",
                MediationRoom.settle_attempt < SETTLE_MAX_ATTEMPTS,
                or_(
                    MediationRoom.settle_locked_until.is_(None),
                    MediationRoom.settle_locked_until < now,
                ),
                or_(
                    MediationRoom.settle_next_retry_at.is_(None),
                    MediationRoom.settle_next_retry_at <= now,
                ),
            )
            .values(
                settle_locked_by=worker_id,
                settle_locked_until=now + timedelta(seconds=SETTLE_LEASE_SECONDS),
                settle_attempt=MediationRoom.settle_attempt + 1,
            )
        )
        db.commit()
        if result.rowcount == 1:
            return get_room(db, room_id)
    return None


def settle_retry_or_fail(db: Session, room_id: int, error: str) -> bool:
    """结算失败收口：未耗尽 → 退避重排（独立 commit）；耗尽 → 记终态错误。"""
    room = get_room(db, room_id)
    if room is None:
        return False
    attempts = int(room.settle_attempt or 0)
    if attempts >= SETTLE_MAX_ATTEMPTS:
        room.settle_locked_by = None
        room.settle_locked_until = None
        room.settle_last_error = error[:480]
        db.commit()
        return False
    room.settle_locked_by = None
    room.settle_locked_until = None
    room.settle_next_retry_at = datetime.utcnow() + timedelta(
        seconds=30 * (2 ** max(attempts - 1, 0))
    )
    room.settle_last_error = error[:480]
    db.commit()
    return True


def reset_settlement(db: Session, room_id: int) -> bool:
    """用户手动重试结算：清租约与退避（status 仍为 settling 才有效）。"""
    result = db.execute(
        update(MediationRoom)
        .where(
            MediationRoom.id == room_id,
            MediationRoom.status == "settling",
            MediationRoom.settle_attempt >= SETTLE_MAX_ATTEMPTS,
        )
        .values(
            settle_attempt=0,
            settle_locked_by=None,
            settle_locked_until=None,
            settle_next_retry_at=None,
            settle_last_error=None,
        )
    )
    db.commit()
    return bool(result.rowcount)


def rooms_needing_summary(db: Session, limit: int = 5) -> List[MediationRoom]:
    """待压缩摘要的房间（summary_pending 标记 + 无在途结算）。"""
    return (
        db.query(MediationRoom)
        .filter(
            MediationRoom.summary_pending.is_(True),
            MediationRoom.status.in_((ROOM_ACTIVE,)),
        )
        .order_by(MediationRoom.id.asc())
        .limit(limit)
        .all()
    )


def update_summary(
    db: Session, room_id: int, *, summary: str, upto_message_id: int
) -> None:
    """写回压缩结果并清标记（独立 commit）。"""
    db.execute(
        update(MediationRoom)
        .where(MediationRoom.id == room_id)
        .values(
            advisor_summary=summary,
            summary_upto_message_id=upto_message_id,
            summary_pending=False,
        )
    )
    db.commit()


def create_room(
    db: Session,
    *,
    relation_id: int,
    creator_user_id: int,
    name: str,
    event_time: str,
    cause_text: str,
    process_text: str,
    current_text: str,
    style_key: str,
) -> MediationRoom:
    room = MediationRoom(
        relation_id=relation_id,
        creator_user_id=creator_user_id,
        name=name,
        event_time=event_time,
        cause_text=cause_text,
        process_text=process_text,
        current_text=current_text,
        style_key=style_key,
        status=ROOM_ACTIVE,
        advisor_phase="engaged",
    )
    db.add(room)
    db.flush()
    return room
