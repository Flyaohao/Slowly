import logging

from fastapi import FastAPI, Request
from fastapi.exception_handlers import (
    http_exception_handler as default_http_exception_handler,
)
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.base import BaseHTTPMiddleware

from app.core.limiter import limiter
from app.api.v1.router import router as v1_router

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("couple")


class RequestLogMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        method = request.method
        logger.info(f">>> {method} {path}")
        response = await call_next(request)
        logger.info(f"<<< {method} {path} -> {response.status_code}")
        return response


app = FastAPI(
    title="Couple AI Translator",
    description="情侣 AI 翻译器 API",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

app.add_middleware(RequestLogMiddleware)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(v1_router)


# ---------------------------------------------------------------------------
# 统一响应封装
#
# 本项目的约定是：所有业务结果都用 `{code, message, data}` 这一层封装，
# `code == 0` 表示成功、非 0 表示业务错误，HTTP 状态码一律 200。
#
# 但历史上大量端点直接 `raise HTTPException(status_code=4xx, detail={...})`，
# 经 FastAPI 默认处理器后变成 `{"detail": {...}}` + 非 2xx。客户端是
# Retrofit + Moshi，只会把 2xx 的响应体反序列化成 ApiResponse，遇到非 2xx
# 直接抛 HttpException —— 于是 detail 里的 `code` / `message` 全部丢失，
# 界面上只剩「HTTP 400」「HTTP 404」这类字符串。
#
# 后果是同一个业务错误，提示可读与否完全取决于端点是 `return` 还是 `raise`：
# 「请先绑定情侣关系」在 /couple/museum 上是可读的，在 /couple/avatars/me
# 上就变成了「HTTP 400」。客户端为此写了三套各自为政的兜底解析
# （AuthRepository 用 JSONObject、CoupleRepository 用正则刮 message、
# AiRepository 按状态码猜中文），互相不一致，且新增端点仍会踩到。
#
# 这里统一收口，而不是去改 120+ 处 raise —— 那些 raise 里的 detail 本身
# 都是 `{"code", "message", "data"}`，语义是对的，只是被包在了错误的外层里。
#
# 三条规则：
#   1. 401 / 403 原样返回。它们承载登录态与权限，客户端要靠状态码本身区分
#      「凭证失效」，改写成语义相反的 200 会让鉴权分支失效。
#   2. detail 是字典 → 业务错误 → 归一化成 HTTP 200 + `{code, message, data}`。
#   3. detail 不是字典 → 框架自己生成的（路由不存在、方法不允许），
#      原样返回 404 / 405。保住这些状态码，部署核对与接口探测才有依据。
# ---------------------------------------------------------------------------

#: 原样返回、不做归一化的状态码。
_PASSTHROUGH_STATUSES = frozenset({401, 403})

#: detail 里没有显式业务码时，按 HTTP 状态码兜底推断。
_STATUS_TO_BUSINESS_CODE = {
    400: 10001,
    404: 10004,
    405: 10005,
    409: 10009,
    415: 10015,
    422: 10002,
    429: 10029,
    500: 10000,
}


def _respond_business_error(request, original_status, code, message, data, level=logging.WARNING):
    """按统一封装返回业务错误，同时把原始状态码写进日志，避免排查时丢失信息。"""
    logger.log(
        level,
        "[业务错误] %s %s -> 原 HTTP %s / 业务码 %s / %s",
        request.method,
        request.url.path,
        original_status,
        code,
        message,
    )
    return JSONResponse(
        status_code=200,
        content={"code": code, "message": message, "data": data},
    )


def _split_detail(status_code: int, detail):
    """把 FastAPI 的 detail 拆成 (code, message, data)。"""
    fallback_code = _STATUS_TO_BUSINESS_CODE.get(status_code, 10000)
    if isinstance(detail, dict):
        return (
            detail.get("code", fallback_code),
            str(detail.get("message") or ""),
            detail.get("data"),
        )
    if isinstance(detail, str):
        return fallback_code, detail, None
    return fallback_code, "请求失败", None


@app.exception_handler(StarletteHTTPException)
async def unified_http_exception_handler(request: Request, exc: StarletteHTTPException):
    if exc.status_code in _PASSTHROUGH_STATUSES:
        return await default_http_exception_handler(request, exc)

    # detail 不是字典时，说明不是业务主动抛的，而是框架生成的路由级错误，
    # 保留原始状态码（否则「接口不存在」会伪装成 200，探测与排障都会失去依据）。
    if not isinstance(exc.detail, dict):
        return await default_http_exception_handler(request, exc)

    code, message, data = _split_detail(exc.status_code, exc.detail)
    return _respond_business_error(request, exc.status_code, code, message, data)


@app.exception_handler(RequestValidationError)
async def unified_validation_exception_handler(request: Request, exc: RequestValidationError):
    """请求体/参数不合法。

    这也是客户端会撞上的一类错误（字段名写错、必填项缺失），但 FastAPI 默认返回
    422 + `{"detail": [...]}`，客户端同样读不到原因。这里摊平成一句可读的提示，
    并把出错的字段名列出来，方便定位是哪一端契约没对齐。
    """
    parts = []
    for err in exc.errors()[:5]:
        loc = ".".join(str(x) for x in err.get("loc", ()) if x != "body")
        parts.append("%s: %s" % (loc or "body", err.get("msg", "")))
    message = "参数校验失败" + ("（" + "；".join(parts) + "）" if parts else "")
    return _respond_business_error(request, 422, 10002, message, None)


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """兜底：未预期的异常。

    同样返回 HTTP 200 + 业务码 10000，让客户端能显示「服务器内部错误」而不是
    「HTTP 500」。真实堆栈通过 logger.exception 完整落到服务端日志，
    排查依据不依赖 HTTP 状态码。
    """
    logger.exception("[服务端异常] %s %s -> %s", request.method, request.url.path, exc)
    return JSONResponse(
        status_code=200,
        content={"code": 10000, "message": "服务器内部错误", "data": None},
    )
