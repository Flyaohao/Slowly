"""观点 API 路由（内部数据仍是 diary_entry）

2026-09-29：单身模式已删除。本模块**保留**——「观点」是情侣模式的功能，
抽屉入口（DrawerContent）与 GuideScreen 都指向它。路由前缀 `/api/v1/single` 是历史命名，
后端不校验模式（只用 get_current_user），情侣用户正常可用。
self_practice 已随单身模式一并删除（此前已被 features.py 冻结为 10006）。
"""

from fastapi import APIRouter
from app.api.v1.single import diary

router = APIRouter()

router.include_router(diary.router)
