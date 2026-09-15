"""在场感动态仓储。"""

from sqlalchemy.orm import Session

from app.models.presence import PresenceMoment


def create_moment(db: Session, relation_id: int, user_id: int, moment_type: str,
                  content: str, image_url=None) -> PresenceMoment:
    moment = PresenceMoment(
        relation_id=relation_id,
        user_id=user_id,
        moment_type=moment_type,
        content=content,
        image_url=image_url,
    )
    db.add(moment)
    db.commit()
    db.refresh(moment)
    return moment


def list_recent(db: Session, relation_id: int, limit: int = 20) -> list[PresenceMoment]:
    return (
        db.query(PresenceMoment)
        .filter(PresenceMoment.relation_id == relation_id)
        .order_by(PresenceMoment.id.desc())
        .limit(limit)
        .all()
    )
