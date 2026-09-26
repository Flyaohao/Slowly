from sqlalchemy.orm import Session
from datetime import date, datetime

from sqlalchemy import func, not_

from app.models.couple_relation import CoupleRelation
from app.models.user_profile import UserProfile
from app.models.letter import Letter
from app.models.ai import AiChatSession
from app.models.museum import MuseumItem
from app.models.practice import RelationshipPractice
from app.models.anniversary import Anniversary
from app.models.user import User
from app.models.dual_perspective import DualPerspectiveEvent, DualPerspectiveRecord
from app.repositories import (
    couple_repo,
    avatar_repo,
    diary_repo,
    profile_repo,
    user_repo,
)
from app.services.letter_service import LOCKED_LETTER_TITLE, locked_future_clause


def _get_partner_id(relation: CoupleRelation, user_id: int) -> int:
    if relation.user_a_id == user_id:
        return relation.user_b_id
    return relation.user_a_id


def get_home_data(db: Session, user_id: int) -> dict:
    """根据用户模式返回首页数据"""
    user = user_repo.get_user_by_id(db, user_id)
    if not user:
        raise ValueError("10001")

    # 双重校验：检查 has_couple 与实际关系是否一致
    if user.has_couple:
        relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
        if relation and relation.status in ("active", "unbinding"):
            return _get_couple_home_data(db, user_id, relation)
        else:
            # 数据不一致，修复
            user_repo.update_has_couple(db, user_id, False)
            return _get_single_home_data(db, user_id)
    else:
        relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
        if relation and relation.status in ("active", "unbinding"):
            # 数据不一致，修复
            user_repo.update_has_couple(db, user_id, True)
            return _get_couple_home_data(db, user_id, relation)
        return _get_single_home_data(db, user_id)


def _get_single_home_data(db: Session, user_id: int) -> dict:
    """单身模式首页数据"""
    user_profile = db.query(UserProfile).filter(UserProfile.user_id == user_id).first()

    # 最近日记
    recent_diaries = diary_repo.get_recent(db, user_id, limit=3)
    recent_diaries_data = [
        {
            "id": d.id,
            "title": d.title,
            "mood": d.mood,
            "created_at": d.created_at.isoformat() if d.created_at else None,
        }
        for d in recent_diaries
    ]

    return {
        "mode": "single",
        "user_nickname": user_profile.nickname if user_profile else None,
        "user_avatar_url": user_profile.avatar_url if user_profile else None,
        "recent_diaries": recent_diaries_data,
        "has_profile": user_profile is not None,
        # 契约 §3.1：任务卡只在情侣首页出现；单人侧给空列表（只增不减，
        # FE HomeResponse.task_cards 默认值同构）
        "task_cards": [],
    }


