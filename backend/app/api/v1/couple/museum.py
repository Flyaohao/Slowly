import os
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, UploadFile, File
from sqlalchemy.orm import Session
from typing import Optional

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.errors import safe_business_code
from app.core.features import require_feature
from app.schemas.common import ApiResponse
from app.schemas.museum_schema import MuseumItemCreate, MuseumItemUpdate, MuseumItemOut
from app.services import museum_service

# 收敛期冻结（契约 §1）：整模块挂依赖，W6 删除时连同依赖一起摘除。
router = APIRouter(
    prefix="/museum",
    tags=["关系博物馆"],
    dependencies=[Depends(require_feature("museum"))],
)

_MAX_IMAGE_SIZE = 5 * 1024 * 1024
_ALLOWED_IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")


@router.post("/upload-image", response_model=ApiResponse)
def upload_image(
    file: UploadFile = File(...),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """上传藏品配图（照片类藏品），返回相对 URL，创建/更新藏品时放入 image_url。

    ## 为什么是同步 `def` 而不是 `async def`

    `museum_service.ensure_relation` 是**同步**函数，会执行阻塞 SQL（查情侣关系）。
    若路由写成 `async def`，这段同步 SQL 就会直接跑在事件循环上，
    阻塞整个进程的其他请求（表现为「一个人传图，全站卡住」）。

    FastAPI 会自动把同步 `def` 路由放进线程池执行，阻塞被限制在单个线程内——
    这才是同步 service 该有的写法。

    读文件同理：`UploadFile.read()` 是 async 方法，同步路由里用底层的
    `file.file.read()`（BinaryIO，FastAPI 官方同步用法）。
    """
    try:
        museum_service.ensure_relation(db, current_user.id)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)

    content = file.file.read()
    if len(content) > _MAX_IMAGE_SIZE:
        return ApiResponse(code=10003, message="文件大小超过限制", data=None)
    ext = os.path.splitext(file.filename or "")[1].lower()
    if ext not in _ALLOWED_IMAGE_EXTS:
        return ApiResponse(code=10003, message="不支持的文件类型", data=None)

    upload_dir = os.path.join("uploads", "museum")
    os.makedirs(upload_dir, exist_ok=True)
    filename = f"{uuid.uuid4().hex}{ext}"
    with open(os.path.join(upload_dir, filename), "wb") as f:
        f.write(content)
    return ApiResponse(data={"image_url": f"/uploads/museum/{filename}"})


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
        return ApiResponse(code=safe_business_code(code, 400), message="创建失败", data=None)
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
        return ApiResponse(code=safe_business_code(code, 400), message="获取失败", data=None)
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
        return ApiResponse(code=safe_business_code(code, 400), message="更新失败", data=None)
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
        return ApiResponse(code=safe_business_code(code, 400), message="删除失败", data=None)
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
        return ApiResponse(code=safe_business_code(code, 400), message="操作失败", data=None)
    return ApiResponse(data=MuseumItemOut.model_validate(item).model_dump())
