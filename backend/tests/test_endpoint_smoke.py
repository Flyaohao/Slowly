"""全端点冒烟遍历：按 OpenAPI schema 自动构造最小请求，逐个打一遍。

用法：
    python tests/test_endpoint_smoke.py              # 用 TestClient 直连 app，不走网络
    python tests/test_endpoint_smoke.py --ai         # 连带真实调用 AI（慢，约 1-3 分钟一个场景）
    python tests/test_endpoint_smoke.py --filter museum,couples   # 只跑路径包含关键字的端点

前置：本机 MySQL 可用（/api/v1/users/me 能返回数据）。
说明：目的是发现 5xx 与契约错误。404/ permission 多为上下文缺失（未建资源、未绑定），记为 WARN 不算失败。
"""

import argparse
import datetime
import random
import re
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

client = TestClient(app)
# schema 直接从 app 取，不走 HTTP：DocsGuardMiddleware 对 /openapi.json 默认拒绝
# （未配置口令一律 401），裸 GET 拿到的是空 body。探测端点仍走 TestClient。
SCHEMA = app.openapi()
PATHS = SCHEMA["paths"]


def resolve(schema):
    while "$ref" in schema:
        ref = schema["$ref"].split("/")[-1]
        schema = SCHEMA["components"]["schemas"][ref]
    return schema


CTX = {"token": None, "token_b": None}

TEXT = "测试内容"
NAME_OVERRIDES = {
    "email": None,
    "password": "Test@123456",
    "nickname": "测试用户",
    "username": "测试用户",
    "phone": "13800000000",
    "invite_code": None,
    "content": TEXT,
    "title": "测试标题",
    "description": "测试描述",
    "body": TEXT,
    "answer": TEXT,
    "reason": TEXT,
    "note": TEXT,
    "remark": TEXT,
    "avatar": "default",
    "url": "https://example.com/a.png",
    "event_time": None,
    "scene_key": "private_advisor",
    "gender": "female",
    "new_password": "Test@123456",
    "code": None,
    "old_password": "Test@123456",
    "refresh_token": None,
}


def _iso():
    return datetime.datetime.now().replace(microsecond=0).isoformat()


def gen_value(name, schema):
    if name == "email":
        return f"smoke{random.randint(10000, 99999)}@example.com"
    if name == "refresh_token" and CTX.get("refresh_token"):
        return CTX["refresh_token"]
    if name in NAME_OVERRIDES and NAME_OVERRIDES[name] is not None:
        return NAME_OVERRIDES[name]
    if name in CTX and isinstance(CTX[name], (str, int)):
        return CTX[name]
    s = resolve(schema)
    if "enum" in s and s["enum"]:
        return s["enum"][0]
    t = s.get("type")
    fmt = s.get("format")
    if "anyOf" in s or "oneOf" in s:
        for sub in s.get("anyOf", []) + s.get("oneOf", []):
            r = resolve(sub)
            if r.get("type") != "null":
                return gen_value(name, r)
    if fmt == "date-time":
        return _iso()
    if fmt == "date":
        return datetime.date.today().isoformat()
    if t == "integer":
        return 1
    if t == "number":
        return 1.0
    if t == "boolean":
        return True
    if t == "array":
        return []
    if t == "object":
        return {}
    if name.endswith("_id") or name == "id":
        return 1
    return TEXT


def is_multipart(request_body):
    return any("multipart" in mt or "urlencoded" in mt for mt in request_body.get("content", {}))


def build_body(request_body):
    content = request_body.get("content", {})
    mt = next(iter(content), None)
    if mt is None:
        return None
    schema = resolve(content[mt]["schema"])
    if schema.get("type") != "object" or "properties" not in schema:
        return {}
    props = schema["properties"]
    required = schema.get("required", list(props.keys()))
    return {k: gen_value(k, props[k]) for k in required if k in props}


def build_query(parameters):
    q = {}
    for p in parameters:
        if p["in"] != "query":
            continue
        name = p["name"]
        if p.get("required"):
            q[name] = gen_value(name, p.get("schema", {}))
    return q or None


def fill_path(path):
    def repl(m):
        key = m.group(1)
        return str(CTX.get(key, 1))

    return re.sub(r"\{([^}]+)\}", repl, path)