def _get_couple_home_data(db: Session, user_id: int, relation: CoupleRelation) -> dict:
    """情侣模式首页数据"""
    relation_id = relation.id
    partner_id = _get_partner_id(relation, user_id)

    # 关系身份
    user_profile = db.query(UserProfile).filter(UserProfile.user_id == user_id).first()
    partner_profile = db.query(UserProfile).filter(UserProfile.user_id == partner_id).first()
    love_days = None
    if relation.bind_time:
        love_days = (datetime.utcnow() - relation.bind_time).days

    relation_info = {
        "user_nickname": user_profile.nickname if user_profile else None,
        "user_avatar_url": user_profile.avatar_url if user_profile else None,
        "partner_nickname": partner_profile.nickname if partner_profile else None,
        "partner_avatar_url": partner_profile.avatar_url if partner_profile else None,
        "love_days": love_days,
    }

    # AI 形象
    avatar = avatar_repo.get_avatar_by_relation_id(db, relation_id)
    avatar_data = None
    if avatar:
        avatar_data = {
            "id": avatar.id,
            "name": avatar.name,
            "body_color": avatar.body_color,
            "face_config": avatar.face_config,
            "outfit_config": avatar.outfit_config,
            "voice_style": avatar.voice_style,
            "background_url": avatar.background_url,
        }

    # 待回应信件：存量未解锁 future 信整条不进列表（§2.5-2 首页不带 title）
    pending_base_filter = (
        Letter.relation_id == relation_id,
        Letter.receiver_id == user_id,
        Letter.status == "sent",
        Letter.deleted_at.is_(None),
        not_(locked_future_clause()),
    )
    pending_letters = (
        db.query(Letter)
        .filter(*pending_base_filter)
        .order_by(Letter.send_time.desc())
        .limit(5)
        .all()
    )
    pending_letters_data = [
        {
            "id": l.id,
            "title": l.title,
            "sender_id": l.sender_id,
            "send_time": l.send_time.isoformat() if l.send_time else None,
        }
        for l in pending_letters
    ]
    # 真实总数（旧实现是被 limit(5) 截断的列表长度，角标永远最多 5）
    pending_letter_count = (
        db.query(func.count(Letter.id)).filter(*pending_base_filter).scalar() or 0
    )

    # 未完成调解（契约 §2.3-7：过滤修正为状态机真实状态集，in_progress 永不产生）
    from app.services.mediation_service import ACTIVE_MEDIATION_STATUSES

    active_mediation = (
        db.query(AiChatSession)
        .filter(
            AiChatSession.relation_id == relation_id,
            AiChatSession.session_type == "mediation",
            AiChatSession.mediation_status.in_(ACTIVE_MEDIATION_STATUSES),
        )
        .order_by(AiChatSession.created_at.desc())
        .first()
    )
    mediation_data = None
    if active_mediation:
        mediation_data = {
            "id": active_mediation.id,
            "title": active_mediation.title,
            "created_at": active_mediation.created_at.isoformat() if active_mediation.created_at else None,
            # 只增不减的新字段（契约 §2.3-7）：区分我发起的 / 邀我的
            "mediation_status": active_mediation.mediation_status,
            "my_role": "inviter" if active_mediation.user_id == user_id else "partner",
        }

    # 未来信
    future_letter = (
        db.query(Letter)
        .filter(
            Letter.relation_id == relation_id,
            Letter.letter_type == "future",
            Letter.unlock_time.isnot(None),
            Letter.unlock_time > datetime.utcnow(),
            Letter.deleted_at.is_(None),
        )
        .order_by(Letter.unlock_time.asc())
        .first()
    )
    future_letter_data = None
    if future_letter:
        future_letter_data = {
            "id": future_letter.id,
            # 契约 §2.5-2：该卡只在解锁前展示，title 一律替换为占位
            # （保留字段本身——JSON 只增不减，但绝不带真实标题）
            "title": LOCKED_LETTER_TITLE,
            "unlock_time": future_letter.unlock_time.isoformat() if future_letter.unlock_time else None,
        }

    # 最近藏品
    recent_museum = (
        db.query(MuseumItem)
        .filter(MuseumItem.relation_id == relation_id)
        .order_by(MuseumItem.created_at.desc())
        .limit(3)
        .all()
    )
    museum_data = [
        {
            "id": m.id,
            "title": m.title,
            "item_type": m.item_type,
            "story": m.story,
        }
        for m in recent_museum
    ]

    # 推荐练习
    practices = db.query(RelationshipPractice).limit(3).all()
    practice_data = [
        {
            "id": p.id,
            "title": p.title,
            "practice_type": p.practice_type,
            "description": p.description,
        }
        for p in practices
    ]

    # 纪念日
    today = date.today()
    upcoming_anniversary = (
        db.query(Anniversary)
        .filter(
            Anniversary.relation_id == relation_id,
        )
        .order_by(Anniversary.anniversary_date.asc())
        .all()
    )
    next_anniversary = None
    for a in upcoming_anniversary:
        anniv_date = a.anniversary_date
        this_year = anniv_date.replace(year=today.year)
        if this_year < today:
            this_year = anniv_date.replace(year=today.year + 1)
        days_until = (this_year - today).days
        if days_until >= 0:
            next_anniversary = {
                "id": a.id,
                "title": a.title,
                "anniversary_date": a.anniversary_date.isoformat(),
                "days_until": days_until,
            }
            break

    # 空间信息
    space = couple_repo.get_space_by_relation_id(db, relation_id)
    space_data = None
    if space:
        space_data = {
            "name": space.name,
            "theme_color": space.theme_color,
            "next_meet_date": space.next_meet_date.isoformat() if space.next_meet_date else None,
        }

    task_cards = _build_task_cards(db, user_id, relation_id, pending_letters)

    return {
        "mode": "couple",
        "relation": relation_info,
        "avatar": avatar_data,
        "pending_letters": pending_letters_data,
        "pending_letter_count": pending_letter_count,
        "active_mediation": mediation_data,
        "future_letter": future_letter_data,
        "recent_museum_items": museum_data,
        "recommended_practices": practice_data,
        "upcoming_anniversary": next_anniversary,
        "space": space_data,
        # 契约 §3.1（只增不减的新字段）：军师首页任务卡，按优先级排序
        "task_cards": task_cards,
    }


