from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.services import mediation_service
from app.repositories import couple_repo

router = APIRouter(prefix="/ai/mediation", tags=["双人调解"])


@router.post("/start", response_model=ApiResponse)
def start_mediation(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )
    result = mediation_service.start_mediation(db, current_user.id, relation.id)
    return ApiResponse(data=result)


@router.get("", response_model=ApiResponse)
def list_mediations(
    role: str = Query("mine", pattern="^(invited|mine|all|history)$"),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """契约 §2.3-3 / §8.5-6：调解列表。

    `invited` 待回应邀请；`mine` / `all` 我参与的（含已完成的，供「能回看」）；
    `history` 只看已完成的。
    """
    result = mediation_service.list_mediations(db, current_user.id, role)
    return ApiResponse(data=result)


@router.post("/{session_id}/accept", response_model=ApiResponse)
def accept_mediation(
    session_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = mediation_service.accept_mediation(db, session_id, current_user.id)
    except ValueError as e:
        code = str(e)
        error_map = {
            "50001": (404, "调解会话不存在"),
            "50002": (403, "无权参与此调解"),
            "50003": (400, "调解已结束"),
        }
        sc, msg = error_map.get(code, (500, "服务异常"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=result)


@router.post("/{session_id}/reject", response_model=ApiResponse)
def reject_mediation(
    session_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = mediation_service.reject_mediation(db, session_id, current_user.id)
    except ValueError as e:
        code = str(e)
        error_map = {
            "50001": (404, "调解会话不存在"),
            "50002": (403, "无权参与此调解"),
        }
        sc, msg = error_map.get(code, (500, "服务异常"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=result)


@router.post("/{session_id}/input", response_model=ApiResponse)
def submit_input(
    session_id: int,
    body: dict,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    content = body.get("content", "")
    if not content:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40001, "message": "内容不能为空", "data": None},
        )
    try:
        result = mediation_service.submit_input(db, session_id, current_user.id, content)
    except ValueError as e:
        code = str(e)
        error_map = {
            "50001": (404, "调解会话不存在"),
            "50002": (403, "无权参与此调解"),
            "50003": (400, "调解状态不允许此操作"),
        }
        sc, msg = error_map.get(code, (500, "服务异常"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=result)


@router.post("/{session_id}/confirm", response_model=ApiResponse)
def confirm_rewrite(
    session_id: int,
    body: dict,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    confirmed = body.get("confirmed", True)
    supplement = body.get("supplement")
    try:
        result = mediation_service.confirm_rewrite(
            db, session_id, current_user.id, confirmed, supplement
        )
    except ValueError as e:
        code = str(e)
        error_map = {
            "50001": (404, "调解会话不存在"),
            "50002": (403, "无权参与此调解"),
            "50003": (400, "调解状态不允许此操作"),
            "50000": (500, "AI 服务异常，请稍后重试"),
        }
        sc, msg = error_map.get(code, (500, "服务异常"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=result)


@router.get("/{session_id}", response_model=ApiResponse)
def get_status(
    session_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """会话状态真源（§2.3-2）。§8.5-3：公开前只回本人发言；§8.5-6：completed 可读。"""
    try:
        result = mediation_service.get_status(db, session_id, current_user.id)
    except ValueError as e:
        code = str(e)
        error_map = {
            "50001": (404, "调解会话不存在"),
            "50002": (403, "无权参与此调解"),
        }
        sc, msg = error_map.get(code, (500, "服务异常"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=result)


@router.post("/{session_id}/next", response_model=ApiResponse)
def next_step(
    session_id: int,
    body: dict,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    action = body.get("action", "")
    if action not in ("end", "pause", "continue"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40001, "message": "无效的操作", "data": None},
        )
    try:
        result = mediation_service.next_step(db, session_id, current_user.id, action)
    except ValueError as e:
        code = str(e)
        error_map = {
            "50001": (404, "调解会话不存在"),
            "50002": (403, "无权参与此调解"),
        }
        sc, msg = error_map.get(code, (500, "服务异常"))
        raise HTTPException(status_code=sc, detail={"code": int(code), "message": msg, "data": None})
    return ApiResponse(data=result)
