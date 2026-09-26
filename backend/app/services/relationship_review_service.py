"""关系复盘历史与回访结果（整改契约 §8.7）。

复盘结果的**权威落点**是 `ai_relationship_review`（追加式留档）。本模块负责
把留档行变成客户端契约、以及回填「后来怎么样了」。

不做的事：不生成复盘（那是 `ai_service.prepare_relationship_review` +
流式引擎的职责），也不伪造「当前议题」——契约明确要求未建完整议题模型前
不得造出那样的页面，所以这里只有历史与结果两个真实能力。
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any, Dict, Optional

from sqlalchemy.orm import Session

from app.repositories import relationship_review_repo

#: 复盘完成后默认的回访窗口（天）。到期后首页出现「上次复盘后来怎么样了」。
DEFAULT_RECALL_DAYS = 3


def _iso(value: Optional[datetime]) -> Optional[str]:
    return value.isoformat() if value else None


def to_payload(row) -> Dict[str, Any]:
    """留档行 → 客户端契约（`AiDto.ReviewRecord` 与之逐字对应）。"""
    return {
        "review_id": row.id,
        "status": row.status,
        "description": row.description or "",
        "context": row.context or "",
        # 契约 §8.7 六项
        "summary": row.summary or "",
        "trigger": row.trigger or "",
        "own_need": row.own_need or "",
        "partner_need": row.partner_need or "",
        "suggested_expression": row.suggested_expression or "",
        "event_time": _iso(row.event_time or row.created_at),
        "outcome": row.outcome,
        "outcome_at": _iso(row.outcome_at),
        "recall_at": _iso(row.recall_at),
        # 生成侧：正文兜底 + 结构化全量（误解处 / 升级降温话术等分组）
        "content": row.content or "",
        "structured_output": row.structured_output or {},
        "risk_level": row.risk_level or "normal",
        "created_at": _iso(row.created_at),
        "updated_at": _iso(row.updated_at),
    }


def list_history(db: Session, user_id: int, page: int = 1, page_size: int = 20) -> Dict[str, Any]:
    """我的复盘历史，倒序分页。每项只给列表需要的字段，详情再取。"""
    rows, total = relationship_review_repo.list_reviews(db, user_id, page, page_size)
    items = [
        {
            "review_id": row.id,
            "status": row.status,
            "summary": row.summary or "",
            "event_time": _iso(row.event_time or row.created_at),
            "outcome": row.outcome,
            "created_at": _iso(row.created_at),
        }
        for row in rows
    ]
    return {"items": items, "total": total, "page": page, "page_size": page_size}


def get_detail(db: Session, user_id: int, review_id: int) -> Dict[str, Any]:
    """单次复盘详情。不是本人的留档一律 70001（不存在），不泄露他人复盘存在性。"""
    row = relationship_review_repo.get_review(db, user_id, review_id)
    if row is None:
        raise ValueError("70001")
    return to_payload(row)


def submit_outcome(db: Session, user_id: int, review_id: int, outcome: str) -> Dict[str, Any]:
    """回填「后来怎么样了」。回填后回访任务到期即消失（repo 里清 recall_at）。"""
    row = relationship_review_repo.set_outcome(db, user_id, review_id, outcome)
    if row is None:
        raise ValueError("70001")
    db.commit()
    db.refresh(row)
    return to_payload(row)


def schedule_recall(
    db: Session, review_id: int, days: int = DEFAULT_RECALL_DAYS
) -> None:
    """排定稍后回访（§8.7「复盘可产生稍后回访任务」）。

    只在复盘产出**可用结果**时排：失败/中断的留档没有可回访的内容，
    排了只会让首页催用户去回访一次空复盘。
    """
    row = relationship_review_repo.get_review_by_id(db, review_id)
    if row is None:
        return
    if row.status != "done" or not (row.summary or row.content):
        return
    relationship_review_repo.set_recall_at(db, review_id, datetime.now() + timedelta(days=days))


def pick_due_recall(db: Session, user_id: int):
    """首页回访卡的数据源：一条到点且尚无结果的复盘。没有则 None。"""
    return relationship_review_repo.pick_due_recall(db, user_id)
