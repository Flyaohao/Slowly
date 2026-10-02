from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.errors import safe_business_code
from app.schemas.common import ApiResponse
from app.schemas.profile_schema import EnrichProfileRequest, ManualVersionRequest
from app.repositories import profile_repo, couple_repo
from app.services.conflict_detector import CONFLICT_PATTERN_INFO
from app.services import profile_service, personality_service
from app.services.ai_service import generate_profile_report

router = APIRouter(prefix="/profiles", tags=["关系画像"])


#: 画像版本/补充链路的业务错误码 → 可读文案。
#: 全部走 HTTP 200 + 业务码（项目统一约定），客户端按 code 分支。
_PROFILE_ERRORS = {
    "70001": (400, "还没有画像，先完成一次问卷再来"),
    "70002": (400, "没有需要补充的维度"),
    "70003": (400, "这次分析把握不足，暂不写入画像"),
    "70005": (404, "版本不存在"),
    "70006": (400, "当前版本不能删除"),
    "70007": (400, "该版本正被关系画像使用，不能删除"),
    "70008": (400, "这条观点已经计入画像了，不用再补充一次"),
}


def _profile_error(exc: ValueError) -> ApiResponse:
    code = str(exc)
    _, message = _PROFILE_ERRORS.get(code, (400, "操作失败"))
    # 非数字码（Pydantic / service 中文消息）回退 400，与全局映射表同口径。
    return ApiResponse(code=safe_business_code(code, 400), message=message, data=None)


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
        # 2026-09-28 画像页可视化：类型中文名 + 维度中文名随分数下发，
        # 客户端不再各自维护第二份映射。纯 additive，老客户端忽略未知键。
        "profile_type_label": profile_service.PROFILE_TYPE_LABELS.get(
            profile.profile_type, profile.profile_type),
        "confidence": profile.confidence,
        "summary": profile.summary,
        "version": profile.version,
        "created_at": profile.created_at.isoformat(),
        "dimension_scores": [
            {
                "dimension_key": s.dimension_key,
                "label": profile_service.DIMENSION_DEFINITIONS.get(
                    s.dimension_key, {}).get("label", s.dimension_key),
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
            "label": profile_service.DIMENSION_DEFINITIONS.get(
                s.dimension_key, {}).get("label", s.dimension_key),
            "score": s.score,
            "explanation": s.explanation,
        }
        for s in scores
    ])


@router.get("/personality", response_model=ApiResponse)
def get_personality(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """人格画像页「性格辅助信息」：自己 + 伴侣的 MBTI/星座（后端算好）。

    纯只读 + 纯计算，不触 LLM；未绑定伴侣时 partner=None（不报错，
    由客户端降级展示引导文案）。
    """
    return ApiResponse(data=personality_service.get_personality_info(db, current_user.id))


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
    except Exception:
        return ApiResponse(code=50001, message="报告生成失败", data=None)


# ============================================================
# 画像版本：历史 / 对比 / 撤回 / 增删（用户需求 #5）
#
# 操作对象一律是**整个画像版本**，不提供「改某一维度」的入口——画像内部的
# 维度分是问卷与观点共同作用的产物，允许用户直接改某一格，等于让画像脱离
# 它的依据链，之后任何解释都无从谈起。用户能做的只有：保存一个版本、
# 回到某个版本、删掉一个不想要的版本。
# ============================================================


@router.get("/versions", response_model=ApiResponse)
def list_profile_versions(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    return ApiResponse(data=profile_service.list_versions(db, current_user.id))


@router.get("/versions/{version_id}", response_model=ApiResponse)
def get_profile_version(
    version_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        return ApiResponse(data=profile_service.version_detail(db, current_user.id, version_id))
    except ValueError as e:
        return _profile_error(e)


@router.get("/versions/{version_id}/diff", response_model=ApiResponse)
def diff_profile_version(
    version_id: int,
    base: int = 0,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """把 `version_id` 与 `base` 对比；`base` 省略时以当前最新版本为基准。"""
    try:
        if not base:
            latest = profile_repo.get_latest_profile(db, current_user.id)
            if latest is None:
                return ApiResponse(code=70001, message="还没有画像，先完成一次问卷再来", data=None)
            base = latest.id
        return ApiResponse(
            data=profile_service.diff_versions(db, current_user.id, base, version_id)
        )
    except ValueError as e:
        return _profile_error(e)


@router.post("/versions", response_model=ApiResponse)
def create_manual_version(
    req: ManualVersionRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        return ApiResponse(data=profile_service.save_manual_version(db, current_user.id, req.note))
    except ValueError as e:
        return _profile_error(e)


@router.post("/versions/{version_id}/restore", response_model=ApiResponse)
def restore_profile_version(
    version_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        return ApiResponse(data=profile_service.restore_version(db, current_user.id, version_id))
    except ValueError as e:
        return _profile_error(e)


@router.delete("/versions/{version_id}", response_model=ApiResponse)
def delete_profile_version(
    version_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        profile_service.delete_version(db, current_user.id, version_id)
        return ApiResponse(data={"deleted": version_id})
    except ValueError as e:
        return _profile_error(e)


@router.post("/me/enrich", response_model=ApiResponse)
def enrich_my_profile(
    req: EnrichProfileRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """用一条观点补充画像（派生新版本）。

    前端**不要**在这里自行判定「该不该写」——置信度门槛、维度白名单、幅度约束
    全部在 service 层，客户端只是把 AI 的建议原样递进来。
    """
    try:
        result = profile_service.enrich_from_viewpoint(
            db,
            current_user.id,
            viewpoint_id=req.viewpoint_id,
            dimensions=req.dimensions,
            summary=req.summary,
            directions={
                k: v.model_dump() for k, v in (req.directions or {}).items()
            },
            confidence=req.confidence,
            force=req.force,
        )
        return ApiResponse(data=result)
    except ValueError as e:
        return _profile_error(e)
