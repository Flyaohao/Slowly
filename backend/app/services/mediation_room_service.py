"""共同调解室 · 房间业务编排（创建/发消息/应答/投票/确认）。

本模块只做**同步 REST 动作**；军师 SSE 流式在 `room_advisor_service`，
结算 worker 在 `room_settlement_service`。

错误码约定（HTTP 200 信封内，code 非 0）：
- 61001 房间不存在            61002 军师正在思考（pending mutex 冲突）
- 61003 状态不允许该操作       61004 结算失败（含 settle_last_error）
"""

import json
import logging
from datetime import datetime
from typing import Optional, Tuple

from sqlalchemy.orm import Session

from app.models.couple_relation import CoupleRelation
from app.models.mediation_room import (
    RESULT_LABELS,
    RoomMessage,
    ROOM_ACTIVE,
    ROOM_SETTLING,
    ROOM_SETTLEMENT_READY,
    ROOM_SETTLED,
)
from app.repositories import mediation_room_repo as room_repo
from app.schemas.mediation_room_schema import (
    RoomMessageOut,
    RoomStateOut,
    RoomSummaryOut,
)
from app.services import mediation_styles
from app.services.party_labels import party_labels

logger = logging.getLogger("couple.room")

#: 业务错误（service 层 ValueError，API 层翻译成 code）
ERR_NO_ROOM = "61001"
ERR_ADVISOR_BUSY = "61002"
ERR_BAD_STATE = "61003"


def role_of(relation, user_id: int) -> str:
    return "user_a" if relation.user_a_id == user_id else "user_b"


def partner_user_id(relation, user_id: int) -> int:
    return relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id


def create_room(db: Session, *, relation, user_id: int, req) -> dict:
    if not mediation_styles.is_valid_style(req.style_key):
        raise ValueError(ERR_BAD_STATE)
    room = room_repo.create_room(
        db,
        relation_id=relation.id,
        creator_user_id=user_id,
        name=req.name.strip(),
        event_time=req.event_time.strip(),
        cause_text=req.cause_text.strip(),
        process_text=req.process_text.strip(),
        current_text=req.current_text.strip(),
        style_key=req.style_key,
    )
    db.commit()
    return _summary_out(room).model_dump()


def list_rooms(db: Session, relation) -> dict:
    rooms = room_repo.list_rooms(db, relation.id)
    active = room_repo.count_active_rooms(db, relation.id)
    return {
        "total": len(rooms),
        "active_count": active,
        "items": [_summary_out(r).model_dump() for r in rooms],
    }


def post_message(
    db: Session, *, relation, user_id: int, room_id: int, content: str, mention: bool
) -> dict:
    """发消息；mention=True 时尝试召唤军师。

    消息插入与 pending 占位同一事务：占位失败（军师正在思考）时**消息仍然
    落库、事务照常提交**，仅返回 advisor_claimed=False + busy 标记——消息是
    用户发言，不应因为召唤失败而丢失（微信群模型：@ 了没回，话还在）。
    """
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    if room.status != ROOM_ACTIVE:
        raise ValueError(ERR_BAD_STATE)

    role = role_of(relation, user_id)
    msg = room_repo.add_message(
        db, room_id, sender_type=role, content=content, sender_user_id=user_id
    )
    room_repo.advance_after_user_message(db, room, msg.id)

    claimed = False
    busy = False
    token = None
    if mention:
        pending = room_repo.try_claim_pending(db, room_id, user_id)
        if pending is None:
            busy = True
        else:
            claimed = True
            token = pending.token
    db.commit()
    return {
        "message_id": msg.id,
        "advisor_claimed": claimed,
        "advisor_busy": busy,
        "token": token,
    }


def agree(db: Session, *, relation, user_id: int, room_id: int) -> dict:
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    role = role_of(relation, user_id)
    ok = room_repo.mark_agree(db, room_id, role)
    if not ok:
        raise ValueError(ERR_BAD_STATE)
    db.commit()
    fresh = room_repo.get_room(db, room_id)
    return {
        "agree_me": True,
        "advisor_aside": fresh.advisor_phase == "aside",
        "waiting_reply": fresh.waiting_reply,
    }


def supplement(
    db: Session, *, relation, user_id: int, room_id: int, content: Optional[str]
) -> dict:
    """任一方补充 → 本轮同意作废 + 军师基于补充再发言（§五.2）。"""
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    if room.status != ROOM_ACTIVE:
        raise ValueError(ERR_BAD_STATE)

    role = role_of(relation, user_id)
    if content and content.strip():
        msg = room_repo.add_message(
            db, room_id, sender_type=role, content=content.strip(),
            sender_user_id=user_id,
        )
        room_repo.advance_after_user_message(db, room, msg.id)
    # 本轮已按的同意作废
    room_repo.reset_agrees(db, room_id)

    pending = room_repo.try_claim_pending(db, room_id, user_id)
    if pending is None:
        db.commit()
        raise ValueError(ERR_ADVISOR_BUSY)
    db.commit()
    return {"advisor_claimed": True, "token": pending.token}


