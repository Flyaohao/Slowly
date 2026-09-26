"""安全加固验证脚本（接口文档保护 / CORS 收紧 / 安全响应头）。

用 FastAPI TestClient 在进程内起应用，不依赖真实服务器，也不需要数据库 ——
只请求不触及 DB 的路径（文档端点与不存在的路径）。

用法（在 backend/ 目录下执行）：
    PYTHONUTF8=1 <venv>/python tests/test_security_hardening.py

退出码 0 表示全部通过，1 表示存在失败项。
"""

import os
import sys

# 必须在 import app.core.config 之前设置：config 是模块级读取环境变量的。
# 这里用固定值，测试脚本自身即"已配置口令"的场景；
# "未配置"的场景通过临时改写 config 模块属性来覆盖（见 C 组）。
os.environ["DOCS_USER"] = "admin"
os.environ["DOCS_PASS"] = "s3cret-test-only"
os.environ.setdefault("JWT_SECRET", "test-only-not-for-production")

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from slowapi.errors import RateLimitExceeded  # noqa: E402

from app.core import config  # noqa: E402
from app.core.limiter import ai_limit, limiter  # noqa: E402
from app.main import app  # noqa: E402

client = TestClient(app)

RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond)))
    flag = "PASS" if cond else "FAIL"
    tail = "  [%s]" % detail if detail else ""
    print("  %s  %s%s" % (flag, name, tail))


AUTH_OK = ("admin", "s3cret-test-only")
AUTH_BAD = ("admin", "wrong-password")
DOC_PATHS = ["/docs", "/redoc", "/openapi.json"]


