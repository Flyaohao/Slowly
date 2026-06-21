from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.dual_perspective_schema import (
    DualPerspectiveEventCreate,
    DualPerspectiveRecordSubmit,
    DualPerspectiveRecordUpdate,
    DualPerspectiveEventOut,
    DualPerspectiveEventListOut,
    DualPerspectiveRecordOut,
)
from app.services import dual_perspective_service

router = APIRouter(prefix="/dual-perspectives", tags=["双视角记录"])


@router.post("", response_model=ApiResponse)
def create_event(
    req: DualPerspectiveEventCreate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        event = dual_perspective_service.create_event(db, current_user.id, req.model_dump())
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        return ApiResponse(code=int(code), message="创建失败", data=None)
    return ApiResponse(data=DualPerspectiveEventListOut.model_validate(event).model_dump())


@router.get("", response_model=ApiResponse)
def list_events(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = dual_perspective_service.list_events(db, current_user.id, page, page_size)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    result["items"] = [DualPerspectiveEventListOut.model_validate(i).model_dump() for i in result["items"]]
    return ApiResponse(data=result)


@router.get("/{event_id}", response_model=ApiResponse)
def get_event_detail(
    event_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        event = dual_perspective_service.get_event_detail(db, current_user.id, event_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "70001":
            return ApiResponse(code=70001, message="事件不存在", data=None)
        if code == "70002":
            return ApiResponse(code=70002, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="获取失败", data=None)
    return ApiResponse(data=DualPerspectiveEventOut.model_validate(event).model_dump())


@router.post("/{event_id}/records", response_model=ApiResponse)
def submit_record(
    event_id: int,
    req: DualPerspectiveRecordSubmit,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        record = dual_perspective_service.submit_record(db, current_user.id, event_id, req.model_dump())
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "70001":
            return ApiResponse(code=70001, message="事件不存在", data=None)
        if code == "70002":
            return ApiResponse(code=70002, message="无权访问", data=None)
        if code == "70003":
            return ApiResponse(code=70003, message="已提交过视角", data=None)
        return ApiResponse(code=int(code), message="提交失败", data=None)
    return ApiResponse(data=DualPerspectiveRecordOut.model_validate(record).model_dump())


@router.put("/{event_id}/records/{record_id}", response_model=ApiResponse)
def edit_record(
    event_id: int,
    record_id: int,
    req: DualPerspectiveRecordUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        record = dual_perspective_service.edit_record(db, current_user.id, event_id, record_id, data)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "70001":
            return ApiResponse(code=70001, message="事件不存在", data=None)
        if code == "70002":
            return ApiResponse(code=70002, message="无权访问", data=None)
        if code == "70004":
            return ApiResponse(code=70004, message="记录不存在", data=None)
        return ApiResponse(code=int(code), message="编辑失败", data=None)
    return ApiResponse(data=DualPerspectiveRecordOut.model_validate(record).model_dump())


@router.post("/{event_id}/reveal", response_model=ApiResponse)
def reveal_event(
    event_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        event = dual_perspective_service.reveal_event(db, current_user.id, event_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "70001":
            return ApiResponse(code=70001, message="事件不存在", data=None)
        if code == "70002":
            return ApiResponse(code=70002, message="无权访问", data=None)
        if code == "70005":
            return ApiResponse(code=70005, message="双方尚未都提交视角", data=None)
        return ApiResponse(code=int(code), message="操作失败", data=None)
    return ApiResponse(data=DualPerspectiveEventOut.model_validate(event).model_dump())
