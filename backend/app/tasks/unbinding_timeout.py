"""冷静期超时定时任务

检查超过 7 天未确认的解绑请求，自动取消解绑，恢复为 active 状态。
"""

import logging
from datetime import datetime, timedelta

from sqlalchemy.orm import Session
from sqlalchemy import and_

from app.core.database import SessionLocal
from app.models.couple_relation import CoupleRelation
from app.repositories import user_repo

logger = logging.getLogger(__name__)

# 冷静期时长（天）
COOLING_PERIOD_DAYS = 7


def check_unbinding_timeout():
    """检查并处理超时的解绑请求"""
    db = SessionLocal()
    try:
        timeout_threshold = datetime.utcnow() - timedelta(days=COOLING_PERIOD_DAYS)

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


def run_scheduler():
    """运行定时调度器（用于独立进程）"""
    import time
    import schedule

    # 每天凌晨 2 点检查
    schedule.every().day.at("02:00").do(check_unbinding_timeout)

    logger.info("Unbinding timeout scheduler started")
    while True:
        schedule.run_pending()
        time.sleep(60)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run_scheduler()
