from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.avatar_schema import (
    AvatarUpdate, VoiceStyleUpdate, AvatarOut, AvatarAssetOut,
)
from app.services import avatar_service

router = APIRouter(prefix="/avatars", tags=["AI形象"])


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
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=AvatarOut.model_validate(avatar).model_dump())


@router.put("/me", response_model=ApiResponse)
def update_avatar(
    req: AvatarUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        avatar = avatar_service.update_avatar(db, current_user.id, data)
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "更新失败"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=AvatarOut.model_validate(avatar).model_dump())


@router.get("/assets", response_model=ApiResponse)
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
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
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
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=AvatarOut.model_validate(avatar).model_dump())
