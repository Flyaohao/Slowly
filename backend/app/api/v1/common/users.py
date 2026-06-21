from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.user_schema import (
    UserProfileUpdateRequest,
    PrivatePasswordRequest,
    PrivateVerifyRequest,
)
from app.services import user_service

router = APIRouter(prefix="/users", tags=["用户"])


@router.get("/me", response_model=ApiResponse)
def get_me(current_user=Depends(get_current_user), db: Session = Depends(get_db)):
    try:
        profile = user_service.get_profile(db, current_user.id)
    except ValueError:
        return ApiResponse(code=10002, message="用户信息不存在", data=None)
    return ApiResponse(data=profile)


@router.put("/me", response_model=ApiResponse)
def update_me(
    req: UserProfileUpdateRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    data = req.model_dump(exclude_unset=True)
    try:
        profile = user_service.update_profile(db, current_user.id, data)
    except ValueError:
        return ApiResponse(code=10002, message="更新失败", data=None)
    return ApiResponse(data=profile)


@router.post("/me/avatar", response_model=ApiResponse)
async def upload_avatar(
    file: UploadFile = File(...),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    content = await file.read()
    if len(content) > 5 * 1024 * 1024:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 10003, "message": "文件大小超过限制", "data": None},
        )
    try:
        avatar_url = user_service.upload_avatar(db, current_user.id, content, file.filename)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 10003, "message": "不支持的文件类型", "data": None},
        )
    return ApiResponse(data={"avatar_url": avatar_url})


@router.post("/me/private-password", response_model=ApiResponse)
def set_private_password(
    req: PrivatePasswordRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    user_service.set_private_password(db, current_user.id, req.password)
    return ApiResponse()


@router.post("/me/private-verify", response_model=ApiResponse)
def verify_private_password(
    req: PrivateVerifyRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        token = user_service.verify_private_password(db, current_user.id, req.password)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": 10005, "message": "密码错误", "data": None},
        )
    return ApiResponse(data={"private_token": token})
