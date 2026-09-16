"""客户端 ↔ 后端 端点契约测试（静态分析，不需要 API Key、不需要数据库）

## 为什么需要这个测试

Android 客户端用 Retrofit 声明路径（`@GET("api/v1/couple/ai/chat")`），
后端用 FastAPI 注册路由。两边是**两处独立维护**的字符串，
一旦漂移，客户端请求会拿到 404 —— 而且前端往往把它当成"网络错误"笼统提示，
从日志里很难看出是路径写错了。

2026-09-14 实际踩到过：v2.0 把 AI 接口拆到 `/api/v1/couple/ai/*` 后，
客户端 `core` 那套仍指向旧的 `/api/v1/ai/*`，导致 AI 主链路整体 404，
而 `feature/couple` 那套已经改对了 —— **只有一半漏改**，
逐文件看很难发现，全量比对才一眼看出来。

本脚本把两边的路径集合拉出来做全量比对，把这类漂移变成可复现的失败。

## 比对方式

1. 后端：直接取 `app.main.app.openapi()["paths"]`，即运行时真实注册的路径。
2. 客户端：扫描 `android/**/*.kt` 中所有 `@GET/@POST/@PUT/@DELETE/@PATCH("...")`
   （跳过 `build/` 目录）。
3. 归一化后比对：
   - 去掉查询串与首尾 `/`
   - 路径参数统一成 `{}`（客户端写 `{id}`、后端写 `{session_id}` 视为同一个位置参数）
   - 客户端未以 `api/` 开头的相对路径，按项目约定补 `api/` 前缀再比

## 运行

    cd backend && python tests/test_api_contract.py

Android 目录不存在时（例如只拷贝了 backend 做容器构建）跳过校验并以 0 退出。
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(BACKEND_DIR)
ANDROID_DIR = os.path.join(REPO_ROOT, "android")

METHODS = ("GET", "POST", "PUT", "DELETE", "PATCH")
RETROFIT_RE = re.compile(r"@(%s)\(\s*\"([^\"]*)\"" % "|".join(METHODS))


def normalize(path: str) -> str:
    """去掉查询串、首尾斜杠，并把路径参数统一成 {}。"""
    p = path.split("?")[0].strip()
    p = p.lstrip("/")
    p = re.sub(r"\{[^}]+\}", "{}", p)
    return p


def load_backend_paths():
    """返回 {归一化路径: 真实路径}。"""
    sys.path.insert(0, BACKEND_DIR)
    cwd = os.getcwd()
    os.chdir(BACKEND_DIR)
    try:
        from app.main import app  # noqa: E402

        paths = {}
        for p in app.openapi()["paths"]:
            paths[normalize(p)] = p
        return paths
    finally:
        os.chdir(cwd)


def scan_client_endpoints():
    """返回 [(相对文件路径, 方法, 原始路径)]。"""
    found = []
    for dirpath, _dirs, files in os.walk(ANDROID_DIR):
        if "build" in dirpath.split(os.sep):
            continue
        for name in files:
            if not name.endswith(".kt"):
                continue
            fp = os.path.join(dirpath, name)
            src = io.open(fp, encoding="utf-8", errors="replace").read()
            rel = os.path.relpath(fp, REPO_ROOT).replace("\\", "/")
            for m in RETROFIT_RE.finditer(src):
                found.append((rel, m.group(1), m.group(2)))
    return found


def main():
    if not os.path.isdir(ANDROID_DIR):
        print("未找到 android/ 目录，跳过客户端契约校验。")
        return 0

    backend_paths = load_backend_paths()
    client_endpoints = scan_client_endpoints()

    missing = []
    matched = {}
    skipped = 0
    for rel, method, raw in client_endpoints:
        if not raw or raw.startswith(("http://", "https://")):
            skipped += 1
            continue
        norm = normalize(raw)
        candidates = [norm]
        if not norm.startswith("api/"):
            candidates.append("api/" + norm)
        # 客户端把路径参数**写死成实参**也是合法调用（如 generations/letter_analysis
        # 对应后端的 generations/{kind}）。归一化只统一 `{}` 的形状，认不出这种，
        # 于是把末尾若干段逐个换成 `{}` 再试一次。
        segs = norm.split("/")
        for i in range(len(segs) - 1, 0, -1):
            candidates.append("/".join(segs[:i] + ["{}"] + segs[i + 1:]))
        hit = next((c for c in candidates if c in backend_paths), None)
        if hit:
            matched.setdefault(hit, []).append((rel, method, raw))
        else:
            missing.append((rel, method, raw))

    print("=" * 74)
    print("后端 OpenAPI 路径数：%d" % len(backend_paths))
    print("客户端声明端点数：%d（跳过外部 URL %d 个）" % (len(client_endpoints), skipped))
    print("能对上：%d   对不上：%d" % (len(matched), len(missing)))
    print("=" * 74)

    unused = sorted(set(backend_paths) - set(matched))
    if unused:
        print("\n后端有、客户端未使用（仅提示，不判失败，共 %d 条）：" % len(unused))
        for n in unused:
            print("  " + backend_paths[n])

    if missing:
        print("\n发现 %d 处客户端端点在后端不存在（请求会 404）：" % len(missing))
        for rel, method, raw in sorted(set(missing)):
            print("  [FAIL] %-6s %-50s %s" % (method, raw, rel))
        print("=" * 74)
        return 1

    print("\n客户端全部端点在后端均已注册 —— 端点契约一致。")
    print("=" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
