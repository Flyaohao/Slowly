"""用户级状态（v3.2 附录 §1.4 / §6.1）：hidden/muted/starred 按用户。

职责互斥（§6）：全局 `status` 管生命周期与共享，user_state 只管**本人**视图；
缺行 ≡ 全 false。写入必须校验：用户是 assertion 所属关系成员（管理可见性）。
单方 mute/hide 不影响另一方（红线6）。

`viewer_state_filter_clause()` 是三处召回查询点共用的 NOT EXISTS 过滤（D5）。
"""
from typing import Optional, Sequence

from sqlalchemy import exists, or_, select


class UserStateRejected(ValueError):
    """user_state 写入被拒（非关系成员 / 断言不存在）。"""


def viewer_state_filter_clause(viewer_user_id: int):
    """查看者维度的 hidden/muted 过滤（NOT EXISTS 关联子查询）。

    不产生行重复（对比 JOIN）；`memory_assertion_user_state` 空表 = 无操作。
    与 `visibility_filter` 互不越界：后者只管可见性（唯一入口），本子句只管
    个性化状态；一方 mute/hide 不影响另一方。
    """
    from app.models.ai import AiMemory, MemoryAssertionUserState

    return ~exists(
        select(1).where(
            MemoryAssertionUserState.assertion_id == AiMemory.id,
            MemoryAssertionUserState.user_id == viewer_user_id,
            or_(
                MemoryAssertionUserState.hidden.is_(True),
                MemoryAssertionUserState.muted.is_(True),
            ),
        )
    )


def _assert_member(db, assertion, user_id: int) -> Sequence[int]:
    """校验 user 是 assertion 所属关系成员（附录 §1.4 管理可见性前提）。"""
    from app.models.couple_relation import CoupleRelation

    rel = (
        db.query(CoupleRelation)
        .filter(CoupleRelation.id == assertion.relation_id)
        .first()
    )
    if rel is None:
        raise UserStateRejected(
            "relation %s not found" % assertion.relation_id
        )
    members = (rel.user_a_id, rel.user_b_id)
    if user_id not in members:
        raise UserStateRejected(
            "user %s is not a member of relation %s" % (user_id, rel.id)
        )
    return members


def set_user_state(
    db,
    *,
    assertion_id: int,
    user_id: int,
    hidden: Optional[bool] = None,
    muted: Optional[bool] = None,
    starred: Optional[bool] = None,
    commit: bool = False,
):
    """upsert 本人的 user_state 行（缺行 ≡ 全 false；None 字段保持原值）。

    **不改 assertion、不改向量**（附录 T6：mute/hide/star 只 upsert 本表）。
    """
    from app.models.ai import AiMemory, MemoryAssertionUserState

    assertion = db.query(AiMemory).filter(AiMemory.id == assertion_id).first()
    if assertion is None:
        raise UserStateRejected("assertion %s not found" % assertion_id)
    _assert_member(db, assertion, user_id)

    row = (
        db.query(MemoryAssertionUserState)
        .filter(
            MemoryAssertionUserState.assertion_id == assertion_id,
            MemoryAssertionUserState.user_id == user_id,
        )
        .first()
    )
    if row is None:
        row = MemoryAssertionUserState(
            assertion_id=assertion_id,
            user_id=user_id,
            hidden=bool(hidden),
            muted=bool(muted),
            starred=bool(starred),
        )
        db.add(row)
    else:
        if hidden is not None:
            row.hidden = bool(hidden)
        if muted is not None:
            row.muted = bool(muted)
        if starred is not None:
            row.starred = bool(starred)
    db.flush()
    if commit:
        db.commit()
    return row


def get_user_state(db, *, assertion_id: int, user_id: int) -> dict:
    """读本人状态；缺行返回全 false（附录 §1.4 语义）。"""
    from app.models.ai import MemoryAssertionUserState

    row = (
        db.query(MemoryAssertionUserState)
        .filter(
            MemoryAssertionUserState.assertion_id == assertion_id,
            MemoryAssertionUserState.user_id == user_id,
        )
        .first()
    )
    if row is None:
        return {"hidden": False, "muted": False, "starred": False}
    return {
        "hidden": row.hidden,
        "muted": row.muted,
        "starred": row.starred,
    }
