from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.couple_schema import CoupleSpaceUpdateRequest, BindRequest
from app.services import couple_service

router = APIRouter(prefix="/couples", tags=["情侣"])


@router.post("/invite", response_model=ApiResponse)
def generate_invite(current_user=Depends(get_current_user), db: Session = Depends(get_db)):
    try:
        result = couple_service.generate_invite_code(db, current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": 30002, "message": "用户已有绑定关系", "data": None},
        )
    return ApiResponse(data=result)


@router.post("/bind", response_model=ApiResponse)
def bind_couple(
    req: BindRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = couple_service.bind_couple(db, current_user.id, req.invite_code)
    except ValueError as e:
        code = str(e)
        error_map = {
            "30001": (400, "恋爱码无效或过期"),
            "30002": (409, "用户已有绑定关系"),
            "30003": (400, "不能绑定自己"),
        }
        sc, message = error_map.get(code, (400, "绑定失败"))
        raise HTTPException(
            status_code=sc,
            detail={"code": int(code), "message": message, "data": None},
        )
    return ApiResponse(data=result)


@router.get("/me", response_model=ApiResponse)
def get_couple_me(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        info = couple_service.get_couple_info(db, current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 30005, "message": "无权操作此关系", "data": None},
        )
    return ApiResponse(data=info)


@router.put("/me/space", response_model=ApiResponse)
def update_space(
    req: CoupleSpaceUpdateRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    data = req.model_dump(exclude_unset=True)
    try:
        info = couple_service.update_space(db, current_user.id, data)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 30005, "message": "无权操作此关系", "data": None},
        )
    return ApiResponse(data=info)


@router.post("/unbind", response_model=ApiResponse)
def request_unbind(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        couple_service.request_unbind(db, current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 30005, "message": "无权操作此关系", "data": None},
        )
    return ApiResponse()


@router.post("/unbind/confirm", response_model=ApiResponse)
def confirm_unbind(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        couple_service.confirm_unbind(db, current_user.id)
    except ValueError as e:
        code = str(e)
        if code == "30004":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": 30004, "message": "冷静期未满，无法确认解绑", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 30005, "message": "无权操作此关系", "data": None},
        )
    return ApiResponse()


@router.post("/unbind/cancel", response_model=ApiResponse)
def cancel_unbind(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        couple_service.cancel_unbind(db, current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 30005, "message": "无权操作此关系", "data": None},
        )
    return ApiResponse()
