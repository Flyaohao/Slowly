from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.profile import (
    RelationshipProfile,
    ProfileDimensionScore,
    CoupleProfile,
)


def create_profile(
    db: Session,
    user_id: int,
    questionnaire_id: int,
    profile_type: str,
    confidence: float,
    summary: str,
    version: int,
    origin: str = "questionnaire",
    origin_note: Optional[str] = None,
    source_viewpoint_id: Optional[int] = None,
) -> RelationshipProfile:
    profile = RelationshipProfile(
        user_id=user_id,
        questionnaire_id=questionnaire_id,
        profile_type=profile_type,
        confidence=confidence,
        summary=summary,
        version=version,
        origin=origin,
        origin_note=origin_note,
        source_viewpoint_id=source_viewpoint_id,
    )
    db.add(profile)
    db.flush()
    return profile


def count_profiles(db: Session, user_id: int) -> int:
    return (
        db.query(RelationshipProfile)
        .filter(RelationshipProfile.user_id == user_id)
        .count()
    )


def count_couple_profile_refs(db: Session, profile_id: int) -> int:
    """该画像版本被几个关系画像引用（删除版本前的安全检查）。"""
    return (
        db.query(CoupleProfile)
        .filter(
            (CoupleProfile.user_a_profile_id == profile_id)
            | (CoupleProfile.user_b_profile_id == profile_id)
        )
        .count()
    )


def delete_profile(db: Session, profile_id: int) -> None:
    """删除一个画像版本及其全部维度分。

    先删维度分再删主体：`profile_dimension_score.profile_id` 是逻辑外键，
    留着孤儿行会让维度分查询把不存在版本的分也算进去。
    """
    db.query(ProfileDimensionScore).filter(
        ProfileDimensionScore.profile_id == profile_id
    ).delete(synchronize_session=False)
    db.query(RelationshipProfile).filter(
        RelationshipProfile.id == profile_id
    ).delete(synchronize_session=False)
    db.flush()


def add_dimension_scores(db: Session, profile_id: int, scores: List[dict]) -> None:
    for s in scores:
        ds = ProfileDimensionScore(
            profile_id=profile_id,
            dimension_key=s["dimension_key"],
            score=s["score"],
            explanation=s.get("explanation"),
        )
        db.add(ds)
    db.flush()


def get_latest_profile(db: Session, user_id: int) -> Optional[RelationshipProfile]:
    return (
        db.query(RelationshipProfile)
        .filter(RelationshipProfile.user_id == user_id)
        .order_by(RelationshipProfile.version.desc())
        .first()
    )


def get_profile_by_id(db: Session, profile_id: int) -> Optional[RelationshipProfile]:
    return db.query(RelationshipProfile).filter(RelationshipProfile.id == profile_id).first()


def get_profile_history(db: Session, user_id: int) -> List[RelationshipProfile]:
    return (
        db.query(RelationshipProfile)
        .filter(RelationshipProfile.user_id == user_id)
        .order_by(RelationshipProfile.version.desc())
        .all()
    )


def get_dimension_scores(db: Session, profile_id: int) -> List[ProfileDimensionScore]:
    return (
        db.query(ProfileDimensionScore)
        .filter(ProfileDimensionScore.profile_id == profile_id)
        .all()
    )


def get_next_version(db: Session, user_id: int) -> int:
    latest = get_latest_profile(db, user_id)
    return (latest.version + 1) if latest else 1


def create_couple_profile(
    db: Session,
    relation_id: int,
    user_a_profile_id: int,
    user_b_profile_id: int,
    conflict_pattern: str,
    summary: str,
) -> CoupleProfile:
    cp = CoupleProfile(
        relation_id=relation_id,
        user_a_profile_id=user_a_profile_id,
        user_b_profile_id=user_b_profile_id,
        conflict_pattern=conflict_pattern,
        summary=summary,
    )
    db.add(cp)
    db.flush()
    return cp


def get_latest_couple_profile(db: Session, relation_id: int) -> Optional[CoupleProfile]:
    return (
        db.query(CoupleProfile)
        .filter(CoupleProfile.relation_id == relation_id)
        .order_by(CoupleProfile.created_at.desc())
        .first()
    )
