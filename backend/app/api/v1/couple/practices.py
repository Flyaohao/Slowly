from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.features import require_feature
from app.schemas.common import ApiResponse
from app.schemas.practice_schema import PracticeOut, PracticeRecordOut, PracticeRecordSubmit, PracticeRecordDetailOut
from app.services import practice_service

# 收敛期冻结（契约 §1）：整模块挂依赖，写入即停（P0-2 冻结止血）。
router = APIRouter(
    prefix="/practices",
    tags=["关系练习"],
    dependencies=[Depends(require_feature("practices"))],
)


@router.get("", response_model=ApiResponse)
def list_practices(
    db: Session = Depends(get_db),
):
    practices = practice_service.list_practices(db)
    return ApiResponse(data=[PracticeOut.model_validate(p).model_dump() for p in practices])


@router.post("/{practice_id}/start", response_model=ApiResponse)
def start_practice(
    practice_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        record = practice_service.start_practice(db, current_user.id, practice_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "90001":
            return ApiResponse(code=90001, message="练习不存在", data=None)
        return ApiResponse(code=int(code), message="操作失败", data=None)
    return ApiResponse(data=PracticeRecordOut.model_validate(record).model_dump())


@router.post("/records/{record_id}/submit", response_model=ApiResponse)
def submit_practice(
    record_id: int,
    req: PracticeRecordSubmit,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        record = practice_service.submit_practice(db, current_user.id, record_id, req.model_dump())
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "90002":
            return ApiResponse(code=90002, message="记录不存在", data=None)
        if code == "90003":
            return ApiResponse(code=90003, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="提交失败", data=None)
    return ApiResponse(data=PracticeRecordOut.model_validate(record).model_dump())


@router.get("/records", response_model=ApiResponse)
def list_records(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = practice_service.list_records(db, current_user.id, page, page_size)
    except ValueError:
        return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
    result["items"] = [PracticeRecordOut.model_validate(i).model_dump() for i in result["items"]]
    return ApiResponse(data=result)


@router.get("/records/{record_id}", response_model=ApiResponse)
def get_record(
    record_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        record = practice_service.get_record(db, current_user.id, record_id)
    except ValueError as e:
        code = str(e)
        if code == "30005":
            return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
        if code == "90002":
            return ApiResponse(code=90002, message="记录不存在", data=None)
        if code == "90003":
            return ApiResponse(code=90003, message="无权访问", data=None)
        return ApiResponse(code=int(code), message="获取失败", data=None)
    # record 现在是 dict，直接返回
    return ApiResponse(data=record)