def _build_task_cards(
    db: Session,
    user_id: int,
    relation_id: int,
    pending_letters: list,
) -> list:
    """契约 §3.1：军师首页任务卡，每类至多 1 张，顺序固定。

    mediation_invite → dual_perspective → pending_letter → feedback_outcome
    → questionnaire。FE 按 type 自行映射路由（不信任 route 字符串拼法），
    route 仍按契约示例给出，供调试与后续版本使用。
    """
    cards = []

    # 1) 调解邀请：伴侣邀我（partner_user_id == 我）且仍处 inviting。
    #    FE routeForTaskCard 固定拼 isInviter=false —— 我发起的邀请绝不出卡。
    invite_session = (
        db.query(AiChatSession)
        .filter(
            AiChatSession.relation_id == relation_id,
            AiChatSession.session_type == "mediation",
            AiChatSession.partner_user_id == user_id,
            AiChatSession.mediation_status == "inviting",
        )
        .order_by(AiChatSession.created_at.desc())
        .first()
    )
    if invite_session is not None:
        cards.append(
            {
                "type": "mediation_invite",
                "id": invite_session.id,
                "title": "双人调解邀请",
                "created_at": (
                    invite_session.created_at.isoformat()
                    if invite_session.created_at
                    else None
                ),
                "route": "mediation_invite?sessionId=%d" % invite_session.id,
            }
        )

    # 2) 双视角：status=one_side 且我尚未提交（NOT EXISTS 我的 record）
    my_record_exists = (
        db.query(DualPerspectiveRecord.id)
        .filter(
            DualPerspectiveRecord.event_id == DualPerspectiveEvent.id,
            DualPerspectiveRecord.user_id == user_id,
        )
        .exists()
    )
    dp_event = (
        db.query(DualPerspectiveEvent)
        .filter(
            DualPerspectiveEvent.relation_id == relation_id,
            DualPerspectiveEvent.status == "one_side",
            ~my_record_exists,
        )
        .order_by(DualPerspectiveEvent.event_time.desc())
        .first()
    )
    if dp_event is not None:
        cards.append(
            {
                "type": "dual_perspective",
                "id": dp_event.id,
                "title": dp_event.title,
                "created_at": (
                    dp_event.created_at.isoformat() if dp_event.created_at else None
                ),
                "route": "dual_perspective_detail/%d" % dp_event.id,
            }
        )

    # 3) 待回信：列表首封（调用方传入的已排除未解锁 future 信）
    if pending_letters:
        first_letter = pending_letters[0]
        cards.append(
            {
                "type": "pending_letter",
                "id": first_letter.id,
                "title": first_letter.title or "",
                "created_at": (
                    first_letter.send_time.isoformat()
                    if first_letter.send_time
                    else None
                ),
                "route": "letter_detail/%d" % first_letter.id,
            }
        )

    # 4) 反馈回访：近 7 天「有建议但无 outcome」首条（契约 §3.4 同一数据源）。
    #    懒导入：ai_service 不依赖 home_service，但避免模块级环。
    from app.services.ai_service import list_pending_feedback

    pending_fb = list_pending_feedback(db, user_id, days=7)
    if pending_fb["items"]:
        first_fb = pending_fb["items"][0]
        cards.append(
            {
                "type": "feedback_outcome",
                # 契约/FE 约定：feedback 卡的 id 语义是会话 id
                "id": first_fb["session_id"],
                "title": "上次建议采用了么？",
                "created_at": first_fb["created_at"],
                "route": "feedback_outcome",
            }
        )

    # 5) 问卷：本人关系画像未完成
    if profile_repo.get_latest_profile(db, user_id) is None:
        cards.append(
            {
                "type": "questionnaire",
                "id": 1,
                "title": "关系画像未完成",
                "created_at": None,
                "route": "questionnaire_intro",
            }
        )

    return cards
