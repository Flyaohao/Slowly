from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from typing import Optional
from datetime import datetime

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.services import memory_service
from app.repositories import couple_repo

router = APIRouter(prefix="/ai/memory", tags=["AI 记忆"])


@router.get("", response_model=ApiResponse)
def get_memories(
    relation_id: Optional[int] = Query(None),
    memory_type: Optional[str] = Query(None),
    source: Optional[str] = Query(None),
    importance: Optional[int] = Query(None),
    since: Optional[datetime] = Query(None),
    until: Optional[datetime] = Query(None),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    # P-C3 §4.1：筛选参数只加不删，全部可选（不传 = 旧行为）
    memories = memory_service.get_memories(
        db, current_user.id, relation_id, memory_type,
        source=source, importance=importance, since=since, until=until,
    )
    return ApiResponse(data=memories)


@router.get("/couple/{relation_id}", response_model=ApiResponse)
def get_couple_memories(
    relation_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    # IDOR 守卫（v3.2 附录 §5.2）：非关系成员一律 403，不区分「关系不存在」
    try:
        memories = memory_service.get_couple_memories(
            db, current_user.id, relation_id
        )
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权访问该关系的记忆", "data": None},
        )
    return ApiResponse(data=memories)


@router.put("/{memory_id}/visibility", response_model=ApiResponse)
def update_visibility(
    memory_id: int,
    body: dict,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    visibility = body.get("visibility", "private")
    if visibility not in ("private", "couple"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40001, "message": "无效的可见性", "data": None},
        )
    try:
        result = memory_service.update_visibility(db, memory_id, current_user.id, visibility)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权操作此记忆", "data": None},
        )
    return ApiResponse(data=result)


@router.put("/{memory_id}/importance", response_model=ApiResponse)
def update_importance(
    memory_id: int,
    body: dict,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """标星/取消标星（P-C3 §4.2）。只接受 2（标星）/ 0（取消）。"""
    importance = body.get("importance", 0)
    if importance not in (0, 2):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40001, "message": "importance 只能是 0 或 2", "data": None},
        )
    try:
        result = memory_service.update_importance(db, memory_id, current_user.id, importance)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权操作此记忆", "data": None},
        )
    return ApiResponse(data=result)


@router.delete("/{memory_id}", response_model=ApiResponse)
def delete_memory(
    memory_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        memory_service.delete_memory(db, memory_id, current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权删除此记忆", "data": None},
        )
    return ApiResponse()
