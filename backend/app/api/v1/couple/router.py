"""情侣模式专属 API 路由"""

from fastapi import APIRouter
from app.api.v1.couple import (
    ai, letters, mediation, memory, ws,
    dual_perspectives, museum,
    anniversaries, wishlists, avatars, presence
)

router = APIRouter()

router.include_router(ai.router)
router.include_router(letters.router)
router.include_router(mediation.router)
router.include_router(memory.router)
router.include_router(ws.router)
router.include_router(dual_perspectives.router)
router.include_router(museum.router)
router.include_router(anniversaries.router)
router.include_router(wishlists.router)
router.include_router(avatars.router)
router.include_router(presence.router)