def call(method, path, body=None, query=None, use_token=True):
    headers = {}
    if use_token and CTX.get("token"):
        headers["Authorization"] = f"Bearer {CTX['token']}"
    try:
        if method == "get":
            return client.get(path, params=query, headers=headers)
        if method == "delete":
            return client.delete(path, headers=headers)
        if method == "put":
            return client.put(path, json=body, headers=headers)
        return client.post(path, json=body, params=query, headers=headers)
    except Exception as e:  # noqa: BLE001
        return e


def _apply_login(data, prefix=""):
    if not isinstance(data, dict):
        return
    for key in ("access_token", "token"):
        if key in data and isinstance(data[key], str):
            CTX[f"{prefix}token"] = data[key]
    nested = data.get("user") if isinstance(data.get("user"), dict) else None
    for key, val in (nested or data).items():
        if key.endswith("_id") or key == "id":
            CTX[key] = val


def bootstrap(suffix):
    """注册两个用户并完成绑定，填充 CTX。"""
    steps = []

    a_mail = f"smoke_a_{suffix}@t.com"
    b_mail = f"smoke_b_{suffix}@t.com"
    for mail in (a_mail, b_mail):
        r = client.post("/api/v1/auth/register", json={"email": mail, "password": "Test@123456", "nickname": "冒烟用户"})
        steps.append(("POST", "/api/v1/auth/register", _sum(r)))

    token_a = token_b = None
    for mail in (a_mail, b_mail):
        r = client.post("/api/v1/auth/login", json={"email": mail, "password": "Test@123456"})
        data = (r.json() or {}).get("data") or {}
        tok = data.get("access_token") or data.get("token")
        if data.get("refresh_token"):
            CTX["refresh_token"] = data["refresh_token"]
        if mail == a_mail:
            token_a = tok
        else:
            token_b = tok
        steps.append(("POST", "/api/v1/auth/login", _sum(r)))

    # 默认身份用 B（绑定后处于关系内）；邀请码必须由 A 生成，否则会判「不能绑定自己」
    CTX["token"] = token_b
    CTX["token_a"] = token_a
    CTX["token_b"] = token_b

    r = client.get("/api/v1/users/me", headers={"Authorization": f"Bearer {token_a}"})
    me = (r.json() or {}).get("data") or {}
    CTX["user_id"] = me.get("id", 1)
    steps.append(("GET", "/api/v1/users/me", _sum(r)))

    r = client.post("/api/v1/couples/invite", headers={"Authorization": f"Bearer {token_a}"})
    data = (r.json() or {}).get("data") or {}
    CTX["invite_code"] = data.get("invite_code") or data.get("code")
    steps.append(("POST", "/api/v1/couples/invite", _sum(r)))

    r = client.post(
        "/api/v1/couples/bind",
        json={"invite_code": CTX["invite_code"]},
        headers={"Authorization": f"Bearer {token_b}"},
    )
    steps.append(("POST", "/api/v1/couples/bind", _sum(r)))

    r = client.get("/api/v1/couples/me", headers={"Authorization": f"Bearer {token_b}"})
    rel = (r.json() or {}).get("data") or {}
    if isinstance(rel, dict):
        for k in ("id", "relation_id"):
            if k in rel:
                CTX["relation_id"] = rel[k]
    steps.append(("GET", "/api/v1/couples/me", _sum(r)))
    return steps


def _sum(resp):
    if isinstance(resp, Exception):
        return (500, -1, f"未捕获异常: {type(resp).__name__}: {resp}")
    try:
        payload = resp.json()
    except Exception:  # noqa: BLE001
        return (resp.status_code, -1, (resp.text or "")[:80])
    if isinstance(payload, dict) and "code" in payload:
        return (resp.status_code, payload.get("code"), str(payload.get("message"))[:60])
    return (resp.status_code, -1, (resp.text or str(payload))[:80])


METHOD_ORDER = {"post": 0, "put": 1, "get": 2, "delete": 3}
DESTRUCTIVE = ("delete",)
AI_PATHS = ("/ai/chat", "/ai/chat/stream", "/ai/rewrite", "/ai/understand-letter", "/ai/rewrite-letter",
            "/ai/generate-reply", "/ai/mediation", "/ai/agent", "/profiles/me/ai-report")