def main():
    print("=" * 72)
    print("安全加固验证")
    print("=" * 72)

    # ── A. 已配置口令：匿名与错误口令都应被拒 ──────────────────────────
    print("\n[A] 接口文档保护（已配置口令）")
    for p in DOC_PATHS:
        r = client.get(p)
        check("%s 匿名 -> 401" % p, r.status_code == 401, "实际 %s" % r.status_code)

    r = client.get("/openapi.json", auth=AUTH_BAD)
    check("openapi.json 错误口令 -> 401", r.status_code == 401, "实际 %s" % r.status_code)

    r = client.get("/docs", auth=AUTH_BAD)
    check("docs 错误口令 -> 401", r.status_code == 401, "实际 %s" % r.status_code)

    r = client.get("/openapi.json", auth=AUTH_OK)
    paths = r.json().get("paths", {}) if r.status_code == 200 else {}
    check(
        "openapi.json 正确口令 -> 200 且含接口",
        r.status_code == 200 and len(paths) > 0,
        "实际 %s，%d 个路径" % (r.status_code, len(paths)),
    )

    r = client.get("/docs")
    check(
        "401 响应带 WWW-Authenticate（浏览器才会弹口令框）",
        r.headers.get("www-authenticate", "").lower().startswith("basic"),
        r.headers.get("www-authenticate", "缺失"),
    )

    # ── B. 未配置口令：必须默认拒绝，而不是默认敞开 ────────────────────
    print("\n[B] 未配置口令时默认拒绝")
    saved_user, saved_pass = config.DOCS_USER, config.DOCS_PASS
    try:
        config.DOCS_USER, config.DOCS_PASS = "", ""
        r = client.get("/openapi.json", auth=AUTH_OK)   # 即便带"正确"口令
        check(
            "未配置口令时空配置 -> 401（不因口令为空而放行）",
            r.status_code == 401,
            "实际 %s" % r.status_code,
        )
    finally:
        config.DOCS_USER, config.DOCS_PASS = saved_user, saved_pass

    r = client.get("/openapi.json", auth=AUTH_OK)
    check("恢复配置后仍可访问 -> 200", r.status_code == 200, "实际 %s" % r.status_code)

    # ── C. CORS 收紧：不再回显任意来源 ────────────────────────────────
    print("\n[C] CORS 收紧")
    evil = {"Origin": "http://evil.example.com"}
    r = client.get("/openapi.json", headers=evil, auth=AUTH_OK)
    check(
        "任意来源不再被回显 allow-origin",
        "access-control-allow-origin" not in {k.lower() for k in r.headers},
        r.headers.get("access-control-allow-origin", "无该头"),
    )
    r = client.options(
        "/api/v1/auth/login",
        headers={
            "Origin": "http://evil.example.com",
            "Access-Control-Request-Method": "POST",
        },
    )
    check(
        "预检请求不再回显 allow-origin",
        "access-control-allow-origin" not in {k.lower() for k in r.headers},
        r.headers.get("access-control-allow-origin", "无该头"),
    )
    check(
        "不再返回 allow-credentials: true",
        r.headers.get("access-control-allow-credentials") != "true",
        "实际 %s" % r.headers.get("access-control-allow-credentials", "无该头"),
    )

    # ── D. 安全响应头 ────────────────────────────────────────────────
    print("\n[D] 安全响应头")
    expected = [
        "x-content-type-options",
        "x-frame-options",
        "referrer-policy",
        "permissions-policy",
        "content-security-policy",
    ]
    r = client.get("/nonexistent-probe")
    present = {k.lower() for k in r.headers}
    for h in expected:
        check("响应含 %s" % h, h in present, r.headers.get(h, "缺失"))

    check(
        "不再暴露 server 头",
        "server" not in present,
        r.headers.get("server", "无（预期）"),
    )
    # 中间件顺序验证：DocsGuard 直接产出的 401 也必须被最外层的 SecurityHeaders 覆盖
    r401 = client.get("/openapi.json")
    check(
        "401 响应同样带安全头（验证中间件注册顺序）",
        "content-security-policy" in {k.lower() for k in r401.headers},
        "401 响应 CSP 头%s" % ("存在" if "content-security-policy" in {k.lower() for k in r401.headers} else "缺失"),
    )

    # ── E. 不误伤 ────────────────────────────────────────────────────
    print("\n[E] 不误伤其他路径")
    r = client.get("/nonexistent-probe")
    check(
        "非文档路径不受 DocsGuard 影响（404 而非 401）",
        r.status_code == 404,
        "实际 %s" % r.status_code,
    )
    r = client.get("/docs/oauth2-redirect")
    check(
        "docs 子路径不被误拦（Swagger 辅助页）",
        r.status_code != 401,
        "实际 %s" % r.status_code,
    )

    # ── F. AI 端点限流：两个维度各自的拉闸能力 ────────────────────────
    # ai_limit() 每次调用都读当前 config 值，所以可以临时把其中一维调低、
    # 另一维放宽，单独验证这一维是否真的会拦 —— 否则"叠加生效"只是纸面结论。
    print("\n[F] AI 端点限流（用户维度 + IP 维度）")

    def probe_codes(user_limit, ip_limit, tag, client_ip):
        saved = (config.AI_RATE_LIMIT, config.AI_IP_RATE_LIMIT)
        try:
            config.AI_RATE_LIMIT, config.AI_IP_RATE_LIMIT = user_limit, ip_limit
            probe = FastAPI()
            probe.state.limiter = limiter
            probe.add_exception_handler(
                RateLimitExceeded,
                lambda req, exc: JSONResponse(status_code=429, content={"detail": "limited"}),
            )
            path = "/probe-" + tag

            def endpoint(request: Request):
                return {"ok": True}

            # ⚠️ slowapi 按函数的 __name__ 归组限流规则与计数。多个探针场景若共用
            # 同一个函数名（例如都叫 _endpoint），规则会互相叠加、计数互相污染 ——
            # 实测表现为 2/minute 只放行 1 次，看起来像"叠加把配额算重了"。
            # 生产代码里每条路由的函数名天然唯一，不受影响；测试里必须手动区分。
            endpoint.__name__ = "probe_endpoint_" + tag

            probe.get(path)(ai_limit()(endpoint))

            # 每个场景再用独立 client IP，避免来源地址维度上的串扰。
            # starlette ≥0.25 的 TestClient（httpx 版）已移除 client= 参数，
            # 改用 ASGI 包装强改 scope["client"]——slowapi get_remote_address
            # 读的正是 scope["client"]，效果与旧参数一致。
            async def _force_ip(scope, receive, send, _ip=client_ip):
                if scope.get("type") == "http":
                    scope = {**scope, "client": (_ip, 12345)}
                await probe(scope, receive, send)

            tc = TestClient(_force_ip)
            return [tc.get(path).status_code for _ in range(3)]
        finally:
            config.AI_RATE_LIMIT, config.AI_IP_RATE_LIMIT = saved

    codes = probe_codes("100/minute", "2/minute", "ip", "10.0.0.1")
    check(
        "IP 维度收紧即被拦（用户维度放宽）",
        codes[0] == 200 and codes[-1] == 429,
        "连续 3 次 %s" % codes,
    )

    codes = probe_codes("2/minute", "100/minute", "user", "10.0.0.2")
    check(
        "用户维度收紧即被拦（IP 维度放宽）",
        codes[0] == 200 and codes[-1] == 429,
        "连续 3 次 %s" % codes,
    )
    check(
        "用户维度限额未被叠加放大（2/minute 应放行前 2 次）",
        codes[:2] == [200, 200],
        "连续 3 次 %s（若首两次即被拦，说明双装饰器把配额算重了）" % codes,
    )

    codes = probe_codes("", "", "off", "10.0.0.3")
    check(
        "两维都为空时完全关闭限流（原有行为不被破坏）",
        codes == [200, 200, 200],
        "连续 3 次 %s" % codes,
    )

    # ── 汇总 ─────────────────────────────────────────────────────────
    failed = [n for n, ok in RESULTS if not ok]
    print("\n" + "=" * 72)
    print("合计 %d 项，失败 %d 项" % (len(RESULTS), len(failed)))
    if failed:
        print("失败项：")
        for n in failed:
            print("  - %s" % n)
    print("=" * 72)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
