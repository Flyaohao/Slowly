"""用户级 AI 配置服务（v5.0 核心）

职责
----
1. **解析**：按 user_id 读出配置，构造协议正确的 LLM / Embedding 客户端
   （D1：全 App 所有 LLM/Embedding 调用点一律经此解析；D2：情侣模式下
   由**触发本次调用的人**承担——像打电话，打出的人花话费；D4：未配置
   直接抛 `AiConfigMissingError`，不回落全局配置）。
2. **CRUD**：配置保存（保存前强制连通性测试，D10）、清空、打码读取、
   key 增删启停（D8 轮换）。
3. **安全**：api-key 只写不读，Fernet 加密落库（D3），读取一律打码。

调用方约定：谁触发 AI 调用，就用谁的 user_id 解析——
- 军师对话：发消息的人；
- 调解室军师/结算：房间创建者（军师只有一位，双方看同一份回复）；
- 记忆蒸馏/索引 worker：任务归属用户；
- 问卷分析：交卷用户。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy.orm import Session

from app.core.crypto import decrypt_text, encrypt_text
from app.core.config import AI_ENABLE_THINKING, AI_THINKING_BUDGET
from app.models.user_ai_config import UserAiConfig, UserAiKey

logger = logging.getLogger("couple.user_ai_config")

#: 业务错误码：用户未配置 AI 服务（前端引导去设置页）
AI_CONFIG_MISSING_CODE = 30010
#: 保存校验失败（连通性测试未通过等）
AI_CONFIG_INVALID_CODE = 30011

#: 与既有 Chroma 集合兼容的向量维度（couple_theory / couple_memory 均按此构建）
REQUIRED_EMBEDDING_DIM = 1024

PROVIDERS = ("openai", "anthropic")


class AiConfigMissingError(RuntimeError):
    """用户未配置 AI 服务。API 层统一翻译为 code=30010。"""

    code = AI_CONFIG_MISSING_CODE

    def __init__(self, user_id: int) -> None:
        super().__init__("用户未配置 AI 服务 user=%s" % user_id)
        self.user_id = user_id


class AiConfigInvalidError(ValueError):
    """配置非法/连通性测试未通过。API 层统一翻译为 code=30011。"""

    code = AI_CONFIG_INVALID_CODE

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


# --------------------------------------------------------------------------- #
# 读取与解析
# --------------------------------------------------------------------------- #
def get_config(db: Session, user_id: int) -> Optional[UserAiConfig]:
    return (
        db.query(UserAiConfig)
        .filter(UserAiConfig.user_id == user_id)
        .first()
    )


def _enabled_keys(config: UserAiConfig) -> List[str]:
    """启用的 key（按 id 序）解密为明文列表。"""
    out = []
    for k in config.keys:
        if not k.enabled:
            continue
        try:
            out.append(decrypt_text(k.key_enc))
        except ValueError:
            logger.exception("[UAICFG] key 解密失败 key_id=%s（跳过）", k.id)
    return out


def resolve(db: Session, user_id: int) -> Tuple[UserAiConfig, List[str]]:
    """读取配置与解密后的 key 列表。未配置/无可用 key → AiConfigMissingError。"""
    config = get_config(db, user_id)
    if config is None:
        raise AiConfigMissingError(user_id)
    keys = _enabled_keys(config)
    if not keys:
        raise AiConfigMissingError(user_id)
    return config, keys


def build_chat_client(
    db: Session,
    user_id: int,
    mode: Optional[str] = None,
    enable_thinking: Optional[bool] = None,
    thinking_budget: Optional[int] = None,
):
    """按用户配置构造聊天客户端（openai/anthropic 双协议）。

    `mode` 语义与全局 `get_client_for_mode` 一致：quick 关思考、
    expert 提预算；显式 enable_thinking/thinking_budget 优先级最高（测试用）。
    轮换起点用 DB 游标（round-robin 跨请求推进）。
    """
    from app.services.llm_client import AnthropicClient, LlmClient

    config, keys = resolve(db, user_id)
    keys = _rotate(keys, config.key_cursor or 0)

    eff_thinking = AI_ENABLE_THINKING if enable_thinking is None else enable_thinking
    eff_budget = AI_THINKING_BUDGET if thinking_budget is None else thinking_budget
    m = (mode or "").strip().lower()
    if m == "quick":
        eff_thinking = False
    elif m == "expert" and enable_thinking is None:
        eff_budget = 2048

    kwargs: Dict[str, Any] = dict(
        api_keys=keys,
        base_url=config.base_url,
        model=config.model_name,
        enable_thinking=eff_thinking,
        thinking_budget=eff_budget,
    )
    cls = AnthropicClient if config.provider_type == "anthropic" else LlmClient
    return cls(**kwargs)


def _rotate(keys: List[str], cursor: int) -> List[str]:
    if not keys:
        return keys
    cursor %= len(keys)
    return keys[cursor:] + keys[:cursor]


def build_embeddings(db: Session, user_id: int):
    """按用户配置构造 embedding 客户端（D5：向量维度已在校验时锁定 1024）。"""
    from app.services.embedding import DashScopeEmbeddings

    config, keys = resolve(db, user_id)
    model = config.embedding_model or ""
    if not model:
        raise AiConfigMissingError(user_id)
    if config.embedding_base_url:
        base_url = config.embedding_base_url
        if config.embedding_api_key_enc:
            try:
                api_keys = [decrypt_text(config.embedding_api_key_enc)]
            except ValueError:
                raise AiConfigMissingError(user_id)
        else:
            api_keys = keys  # 与聊天共用同一组 key（轮换起点同游标）
    else:
        # 未单配 embedding 端点：复用聊天端点与 key（OpenAI 兼容场景常态）
        base_url = config.base_url
        api_keys = keys
    return DashScopeEmbeddings(
        api_key=api_keys[0],
        base_url=base_url,
        model=model,
        dim=config.embedding_dim or REQUIRED_EMBEDDING_DIM,
    )


def record_key_error(db: Session, user_id: int, error_code: str) -> None:
    """key 轮换发生后的留痕（尽力而为，绝不抛错影响主链路）。"""
    try:
        config = get_config(db, user_id)
        if config is None:
            return
        keys = [k for k in config.keys if k.enabled]
        if not keys:
            return
        idx = (config.key_cursor or 0) % len(keys)
        keys[idx].last_error_code = (error_code or "")[:60]
        keys[idx].last_error_at = datetime.utcnow()
        config.key_cursor = (idx + 1) % len(keys)
        db.commit()
    except Exception:  # noqa: BLE001
        db.rollback()
        logger.exception("[UAICFG] key 错误留痕失败 user=%s", user_id)


# --------------------------------------------------------------------------- #
# 打码输出
# --------------------------------------------------------------------------- #
def _mask_key(plain: str) -> str:
    if len(plain) <= 8:
        return "****"
    return "%s****%s" % (plain[:3], plain[-4:])


def masked_payload(db: Session, user_id: int) -> Optional[Dict[str, Any]]:
    """设置页读取：key 一律打码，加密字段绝不外露。"""
    config = get_config(db, user_id)
    if config is None:
        return None
    key_items = []
    for k in config.keys:
        try:
            masked = _mask_key(decrypt_text(k.key_enc))
        except ValueError:
            masked = "****"
        key_items.append({
            "id": k.id,
            "masked": masked,
            "label": k.label or "",
            "enabled": bool(k.enabled),
            "last_error_code": k.last_error_code or "",
            "last_error_at": k.last_error_at.isoformat() if k.last_error_at else None,
        })
    return {
        "provider_type": config.provider_type,
        "base_url": config.base_url,
        "model_name": config.model_name,
        "embedding_base_url": config.embedding_base_url or "",
        "embedding_model": config.embedding_model or "",
        "embedding_dim": config.embedding_dim,
        "enable_rate_limit": bool(config.enable_rate_limit),
        "keys": key_items,
        "updated_at": config.updated_at.isoformat() if config.updated_at else None,
    }


# --------------------------------------------------------------------------- #
# 连通性测试（保存前强制，D10）
# --------------------------------------------------------------------------- #
def _chat_probe(provider: str, base_url: str, model: str, keys: List[str]) -> None:
    """最小聊天探测：失败抛 AiConfigInvalidError（带原因）。"""
    from app.services.llm_client import AnthropicClient, LlmClient

    cls = AnthropicClient if provider == "anthropic" else LlmClient
    client = cls(
        api_keys=keys,
        base_url=base_url,
        model=model,
        enable_thinking=False,  # 探测不看思考，关闭最稳
    )
    try:
        text = client.invoke(
            [{"role": "user", "content": "请只回复两个字：连接"}],
            temperature=0,
            max_tokens=200,
            scene="ai_config_test",
        )
    except Exception as exc:
        raise AiConfigInvalidError("对话模型连接失败：%s" % str(exc)[:300])
    if not (text or "").strip():
        raise AiConfigInvalidError("对话模型连接失败：模型返回为空")


def _embedding_probe(
    base_url: str,
    model: str,
    keys: List[str],
) -> int:
    """embedding 探测：返回实测维度；不匹配 1024 直接判死（Chroma 集合约束）。"""
    from app.services.embedding import DashScopeEmbeddings

    emb = DashScopeEmbeddings(
        api_key=keys[0], base_url=base_url, model=model,
        dim=REQUIRED_EMBEDDING_DIM,
    )
    try:
        vec = emb.embed_query("连通性测试")
    except Exception as exc:
        raise AiConfigInvalidError("Embedding 连接失败：%s" % str(exc)[:300])
    dim = len(vec)
    if dim != REQUIRED_EMBEDDING_DIM:
        raise AiConfigInvalidError(
            "Embedding 维度不匹配：模型返回 %d 维，本应用向量库固定 %d 维，"
            "请更换 embedding 模型（如 text-embedding-v4）"
            % (dim, REQUIRED_EMBEDDING_DIM)
        )
    return dim


def test_config(
    *,
    provider_type: str,
    base_url: str,
    model_name: str,
    embedding_base_url: str = "",
    embedding_model: str = "",
    embedding_api_key: str = "",
    keys: List[str] = (),
) -> int:
    """只测不存（设置页「测试连接」）。通过返回实测 embedding 维度。

    与 `test_and_save_config` 的校验规则保持一致；失败抛 AiConfigInvalidError。
    """
    provider_type = (provider_type or "").strip().lower()
    if provider_type not in PROVIDERS:
        raise AiConfigInvalidError("provider_type 必须是 openai 或 anthropic")
    base_url = (base_url or "").strip().rstrip("/")
    if not base_url.startswith(("http://", "https://")):
        raise AiConfigInvalidError("Base URL 必须以 http:// 或 https:// 开头")
    model_name = (model_name or "").strip()
    if not model_name:
        raise AiConfigInvalidError("模型名称不能为空")
    key_list = [k.strip() for k in keys if k and k.strip()]
    if not key_list:
        raise AiConfigInvalidError("至少需要一把 API Key")

    _chat_probe(provider_type, base_url, model_name, key_list)

    embedding_model = (embedding_model or "").strip()
    embedding_base_url = (embedding_base_url or "").strip().rstrip("/")
    if provider_type == "anthropic" and not embedding_base_url:
        raise AiConfigInvalidError(
            "Anthropic 协议没有 Embedding API，请单独填写 Embedding 端点"
            "（OpenAI 兼容，如 DashScope）"
        )
    if not embedding_model:
        raise AiConfigInvalidError("Embedding 模型不能为空")
    if not embedding_base_url:
        embedding_base_url = base_url
    emb_keys = [embedding_api_key.strip()] if embedding_api_key.strip() else key_list
    return _embedding_probe(embedding_base_url, embedding_model, emb_keys)


def test_and_save_config(
    db: Session,
    user_id: int,
    *,
    provider_type: str,
    base_url: str,
    model_name: str,
    embedding_base_url: str = "",
    embedding_model: str = "",
    embedding_api_key: str = "",
    enable_rate_limit: bool = True,
    new_keys: List[str] = (),
) -> UserAiConfig:
    """保存前强制测试（D10）：对话 + embedding 双探测，全部通过才落库。

    `new_keys` 为本次新增的明文 key；已有 key 保留（PUT 不回传明文）。
    embedding key 规则：单独填了 embedding_api_key 用之；否则用聊天 key 组。
    """
    provider_type = (provider_type or "").strip().lower()
    if provider_type not in PROVIDERS:
        raise AiConfigInvalidError("provider_type 必须是 openai 或 anthropic")
    base_url = (base_url or "").strip().rstrip("/")
    if not base_url.startswith(("http://", "https://")):
        raise AiConfigInvalidError("Base URL 必须以 http:// 或 https:// 开头")
    model_name = (model_name or "").strip()
    if not model_name:
        raise AiConfigInvalidError("模型名称不能为空")

    existing = get_config(db, user_id)
    old_keys = _enabled_keys(existing) if existing else []
    all_keys = list(old_keys) + [k.strip() for k in new_keys if k.strip()]
    if not all_keys:
        raise AiConfigInvalidError("至少需要一把 API Key")

    # ---- 对话探测 ----
    _chat_probe(provider_type, base_url, model_name, all_keys)

    # ---- embedding 配置与探测 ----
    embedding_model = (embedding_model or "").strip()
    embedding_base_url = (embedding_base_url or "").strip().rstrip("/")
    if provider_type == "anthropic" and not embedding_base_url:
        raise AiConfigInvalidError(
            "Anthropic 协议没有 Embedding API，请单独填写 Embedding 端点"
            "（OpenAI 兼容，如 DashScope）"
        )
    if not embedding_model:
        raise AiConfigInvalidError("Embedding 模型不能为空")
    if not embedding_base_url:
        embedding_base_url = base_url  # 未单配 → 与聊天同端点

    if embedding_api_key.strip():
        emb_keys = [embedding_api_key.strip()]
    else:
        emb_keys = all_keys
    dim = _embedding_probe(embedding_base_url, embedding_model, emb_keys)

    # ---- 全部通过，落库 ----
    config = existing or UserAiConfig(user_id=user_id)
    config.provider_type = provider_type
    config.base_url = base_url
    config.model_name = model_name
    config.embedding_base_url = embedding_base_url or None
    config.embedding_model = embedding_model
    config.embedding_dim = dim
    config.enable_rate_limit = bool(enable_rate_limit)
    if embedding_api_key.strip():
        config.embedding_api_key_enc = encrypt_text(embedding_api_key.strip())
    if existing is None:
        db.add(config)
    db.flush()

    for plain in new_keys:
        plain = plain.strip()
        if not plain:
            continue
        db.add(UserAiKey(
            user_id=user_id,
            config_id=config.id,
            key_enc=encrypt_text(plain),
            enabled=True,
        ))
    db.commit()
    logger.info(
        "[UAICFG] 配置已保存 user=%s provider=%s model=%s keys=%d dim=%s",
        user_id, provider_type, model_name, len(all_keys), dim,
    )
    return config


def clear_config(db: Session, user_id: int) -> None:
    """清空配置（重置）。未配置 AI 功能即刻不可用（强制配置 D4）。"""
    config = get_config(db, user_id)
    if config is not None:
        db.delete(config)
        db.commit()


def add_key(db: Session, user_id: int, plain_key: str, label: str = "") -> Dict[str, Any]:
    """新增一把 key（即时探测通过才入库）。"""
    plain_key = (plain_key or "").strip()
    if not plain_key:
        raise AiConfigInvalidError("API Key 不能为空")
    config, keys = resolve(db, user_id)
    probe_keys = keys + [plain_key]
    _chat_probe(config.provider_type, config.base_url, config.model_name, probe_keys)
    row = UserAiKey(
        user_id=user_id,
        config_id=config.id,
        key_enc=encrypt_text(plain_key),
        label=(label or "").strip()[:50] or None,
        enabled=True,
    )
    db.add(row)
    db.commit()
    return {"id": row.id, "masked": _mask_key(plain_key), "label": row.label or ""}


def set_key_enabled(db: Session, user_id: int, key_id: int, enabled: bool) -> None:
    row = (
        db.query(UserAiKey)
        .filter(UserAiKey.id == key_id, UserAiKey.user_id == user_id)
        .first()
    )
    if row is None:
        raise AiConfigInvalidError("key 不存在")
    row.enabled = bool(enabled)
    db.commit()


def delete_key(db: Session, user_id: int, key_id: int) -> None:
    row = (
        db.query(UserAiKey)
        .filter(UserAiKey.id == key_id, UserAiKey.user_id == user_id)
        .first()
    )
    if row is None:
        raise AiConfigInvalidError("key 不存在")
    db.delete(row)
    db.commit()
    remaining = [k for k in (get_config(db, user_id).keys if get_config(db, user_id) else []) if k.enabled]
    if not remaining:
        # 最后一把 key 删掉 = 配置不可用，整体清掉避免半死状态
        clear_config(db, user_id)
