"""统一错误封装测试（不需要数据库、不需要 API Key）

## 为什么需要这个测试

本项目约定所有业务结果都用 `{code, message, data}` 封装，HTTP 状态码一律 200。
但历史上大量端点写的是 `raise HTTPException(status_code=4xx, detail={...})`，
经 FastAPI 默认处理器后变成 `{"detail": {...}}` + 非 2xx —— 客户端是
Retrofit + Moshi，只会把 2xx 的响应体反序列化成 ApiResponse，非 2xx 直接抛
HttpException，`code` / `message` 全部丢失，界面上只剩「HTTP 400」这种字符串。
（客户端为此写了三套各自为政的兜底：AuthRepository 用 JSONObject 解析、
CoupleRepository 用正则刮 message、AiRepository 按状态码猜中文。）

`app/main.py` 里的异常处理器把这件事收口了，规则有三条，缺一不可：

  1. 401 / 403 原样返回 —— 客户端要靠状态码本身区分「凭证失效」，
     改写成语义相反的 200 会让鉴权分支失效；
  2. `detail` 是字典 → 业务错误 → 归一化成 HTTP 200 + `{code, message, data}`；
  3. `detail` 不是字典 → 框架生成的路由级错误（404 / 405）→ 原样返回，
     否则「接口不存在」会伪装成 200，部署核对与接口探测都失去依据。

第 3 条最容易被误伤：若为了「统一」而无差别地把所有异常都改成 200，
`tests/probe_online_endpoints.py` 这类靠 404 / 405 判别部署状态的工具会直接失效。
本测试逐条守住这三条规则。

## 实现说明

不连数据库：在临时 FastAPI 应用上挂载 `app.main` 里**真实的**处理器函数，
用两条测试路由分别模拟「业务错误（字典 detail）」与「路由级错误（字符串 detail）」，
再由 TestClient 断言；另用真实 app 复查 404 / 405 / 参数校验三条分支。
既测到真实代码，又不依赖任何外部环境。

## 运行

    cd backend && python tests/test_error_envelope.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from fastapi import FastAPI, HTTPException  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from starlette.exceptions import HTTPException as StarletteHTTPException  # noqa: E402

from app.main import (  # noqa: E402
    _PASSTHROUGH_STATUSES,
    _split_detail,
    unified_http_exception_handler,
)
from app.main import app as real_app  # noqa: E402


def build_probe_app() -> FastAPI:
    """临时应用：挂上真实处理器，用假路由模拟两类错误来源。"""
    probe = FastAPI()
    probe.add_exception_handler(StarletteHTTPException, unified_http_exception_handler)

    @probe.get("/biz/400")
    def biz_400():
        raise HTTPException(status_code=400, detail={"code": 30005, "message": "请先绑定情侣关系", "data": None})

    @probe.get("/biz/404")
    def biz_404():
        raise HTTPException(status_code=404, detail={"code": 60001, "message": "信件不存在", "data": None})

    @probe.get("/biz/403")
    def biz_403():
        raise HTTPException(status_code=403, detail={"code": 90003, "message": "无权访问", "data": None})

    return probe


def main():
    results = []

    def check(name, ok, detail=""):
        results.append((name, ok))
        print("%s %s%s" % ("✅" if ok else "❌", name, ("  — " + detail) if detail else ""))

    def json_of(r):
        if r.headers.get("content-type", "").startswith("application/json"):
            return r.json()
        return {}

    probe = TestClient(build_probe_app(), raise_server_exceptions=False)

    # ---- 规则 2：字典 detail 归一化成 HTTP 200 + {code, message, data} ----
    for path, want_code, want_msg in (
        ("/biz/400", 30005, "请先绑定情侣关系"),
        ("/biz/404", 60001, "信件不存在"),
    ):
        r = probe.get(path)
        body = json_of(r)
        check(
            "规则2 业务错误归一化 %s -> HTTP 200 + code %s" % (path, want_code),
            r.status_code == 200 and body.get("code") == want_code and body.get("message") == want_msg,
            "实际 HTTP %s / code %s / message %s" % (r.status_code, body.get("code"), body.get("message")),
        )

    # ---- 规则 1：401 / 403 原样返回 ----
    r = probe.get("/biz/403")
    check("规则1 403 保持原状态码（不归一化）", r.status_code == 403, "实际 HTTP %s" % r.status_code)
    check("规则1 通配集合恰为 {401, 403}",
          _PASSTHROUGH_STATUSES == frozenset({401, 403}),
          str(sorted(_PASSTHROUGH_STATUSES)))

    # ---- 规则 3：非字典 detail（框架路由级错误）原样返回 ----
    r = probe.get("/definitely-not-exist")
    check("规则3 未知路由保持 404", r.status_code == 404, "实际 HTTP %s" % r.status_code)
    r = probe.post("/biz/400")
    check("规则3 方法不允许保持 405", r.status_code == 405, "实际 HTTP %s" % r.status_code)

    # ---- 在真实应用上复查同样三条分支 ----
    real_client = TestClient(real_app, raise_server_exceptions=False)

    r = real_client.get("/api/v1/definitely-not-exist")
    check("真实应用 未知路由保持 404", r.status_code == 404, "实际 HTTP %s" % r.status_code)

    r = real_client.get("/api/v1/auth/login")
    check("真实应用 方法不允许保持 405", r.status_code == 405, "实际 HTTP %s" % r.status_code)

    r = real_client.post("/api/v1/auth/register", json={})
    body = json_of(r)
    check(
        "真实应用 参数校验失败 -> HTTP 200 + code 10002 + 可读提示",
        r.status_code == 200 and body.get("code") == 10002 and "参数校验失败" in (body.get("message") or ""),
        "实际 HTTP %s / code %s / message %s" % (r.status_code, body.get("code"), body.get("message")),
    )

    # ---- _split_detail 的边界 ----
    check("_split_detail 字典含 code",
          _split_detail(400, {"code": 1234, "message": "m", "data": None}) == (1234, "m", None))
    check("_split_detail 字典缺 code 时按状态码兜底", _split_detail(400, {"message": "m"})[0] == 10001)
    check("_split_detail 字符串 detail 原样用作 message", _split_detail(404, "Not Found")[1] == "Not Found")
    check("_split_detail 非字典非字符串兜底", _split_detail(500, None)[1] == "请求失败")

    print("\n" + "=" * 72)
    failed = [n for n, ok in results if not ok]
    if failed:
        print("失败 %d 项：" % len(failed))
        for n in failed:
            print("  " + n)
        print("=" * 72)
        return 1
    print("共 %d 项检查全部通过 —— 三条规则均成立。" % len(results))
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())
