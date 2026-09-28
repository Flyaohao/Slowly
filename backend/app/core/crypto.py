"""用户级 AI 配置的敏感字段加密（Fernet 对称加密）。

主密钥来自环境变量 ``AI_CONFIG_SECRET``（部署凭据文件已同步追加）。
用户 api-key 等敏感信息**永不明文落库**：写入前加密，读出时解密。

为什么用 sha256 派生而不是直接放 Fernet key：
    Fernet 要求 32 字节 url-safe base64，运维直接编辑环境变量时
    极易写错格式；sha256(任意长度口令) 派生则任何非空口令都合法。
"""

from __future__ import annotations

import base64
import hashlib
import os

from cryptography.fernet import Fernet, InvalidToken

#: 开发环境兜底口令——生产必须配置 AI_CONFIG_SECRET，否则加密形同虚设
_DEV_FALLBACK_SECRET = "couple-dev-only-not-for-production"

_fernet: Fernet | None = None


def _get_fernet() -> Fernet:
    global _fernet
    if _fernet is None:
        secret = os.getenv("AI_CONFIG_SECRET", "").strip()
        if not secret:
            secret = _DEV_FALLBACK_SECRET
            import logging

            logging.getLogger("couple.crypto").warning(
                "[CRYPTO] 未配置 AI_CONFIG_SECRET，使用开发兜底密钥（生产环境必须配置）"
            )
        key = base64.urlsafe_b64encode(hashlib.sha256(secret.encode("utf-8")).digest())
        _fernet = Fernet(key)
    return _fernet


def encrypt_text(plain: str) -> str:
    """明文 → Fernet token（str，可直接存 VARCHAR）。空串原样返回。"""
    if not plain:
        return ""
    token = _get_fernet().encrypt(plain.encode("utf-8"))
    return token.decode("ascii")


def decrypt_text(token: str) -> str:
    """Fernet token → 明文。解密失败（密钥轮换/数据损坏）抛 ValueError。"""
    if not token:
        return ""
    try:
        return _get_fernet().decrypt(token.encode("ascii")).decode("utf-8")
    except (InvalidToken, ValueError) as exc:
        raise ValueError("AI 配置解密失败：主密钥不匹配或数据损坏") from exc