NO_AUTH = ("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/forgot-password")
# 这些业务码出现=资源不存在/无权限/入参不合规则，属脚本占位数据导致的预期结果
RESOURCE_CODES = {
    20002, 20005, 30002, 30004, 30005, 40002, 40004, 40005,
    50002, 50003, 60001, 70002, 80001, 90003, 100001,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ai", action="store_true", help="包含真实 AI 调用（慢）")
    ap.add_argument("--filter", default="", help="只跑路径包含这些关键字的端点，逗号分隔")
    args = ap.parse_args()

    suffix = random.randint(10000, 99999)
    rows = []

    print("前置：注册两个测试账号并完成绑定")
    for row in bootstrap(suffix):
        rows.append(row)
        print(f"  {row[0]:6s} {row[1]:45s} -> HTTP {row[2][0]} code={row[2][1]} {row[2][2]}")

    items = []
    for path, methods in PATHS.items():
        for method, spec in methods.items():
            items.append((method.lower(), path, spec))
    items.sort(key=lambda x: (METHOD_ORDER.get(x[0], 9), x[1]))

    filters = [f for f in args.filter.split(",") if f]
    manual = []

    print("\n逐个端点遍历")
    print("-" * 96)
    for method, path, spec in items:
        if filters and not any(f in path for f in filters):
            continue
        if not args.ai and any(p in path for p in AI_PATHS):
            continue
        if path == "/api/v1/couple/ai/ws" or "websocket" in spec:
            continue
        rb = spec.get("requestBody", {})
        if is_multipart(rb):
            manual.append((method.upper(), real_path, "multipart/form-data，脚本不覆盖"))
            print(f" -- {method.upper():6s} {real_path:52s} 跳过：文件上传需人工或用 curl 测")
            continue
        use_token = path not in NO_AUTH
        body = build_body(spec.get("requestBody", {}))
        query = build_query(spec.get("parameters", []))
        real_path = fill_path(path)
        resp = call(method, real_path, body=body, query=query, use_token=use_token)
        status, code, msg = _sum(resp)

        if (resp.status_code if not isinstance(resp, Exception) else 500) < 400:
            try:
                payload = resp.json()
            except Exception:  # noqa: BLE001
                payload = None
            if isinstance(payload, dict):
                d = payload.get("data")
                if isinstance(d, dict):
                    for k, v in d.items():
                        if isinstance(v, (int, str)) and (k.endswith("_id") or k == "id" or k.endswith("Ids")):
                            CTX.setdefault(k, v)
                        if k.endswith("_id") or k == "id":
                            CTX[k] = v
                    for k in ("items", "list", "records"):
                        lst = d.get(k)
                        if isinstance(lst, list) and lst and isinstance(lst[0], dict):
                            for kk, vv in lst[0].items():
                                if kk == "id" or kk.endswith("_id"):
                                    CTX.setdefault(kk, vv)

        rows.append((method.upper(), real_path, (status, code, msg)))
        flag = "  " if status < 400 else ("!!" if status >= 500 else " ?")
        print(f"{flag} {method.upper():6s} {real_path:52s} HTTP {status} code={code} {msg}")

    print("-" * 96)
    if manual:
        print("\n需人工验证（脚本不覆盖）:")
        for m, p, why in manual:
            print(f"  {m} {p} —— {why}")
    total = len(rows)
    bad = [r for r in rows if r[2][0] >= 500]
    # 脚本用占位 id，必然撞到「资源不存在/无权访问」；这些是上下文缺失，不是缺陷
    ctx_missing = [
        r for r in rows
        if r[2][1] in RESOURCE_CODES or (400 <= r[2][0] < 500 and r[2][1] in (-1, 20002, 20005))
    ]
    warn = [r for r in rows if 400 <= r[2][0] < 500 and r not in ctx_missing and r not in bad]
    print(f"总计 {total} 项 ／ 5xx {len(bad)} 项 ／ 待判定 4xx {len(warn)} 项 ／ 占位 id 导致的预期错误 {len(ctx_missing)} 项")
    if bad:
        print("\n需要修的（5xx / 未捕获异常）:")
        for m, p, s in bad:
            print(f"  {m} {p} -> {s[2]}")
    if warn:
        print("\n4xx（多数为缺少上下文数据，需人工判断是否真缺陷）:")
        for m, p, s in warn:
            print(f"  {m} {p} -> HTTP {s[0]} code={s[1]} {s[2]}")


if __name__ == "__main__":
    main()
