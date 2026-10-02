from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.errors import safe_business_code
from app.core.features import require_feature
from app.schemas.common import ApiResponse
from app.schemas.avatar_schema import (
    AvatarUpdate, VoiceStyleUpdate, AvatarOut, AvatarAssetOut,
)
from app.services import avatar_service

router = APIRouter(prefix="/avatars", tags=["AI形象"])

#: 契约 §1「AI 形象（捏脸）」：PUT /me 冻结的 appearance 字段。
#: `name` 不在名单里（保留为军师设置存储，§3.3）；`voice_style` 走
#: POST /me/voice-style 专属端点，同样保留。
_AVATAR_APPEARANCE_FIELDS = ("body_color", "face_config", "outfit_config", "background_url")

#: 复用为可直接调用的冻结检查：W6 打开开关后 PUT 的字段级检查自动放行，
#: 与 GET /assets 上挂的依赖同源（同一个 FEATURE_FLAGS 条目）。
_check_appearance_frozen = require_feature("avatar_appearance")


@router.get("/me", response_model=ApiResponse)
def get_avatar(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        avatar = avatar_service.get_avatar(db, current_user.id)
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "获取失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    return ApiResponse(data=AvatarOut.model_validate(avatar).model_dump())


@router.put("/me", response_model=ApiResponse)
def update_avatar(
    req: AvatarUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        # 契约 §1：appearance 字段冻结为 10006，name 保留。
        # 只要请求体出现 appearance 字段就拦（含显式 null——清空形象
        # 同样是对捏脸数据的写操作）；仅改 name 的请求正常放行。
        if any(k in data for k in _AVATAR_APPEARANCE_FIELDS):
            _check_appearance_frozen()
        avatar = avatar_service.update_avatar(db, current_user.id, data)
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "更新失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    return ApiResponse(data=AvatarOut.model_validate(avatar).model_dump())


@router.get(
    "/assets",
    response_model=ApiResponse,
    # 契约 §1：assets 属捏脸素材，整端点冻结 10006（冻结检查在鉴权之前，
    # 与 museum 等已接线模块同一挂法）
    dependencies=[Depends(require_feature("avatar_appearance"))],
)
def get_assets(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        assets = avatar_service.get_assets(db, current_user.id)
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "获取失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    items = [AvatarAssetOut.model_validate(a).model_dump() for a in assets]
    return ApiResponse(data=items)


@router.post("/me/voice-style", response_model=ApiResponse)
def set_voice_style(
    req: VoiceStyleUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        avatar = avatar_service.set_voice_style(db, current_user.id, req.voice_style)
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "设置失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    return ApiResponse(data=AvatarOut.model_validate(avatar).model_dump())
