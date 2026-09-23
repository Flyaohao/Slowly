"""
开工前第 0 步：为测试主用户造一份画像数据。

背景：
  本地库 relationship_profile / profile_dimension_score 均为 0 行，
  test_generation_stream 的 E/F 段与 P0-1/P0-2/P0-5 的验收都读不到画像。

目标用户选取规则与 tests/test_generation_stream.py 的 pick_active_relation()
完全一致：取第一条 status='active' 的 couple_relation 的 user_a_id，
保证 E/F 段登录的 token 与画像归属同一人。

幂等：目标用户已有画像且 11 个维度分齐全时跳过；只有画像没有分时补分。
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal
from app.models.couple_relation import CoupleRelation
from app.models.questionnaire import Questionnaire
from app.models.profile import ProfileDimensionScore, RelationshipProfile
from app.services.profile_service import (
    DIMENSION_DEFINITIONS,
    _build_summary,
    _calculate_confidence,
    _classify_attachment,
    _explain_dimension,
)

# 覆盖 4 个分数段，方便 P0-1 验收时核对 bands 命中：
#   high(>=75) / mid_high(>=50) / mid_low(>=25) / low(<25)
# anxiety=72, avoidance=30 → _classify_attachment 判为 anxious
TEST_SCORES = {
    "attachment_anxiety": 72.0,          # mid_high
    "attachment_avoidance": 30.0,        # mid_low
    "conflict_pursue": 65.0,             # mid_high
    "conflict_withdraw": 40.0,           # mid_low
    "defensive_response": 48.0,          # mid_low
    "emotional_validation_need": 78.0,   # high
    "factual_explanation_need": 45.0,    # mid_low
    "personal_space_need": 32.0,         # mid_low
    "reassurance_need": 82.0,            # high
    "directness_preference": 55.0,       # mid_high
    "softness_preference": 18.0,         # low
}


def pick_target_user_id(db) -> int:
    rel = (
        db.query(CoupleRelation)
        .filter(CoupleRelation.status == "active")
        .first()
    )
    if not rel:
        raise RuntimeError("库里没有 active 的 couple_relation，无法确定测试用户")
    return rel.user_a_id


def seed_test_profile() -> None:
    db = SessionLocal()
    try:
        user_id = pick_target_user_id(db)
        print(f"目标用户 user_id={user_id}（与 pick_active_relation 规则一致）")

        active_q = (
            db.query(Questionnaire)
            .filter(Questionnaire.status == "active")
            .first()
        )
        if not active_q:
            raise RuntimeError("库里没有 active 的问卷，先跑 scripts/seed_questionnaire.py")
        print(f"active questionnaire id={active_q.id} title={active_q.title}")

        existing = (
            db.query(RelationshipProfile)
            .filter(RelationshipProfile.user_id == user_id)
            .order_by(RelationshipProfile.version.desc())
            .first()
        )

        if existing:
            score_rows = (
                db.query(ProfileDimensionScore)
                .filter(ProfileDimensionScore.profile_id == existing.id)
                .all()
            )
            if len(score_rows) >= len(TEST_SCORES):
                print(
                    f"画像已存在 profile_id={existing.id} "
                    f"type={existing.profile_type} 维度分={len(score_rows)} 条，跳过"
                )
                return
            # 有画像缺分：清掉残缺分重灌，保持幂等
            db.query(ProfileDimensionScore).filter(
                ProfileDimensionScore.profile_id == existing.id
            ).delete(synchronize_session=False)
            profile = existing
            print(f"画像存在但维度分不足（{len(score_rows)}），重灌 11 条")
        else:
            anxiety = TEST_SCORES["attachment_anxiety"]
            avoidance = TEST_SCORES["attachment_avoidance"]
            profile_type = _classify_attachment(anxiety, avoidance)
            confidence = _calculate_confidence(anxiety, avoidance)
            summary = _build_summary(profile_type, TEST_SCORES)

            profile = RelationshipProfile(
                user_id=user_id,
                questionnaire_id=active_q.id,
                profile_type=profile_type,
                confidence=confidence,
                summary=summary,
                version=1,
            )
            db.add(profile)
            db.flush()
            print(
                f"新建画像 profile_id={profile.id} "
                f"type={profile_type} confidence={confidence}"
            )

        for dim_key, score in TEST_SCORES.items():
            if dim_key not in DIMENSION_DEFINITIONS:
                raise RuntimeError(f"未知维度 key: {dim_key}")
            db.add(
                ProfileDimensionScore(
                    profile_id=profile.id,
                    dimension_key=dim_key,
                    score=score,
                    explanation=_explain_dimension(dim_key, score),
                )
            )

        db.commit()
        print(f"已写入 {len(TEST_SCORES)} 条 profile_dimension_score")
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def verify() -> None:
    db = SessionLocal()
    try:
        n_profile = db.query(RelationshipProfile).count()
        n_scores = db.query(ProfileDimensionScore).count()
        print(f"核对：relationship_profile={n_profile} 行，"
              f"profile_dimension_score={n_scores} 行")
        if n_profile == 0 or n_scores < len(TEST_SCORES):
            raise RuntimeError("核对失败：画像数据未就绪")
    finally:
        db.close()


if __name__ == "__main__":
    seed_test_profile()
    verify()
    print("OK: 画像测试数据就绪")
