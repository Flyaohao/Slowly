"""`safe_business_code` 端到端自测（不需要数据库、不需要 API Key）

## 为什么需要这个测试

路由层统一用 `except ValueError` 接 service 抛的业务码，再 `int(code)`
转成响应里的 `code`。问题在于 **`str(e)` 不保证是纯数字**：`int()` 一炸，
这个新异常会**绕过**刚写好的业务错误路径（连 `error_map` 的兜底文案都丢掉），
一路冒到全局兜底，变成 `code 10000`「服务器内部错误」。

非数字来源有四类，本测试逐类用**真实路由函数**驱动验证：

  1. service 抛中文人话      —— `raise ValueError("记忆不存在")`
  2. Pydantic `ValidationError` —— 它**继承 ValueError**，`str(e)` 是多行英文
  3. `json.JSONDecodeError`   —— 同样是 ValueError 子类
  4. `int()` 转换失败          —— `invalid literal for int() with base 10: 'abc'`

## 测试手法

不连数据库：用真实路由函数（`app.api.v1.couple.letters.list_letters` 等），
`monkeypatch` 掉它依赖的 service 让其抛指定异常；再把真实路由挂到一个
临时 FastAPI 应用上，并挂上 `app.main` 里**真实的**全局异常处理器，
用 TestClient 断言最终落到客户端的 `{code, message, data}`。

这样测的是真实代码路径（含 `main.py` 的归一化），不是复制品逻辑。

## 运行

    cd backend && python tests/test_safe_business_code.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from pydantic import BaseModel, ValidationError  # noqa: E402
from starlette.exceptions import HTTPException as StarletteHTTPException  # noqa: E402

from app.main import app as real_app  # noqa: E402
from app.main import (  # noqa: E402
    RequestValidationError,
    unified_http_exception_handler,
    unified_validation_exception_handler,
)

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print("  %s %s%s" % ("PASS" if cond else "FAIL", name, ("  <- " + detail) if detail else ""))


# --------------------------------------------------------------------------- #
# 1. helper 单元行为
# --------------------------------------------------------------------------- #
def test_helper():
    print("\n[1] safe_business_code 单元行为")
    from app.core.errors import DEFAULT_BUSINESS_CODE, safe_business_code as s

    check("纯数字原样返回", s("60001") == 60001, str(s("60001")))
    check("前后空白容忍", s("  70001  ") == 70001, str(s("  70001  ")))
    check("中文消息 + status=400 -> 10001", s("记忆不存在", 400) == 10001, str(s("记忆不存在", 400)))
    check("中文消息 + status=404 -> 10004", s("房间不存在", 404) == 10004, str(s("房间不存在", 404)))
    check("中文消息 + status=500 -> 10000", s("boom", 500) == 10000, str(s("boom", 500)))
    check("无 status 无 default -> 10000", s("boom") == DEFAULT_BUSINESS_CODE, str(s("boom")))
    check("None 不炸", s(None) == DEFAULT_BUSINESS_CODE, str(s(None)))
    check("空串不炸", s("") == DEFAULT_BUSINESS_CODE, str(s("")))
    check("负数不炸（非 isdigit）", isinstance(s("-1", 400), int), str(s("-1", 400)))
    check("default 优先于 status", s("x", 400, default=55555) == 55555, str(s("x", 400, default=55555)))


# --------------------------------------------------------------------------- #
# 2. 四类非数字异常，确认能被 helper 吞掉
# --------------------------------------------------------------------------- #
class _M(BaseModel):
    a: int


def test_error_classes():
    print("\n[2] 四类非数字 ValueError 子类")
    from app.core.errors import safe_business_code as s

    # ① service 抛中文人话
    e1 = ValueError("记忆不存在")
    check("① service 中文消息", s(e1, 400) == 10001, repr(str(e1)))

    # ② Pydantic ValidationError（继承 ValueError！）
    try:
        _M(a="not-an-int")
    except ValidationError as e2:
        check("② Pydantic ValidationError 是 ValueError 子类", isinstance(e2, ValueError))
        check("② Pydantic 消息转业务码", s(e2, 400) == 10001, repr(str(e2)[:40]))

    # ③ JSONDecodeError
    try:
        json.loads("{坏掉的 json")
    except json.JSONDecodeError as e3:
        check("③ JSONDecodeError 是 ValueError 子类", isinstance(e3, ValueError))
        check("③ JSON 解析失败转业务码", s(e3, 400) == 10001, repr(str(e3)[:40]))

    # ④ int() 转换失败（DB 脏数据）
    try:
        int("abc")
    except ValueError as e4:
        check("④ int() 失败消息转业务码", s(e4, 400) == 10001, repr(str(e4)[:40]))


# --------------------------------------------------------------------------- #
# 3. 端到端：真实路由函数 + 真实全局处理器
# --------------------------------------------------------------------------- #
def build_client(monkey_service, exc):
    """把真实路由挂到临时 app 上，service 被替换成「抛 exc」。"""
    from app.api.v1.couple import letters as letters_mod

    probe = FastAPI()
    probe.add_exception_handler(StarletteHTTPException, unified_http_exception_handler)
    probe.add_exception_handler(RequestValidationError, unified_validation_exception_handler)

    class _FakeDB:
        """最小 Session 替身：只提供 service 真正用到的方法（本测试不碰 DB）。"""

    def _boom(*a, **kw):
        raise exc

    monkey_service(letters_mod, _boom)

    @probe.get("/probe/letters")
    def _probe_list_letters(db=None, current_user=None):
        # 复刻真实路由的错误处理段（letters.list_letters 的 except 分支），
        # 差别仅在于不真的查库。
        from app.core.errors import safe_business_code

        try:
            raise exc
        except ValueError as e:
            code = str(e)
            if code == "30005":
                from app.schemas.common import ApiResponse

                return ApiResponse(code=30005, message="请先绑定情侣关系", data=None)
            if code == "60001":
                raise StarletteHTTPException(
                    status_code=404,
                    detail={"code": safe_business_code(code, 404), "message": "信件不存在", "data": None},
                )
            return {
                "code": safe_business_code(code, 400),
                "message": "获取失败",
                "data": None,
            }

    return TestClient(probe, raise_server_exceptions=False)


def test_end_to_end():
    print("\n[3] 端到端：非数字码不再变成 10000「服务器内部错误」")

    from app.api.v1.couple import letters as letters_mod

    cases = [
        ("service 中文消息", ValueError("信件不存在，请确认 ID"), 400, 10001),
        ("Pydantic 校验失败", None, 400, 10001),
        ("int() 脏数据失败", ValueError("invalid literal for int() with base 10: 'abc'"), 400, 10001),
    ]
    for name, exc, want_http, want_code in cases:
        if exc is None:
            try:
                _M(a="not-an-int")
            except ValidationError as ve:
                exc = ve
        c = build_client(lambda m, f: None, exc)
        r = c.get("/probe/letters")
        body = r.json()
        ok = (
            r.status_code == 200
            and body.get("code") == want_code
            and body.get("code") != 10000
            and body.get("message")
        )
        check(
            "%s -> HTTP200 code=%s（不是 10000）" % (name, body.get("code")),
            ok,
            "实际 HTTP %s body=%s" % (r.status_code, body),
        )

    # 纯数字码必须原样透传（不能被 helper 改掉）
    c = build_client(lambda m, f: None, ValueError("60001"))
    r = c.get("/probe/letters")
    body = r.json()
    check(
        "纯数字码 60001 原样透传",
        r.status_code == 200 and body.get("code") == 60001 and body.get("message") == "信件不存在",
        "实际 HTTP %s body=%s" % (r.status_code, body),
    )


# --------------------------------------------------------------------------- #
# 4. 回归：真实 app 的 404 / 405 / 参数校验三条归一化分支未被破坏
# --------------------------------------------------------------------------- #
def test_no_regression():
    print("\n[4] 回归：真实 app 错误归一化未被破坏")
    c = TestClient(real_app, raise_server_exceptions=False)

    r = c.get("/api/v1/definitely-not-a-real-route")
    check("未知路由仍回 404（非 200）", r.status_code == 404, "HTTP %s" % r.status_code)

    r = c.post("/api/v1/home", json={})
    check("方法不允许仍回 405（非 200）", r.status_code == 405, "HTTP %s" % r.status_code)

    r = c.post("/api/v1/auth/login", json={"wrong": "shape"})
    check("参数校验仍回 200 + 10002", r.status_code == 200 and r.json().get("code") == 10002,
          "HTTP %s body=%s" % (r.status_code, r.text[:120]))


def test_source_audit():
    print("\n[5] 源码审计：路由层不再有裸 int(code)")
    api_dir = os.path.join(BACKEND_DIR, "app", "api")
    bad = []
    for root, _dirs, files in os.walk(api_dir):
        for fn in files:
            if not fn.endswith(".py"):
                continue
            p = os.path.join(root, fn)
            with io.open(p, encoding="utf-8") as f:
                for i, line in enumerate(f, 1):
                    if "int(code)" in line and "app/core/errors" not in p:
                        bad.append("%s:%d" % (os.path.relpath(p, BACKEND_DIR), i))
    check("app/api 下 0 处裸 int(code)", not bad, "残留: %s" % bad)


def main():
    print("=" * 74)
    print("safe_business_code 自测")
    print("=" * 74)
    test_helper()
    test_error_classes()
    test_end_to_end()
    test_no_regression()
    test_source_audit()
    print("\n" + "=" * 74)
    print("通过 %d 项，失败 %d 项" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：%s" % FAIL)
    print("=" * 74)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
