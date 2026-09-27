# -*- coding: utf-8 -*-
"""调解室双方称呼标签：按 user_profile.gender 映射「男方 / 女方」。

背景（2026-09-28 真机事故）：调解室 prompt 曾把 user_a/user_b 硬编码成
「女方/男方」，user_a 实为男性时军师把他的发言当「女方」回应。
修复后：性别从资料读取并显式喂给 LLM；用户填「其他 / 不愿透露」或未填时，
回退中性称呼「当事人A / 当事人B」——**绝不让 LLM 自己猜性别**。

`has_explicit_gender` 同时服务情侣绑定强制校验（30004/30005）。
"""

from typing import Optional

_MALE = {"男", "男性", "male", "m"}
_FEMALE = {"女", "女性", "female", "f"}

#: 性别不可用时的中性称呼（回退，不猜）
NEUTRAL_A = "当事人A"
NEUTRAL_B = "当事人B"


def gender_label(value: Optional[str]) -> Optional[str]:
    """gender 原始值 → 「男方」/「女方」；无法判定返回 None。"""
    if not value:
        return None
    normalized = str(value).strip().lower()
    if normalized in _MALE:
        return "男方"
    if normalized in _FEMALE:
        return "女方"
    return None


def has_explicit_gender(value: Optional[str]) -> bool:
    """绑定校验用：是否填了明确的男/女（其他/不愿透露/空 都算没填）。"""
    return gender_label(value) is not None


def party_labels(db, relation) -> dict:
    """relation 两侧的称呼标签：{"user_a": ..., "user_b": ...}。

    读 user_profile.gender；读不到 profile 或性别不明确时回退中性称呼。
    """
    from app.repositories import user_repo

    def _label(user_id):
        profile = user_repo.get_profile_by_user_id(db, user_id)
        return gender_label(profile.gender if profile else None)

    return {
        "user_a": _label(relation.user_a_id) or NEUTRAL_A,
        "user_b": _label(relation.user_b_id) or NEUTRAL_B,
    }
