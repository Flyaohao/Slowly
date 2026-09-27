"""共同调解室 API（设计文档 2026-09-28 §九）。

挂在 /api/v1/couple 下。SSE 端点路径以 `/stream` 结尾（客户端
StreamSafeLoggingInterceptor 对 /stream 结尾的端点强制 BASIC 日志，
防 BODY 缓冲毁掉流式）。

业务错误走 HTTP 200 信封（code 非 0）；未登录/未绑定情侣仍走 401/403。
"""

import logging
import threading

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session
from starlette.background import BackgroundTask

from app.core.database import get_db
from app.core.dependencies import require_couple_mode
from app.core.limiter import ai_limit
from app.schemas.common import ApiResponse
from app.schemas.mediation_room_schema import (
    AdvisorStreamRequest,
    EndVoteRequest,
    RoomCreateRequest,
    RoomMessageRequest,
    SupplementRequest,
)
from app.services import (
    mediation_room_service as room_service,
    room_advisor_service,
)
from app.services.mediation_styles import list_styles
from app.services.sse import sse_encode, SSE_HEADERS as _SSE_HEADERS

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/mediation-rooms", tags=["共同调解室"])

#: service ValueError → 信封 code
_ERROR_MAP = {
    room_service.ERR_NO_ROOM: (61001, "房间不存在"),
    room_service.ERR_ADVISOR_BUSY: (61002, "军师正在思考，请稍后再召唤"),
    room_service.ERR_BAD_STATE: (61003, "当前状态不允许该操作"),
}


def _bad_request(code: int, message: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail={"code": code, "message": message, "data": None},
    )


@router.get("/styles", response_model=ApiResponse)
def get_styles():
    """军师风格列表（§六：注册表制，前端可选、后端可扩展）。"""
    return ApiResponse(data=list_styles())


@router.post("", response_model=ApiResponse)
def create_room(
    req: RoomCreateRequest,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    try:
        data = room_service.create_room(db, relation=relation, user_id=current_user.id, req=req)
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61003, "创建失败"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.get("", response_model=ApiResponse)
def list_rooms(
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    return ApiResponse(data=room_service.list_rooms(db, relation))


@router.get("/{room_id}", response_model=ApiResponse)
def get_room(
    room_id: int,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    try:
        data = room_service.get_room_detail(
            db, relation=relation, user_id=current_user.id, room_id=room_id
        )
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61001, "房间不存在"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.get("/{room_id}/messages", response_model=ApiResponse)
def get_messages(
    room_id: int,
    after_id: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=200),
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    """短轮询：新消息 + 状态快照（D-REALTIME MVP：2s 间隔）。"""
    current_user, relation = auth
    try:
        data = room_service.get_messages(
            db, relation=relation, user_id=current_user.id,
            room_id=room_id, after_id=after_id, limit=limit,
        )
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61001, "房间不存在"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.post("/{room_id}/messages", response_model=ApiResponse)
def post_message(
    room_id: int,
    req: RoomMessageRequest,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    """发消息；mention=True 同时尝试召唤军师（@军师）。

    军师正忙时消息照常落库，仅 advisor_busy=true（设计 §四：后者收到
    「军师正在思考」并拒绝召唤；不丢用户发言）。
    """
    current_user, relation = auth
    try:
        data = room_service.post_message(
            db, relation=relation, user_id=current_user.id,
            room_id=room_id, content=req.content, mention=req.mention,
        )
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61003, "当前状态不允许该操作"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.post("/{room_id}/advisor/stream")
@ai_limit()
def advisor_stream(
    request: Request,
    room_id: int,
    req: AdvisorStreamRequest,
    current_user=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    """军师发言 SSE 流（D-CHANNEL：API 进程，与军师 tab 同机制）。"""
    current_user, relation = current_user
    # 归属校验在开流前完成
    from app.repositories import mediation_room_repo as room_repo

    if room_repo.room_for_user(db, room_id, relation.id) is None:
        raise _bad_request(61001, "房间不存在")

    disconnect_event = threading.Event()

    def _guarded():
        try:
            for chunk in sse_encode(
                room_advisor_service.stream_reply_events(db, room_id, req.token)
            ):
                yield chunk
        finally:
            disconnect_event.set()

    return StreamingResponse(
        _guarded(),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
        background=BackgroundTask(_disconnect_watcher, request, disconnect_event),
    )


async def _disconnect_watcher(request: Request, disconnect_event: threading.Event) -> None:
    """断连即置位取消（同 ai.py 的实现理由，见彼处长注释）。"""
    import asyncio

    try:
        while not disconnect_event.is_set():
            if await request.is_disconnected():
                disconnect_event.set()
                return
            await asyncio.sleep(0.01)
    except asyncio.CancelledError:
        raise
    except Exception:  # noqa: BLE001
        logger.warning("[ROOM] 断连观察任务异常", exc_info=True)


@router.post("/{room_id}/agree", response_model=ApiResponse)
def agree(
    room_id: int,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    try:
        data = room_service.agree(
            db, relation=relation, user_id=current_user.id, room_id=room_id
        )
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61003, "当前状态不允许该操作"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.post("/{room_id}/supplement", response_model=ApiResponse)
def supplement(
    room_id: int,
    req: SupplementRequest,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    try:
        data = room_service.supplement(
            db, relation=relation, user_id=current_user.id,
            room_id=room_id, content=req.content,
        )
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61002, "军师正在思考"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.post("/{room_id}/end", response_model=ApiResponse)
def end_vote(
    room_id: int,
    req: EndVoteRequest,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    try:
        data = room_service.end_vote(
            db, relation=relation, user_id=current_user.id,
            room_id=room_id, action=req.action,
        )
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61003, "当前状态不允许该操作"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.post("/{room_id}/settlement/confirm", response_model=ApiResponse)
def confirm_settlement(
    room_id: int,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    try:
        data = room_service.settlement_confirm_action(
            db, relation=relation, user_id=current_user.id, room_id=room_id
        )
    except ValueError as e:
        code, message = _ERROR_MAP.get(str(e), (61003, "当前状态不允许该操作"))
        raise _bad_request(code, message)
    return ApiResponse(data=data)


@router.post("/{room_id}/settlement/retry", response_model=ApiResponse)
def retry_settlement(
    room_id: int,
    auth=Depends(require_couple_mode),
    db: Session = Depends(get_db),
):
    current_user, relation = auth
    data = room_service.retry_settlement(
        db, relation=relation, user_id=current_user.id, room_id=room_id
    )
    return ApiResponse(data=data)
