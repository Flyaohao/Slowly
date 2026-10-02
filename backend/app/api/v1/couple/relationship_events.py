"""关系历史事件 API（用户手动增删改查）。

与已冻结的模块不同，本模块是**新上线能力**，因此不挂 `require_feature`。
"""
from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.errors import safe_business_code
from app.schemas.common import ApiResponse
from app.schemas.relationship_event_schema import (
    RelationshipEventCreate,
    RelationshipEventOut,
    RelationshipEventUpdate,
)
from app.services import relationship_event_service

router = APIRouter(prefix="/relationship-events", tags=["关系事件"])


def _error(e: ValueError) -> ApiResponse:
    """统一的业务错误映射（与其它 couple 模块同形）。"""
    code = str(e)
    if code == "30005":
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    if code == "20001":
        return ApiResponse(
            code=20001,
            message="必须写清这件事对你们关系的作用（积极或消极），否则不能记录",
            data=None,
        )
    if code == "100001":
        return ApiResponse(code=100001, message="事件不存在", data=None)
    try:
        return ApiResponse(code=safe_business_code(code, 400), message="操作失败", data=None)
    except (TypeError, ValueError):
        return ApiResponse(code=100001, message="操作失败", data=None)


@router.post("", response_model=ApiResponse)
def create_event(
    req: RelationshipEventCreate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = relationship_event_service.create_event(
            db, current_user.id, req.model_dump()
        )
    except ValueError as e:
        return _error(e)
    return ApiResponse(
        data=RelationshipEventOut.model_validate(item).model_dump()
    )


@router.get("", response_model=ApiResponse)
def list_events(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = relationship_event_service.list_events(
            db, current_user.id, page, page_size
        )
    except ValueError as e:
        return _error(e)
    result["items"] = [
        RelationshipEventOut.model_validate(i).model_dump()
        for i in result["items"]
    ]
    return ApiResponse(data=result)


@router.get("/{event_id}", response_model=ApiResponse)
def get_event(
    event_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = relationship_event_service.get_event(db, current_user.id, event_id)
    except ValueError as e:
        return _error(e)
    return ApiResponse(
        data=RelationshipEventOut.model_validate(item).model_dump()
    )


@router.put("/{event_id}", response_model=ApiResponse)
def update_event(
    event_id: int,
    req: RelationshipEventUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        item = relationship_event_service.update_event(
            db, current_user.id, event_id, req.model_dump(exclude_unset=True)
        )
    except ValueError as e:
        return _error(e)
    return ApiResponse(
        data=RelationshipEventOut.model_validate(item).model_dump()
    )


@router.delete("/{event_id}", response_model=ApiResponse)
def delete_event(
    event_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        relationship_event_service.delete_event(db, current_user.id, event_id)
    except ValueError as e:
        return _error(e)
    return ApiResponse()
