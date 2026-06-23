from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.repositories import profile_repo, couple_repo
from app.services.conflict_detector import CONFLICT_PATTERN_INFO
from app.services.ai_service import generate_profile_report

router = APIRouter(prefix="/profiles", tags=["关系画像"])


@router.get("/me", response_model=ApiResponse)
def get_my_profile(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    profile = profile_repo.get_latest_profile(db, current_user.id)
    if not profile:
        return ApiResponse(data=None)

    scores = profile_repo.get_dimension_scores(db, profile.id)
    return ApiResponse(data={
        "id": profile.id,
        "user_id": profile.user_id,
        "profile_type": profile.profile_type,
        "confidence": profile.confidence,
        "summary": profile.summary,
        "version": profile.version,
        "created_at": profile.created_at.isoformat(),
        "dimension_scores": [
            {
                "dimension_key": s.dimension_key,
                "score": s.score,
                "explanation": s.explanation,
            }
            for s in scores
        ],
    })


@router.get("/me/dimensions", response_model=ApiResponse)
def get_my_dimensions(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    profile = profile_repo.get_latest_profile(db, current_user.id)
    if not profile:
        return ApiResponse(data=[])

    scores = profile_repo.get_dimension_scores(db, profile.id)
    return ApiResponse(data=[
        {
            "dimension_key": s.dimension_key,
            "score": s.score,
            "explanation": s.explanation,
        }
        for s in scores
    ])


@router.get("/couple", response_model=ApiResponse)
def get_couple_profile(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)

    cp = profile_repo.get_latest_couple_profile(db, relation.id)
    if not cp:
        return ApiResponse(data=None)

    profile_a = profile_repo.get_profile_by_id(db, cp.user_a_profile_id)
    profile_b = profile_repo.get_profile_by_id(db, cp.user_b_profile_id)

    scores_a = profile_repo.get_dimension_scores(db, cp.user_a_profile_id) if profile_a else []
    scores_b = profile_repo.get_dimension_scores(db, cp.user_b_profile_id) if profile_b else []

    pattern_info = CONFLICT_PATTERN_INFO.get(cp.conflict_pattern, {})

    return ApiResponse(data={
        "id": cp.id,
        "relation_id": cp.relation_id,
        "user_a_profile_id": cp.user_a_profile_id,
        "user_b_profile_id": cp.user_b_profile_id,
        "conflict_pattern": cp.conflict_pattern,
        "conflict_pattern_name": pattern_info.get("name", cp.conflict_pattern),
        "conflict_pattern_description": pattern_info.get("description", ""),
        "summary": cp.summary,
        "created_at": cp.created_at.isoformat(),
        "user_a_profile": {
            "id": profile_a.id,
            "user_id": profile_a.user_id,
            "profile_type": profile_a.profile_type,
            "confidence": profile_a.confidence,
            "summary": profile_a.summary,
            "version": profile_a.version,
            "created_at": profile_a.created_at.isoformat(),
        } if profile_a else None,
        "user_b_profile": {
            "id": profile_b.id,
            "user_id": profile_b.user_id,
            "profile_type": profile_b.profile_type,
            "confidence": profile_b.confidence,
            "summary": profile_b.summary,
            "version": profile_b.version,
            "created_at": profile_b.created_at.isoformat(),
        } if profile_b else None,
        "user_a_dimensions": [
            {"id": s.id, "dimension_key": s.dimension_key, "score": s.score, "explanation": s.explanation}
            for s in scores_a
        ],
        "user_b_dimensions": [
            {"id": s.id, "dimension_key": s.dimension_key, "score": s.score, "explanation": s.explanation}
            for s in scores_b
        ],
    })


@router.get("/history", response_model=ApiResponse)
def get_profile_history(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    profiles = profile_repo.get_profile_history(db, current_user.id)
    return ApiResponse(data=[
        {
            "id": p.id,
            "profile_type": p.profile_type,
            "confidence": p.confidence,
            "summary": p.summary,
            "version": p.version,
            "created_at": p.created_at.isoformat(),
        }
        for p in profiles
    ])


@router.get("/me/ai-report", response_model=ApiResponse)
def get_ai_report(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取 AI 生成的画像分析报告（Markdown 格式）"""
    try:
        report = generate_profile_report(db, current_user.id)
        return ApiResponse(data={"report": report})
    except ValueError as e:
        code = str(e)
        if code == "40001":
            return ApiResponse(code=40001, message="请先完成问卷", data=None)
        return ApiResponse(code=50001, message="报告生成失败", data=None)
