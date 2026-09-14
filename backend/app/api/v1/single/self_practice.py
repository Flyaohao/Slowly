from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.self_practice_schema import (
    SelfPracticeResponse,
    SelfPracticeRecordResponse,
    SubmitSelfPracticeRequest,
)
from app.services import self_practice_service

router = APIRouter(prefix="/self-practices", tags=["自我练习"])


def _to_practice_response(p) -> dict:
    return {
        "id": p.id,
        "title": p.title,
        "practice_type": p.practice_type,
        "description": p.description,
        "guidance": p.guidance,
    }


def _to_record_response(r) -> dict:
    return {
        "id": r.id,
        "practice_id": r.practice_id,
        "user_id": r.user_id,
        "status": r.status,
        "content": r.content,
        "reflection": r.reflection,
        "created_at": r.created_at.isoformat() if r.created_at else None,
        "updated_at": r.updated_at.isoformat() if r.updated_at else None,
    }


@router.get("", response_model=ApiResponse)
def list_practices(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    items = self_practice_service.list_practices(db)
    return ApiResponse(data=[_to_practice_response(i) for i in items])


@router.post("/{practice_id}/start", response_model=ApiResponse)
def start_practice(
    practice_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        record = self_practice_service.start_practice(db, current_user.id, practice_id)
    except ValueError as e:
        code = str(e)
        if code == "90001":
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": 90001, "message": "练习不存在", "data": None},
            )
        raise HTTPException(status_code=400, detail={"code": 90000, "message": "操作失败", "data": None})
    return ApiResponse(data=_to_record_response(record))


@router.post("/records/{record_id}/submit", response_model=ApiResponse)
def submit_practice(
    record_id: int,
    req: SubmitSelfPracticeRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        record = self_practice_service.submit_practice(
            db, current_user.id, record_id, req.model_dump()
        )
    except ValueError as e:
        code = str(e)
        if code == "90002":
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": 90002, "message": "记录不存在", "data": None},
            )
        if code == "90003":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={"code": 90003, "message": "无权操作", "data": None},
            )
        raise HTTPException(status_code=400, detail={"code": 90000, "message": "操作失败", "data": None})
    return ApiResponse(data=_to_record_response(record))


@router.get("/records", response_model=ApiResponse)
def list_records(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    result = self_practice_service.list_records(db, current_user.id, page, page_size)
    result["items"] = [_to_record_response(r) for r in result["items"]]
    return ApiResponse(data=result)


@router.get("/records/{record_id}", response_model=ApiResponse)
def get_record(
    record_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        record = self_practice_service.get_record(db, current_user.id, record_id)
    except ValueError as e:
        code = str(e)
        if code == "90002":
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": 90002, "message": "记录不存在", "data": None},
            )
        if code == "90003":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={"code": 90003, "message": "无权操作", "data": None},
            )
        raise HTTPException(status_code=400, detail={"code": 90000, "message": "操作失败", "data": None})
    return ApiResponse(data=_to_record_response(record))
