import asyncio
import json
import logging
from typing import Optional

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from starlette.websockets import WebSocket

from app.security.jwt import decode_token
from app.services.mediation_service import manager
from app.core.database import SessionLocal
from app.repositories import ai_repo, user_repo

logger = logging.getLogger(__name__)

router = APIRouter(tags=["WebSocket"])


@router.websocket("/ai/ws")
async def websocket_endpoint(
    ws: WebSocket,
    token: str = Query(...),
    session_id: Optional[int] = Query(None),
):
    """实时通道：token 必校验；可选 session_id 做调解成员校验（契约 §2.3-4）。

    WS 只做**服务端推送**（通知帧 + mediation_status 状态帧），
    accept/reject/input/confirm 等动作一律走 REST——本端点不再有回声语义。
    session_id 传入且非会话成员 → close 4003；不传则为通用通知通道。
    """
    payload = decode_token(token)
    if not payload or payload.get("type") != "access":
        await ws.close(code=4001, reason="Invalid token")
        return

    user_id = int(payload["sub"])

    db = SessionLocal()
    try:
        # 模式校验：仅情侣模式可连接 WebSocket
        user = user_repo.get_user_by_id(db, user_id)
        if not user or not user.has_couple:
            await ws.close(code=4003, reason="Single mode - WebSocket not available")
            return

        # 成员校验：带 session_id 连接时，非成员直接拒（契约 §2.3-4）
        if session_id is not None:
            session = ai_repo.get_session_by_id(db, session_id)
            if (
                session is None
                or user_id not in (session.user_id, session.partner_user_id)
            ):
                await ws.close(code=4003, reason="Not a member of this session")
                return
    finally:
        db.close()

    await manager.connect(user_id, ws)

    try:
        while True:
            try:
                data = await asyncio.wait_for(ws.receive_text(), timeout=30)
                msg = json.loads(data)
                msg_type = msg.get("type", "")

                # 只保留心跳/回执协议。动作类消息（accept/input/confirm 等）
                # 已按契约 §2.3-4 删除——服务端不再代执行、也不回声。
                if msg_type in ("pong", "ack"):
                    continue

            except asyncio.TimeoutError:
                try:
                    await ws.send_json({"type": "ping"})
                    data = await asyncio.wait_for(ws.receive_text(), timeout=60)
                    msg = json.loads(data)
                    if msg.get("type") != "pong":
                        pass
                except (asyncio.TimeoutError, Exception):
                    logger.warning("Heartbeat timeout for user %s", user_id)
                    break

    except WebSocketDisconnect:
        logger.info("User %s disconnected", user_id)
    except Exception as e:
        logger.error("WebSocket error for user %s: %s", user_id, e)
    finally:
        manager.disconnect(user_id, ws)
