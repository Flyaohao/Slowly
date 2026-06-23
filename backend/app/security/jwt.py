import jwt
from typing import Optional, Dict, Any

from app.core.config import JWT_SECRET

ALGORITHM = "HS256"


def create_access_token(user_id: int) -> str:
    """创建永不过期的 access token。只有用户主动退出登录时才失效。"""
    payload = {"sub": str(user_id), "type": "access"}
    return jwt.encode(payload, JWT_SECRET, algorithm=ALGORITHM)


def create_refresh_token(user_id: int) -> str:
    """创建永不过期的 refresh token。只有用户主动退出登录时才失效。"""
    payload = {"sub": str(user_id), "type": "refresh"}
    return jwt.encode(payload, JWT_SECRET, algorithm=ALGORITHM)


def create_private_access_token(user_id: int) -> str:
    """创建永不过期的 private access token。只有用户主动退出登录时才失效。"""
    payload = {"sub": str(user_id), "type": "private"}
    return jwt.encode(payload, JWT_SECRET, algorithm=ALGORITHM)


def decode_token(token: str) -> Optional[Dict[str, Any]]:
    try:
        return jwt.decode(token, JWT_SECRET, algorithms=[ALGORITHM])
    except jwt.InvalidTokenError:
        return None
