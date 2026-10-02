from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from pydantic import BaseModel, Field
from typing import Optional
from datetime import date

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.errors import safe_business_code
from app.core.features import require_feature
from app.schemas.common import ApiResponse
from app.services import presence_service

router = APIRouter(tags=["在场感"])


class MeetDateUpdate(BaseModel):
    meet_date: date


class MomentShare(BaseModel):
    content: str = Field(..., min_length=1, max_length=1000)
    moment_type: str = Field("text", max_length=30)
    image_url: Optional[str] = Field(None, max_length=500)


class CompanionRequest(BaseModel):
    message: Optional[str] = Field(None, max_length=200)


# 契约 §1：meet-date 端点**保留**给关系管理使用，不挂冻结依赖。
@router.put("/couples/me/space/meet-date", response_model=ApiResponse)
def set_meet_date(
    req: MeetDateUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = presence_service.set_meet_date(db, current_user.id, req.meet_date)
    except ValueError as e:
        code = str(e)
        error_map = {
            "30005": (400, "请先绑定情侣关系"),
            "40001": (404, "空间不存在"),
        }
        sc, msg = error_map.get(code, (400, "设置失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    return ApiResponse(data=data)


@router.post("/presence/moment", response_model=ApiResponse, dependencies=[Depends(require_feature("presence"))])
def share_moment(
    req: MomentShare,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = presence_service.share_moment(
            db, current_user.id, req.content, req.moment_type, req.image_url
        )
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "分享失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    return ApiResponse(data=data)


@router.get("/presence/feed", response_model=ApiResponse, dependencies=[Depends(require_feature("presence"))])
def get_feed(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = presence_service.get_feed(db, current_user.id)
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "获取失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    return ApiResponse(data=data)


@router.post("/presence/companion-request", response_model=ApiResponse, dependencies=[Depends(require_feature("presence"))])
def send_companion_request(
    req: CompanionRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = presence_service.send_companion_request(db, current_user.id, req.message)
    except ValueError as e:
        code = str(e)
        error_map = {"30005": (400, "请先绑定情侣关系")}
        sc, msg = error_map.get(code, (400, "发送失败"))
        raise HTTPException(status_code=sc, detail={"code": safe_business_code(code, sc), "message": msg, "data": None})
    return ApiResponse(data=data)
