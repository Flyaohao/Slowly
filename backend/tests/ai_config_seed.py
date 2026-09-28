# -*- coding: utf-8 -*-
"""测试公共前置：v5.0 用户级 AI 配置种子。

v5.0 起 AI 链路强制配置（设计决策 D4）：`prepare_*` / `build_chat_client`
在用户无 `user_ai_config` 行时抛 `AiConfigMissingError`（业务码 30010）。
既有测试套件大多在桩层注入假客户端，但**解析层**仍会查库——因此凡是
触发 AI 路径的套件，造完用户后调用 [`ensure_ai_config`] 补一条配置即可：

    from tests.ai_config_seed import ensure_ai_config
    ensure_ai_config(db, user_id)              # 桩套件：dummy key
    ensure_ai_config(db, user_id, real=True)   # 真调模型套件：全局 key

幂等：已有配置时不动。
"""
import os


def ensure_ai_config(db, user_id: int, *, real: bool = False) -> bool:
    """给 user_id 补一条可用 AI 配置；返回是否新插入。

    `real=True` 用环境变量里的真实 key/base_url/model（真调模型的套件）；
    否则用 dummy 值——桩注入套件只需要 `resolve()` 通过。
    """
    from app.core.config import AI_API_KEY, AI_BASE_URL, AI_MODEL
    from app.core.crypto import encrypt_text
    from app.models.user_ai_config import UserAiConfig, UserAiKey
    from app.services.user_ai_config_service import get_config

    if get_config(db, user_id) is not None:
        return False

    if real:
        base_url = AI_BASE_URL
        model_name = AI_MODEL
        api_key = AI_API_KEY or "sk-test-dummy"
    else:
        base_url = os.getenv("AI_BASE_URL", "https://test.invalid/v1")
        model_name = os.getenv("AI_MODEL", "test-model")
        api_key = "sk-test-dummy"

    cfg = UserAiConfig(
        user_id=user_id,
        provider_type="openai",
        base_url=base_url.rstrip("/"),
        model_name=model_name,
        embedding_base_url=base_url.rstrip("/"),
        embedding_model="text-embedding-v4",
        embedding_dim=1024,
        enable_rate_limit=True,
    )
    db.add(cfg)
    db.flush()
    db.add(UserAiKey(
        user_id=user_id,
        config_id=cfg.id,
        key_enc=encrypt_text(api_key),
        enabled=True,
    ))
    db.commit()
    return True
