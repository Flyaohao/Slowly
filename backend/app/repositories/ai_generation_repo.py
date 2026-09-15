from typing import Optional

from sqlalchemy.orm import Session

from app.models.ai_generation import AiGeneration


def get_generation(
    db: Session,
    user_id: int,
    generation_kind: str,
    target_type: str = "none",
    target_id: Optional[int] = None,
) -> Optional[AiGeneration]:
    """取某个目标的最近一条生成记录。

    同一个目标的重复生成是**覆盖**而非追加（见 `AiGeneration` 的说明），
    所以这里加 `order_by(id.desc())` 兜底：即便历史上因为并发或手工操作
    留下过重复行，也一定拿到最新的那条，不会一会儿看到新结果一会儿看到旧结果。
    """
    query = db.query(AiGeneration).filter(
        AiGeneration.user_id == user_id,
        AiGeneration.generation_kind == generation_kind,
        AiGeneration.target_type == target_type,
    )
    if target_id is None:
        query = query.filter(AiGeneration.target_id.is_(None))
    else:
        query = query.filter(AiGeneration.target_id == target_id)
    return query.order_by(AiGeneration.id.desc()).first()


def get_generation_by_id(db: Session, generation_id: int) -> Optional[AiGeneration]:
    return db.query(AiGeneration).filter(AiGeneration.id == generation_id).first()


def upsert_generation(
    db: Session,
    *,
    user_id: int,
    relation_id: Optional[int],
    generation_kind: str,
    scene_key: str,
    content: str,
    status: str = "streaming",
    target_type: str = "none",
    target_id: Optional[int] = None,
    thinking: Optional[str] = None,
    structured_output: Optional[dict] = None,
    risk_level: Optional[str] = None,
    model: Optional[str] = None,
) -> AiGeneration:
    """写入（或更新）某个目标的最新生成结果。

    `content` 必填：MySQL 的 TEXT 列不允许有默认值，模型侧也就没给 Python
    默认值，漏传会在 flush 时报错而不是静默写入空串——这是有意为之。
    """
    row = get_generation(db, user_id, generation_kind, target_type, target_id)
    if row is None:
        row = AiGeneration(
            user_id=user_id,
            relation_id=relation_id,
            generation_kind=generation_kind,
            target_type=target_type,
            target_id=target_id,
            scene_key=scene_key,
            status=status,
            content=content,
            thinking=thinking,
            structured_output=structured_output,
            risk_level=risk_level,
            model=model,
        )
        db.add(row)
    else:
        row.relation_id = relation_id
        row.scene_key = scene_key
        row.status = status
        row.content = content
        row.thinking = thinking
        row.structured_output = structured_output
        row.risk_level = risk_level
        row.model = model
    db.flush()
    return row
