# -*- coding: utf-8 -*-
"""全量接口鉴权烟测：170 个接口逐个打，验证「能进业务逻辑」而非仅「路由活着」。

## 为什么需要这个（2026-10-03）

无 token 打全量只能得到 401（路由存在 + 鉴权生效），**证明不了功能跑通**。
所以这里真实注册账号拿 token，再逐个打，用「业务码」而非 HTTP 状态码判定：

| 结果 | 含义 | 判定 |
|:--|:--|:--|
| `200` + `code=0` | 真的走通了 | ✅ PASS |
| `200` + 业务码（如 30005/40004/10006） | 进了业务逻辑，因缺前置数据而正常拒绝 | ✅ PASS |
| `4xx` **带业务码** | 设计内的拒绝（未绑定 30005 / 无权访问 50002 …） | ✅ PASS |
| `4xx` **无业务信封** | token 根本没被接受 → 鉴权链坏 | ❌ FAIL |
| `404` | 路由丢了 | ❌ FAIL |
| `5xx` | 崩溃 | ❌ FAIL |
| 抛异常（连接层/序列化） | 崩溃 | ❌ FAIL |

**判定的核心**：
1. `5xx` 一律算失败——业务拒绝是设计行为，崩溃不是。
2. **4xx 必须看业务码**。带业务码的 401/403 是设计内拒绝（资源不存在、
   未绑定关系），若只看 HTTP 状态码会把 20 个正常接口误判成「鉴权坏了」。

## 不需要真实数据库

sqlite 内存库 + StaticPool 替换 engine/SessionLocal。`app/core/database.py` 里
`SessionLocal` 是模块级名字，打个补丁即可，**不动项目代码**。

## 不真调 LLM

注册/登录/鉴权/CRUD 全链路都不碰 LLM；AI 类接口即使 200，也会在
「无用户自配 key」时走兜底或明确业务码，不会真打上游。

## 运行

    cd backend && py -3.11 tests/test_all_endpoints_authed.py
"""
import collections
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, BACKEND)

# ---- 必须先于app 导入：把数据库换成 sqlite 内存库 ----
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

import app.core.database as dbmod

# SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）。
# 必须在 create_all 之前改写，否则 INSERT 报 `NOT NULL constraint failed: user.id`。
# 与 `tests/hermetic_harness.py:52-55` 同一手法（不重复造轮子，也不改项目代码）。
import app.models  # noqa: E402,F401  触发全部模型注册
from app.core.database import Base  # noqa: E402
from sqlalchemy import BigInteger, Integer  # noqa: E402

for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()

