from fastapi import Request
from slowapi import Limiter
from slowapi.util import get_remote_address

from app.security.jwt import decode_token

limiter = Limiter(key_func=get_remote_address)


def get_request_key(request: Request) -> str:
    """slowapi 限流键：登录用户按 user_id，匿名请求退回 IP。

    AI 端点的真实主体是「人」而不是 IP——同一 WiFi 下两个人不该共享配额，
    换 IP 的同一账号也不该刷新配额。token 解析失败时退回 IP 键，
    保证未登录探测（冒烟测试等）仍有限流兜底。
    """
    auth = request.headers.get("Authorization") or ""
    if auth.lower().startswith("bearer "):
        payload = decode_token(auth[7:].strip())
        if payload and payload.get("sub"):
            return "user:%s" % payload["sub"]
    return get_remote_address(request)
