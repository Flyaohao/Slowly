"""部署缺口检查器：把客户端声明的端点打到指定服务器，用状态码判别哪些尚未部署。

## 用途

后端与客户端都改完、本地 OpenAPI 也校验通过之后，**线上是否已经跟上**是另一个问题。
本项目实际遇到过：阶段 1-5 的成果都在本地，线上仍跑着更早的版本，
客户端一升级就出现 404。这个脚本把"线上缺哪些端点"变成一张清单。

## 判别法

用 GET 探测（**只读，不产生写操作**）：

    404         路径不存在（线上版本旧 / 未部署）
    401 / 403   路径存在，只是未授权
    405         路径存在，只是方法不对（用 GET 探 POST 端点时会这样）
    200 / 422   路径存在

只发 GET 是关键：即便目标端点是 POST/PUT/DELETE，用 GET 探测也只会得到 405，
不会真的写入数据。

## 运行

必须显式指定地址，避免误打生产环境：

    cd backend && python tests/probe_online_endpoints.py --base http://<host>:<port>

也可用环境变量：

    COUPLE_PROBE_BASE=http://<host>:<port> python tests/probe_online_endpoints.py
"""
import concurrent.futures as cf
import os
import re
import subprocess
import sys

from test_api_contract import ANDROID_DIR, scan_client_endpoints, normalize


def build_targets():
    """把客户端端点整理成 {真实路径: 方法集合}，路径参数填 1 便于探测。"""
    targets = {}
    for _rel, method, raw in scan_client_endpoints():
        if not raw or raw.startswith(("http://", "https://")):
            continue
        p = raw.split("?")[0].lstrip("/")
        if not p.startswith("api/"):
            p = "api/" + p
        p = re.sub(r"\{[^}]+\}", "1", p)
        targets.setdefault("/" + p, set()).add(method)
    return targets


def probe(base, item):
    path, methods = item
    try:
        out = subprocess.run(
            ["curl", "-s", "-o", os.devnull, "-w", "%{http_code}",
             "-m", "8", base.rstrip("/") + path],
            capture_output=True, text=True, timeout=20,
        )
        return path, sorted(methods), out.stdout.strip()
    except Exception as exc:  # noqa: BLE001
        return path, sorted(methods), "ERR:%s" % exc


def main():
    base = os.getenv("COUPLE_PROBE_BASE", "")
    if "--base" in sys.argv:
        i = sys.argv.index("--base")
        if i + 1 < len(sys.argv):
            base = sys.argv[i + 1]
    if not base:
        print("未指定目标地址，已跳过。")
        print("用法：python tests/probe_online_endpoints.py --base http://<host>:<port>")
        return 0

    if not os.path.isdir(ANDROID_DIR):
        print("未找到 android/ 目录，无法收集客户端端点。")
        return 1

    targets = build_targets()
    print("探测目标：%s" % base)
    print("待探测端点：%d" % len(targets))
    print("=" * 74)

    results = []
    with cf.ThreadPoolExecutor(max_workers=12) as ex:
        for r in ex.map(lambda it: probe(base, it), sorted(targets.items())):
            results.append(r)

    missing, present, other = [], [], []
    for path, methods, code in results:
        if code == "404":
            missing.append((path, methods))
        elif code in ("401", "403", "405", "200", "422"):
            present.append((path, methods))
        else:
            other.append((path, methods, code))

    print("已部署：%d   未部署(404)：%d   其他：%d"
          % (len(present), len(missing), len(other)))
    print("=" * 74)

    if missing:
        print("\n以下端点线上不存在 —— 部署后才会生效：")
        for path, methods in missing:
            print("  %-58s %s" % (path, ",".join(methods)))
    else:
        print("\n线上端点已与客户端完全对齐。")

    if other:
        print("\n以下端点返回了非预期状态码，需人工确认：")
        for path, methods, code in other:
            print("  %-58s %-18s -> %s" % (path, ",".join(methods), code))

    print("=" * 74)
    return 0


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    sys.exit(main())
