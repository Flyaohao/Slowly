from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.limiter import limiter
from app.schemas.common import ApiResponse
from app.schemas.auth_schema import (
    RegisterRequest,
    LoginRequest,
    RefreshRequest,
    ForgotPasswordRequest,
    ResetPasswordRequest,
)
from app.services import auth_service

router = APIRouter(prefix="/auth", tags=["认证"])


@router.post("/register", response_model=ApiResponse)
@limiter.limit("3/minute")
def register(request: Request, req: RegisterRequest, db: Session = Depends(get_db)):
    try:
        user_id = auth_service.register(db, req.email, req.password)
    except ValueError as e:
        code = str(e)
        if code == "20001":
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={"code": 20001, "message": "邮箱已注册", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 10001, "message": "密码强度不足", "data": None},
        )
    return ApiResponse(data={"user_id": user_id})


@router.post("/login", response_model=ApiResponse)
@limiter.limit("5/minute")
def login(request: Request, req: LoginRequest, db: Session = Depends(get_db)):
    try:
        tokens = auth_service.login(db, req.email, req.password)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": 20002, "message": "邮箱或密码错误", "data": None},
        )
    return ApiResponse(data=tokens)


@router.post("/refresh", response_model=ApiResponse)
def refresh(req: RefreshRequest):
    try:
        access_token = auth_service.refresh_access_token(req.refresh_token)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": 20005, "message": "Token 无效", "data": None},
        )
    return ApiResponse(data={"access_token": access_token})


@router.post("/forgot-password", response_model=ApiResponse)
@limiter.limit("1/minute")
def forgot_password(request: Request, req: ForgotPasswordRequest, db: Session = Depends(get_db)):
    try:
        dev_code = auth_service.forgot_password(db, req.email)
    except ValueError as e:
        if str(e) == "20004":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": 20004, "message": "邮件发送失败，请稍后重试", "data": None},
            )
        raise
    # EMAIL_DEV_MODE 下回显 dev_code 便于联调；生产环境恒为 None
    data = {"dev_code": dev_code} if dev_code else None
    return ApiResponse(data=data)


@router.post("/reset-password", response_model=ApiResponse)
def reset_password(req: ResetPasswordRequest, db: Session = Depends(get_db)):
    try:
        auth_service.reset_password(db, req.email, req.code, req.new_password)
    except ValueError as e:
        code = str(e)
        if code == "20003":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": 20003, "message": "验证码无效或过期", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 10001, "message": "密码强度不足", "data": None},
        )
    return ApiResponse()
