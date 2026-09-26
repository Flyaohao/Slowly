"""军师设置（契约 §3.3）：avatar 4 新列 + voice_style 透传。

端点放 common 前缀（单双模式通用），但存储挂在 relation 键控的 ``ai_avatar``
行上（契约 §5 迁移表只给了 avatar +4 列，没有单人侧存储）：

- **GET**：无关系（单人模式）→ 返回与 FE ``AdvisorDto.defaults()`` 同构的默认值，
  只读可用；
- **PUT**：无关系 → ``30005``（无处可存，不静默丢配置）。

这是「单双模式通用 vs relation 键控存储」的已声明裁决，见最终报告冲突项。
"""
from typing import Optional

from sqlalchemy.orm import Session

from app.models.avatar import AiAvatar
from app.repositories import avatar_repo, couple_repo

#: 无 avatar 行 / 无关系时的对外默认（与 FE AdvisorDto.defaults 同构）
DEFAULT_SETTINGS = {
    "address_name": "",
    "detail_level": "standard",
    "proactivity": "moderate",
    "show_evidence": True,
    "voice_style": "gentle",
}


def _settings_from_avatar(avatar: Optional[AiAvatar]) -> dict:
    """avatar 行 → 对外 payload（GET → PUT 同构；address_name 永不为 null）。"""
    if avatar is None:
        return dict(DEFAULT_SETTINGS)
    return {
        # FE 是非空 Kotlin String：空/NULL 一律口径 ""
        "address_name": avatar.address_name or "",
        "detail_level": avatar.detail_level or "standard",
        "proactivity": avatar.proactivity or "moderate",
        "show_evidence": bool(avatar.show_evidence),
        "voice_style": avatar.voice_style or "gentle",
    }


def get_settings(db: Session, user_id: int) -> dict:
    """读取军师设置；单人模式返回默认值（只读）。"""
    relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if not relation:
        return dict(DEFAULT_SETTINGS)
    return _settings_from_avatar(
        avatar_repo.get_avatar_by_relation_id(db, relation.id)
    )


def update_settings(db: Session, user_id: int, data: dict) -> dict:
    """更新军师设置（部分更新，data 为 exclude_unset 后的 dict）。

    无关系 → ``ValueError("30005")``（由 API 层转 30005 信封）。
    注意 ``avatar_repo.update_avatar`` 跳过 None——显式 null 表示「不修改」，
    清空 address_name 用空串。
    """
    relation = couple_repo.get_relation_by_user_including_unbinding(db, user_id)
    if not relation:
        raise ValueError("30005")
    avatar = avatar_repo.get_or_create_avatar(db, relation.id)
    avatar_repo.update_avatar(db, avatar, data)
    db.commit()
    db.refresh(avatar)
    return _settings_from_avatar(avatar)
