from sqlalchemy.orm import Session
from datetime import date, datetime

from app.models.couple_relation import CoupleRelation
from app.models.user_profile import UserProfile
from app.models.letter import Letter
from app.models.ai import AiChatSession
from app.models.museum import MuseumItem
from app.models.practice import RelationshipPractice
from app.models.anniversary import Anniversary
from app.models.user import User
from app.repositories import couple_repo, avatar_repo, diary_repo, user_repo


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

    # 待回应信件
    pending_letters = (
        db.query(Letter)
        .filter(
            Letter.relation_id == relation_id,
            Letter.receiver_id == user_id,
            Letter.status == "sent",
            Letter.deleted_at.is_(None),
        )
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

    # 未完成调解
    active_mediation = (
        db.query(AiChatSession)
        .filter(
            AiChatSession.relation_id == relation_id,
            AiChatSession.session_type == "mediation",
            AiChatSession.mediation_status == "in_progress",
        )
        .first()
    )
    mediation_data = None
    if active_mediation:
        mediation_data = {
            "id": active_mediation.id,
            "title": active_mediation.title,
            "created_at": active_mediation.created_at.isoformat() if active_mediation.created_at else None,
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
            "title": future_letter.title,
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

    return {
        "mode": "couple",
        "relation": relation_info,
        "avatar": avatar_data,
        "pending_letters": pending_letters_data,
        "pending_letter_count": len(pending_letters_data),
        "active_mediation": mediation_data,
        "future_letter": future_letter_data,
        "recent_museum_items": museum_data,
        "recommended_practices": practice_data,
        "upcoming_anniversary": next_anniversary,
        "space": space_data,
    }
