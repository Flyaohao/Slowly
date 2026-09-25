from fastapi import Request
from slowapi import Limiter
from slowapi.util import get_remote_address

from app.core import config
from app.security.jwt import decode_token

limiter = Limiter(key_func=get_remote_address)


def ai_limit():
    """AI 端点限流装饰器。`AI_RATE_LIMIT` 为空或 0 时完全关闭（identity 装饰器）。

    不能直接把 "0/hour" 交给 slowapi——那意味着「每小时 0 次」= 全部拒绝。

    放在这里而不是各 AI 路由模块内部：情侣侧与个人侧的 AI 端点（量表分析在
    `common/questionnaires.py`）都要用，各抄一份迟早会漂移。

    两个维度叠加，slowapi 会同时校验：
      · 用户维度（`get_request_key`）—— 同 WiFi 下两人不共享配额，换 IP 也不刷新配额；
      · IP 维度（`AI_IP_RATE_LIMIT`）—— 批量注册后每个账号各享一份配额，用 IP 总量兜住。
    """
    if config.AI_RATE_LIMIT and config.AI_RATE_LIMIT not in ("0", "0/hour"):
        user_dim = limiter.limit(config.AI_RATE_LIMIT, key_func=get_request_key)
        ip_dim = limiter.limit(config.AI_IP_RATE_LIMIT, key_func=get_remote_address)
        return lambda f: user_dim(ip_dim(f))
    return lambda f: f


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