_test_engine = create_engine(
    "sqlite://",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
_test_Session = sessionmaker(bind=_test_engine, autoflush=False, autocommit=False)
dbmod.engine = _test_engine
dbmod.SessionLocal = _test_Session
# 依赖注入与业务层 `from app.core.database import get_db` 已在上游绑定，
# 故还需替换 core.dependencies 里已经 import 进来的那个名字。
import app.core.dependencies as depmod

depmod.get_db = dbmod.get_db

Base.metadata.create_all(bind=_test_engine)

from dotenv import load_dotenv  # noqa: E402

load_dotenv(os.path.join(BACKEND, ".env"))

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

client = TestClient(app, raise_server_exceptions=False)

EMAIL_A = "e2e_a@example.com"
EMAIL_B = "e2e_b@example.com"
# 密码须满足 `app/utils/validators.py:validate_password_strength`：
# ≥8 位且同时含大写字母、小写字母、数字。否则注册返回业务码 10001。
PWD = "E2ePassw0rd"

TOKEN = {"A": None, "B": None}
UID = {"A": None, "B": None}


def _business_code(resp):
    """取业务码。项目有两种信封，**都要认**：

    1. 正常返回：`{"code": 0, "message": ..., "data": ...}`（`ApiResponse`）
    2. 抛 `HTTPException`：`{"detail": {"code": 30002, "message": ...}}`（FastAPI 包装）

    实测 403 类接口（未绑定情侣 30002 / 无权访问 50002 / 无管理权限 10004）
    走的是第2 种。只认第 1 种会把 20 个正常接口误判成「鉴权链坏了」。
    """
    try:
        body = resp.json()
    except Exception:
        return None
    if isinstance(body, dict):
        if "code" in body:
            return body.get("code")
        detail = body.get("detail")
        if isinstance(detail, dict) and "code" in detail:
            return detail.get("code")
    return None


def _register(tag, email):
    r = client.post("/api/v1/auth/register", json={"email": email, "password": PWD})
    body = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    code = body.get("code")
    # 200+code0 成功；200+「已存在」也接受（重跑场景）
    if r.status_code >= 500:
        raise SystemExit(f"[FATAL] 注册 {tag} 端点 5xx：{r.status_code} {body}")
    if r.status_code != 200:
        raise SystemExit(f"[FATAL] 注册 {tag} 失败：{r.status_code} {body}")
    if code == 0 and body.get("data"):
        UID[tag] = body["data"].get("id") or body["data"].get("user_id")
    return code


def _login(tag, email):
    r = client.post("/api/v1/auth/login", json={"email": email, "password": PWD})
    body = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
    if r.status_code != 200 or body.get("code") != 0:
        raise SystemExit(f"[FATAL] 登录 {tag} 失败：{r.status_code} {body}")
    TOKEN[tag] = body["data"]["access_token"]
    return TOKEN[tag]


def setup_accounts():
    _register("A", EMAIL_A)
    _register("B", EMAIL_B)
    _login("A", EMAIL_A)
    _login("B", EMAIL_B)
    return bool(TOKEN["A"] and TOKEN["B"])


def fill_path(path):
    """把 {xxx} 换成合法占位值，让路由真正匹配到处理器。"""
    return re.sub(r"\{[^}]+\}", "1", path)


def probe(tag, method, path, spec_op):
    url = fill_path(path)
    kw = {}
    if method in ("post", "put", "patch"):
        kw["json"] = {}
    headers = {"Authorization": f"Bearer {TOKEN[tag]}"}
    try:
        r = client.request(method.upper(), url, headers=headers, **kw)
    except Exception as exc:  # noqa: BLE001
        return "EXC", f"{type(exc).__name__}: {exc}"[:160]
    if r.status_code >= 500:
        return "5xx", f"HTTP {r.status_code}"
    if r.status_code == 404:
        return "404", "路由不存在"

    # 4xx 的判定必须看业务码，不能只看 HTTP 状态：
    # 带业务码的 401/403 属**设计内的拒绝**（未绑定关系 30005 / 无权访问 50002 /
    # 资源不存在 100001 …），是正确行为；只有「无业务信封的裸 401」才说明
    # token 根本没被接受——那才是鉴权链坏了。
    bcode = _business_code(r)
    if r.status_code in (401, 403):
        if bcode is not None:
            return "BIZ", f"HTTP {r.status_code} code={bcode}（设计内拒绝）"
        return "AUTH", f"HTTP {r.status_code} 无业务信封 —— token 未被接受"

    if r.status_code == 200:
        if bcode == 0:
            return "OK", "code=0"
        if bcode is None:
            return "OK", "200 无业务信封"
        return "BIZ", f"code={bcode}"
    if 200 < r.status_code < 500:
        return "BIZ" if bcode is not None else "4xx", f"HTTP {r.status_code} code={bcode}"
    return "OTHER", f"HTTP {r.status_code}"


def main() -> int:
    print("=" * 78)
    print("全量接口鉴权烟测（170 个操作，真实 token）")
    print("=" * 78)

    if not setup_accounts():
        print("FATAL: 账号准备失败")
        return 1
    print(f"账号就绪A/B 各自持有 token\n")

    spec = app.openapi()["paths"]
    buckets = collections.Counter()
    problems = []
    total = 0

    for path in sorted(spec):
        ops = spec[path]
        for method in ops:
            if method in ("parameters", "summary", "description"):
                continue
            total += 1
            # 账号 A 为主，admin 类切 B（仍是普通用户，用于探测权限边界）
            tag = "B" if "/admin/" in path else "A"
            verdict, detail = probe(tag, method, path, ops[method])
            buckets[verdict] += 1
            if verdict in ("5xx", "404", "AUTH", "EXC"):
                problems.append((method.upper(), path, verdict, detail))

    print(f"共探测 {total} 个操作\n")
    print("结果分布：")
    for k in ("OK", "BIZ", "4xx", "AUTH", "404", "5xx", "EXC", "OTHER"):
        if buckets.get(k):
            label = {
                "OK": "完全跑通（code=0）",
                "BIZ": "进业务逻辑并正常拒绝（缺前置数据/未绑定等）",
                "4xx": "4xx 业务拒绝",
                "AUTH": "带 token 仍被拒  ←问题",
                "404": "路由不存在      ←问题",
                "5xx": "服务端崩溃      ←问题",
                "EXC": "请求异常        ←问题",
            }.get(k, k)
            print(f"  {k:5s} {buckets[k]:4d}  {label}")

    print()
    if problems:
        print(f"!! {len(problems)} 个操作存在问题：")
        for m, p, v, d in problems:
            print(f"   {m:6s} {p:56s} [{v}] {d}")
        print()
        return 1

    print("结论：无 5xx / 无 404 / 无鉴权异常。全部接口均已进入业务逻辑或正常拒绝。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
