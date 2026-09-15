"""冷静期超时定时任务

检查超过冷静期（72 小时）未确认的解绑请求，自动取消解绑，恢复为 active 状态。
"""

import logging
from datetime import datetime, timedelta

from sqlalchemy.orm import Session
from sqlalchemy import and_

from app.core.database import SessionLocal
from app.models.couple_relation import CoupleRelation
from app.repositories import user_repo

logger = logging.getLogger(__name__)

# 冷静期时长（小时），与 couple_service.confirm_unbind 保持一致
COOLING_PERIOD_HOURS = 72


def check_unbinding_timeout():
    """检查并处理超时的解绑请求"""
    db = SessionLocal()
    try:
        timeout_threshold = datetime.utcnow() - timedelta(hours=COOLING_PERIOD_HOURS)

        # 查找超时的 unbinding 记录
        expired_relations = (
            db.query(CoupleRelation)
            .filter(
                and_(
                    CoupleRelation.status == "unbinding",
                    CoupleRelation.unbind_requested_at < timeout_threshold,
                )
            )
            .all()
        )

        if not expired_relations:
            logger.info("No expired unbinding requests found")
            return

        for relation in expired_relations:
            logger.info(
                f"Cancelling expired unbind for relation {relation.id} "
                f"(requested at {relation.unbind_requested_at})"
            )

            # 恢复为 active 状态
            relation.status = "active"
            relation.unbind_requested_by = None
            relation.unbind_requested_at = None

        db.commit()
        logger.info(f"Cancelled {len(expired_relations)} expired unbinding requests")

    except Exception as e:
        logger.error(f"Error checking unbinding timeout: {e}")
        db.rollback()
    finally:
        db.close()


def run_scheduler(interval_seconds: int = 3600):
    """常驻轮询（纯标准库，不引 schedule 依赖）。

    每小时执行一次检查即可：超时判定本身就是「超过 72h 自动取消」，
    每小时跑一次与每天定点跑效果一致（最多晚 1 小时处理）。
    """
    import time

    logger.info("Unbinding timeout scheduler started, interval=%ss", interval_seconds)
    while True:
        try:
            check_unbinding_timeout()
        except Exception as e:
            # 主循环抗崩：单次检查失败不应让容器进入重启循环
            logger.error("scheduler loop error: %s", e)
        time.sleep(interval_seconds)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run_scheduler()
