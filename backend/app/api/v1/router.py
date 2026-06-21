"""API v1 路由注册

按模式分组：
- common: 共享 API（auth, couples, questionnaire, profile, home）
- single: 单身模式专属 API（diary, self_practice）
- couple: 情侣模式专属 API（letters, ai, mediation, etc.）
"""

from fastapi import APIRouter

from app.api.v1.common.router import router as common_router
from app.api.v1.single.router import router as single_router
from app.api.v1.couple.router import router as couple_router
from app.api.v1 import admin

router = APIRouter(prefix="/api/v1")

# 共享 API
router.include_router(common_router)

# 单身模式专属 API
router.include_router(single_router, prefix="/single", tags=["单身模式"])

# 情侣模式专属 API
router.include_router(couple_router, prefix="/couple", tags=["情侣模式"])

# 管理 API
router.include_router(admin.router)
