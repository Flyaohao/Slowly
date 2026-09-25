import base64
import logging
import os
import secrets

from fastapi import FastAPI, Request
from fastapi.exception_handlers import (
    http_exception_handler as default_http_exception_handler,
)
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from slowapi.errors import RateLimitExceeded
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import Response
from starlette.staticfiles import StaticFiles

from app.core import config
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


# ---------------------------------------------------------------------------
# 接口文档保护：/docs、/redoc、/openapi.json 走 HTTP Basic。
#
# openapi.json 会把全部端点的路径、参数、结构一次给全，等于一份现成的攻击说明书；
# 一旦匿名可读，枚举端点就不再需要任何技巧（冒烟脚本都能直接跑）。
# 生产环境保留文档但加口令，比直接关掉实用 —— 自己要能随时查。
#
# 未配置口令时一律返回 401（默认拒绝），避免"忘了设变量"变成"默默敞开"。
# ---------------------------------------------------------------------------
_DOCS_PATHS = {"/docs", "/redoc", "/openapi.json"}


def _docs_authorized(request: Request) -> bool:
    if not config.DOCS_USER or not config.DOCS_PASS:
        return False
    raw = request.headers.get("authorization", "")
    if not raw.lower().startswith("basic "):
        return False
    try:
        user, _, pwd = (
            base64.b64decode(raw[6:], validate=True).decode("utf-8").partition(":")
        )
    except Exception:  # noqa: BLE001 —— 头部不是合法 base64 就当认证失败
        return False
    # compare_digest 防时序攻击：普通 == 会因提前返回而泄露前缀信息
    return secrets.compare_digest(user, config.DOCS_USER) and secrets.compare_digest(
        pwd, config.DOCS_PASS
    )


class DocsGuardMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if request.url.path in _DOCS_PATHS and not _docs_authorized(request):
            return Response(
                status_code=401,
                headers={"WWW-Authenticate": 'Basic realm="docs"'},
            )
        return await call_next(request)


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """统一安全响应头。

    CSP 里保留 'unsafe-inline' 是刻意的：宣传页是单文件、内联 <style>/<script>。
    该页面无用户输入、无第三方资源，XSS 面几乎为零，为此拆文件不划算。

    frame-ancestors 'none' 必须由响应头下发 —— 写在页面 <meta> 里不生效，
    这是防点击劫持的关键一条。
    """

    async def dispatch(self, request: Request, call_next):
        resp = await call_next(request)
        resp.headers["X-Content-Type-Options"] = "nosniff"
        resp.headers["X-Frame-Options"] = "DENY"
        resp.headers["Referrer-Policy"] = "no-referrer"
        resp.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        resp.headers["Cross-Origin-Opener-Policy"] = "same-origin"
        resp.headers["Content-Security-Policy"] = (
            "default-src 'self'; img-src 'self' data:; "
            "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
            "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        )
        # 不暴露技术栈。
        # ⚠️ MutableHeaders 没有 pop()，必须用 del + 存在性判断，否则每个请求都会 500。
        # ⚠️ 光在这里删不干净：uvicorn 会在**协议层**补回 server 头，应用层删了它也照加。
        #    线上必须同时以 `--no-server-header` 启动（见 backend/docker-entrypoint.sh）。
        #    本地 TestClient 不经过 uvicorn，测不出这个差异 —— 别只看单测就以为生效了。
        if "server" in resp.headers:
            del resp.headers["server"]
        return resp


app = FastAPI(
    title="Slowly慢慢说",
    description="Slowly慢慢说 API",
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

app.add_middleware(RequestLogMiddleware)
app.state.limiter = limiter

_allowed_origins = [o.strip() for o in config.CORS_ORIGINS.split(",") if o.strip()]

app.add_middleware(
    CORSMiddleware,
    # 默认空列表 = 不允许任何跨站来源。App 用 Retrofit，不受 CORS 约束（那是浏览器
    # 才有的机制）；收紧要防的是「任意网站诱导访问者浏览器调用本 API」。
    allow_origins=_allowed_origins,
    # 客户端用 Bearer Token，不依赖 cookie 凭据
    allow_credentials=False,
    allow_methods=["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)

# ⚠️ 顺序很关键：Starlette 的中间件是「后注册的先执行」（洋葱模型）。
# SecurityHeadersMiddleware 必须**最后注册**才能成为最外层 ——
# 这样连 DocsGuard 直接返回的 401 也会带上安全头。
# 最终顺序：RequestLog → CORS → DocsGuard → SecurityHeaders
app.add_middleware(DocsGuardMiddleware)
app.add_middleware(SecurityHeadersMiddleware)

app.include_router(v1_router)

# ---------------------------------------------------------------------------
# 静态文件：/uploads 下存放上传的图片（头像、纪念馆藏品配图等）。
# 此前 upload_avatar 把文件写到 uploads/avatars 并返回 /uploads/... URL，
# 但没有任何东西服务这个路径 —— 头像一直是存了却加载不出来的。
# ---------------------------------------------------------------------------
_UPLOAD_DIR = os.getenv("UPLOAD_DIR", "uploads")
os.makedirs(_UPLOAD_DIR, exist_ok=True)
app.mount("/uploads", StaticFiles(directory=_UPLOAD_DIR), name="uploads")

# ---------------------------------------------------------------------------
# 静态文件：宣传页与安卓安装包下载。
#
# 两者刻意用不同的发布方式：
#
#   /app   —— 宣传页（单文件 HTML，约 40 KB）。**随镜像发布**，
#             跟代码一起走部署流程，不需要额外的宿主目录。
#
#   /download —— 安装包（APK，20 MB 起）。**走宿主 bind mount，不进镜像、
#             也不进部署包**：一是免得每次换包都重建镜像（重建要几分钟），
#             二是避免 20 MB 的二进制被反复打进部署包。
#             换包时只需把新文件放进宿主的 apk/ 目录。
#
# 目录用环境变量兜底默认值，因此**不需要往 compose 的 environment
# 白名单里加新变量** —— 少一个"设了却在容器里看不到"的坑。
# ---------------------------------------------------------------------------
_LANDING_DIR = os.getenv("LANDING_DIR", "static/app")
if os.path.isdir(_LANDING_DIR):
    # html=True：访问 /app/ 时自动返回 index.html，无需显式写文件名
    app.mount("/app", StaticFiles(directory=_LANDING_DIR, html=True), name="landing")

_APK_DIR = os.getenv("APK_DIR", "static/apk")
os.makedirs(_APK_DIR, exist_ok=True)
app.mount("/download", StaticFiles(directory=_APK_DIR), name="download")


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


@app.exception_handler(RateLimitExceeded)
async def unified_rate_limit_handler(request: Request, exc: RateLimitExceeded):
    """限流触发（slowapi）。

    不用 slowapi 自带的处理器：它返回 429 + `{"error": ...}`，既绕开了
    `{code, message, data}` 统一封装，客户端也读不到人话。这里归一化成
    HTTP 200 + 业务码 10029，文案可读；原始 429 保留在日志里。
    """
    return _respond_business_error(
        request, 429, 10029, "请求太频繁啦，请休息一会儿再试", None
    )


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
