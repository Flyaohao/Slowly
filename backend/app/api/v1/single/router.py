"""单身模式专属 API 路由"""

from fastapi import APIRouter
from app.api.v1.single import diary, self_practice

router = APIRouter()

router.include_router(diary.router)
router.include_router(self_practice.router)
