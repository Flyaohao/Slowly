from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.museum_schema import MuseumItemCreate, MuseumItemUpdate, MuseumItemOut
from app.services import museum_service

router = APIRouter(prefix="/museum", tags=["关系博物馆"])


@router.post("", response_model=ApiResponse)
def create_item(
    req: MuseumItemCreate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = museum_service.create_item(db, current_user.id, req.model_dump())
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        return ApiResponse(code=int(code), message="创建失败", data=None)
    return ApiResponse(data=MuseumItemOut.model_validate(item).model_dump())


@router.get("", response_model=ApiResponse)
def list_items(
    item_type: Optional[str] = Query(None),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = museum_service.list_items(db, current_user.id, item_type, page, page_size)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    result["items"] = [MuseumItemOut.model_validate(i).model_dump() for i in result["items"]]
    return ApiResponse(data=result)


@router.get("/{item_id}", response_model=ApiResponse)
def get_item(
    item_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = museum_service.get_item(db, current_user.id, item_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "80001":
            return ApiResponse(code=80001, message="藏品不存在", data=None)
        if code == "80002":
            return ApiResponse(code=80002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="获取失败", data=None)
    return ApiResponse(data=MuseumItemOut.model_validate(item).model_dump())


@router.put("/{item_id}", response_model=ApiResponse)
def update_item(
    item_id: int,
    req: MuseumItemUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        item = museum_service.update_item(db, current_user.id, item_id, data)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "80001":
            return ApiResponse(code=80001, message="藏品不存在", data=None)
        if code == "80002":
            return ApiResponse(code=80002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="更新失败", data=None)
    return ApiResponse(data=MuseumItemOut.model_validate(item).model_dump())


@router.delete("/{item_id}", response_model=ApiResponse)
def delete_item(
    item_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        museum_service.delete_item(db, current_user.id, item_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "80001":
            return ApiResponse(code=80001, message="藏品不存在", data=None)
        if code == "80002":
            return ApiResponse(code=80002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="删除失败", data=None)
    return ApiResponse()


@router.post("/{item_id}/pin", response_model=ApiResponse)
def toggle_pin(
    item_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = museum_service.toggle_pin(db, current_user.id, item_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "80001":
            return ApiResponse(code=80001, message="藏品不存在", data=None)
        if code == "80002":
            return ApiResponse(code=80002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="操作失败", data=None)
    return ApiResponse(data=MuseumItemOut.model_validate(item).model_dump())
