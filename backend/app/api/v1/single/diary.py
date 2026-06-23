from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.diary_schema import (
    CreateDiaryRequest,
    UpdateDiaryRequest,
    DiaryResponse,
    DiaryListResponse,
    BatchDeleteRequest,
    BatchDeleteResponse,
)
from app.repositories.single import diary_repo

router = APIRouter(prefix="/diary", tags=["日记"])


def _to_diary_response(entry) -> DiaryResponse:
    return DiaryResponse(
        id=entry.id,
        title=entry.title,
        content=entry.content,
        mood=entry.mood,
        weather=entry.weather,
        is_favorite=entry.is_favorite,
        created_at=entry.created_at.isoformat() if entry.created_at else None,
        updated_at=entry.updated_at.isoformat() if entry.updated_at else None,
    )


@router.post("", response_model=ApiResponse)
def create_diary(
    req: CreateDiaryRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        entry = diary_repo.create(
            db=db,
            user_id=current_user.id,
            title=req.title,
            content=req.content,
            mood=req.mood,
            weather=req.weather,
        )
        return ApiResponse(data=_to_diary_response(entry).model_dump())
    except Exception:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 60002, "message": "创建日记失败", "data": None},
        )


@router.get("", response_model=ApiResponse)
def get_diaries(
    page: int = Query(1, ge=1),
    limit: int = Query(20, ge=1, le=100),
    filter_type: str = Query("all", pattern="^(all|week|month|favorite)$"),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    items, total = diary_repo.get_list(
        db=db,
        user_id=current_user.id,
        page=page,
        limit=limit,
        filter_type=filter_type,
    )
    return ApiResponse(
        data={
            "items": [_to_diary_response(i).model_dump() for i in items],
            "total": total,
        }
    )


@router.post("/batch-delete", response_model=ApiResponse)
def batch_delete(
    req: BatchDeleteRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        count = diary_repo.batch_soft_delete(db, req.ids, current_user.id)
        return ApiResponse(data={"deleted_count": count})
    except Exception:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 60003, "message": "批量删除失败", "data": None},
        )


@router.get("/{diary_id}", response_model=ApiResponse)
def get_diary(
    diary_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    entry = diary_repo.get_by_id(db, diary_id, current_user.id)
    if not entry:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 60001, "message": "日记不存在", "data": None},
        )
    return ApiResponse(data=_to_diary_response(entry).model_dump())


@router.put("/{diary_id}", response_model=ApiResponse)
def update_diary(
    diary_id: int,
    req: UpdateDiaryRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        entry = diary_repo.update(db, diary_id, current_user.id, data)
        if not entry:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": 60001, "message": "日记不存在", "data": None},
            )
        return ApiResponse(data=_to_diary_response(entry).model_dump())
    except HTTPException:
        raise
    except Exception:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 60004, "message": "更新日记失败", "data": None},
        )


@router.delete("/{diary_id}", response_model=ApiResponse)
def delete_diary(
    diary_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        success = diary_repo.soft_delete(db, diary_id, current_user.id)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": 60001, "message": "日记不存在", "data": None},
            )
        return ApiResponse()
    except HTTPException:
        raise
    except Exception:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 60005, "message": "删除日记失败", "data": None},
        )


@router.post("/{diary_id}/favorite", response_model=ApiResponse)
def toggle_favorite(
    diary_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    entry = diary_repo.toggle_favorite(db, diary_id, current_user.id)
    if not entry:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 60001, "message": "日记不存在", "data": None},
        )
    return ApiResponse(data=_to_diary_response(entry).model_dump())
