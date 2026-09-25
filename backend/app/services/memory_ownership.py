"""所有权、授权与来源认识论默认（v3.2 §6 + §5.3 + 计划裁决 EPISTEMIC_BY_SOURCE）。

纯函数模块，无 DB。三块：
1. `OWNERSHIP_BY_SOURCE` / `default_ownership()`：谁是 owner（revoke_share 的
   对象）、谁是 creator、ownership_type（user/relation/system）。
2. `can_revoke_share()` / `can_hide_for_user()`：§5.3/§6.2 权限矩阵纯函数。
3. `EPISTEMIC_BY_SOURCE`：(epistemic_type, assertion_origin) 按 source_type 的
   默认——AI 蒸馏的 diary/letter/museum 用 **unknown**（unknown 不撒谎）。
"""
from typing import Dict, Optional, Tuple

#: source_type → ownership_type（计划 Step 4 裁决）
#: letter/diary/chat_summary/museum → user；dual/questionnaire/anniversary → relation；
#: 系统事件 → system
OWNERSHIP_BY_SOURCE: Dict[str, str] = {
    "letter": "user",
    "diary": "user",
    "chat_summary": "user",
    "museum": "user",
    "dual": "relation",
    "questionnaire": "relation",
    "anniversary": "relation",
    "system_event": "system",
}

#: source_type → (epistemic_type, assertion_origin) 默认
#: - anniversary：用户登记的纪念日 = 自述 + 业务事件行（enforce 后归 system_event）
#: - questionnaire：问卷是解释/推断 → interpretation/inference
#: - AI 蒸馏 diary/letter/museum：unknown/user_message（unknown 不撒谎）
#: - chat_summary：会话摘要 → summary/inference
EPISTEMIC_BY_SOURCE: Dict[str, Tuple[str, str]] = {
    "anniversary": ("self_report", "business_event"),
    "questionnaire": ("interpretation", "inference"),
    "diary": ("unknown", "user_message"),
    "letter": ("unknown", "user_message"),
    "museum": ("unknown", "user_message"),
    "chat_summary": ("summary", "inference"),
}

#: 未列入映射的 source 默认（legacy / 未识别）
DEFAULT_EPISTEMIC: Tuple[str, str] = ("unknown", "user_message")
DEFAULT_OWNERSHIP = "user"


def default_ownership(
    source_type: Optional[str],
    user_id: Optional[int] = None,
    relation_creator_user_id: Optional[int] = None,
) -> Tuple[Optional[int], Optional[int], str]:
    """按 source 推默认三元组 → `(owner_user_id, created_by_user_id, ownership_type)`。

    - user：owner=creator=user_id（AI 蒸馏的私信/日记/博物馆内容归作者）
    - relation：owner=NULL（关系共有）、creator=写出该行的用户
    - system：owner=NULL、creator=NULL（无个人可 revoke，§5.3）
    """
    otype = OWNERSHIP_BY_SOURCE.get(source_type or "", DEFAULT_OWNERSHIP)
    creator = user_id if relation_creator_user_id is None else relation_creator_user_id
    if otype == "system":
        return None, None, "system"
    if otype == "relation":
        return None, creator, "relation"
    return user_id, user_id if user_id is not None else creator, "user"


def epistemic_default(source_type: Optional[str]) -> Tuple[str, str]:
    """source → (epistemic_type, assertion_origin) 默认；未知源 → DEFAULT_EPISTEMIC。"""
    return EPISTEMIC_BY_SOURCE.get(source_type or "", DEFAULT_EPISTEMIC)


def can_revoke_share(
    ownership_type: str,
    owner_user_id: Optional[int],
    created_by_user_id: Optional[int],
    actor_user_id: int,
) -> bool:
    """§5.3 revoke_share 矩阵：
    user → owner_user_id；relation → created_by_user_id；system → 无人。
    """
    if ownership_type == "user":
        return owner_user_id is not None and owner_user_id == actor_user_id
    if ownership_type == "relation":
        return created_by_user_id is not None and created_by_user_id == actor_user_id
    return False  # system：无人可 revoke


def can_hide_for_user(
    viewer_user_id: int,
    actor_user_id: int,
    relation_member_ids: Tuple[int, ...],
) -> bool:
    """hide/mute/star 是**按用户**的私有开关（§6）：本人可见/可操作自己的状态。

    另一方的开关不影响你（单方 mute 不牵连对方）——因此操作者必须是
    viewer 本人；关系成员校验用于防止越权给非成员写 user_state 行。
    """
    if actor_user_id != viewer_user_id:
        return False
    return viewer_user_id in (relation_member_ids or ())
