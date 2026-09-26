"""关系复盘历史留档的读写（整改契约 §8.7）。

与 `ai_generation_repo` 的关键差异：**追加**而非覆盖。每次复盘插入新行，
历史列表按 id 倒序返回，用户永远能翻回上几次看到当时写了什么、
后来又是什么结果。
"""

from datetime import datetime
from typing import List, Optional

from sqlalchemy.orm import Session

from app.models.relationship_review import AiRelationshipReview


def create_review(
    db: Session,
    *,
    user_id: int,
    relation_id: int,
    description: str,
    context: Optional[str] = None,
    event_time: Optional[datetime] = None,
    status: str = "streaming",
) -> AiRelationshipReview:
    """开一条新的复盘留档（先占位，流式结束后回填结果）。"""
    row = AiRelationshipReview(
        user_id=user_id,
        relation_id=relation_id,
        description=description,
        context=context,
        event_time=event_time,
        status=status,
        content="",
    )
    db.add(row)
    db.flush()
    return row


def get_review(db: Session, user_id: int, review_id: int) -> Optional[AiRelationshipReview]:
    """按 id 取**本人**的复盘。跨用户一律当作不存在（调用方翻译成 404）。"""
    return (
        db.query(AiRelationshipReview)
        .filter(
            AiRelationshipReview.id == review_id,
            AiRelationshipReview.user_id == user_id,
        )
        .first()
    )


def get_review_by_id(db: Session, review_id: int) -> Optional[AiRelationshipReview]:
    """按 id 取（不校验归属）。只给内部管线用，对外接口一律走 [get_review]。"""
    return db.query(AiRelationshipReview).filter(AiRelationshipReview.id == review_id).first()


def list_reviews(
    db: Session, user_id: int, page: int = 1, page_size: int = 20
) -> tuple:
    """我的复盘历史，倒序分页。返回 `(items, total)`。"""
    query = db.query(AiRelationshipReview).filter(AiRelationshipReview.user_id == user_id)
    total = query.count()
    items = (
        query.order_by(AiRelationshipReview.id.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )
    return items, total


def save_review_result(
    db: Session,
    *,
    review_id: int,
    status: str,
    content: str,
    structured_output: Optional[dict],
    risk_level: Optional[str],
    summary: str = "",
    trigger: str = "",
    own_need: str = "",
    partner_need: str = "",
    suggested_expression: str = "",
) -> None:
    """流式结束后回填结果（同一行的原地更新，不新增行）。"""
    row = db.query(AiRelationshipReview).filter(AiRelationshipReview.id == review_id).first()
    if row is None:
        return
    row.status = status
    row.content = content
    row.structured_output = structured_output
    row.risk_level = risk_level
    # 六项结构化字段：解析不出来时保持空串（正文仍然可读）
    row.summary = summary
    row.trigger = trigger
    row.own_need = own_need
    row.partner_need = partner_need
    row.suggested_expression = suggested_expression
    # 事件发生时间缺省回落创建时间：正常路径由 service 在插入时就填好，
    # 这里只是兜住历史脏行，让「发生时间」不可能为空。
    if row.event_time is None:
        row.event_time = row.created_at
    db.flush()


def set_outcome(
    db: Session, user_id: int, review_id: int, outcome: str
) -> Optional[AiRelationshipReview]:
    """回填「后来怎么样了」。返回 None 表示不是本人的复盘。"""
    row = get_review(db, user_id, review_id)
    if row is None:
        return None
    row.outcome = outcome
    row.outcome_at = datetime.now()
    # 结果已回填 → 回访任务到期即消失
    row.recall_at = None
    db.flush()
    return row


def set_recall_at(db: Session, review_id: int, when: Optional[datetime]) -> None:
    """排定/取消稍后回访任务。"""
    row = db.query(AiRelationshipReview).filter(AiRelationshipReview.id == review_id).first()
    if row is None:
        return
    row.recall_at = when
    db.flush()


def pick_due_recall(db: Session, user_id: int, now: Optional[datetime] = None):
    """取一条到点且尚无结果的回访任务（首页卡用）。没有则 None。"""
    now = now or datetime.now()
    return (
        db.query(AiRelationshipReview)
        .filter(
            AiRelationshipReview.user_id == user_id,
            AiRelationshipReview.recall_at.isnot(None),
            AiRelationshipReview.recall_at <= now,
            AiRelationshipReview.outcome.is_(None),
        )
        .order_by(AiRelationshipReview.recall_at.asc())
        .first()
    )
