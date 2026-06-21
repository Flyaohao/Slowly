"""共享 API 路由（两种模式都需要）"""

from fastapi import APIRouter
from app.api.v1.common import auth, users, couples, questionnaires, profiles, home

router = APIRouter()

router.include_router(auth.router)
router.include_router(users.router)
router.include_router(couples.router)
router.include_router(questionnaires.router)
router.include_router(profiles.router)
router.include_router(home.router)
