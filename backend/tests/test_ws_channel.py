# -*- coding: utf-8 -*-
"""WebSocket 实时通道验证脚本（2026-09-15）。

覆盖：
1. /api/v1/couple/ai/ws 路由注册（app.routes 可见）
2. presence 分享/陪伴请求 → 实时通知发到伴侣 ConnectionManager
   （monkeypatch manager.send_to_user 捕获，不发真 socket）
3. 通知报文形状（type=notification + notification_type + data.content）

运行：python tests/test_ws_channel.py
（需要本机 MySQL：用真实库建两个用户一条关系做端到端）
"""
import asyncio
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}  {detail}")
        FAILURES.append(name)


def case_route():
    print("\n[1] WS 路由注册（TestClient 真连验证）")
    # app.routes 是懒加载的（_IncludedRouter，数 routes 只有 4 条，已知坑），
    # websockets 又不进 openapi，所以直接真连一次最可靠：
    # 路径存在 + token 非法 → 服务端以 4001 关闭；路径不存在 → 连接直接被拒。
    from fastapi import WebSocketDisconnect
    from starlette.testclient import TestClient
    from app.main import app

    client = TestClient(app)
    closed_with_4001 = False
    try:
        with client.websocket_connect("/api/v1/couple/ai/ws?token=invalid-token") as ws:
            ws.receive_text()
    except WebSocketDisconnect as e:
        closed_with_4001 = (e.code == 4001)
    except Exception as e:  # noqa: BLE001
        check("路径存在且拒绝非法 token（4001）", False, f"异常 {type(e).__name__}: {e}")
        return
    check("路径存在且拒绝非法 token（4001）", closed_with_4001)


def case_presence_notify():
    print("\n[2] presence 实时通知（捕获式端到端）")
    from app.services import presence_service
    from app.services.mediation_service import manager
    from app.repositories import user_repo, couple_repo
    from app.security.password import hash_password

    sent = []

    async def fake_send(uid, message):
        sent.append((uid, message))

    manager.send_to_user = fake_send  # 劫持：不碰真 socket

    db = Session()
    try:
        # 建一对测试关系（存在即复用）
        marker = "__ws_test__"
        user_a = user_repo.get_user_by_email(db, f"{marker}a@example.com")
        user_b = user_repo.get_user_by_email(db, f"{marker}b@example.com")
        if not user_a:
            user_a = user_repo.create_user(db, f"{marker}a@example.com", hash_password("Passw0rd!"))
        if not user_b:
            user_b = user_repo.create_user(db, f"{marker}b@example.com", hash_password("Passw0rd!"))
        relation = couple_repo.get_relation_by_user_including_unbinding(db, user_a.id)
        if not relation:
            # 兜底：直接造一条 active 关系
            from app.models.couple_relation import CoupleRelation
            relation = CoupleRelation(user_a_id=user_a.id, user_b_id=user_b.id, status="active")
            db.add(relation)
            db.commit()
            db.refresh(relation)

        sent.clear()
        moment = presence_service.share_moment(db, user_a.id, "刚下班，好累")
        partner_id = user_b.id
        check("分享此刻 → 通知发给伴侣", any(uid == partner_id for uid, _ in sent), str([u for u, _ in sent]))
        msg = next((m for uid, m in sent if uid == partner_id), None)
        check("报文 type=notification", msg and msg.get("type") == "notification", str(msg))
        check("notification_type=partner_moment",
              msg and msg.get("notification_type") == "partner_moment", str(msg))
        check("data 带内容", msg and msg.get("data", {}).get("content") == "刚下班，好累", str(msg))

        sent.clear()
        presence_service.send_companion_request(db, user_a.id, "陪我五分钟")
        msg2 = next((m for uid, m in sent if uid == partner_id), None)
        check("陪伴请求 → companion_request",
              msg2 and msg2.get("notification_type") == "companion_request", str(msg2))

        # 清理测试动态（避免污染 feed）
        from app.models.presence import PresenceMoment
        db.query(PresenceMoment).filter(
            PresenceMoment.user_id == user_a.id,
            PresenceMoment.content.in_(["刚下班，好累", "陪我五分钟"]),
        ).delete(synchronize_session=False)
        db.commit()
    finally:
        db.close()


def case_heartbeat_contract():
    print("\n[3] 心跳协议契约（服务端 ping → 客户端须回 pong）")
    from app.api.v1.couple import ws as ws_module
    check("端点函数存在", hasattr(ws_module, "websocket_endpoint"))
    src = Path(ws_module.__file__).read_text(encoding="utf-8")
    check("服务端发 ping", '"type": "ping"' in src or "'type': 'ping'" in src)
    check("客户端 pong 会被忽略（协议占位）", "pong" in src)


from app.core.database import SessionLocal as Session  # noqa: E402


def main() -> int:
    case_route()
    case_presence_notify()
    case_heartbeat_contract()
    print("\n========== 结果 ==========")
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