def end_vote(
    db: Session, *, relation, user_id: int, room_id: int, action: str
) -> dict:
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    role = role_of(relation, user_id)
    outcome = room_repo.set_end_vote(db, room_id, role, voted=(action == "vote"))
    if outcome is None:
        raise ValueError(ERR_BAD_STATE)
    db.commit()
    fresh = room_repo.get_room(db, room_id)
    return {
        "end_vote_me": action == "vote",
        "end_vote_partner": (
            fresh.end_vote_user_b if role == "user_a" else fresh.end_vote_user_a
        ),
        "both_voted": outcome is True,
        "status": fresh.status,
    }


def settlement_confirm_action(db: Session, *, relation, user_id: int, room_id: int) -> dict:
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    role = role_of(relation, user_id)
    outcome = room_repo.settlement_confirm(db, room_id, role)
    if outcome is None:
        raise ValueError(ERR_BAD_STATE)
    db.commit()
    fresh = room_repo.get_room(db, room_id)
    return {
        "both_confirmed": outcome is True,
        "status": fresh.status,
    }


def retry_settlement(db: Session, *, relation, user_id: int, room_id: int) -> dict:
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    ok = room_repo.reset_settlement(db, room_id)
    return {"retry_queued": bool(ok)}


def get_room_state(db: Session, *, relation, user_id: int, room_id: int) -> dict:
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    return _state_out(db, room, role_of(relation, user_id)).model_dump()


def get_messages(
    db: Session, *, relation, user_id: int, room_id: int, after_id: int, limit: int = 100
) -> dict:
    """短轮询：新消息 + 状态快照一次带回（D-REALTIME MVP）。"""
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    msgs = room_repo.get_messages(db, room_id, after_id=after_id, limit=limit)
    return {
        "messages": [_message_out(m).model_dump() for m in msgs],
        "state": _state_out(db, room, role_of(relation, user_id)).model_dump(),
    }


def get_room_detail(db: Session, *, relation, user_id: int, room_id: int) -> dict:
    room = room_repo.room_for_user(db, room_id, relation.id)
    if room is None:
        raise ValueError(ERR_NO_ROOM)
    style = mediation_styles.get_style(room.style_key)
    data = _summary_out(room).model_dump()
    data.update(
        {
            "cause_text": room.cause_text,
            "process_text": room.process_text,
            "current_text": room.current_text,
            "style_label": style["label"],
            "state": _state_out(db, room, role_of(relation, user_id)).model_dump(),
        }
    )
    return data


# --------------------------------------------------------------------------- #
# 序列化
# --------------------------------------------------------------------------- #

def _summary_out(room) -> RoomSummaryOut:
    return RoomSummaryOut(
        id=room.id,
        name=room.name,
        event_time=room.event_time,
        style_key=room.style_key,
        status=room.status,
        advisor_phase=room.advisor_phase,
        creator_user_id=room.creator_user_id,
        last_message_at=room.last_message_at.isoformat() if room.last_message_at else None,
        created_at=room.created_at.isoformat() if room.created_at else None,
    )


def _message_out(m: RoomMessage) -> RoomMessageOut:
    return RoomMessageOut(
        id=m.id,
        sender_type=m.sender_type,
        sender_user_id=m.sender_user_id,
        content=m.content,
        thinking=m.thinking,
        risk_level=m.risk_level,
        round_no=m.round_no,
        created_at=m.created_at.isoformat() if m.created_at else None,
    )


def _settlement_dict(room) -> Optional[dict]:
    if not room.settlement:
        return None
    try:
        data = json.loads(room.settlement)
    except (TypeError, ValueError):
        return None
    data["result_label"] = RESULT_LABELS.get(room.result, room.result)
    return data


def _settlement_dict_with_labels(db: Session, room) -> Optional[dict]:
    """调解书 + 双方称呼标签（D7：前端禁再硬编码「女方/男方」，
    标签与 party_labels.py 单一事实源一致——按资料性别映射，
    其他/未填回退「当事人A/B」，随快照实时计算）。"""
    data = _settlement_dict(room)
    if data is None:
        return None
    relation = (
        db.query(CoupleRelation)
        .filter(CoupleRelation.id == room.relation_id)
        .first()
    )
    if relation is not None:
        data["party_labels"] = party_labels(db, relation)
    return data


def _state_out(db: Session, room, role: str) -> RoomStateOut:
    generating = room_repo.get_pending_fresh(db, room.id) is not None
    partner_role = "user_b" if role == "user_a" else "user_a"
    state = RoomStateOut(
        room_id=room.id,
        status=room.status,
        advisor_phase=room.advisor_phase,
        round_no=room.round_no,
        waiting_reply=room.waiting_reply,
        my_role=role,
        agree_me=getattr(room, "agree_" + role),
        agree_partner=getattr(room, "agree_" + partner_role),
        end_vote_me=getattr(room, "end_vote_" + role),
        end_vote_partner=getattr(room, "end_vote_" + partner_role),
        advisor_generating=generating,
        result=room.result,
        settle_error=room.settle_last_error,
        last_message_id=room.last_message_id,
    )
    if room.status in (ROOM_SETTLING, ROOM_SETTLEMENT_READY, ROOM_SETTLED):
        state.settlement = _settlement_dict_with_labels(db, room)
        state.confirm_me = getattr(room, "confirm_" + role)
        state.confirm_partner = getattr(room, "confirm_" + partner_role)
    return state
