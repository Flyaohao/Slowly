import asyncio
import json
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Query
from starlette.websockets import WebSocket

from app.security.jwt import decode_token
from app.services.mediation_service import manager
from app.core.database import SessionLocal
from app.repositories import user_repo

logger = logging.getLogger(__name__)

router = APIRouter(tags=["WebSocket"])


@router.websocket("/ai/ws")
async def websocket_endpoint(
    ws: WebSocket,
    token: str = Query(...),
):
    payload = decode_token(token)
    if not payload or payload.get("type") != "access":
        await ws.close(code=4001, reason="Invalid token")
        return

    user_id = int(payload["sub"])

    # 模式校验：仅情侣模式可连接 WebSocket
    db = SessionLocal()
    try:
        user = user_repo.get_user_by_id(db, user_id)
        if not user or not user.has_couple:
            await ws.close(code=4003, reason="Single mode - WebSocket not available")
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

                if msg_type == "pong":
                    continue

                if msg_type == "mediation_accept":
                    session_id = msg.get("session_id")
                    await manager.send_to_user(user_id, {
                        "type": "mediation_status",
                        "session_id": session_id,
                        "status": "accepted",
                    })

                elif msg_type == "mediation_reject":
                    session_id = msg.get("session_id")
                    await manager.send_to_user(user_id, {
                        "type": "mediation_status",
                        "session_id": session_id,
                        "status": "rejected",
                    })

                elif msg_type == "mediation_input_done":
                    session_id = msg.get("session_id")
                    await manager.send_to_user(user_id, {
                        "type": "mediation_status",
                        "session_id": session_id,
                        "status": "input_done",
                    })

                elif msg_type == "mediation_confirm":
                    session_id = msg.get("session_id")
                    confirmed = msg.get("confirmed", False)
                    await manager.send_to_user(user_id, {
                        "type": "mediation_status",
                        "session_id": session_id,
                        "status": "confirmed" if confirmed else "rejected",
                    })

                elif msg_type == "ack":
                    pass

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
