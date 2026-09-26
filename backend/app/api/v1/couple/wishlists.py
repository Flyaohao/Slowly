from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.features import require_feature
from app.schemas.common import ApiResponse
from app.schemas.anniversary_schema import WishlistCreate, WishlistUpdate, WishlistOut
from app.services import anniversary_service

# 收敛期冻结（契约 §1）：整模块挂依赖，W6 删除时连同依赖一起摘除。
router = APIRouter(
    prefix="/wishlists",
    tags=["愿望清单"],
    dependencies=[Depends(require_feature("wishlists"))],
)


@router.post("", response_model=ApiResponse)
def create_wishlist(
    req: WishlistCreate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = anniversary_service.create_wishlist(db, current_user.id, req.model_dump())
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        return ApiResponse(code=int(code), message="创建失败", data=None)
    return ApiResponse(data=WishlistOut.model_validate(item).model_dump())


@router.get("", response_model=ApiResponse)
def list_wishlists(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = anniversary_service.list_wishlists(db, current_user.id, page, page_size)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    result["items"] = [WishlistOut.model_validate(i).model_dump() for i in result["items"]]
    return ApiResponse(data=result)


@router.put("/{item_id}", response_model=ApiResponse)
def update_wishlist(
    item_id: int,
    req: WishlistUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        item = anniversary_service.update_wishlist(db, current_user.id, item_id, data)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "100001":
            return ApiResponse(code=100001, message="愿望不存在", data=None)
        if code == "100002":
            return ApiResponse(code=100002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="更新失败", data=None)
    return ApiResponse(data=WishlistOut.model_validate(item).model_dump())


@router.post("/{item_id}/complete", response_model=ApiResponse)
def complete_wishlist(
    item_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = anniversary_service.complete_wishlist(db, current_user.id, item_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "100001":
            return ApiResponse(code=100001, message="愿望不存在", data=None)
        if code == "100002":
            return ApiResponse(code=100002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="操作失败", data=None)
    return ApiResponse(data=WishlistOut.model_validate(item).model_dump())


@router.delete("/{item_id}", response_model=ApiResponse)
def delete_wishlist(
    item_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        anniversary_service.delete_wishlist(db, current_user.id, item_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "100001":
            return ApiResponse(code=100001, message="愿望不存在", data=None)
        if code == "100002":
            return ApiResponse(code=100002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="删除失败", data=None)
    return ApiResponse()
