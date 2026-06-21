from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.anniversary_schema import (
    AnniversaryCreate, AnniversaryUpdate, AnniversaryOut,
)
from app.services import anniversary_service

router = APIRouter(prefix="/anniversaries", tags=["纪念日"])


@router.post("", response_model=ApiResponse)
def create_anniversary(
    req: AnniversaryCreate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = anniversary_service.create_anniversary(db, current_user.id, req.model_dump())
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        return ApiResponse(code=int(code), message="创建失败", data=None)
    return ApiResponse(data=AnniversaryOut.model_validate(item).model_dump())


@router.get("", response_model=ApiResponse)
def list_anniversaries(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = anniversary_service.list_anniversaries(db, current_user.id, page, page_size)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    result["items"] = [AnniversaryOut.model_validate(i).model_dump() for i in result["items"]]
    return ApiResponse(data=result)


@router.put("/{item_id}", response_model=ApiResponse)
def update_anniversary(
    item_id: int,
    req: AnniversaryUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        item = anniversary_service.update_anniversary(db, current_user.id, item_id, data)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "100001":
            return ApiResponse(code=100001, message="纪念日不存在", data=None)
        if code == "100002":
            return ApiResponse(code=100002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="更新失败", data=None)
    return ApiResponse(data=AnniversaryOut.model_validate(item).model_dump())


@router.delete("/{item_id}", response_model=ApiResponse)
def delete_anniversary(
    item_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        anniversary_service.delete_anniversary(db, current_user.id, item_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "100001":
            return ApiResponse(code=100001, message="纪念日不存在", data=None)
        if code == "100002":
            return ApiResponse(code=100002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="删除失败", data=None)
    return ApiResponse()
