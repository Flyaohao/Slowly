from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from typing import Optional, List

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.features import feature_disabled_error
from app.schemas.common import ApiResponse
from app.schemas.letter_schema import (
    LetterCreate,
    LetterUpdate,
    LetterOut,
    LetterListResponse,
    BatchDeleteRequest,
)
from app.services import letter_service

router = APIRouter(prefix="/letters", tags=["信件"])


@router.post("", response_model=ApiResponse)
def create_letter(
    req: LetterCreate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        letter = letter_service.create_letter(db, current_user.id, req.model_dump())
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "60002":
            return ApiResponse(code=60002, message="无权访问此信件", data=None)
        if code == "60004":
            return ApiResponse(code=60004, message="单身模式下仅支持普通信和未说出口", data=None)
        if code == "10006":
            raise feature_disabled_error()
        return ApiResponse(code=int(code), message="创建失败", data=None)
    return ApiResponse(data=LetterOut.model_validate(letter).model_dump())


@router.get("", response_model=ApiResponse)
def list_letters(
    letter_type: Optional[str] = Query(None),
    status: Optional[str] = Query(None),
    direction: Optional[str] = Query(None),
    is_favorite: Optional[bool] = Query(None),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = letter_service.list_letters(
            db=db,
            user_id=current_user.id,
            letter_type=letter_type,
            status=status,
            direction=direction,
            is_favorite=is_favorite,
            page=page,
            page_size=page_size,
        )
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    return ApiResponse(data=LetterListResponse.model_validate(result).model_dump())


@router.get("/inbox", response_model=ApiResponse)
def list_inbox(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = letter_service.list_inbox(db, current_user.id, page, page_size)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    return ApiResponse(data=LetterListResponse.model_validate(result).model_dump())


@router.get("/drafts", response_model=ApiResponse)
def list_drafts(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = letter_service.list_drafts(db, current_user.id, page, page_size)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    return ApiResponse(data=LetterListResponse.model_validate(result).model_dump())


@router.get("/{letter_id}", response_model=ApiResponse)
def get_letter(
    letter_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        letter = letter_service.get_letter(db, current_user.id, letter_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail={"code": 30005, "message": "请先绑定情侣关系", "data": None})
        if code == "60001":
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": 60001, "message": "信件不存在", "data": None})
        if code == "60002":
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail={"code": 60002, "message": "无权访问此信件", "data": None})
        return ApiResponse(code=int(code), message="获取失败", data=None)
    return ApiResponse(data=LetterOut.model_validate(letter).model_dump())


@router.put("/{letter_id}", response_model=ApiResponse)
def update_letter(
    letter_id: int,
    req: LetterUpdate,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        data = req.model_dump(exclude_unset=True)
        letter = letter_service.update_letter(db, current_user.id, letter_id, data)
    except ValueError as e:
        code = str(e)
        if code == "60001":
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": 60001, "message": "信件不存在", "data": None})
        if code == "60002":
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail={"code": 60002, "message": "无权访问此信件", "data": None})
        if code == "60003":
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail={"code": 60003, "message": "信件已发送，无法编辑", "data": None})
        if code == "10006":
            raise feature_disabled_error()
        return ApiResponse(code=int(code), message="更新失败", data=None)
    return ApiResponse(data=LetterOut.model_validate(letter).model_dump())


@router.delete("/{letter_id}", response_model=ApiResponse)
def delete_letter(
    letter_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        letter_service.delete_letter(db, current_user.id, letter_id)
    except ValueError as e:
        code = str(e)
        if code == "60001":
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": 60001, "message": "信件不存在", "data": None})
        if code == "60002":
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail={"code": 60002, "message": "无权访问此信件", "data": None})
        return ApiResponse(code=int(code), message="删除失败", data=None)
    return ApiResponse()


@router.post("/{letter_id}/send", response_model=ApiResponse)
def send_letter(
    letter_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        letter = letter_service.send_letter(db, current_user.id, letter_id)
    except ValueError as e:
        code = str(e)
        if code == "60001":
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": 60001, "message": "信件不存在", "data": None})
        if code == "60002":
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail={"code": 60002, "message": "无权访问此信件", "data": None})
        if code == "60003":
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail={"code": 60003, "message": "信件已发送，无法重复发送", "data": None})
        if code == "30005":
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail={"code": 30005, "message": "请先绑定情侣关系", "data": None})
        if code == "10006":
            raise feature_disabled_error()
        return ApiResponse(code=int(code), message="发送失败", data=None)
    return ApiResponse(data=LetterOut.model_validate(letter).model_dump())


@router.post("/batch-delete", response_model=ApiResponse)
def batch_delete_letters(
    req: BatchDeleteRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    deleted_count = letter_service.batch_delete_letters(db, current_user.id, req.letter_ids)
    db.commit()
    return ApiResponse(data={"deleted_count": deleted_count})


@router.post("/{letter_id}/favorite", response_model=ApiResponse)
def toggle_favorite(
    letter_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        letter = letter_service.toggle_favorite(db, current_user.id, letter_id)
    except ValueError as e:
        code = str(e)
        if code == "60001":
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": 60001, "message": "信件不存在", "data": None})
        if code == "60002":
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail={"code": 60002, "message": "无权访问此信件", "data": None})
        return ApiResponse(code=int(code), message="操作失败", data=None)
    return ApiResponse(data=LetterOut.model_validate(letter).model_dump())
