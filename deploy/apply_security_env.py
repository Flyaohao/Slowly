"""在服务器上为「安全加固」补齐 compose 与 .env（幂等，可重复执行）。

为什么需要单独这一步：
    仓库里的 docker-compose.yml 与线上那份**并不相同** —— 线上多了一段 default
    网络的 subnet 钉子（172.19.0.0/16，宿主 nftables 只放行 172.16.0.0/12 访问
    宿主 3306）。直接用仓库版本覆盖会把这段配置抹掉，容器随即连不上宿主 MySQL。
    所以只能就地在线上那份文件里追加新增的环境变量。

    compose 的 environment 是白名单转发：.env 里设了但没在这里列出的变量不会进容器。

用法（在 /usr/src/couple-deploy 下执行）：
    python3 apply_security_env.py

口令可用环境变量覆盖，默认值取自本次加固生成的那份：
    SEC_DOCS_USER=xxx SEC_DOCS_PASS=yyy python3 apply_security_env.py

执行后会自动备份原文件为 *.bak-sec<时间戳>。
"""

import io
import os
import shutil
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
COMPOSE = os.path.join(ROOT, "docker-compose.yml")
ENV_FILE = os.path.join(ROOT, ".env")

DOCS_USER = os.getenv("SEC_DOCS_USER", "admin")
DOCS_PASS = os.getenv("SEC_DOCS_PASS", "RpcN5StpzSZ3h3wvlFPkalOX")

# 插入锚点：与线上文件逐字一致（6 空格缩进）
ANCHOR = "      AI_RATE_LIMIT: ${AI_RATE_LIMIT:-20/hour}"
INSERT = [
    "      # ---- 安全加固（2026-09-26）----",
    "      # AI 端点 IP 维度兜底限流（防批量注册绕过按用户配额）",
    "      AI_IP_RATE_LIMIT: ${AI_IP_RATE_LIMIT:-120/hour}",
    "      # 接口文档保护口令（/docs /redoc /openapi.json），留空则一律 401",
    "      DOCS_USER: ${DOCS_USER:-}",
    "      DOCS_PASS: ${DOCS_PASS:-}",
    "      # 允许跨站访问的来源，默认空 = 不允许任何跨站来源",
    "      CORS_ORIGINS: ${CORS_ORIGINS:-}",
]

ENV_LINES = [
    "",
    "# ---- 安全加固（2026-09-26）----",
    "DOCS_USER=" + DOCS_USER,
    "DOCS_PASS=" + DOCS_PASS,
    "CORS_ORIGINS=",
    "AI_IP_RATE_LIMIT=120/hour",
]


def backup(path, stamp):
    if os.path.isfile(path):
        dst = "%s.bak-sec%s" % (path, stamp)
        shutil.copy2(path, dst)
        print("  已备份 -> %s" % os.path.basename(dst))


def patch_compose(stamp):
    print("[1/2] docker-compose.yml")
    if not os.path.isfile(COMPOSE):
        print("  [FAIL] 找不到 %s" % COMPOSE)
        return False
    text = io.open(COMPOSE, encoding="utf-8").read()

    if "DOCS_USER" in text:
        print("  已包含安全加固变量，跳过")
        return True
    if ANCHOR not in text:
        print("  [FAIL] 未找到锚点行，文件结构与预期不符，未做任何修改：")
        print("         %s" % ANCHOR)
        return False

    backup(COMPOSE, stamp)
    new = text.replace(ANCHOR, ANCHOR + "\n" + "\n".join(INSERT), 1)
    io.open(COMPOSE, "w", encoding="utf-8").write(new)
    print("  已追加 %d 行环境变量" % len(INSERT))
    return True


def patch_env(stamp):
    print("[2/2] .env")
    if not os.path.isfile(ENV_FILE):
        print("  [FAIL] 找不到 %s（线上必须有这份文件）" % ENV_FILE)
        return False
    text = io.open(ENV_FILE, encoding="utf-8").read()

    if "DOCS_PASS" in text:
        print("  已包含 DOCS_PASS，跳过（避免重复追加）")
        return True

    backup(ENV_FILE, stamp)
    if not text.endswith("\n"):
        text += "\n"
    io.open(ENV_FILE, "w", encoding="utf-8").write(text + "\n".join(ENV_LINES) + "\n")
    print("  已追加 %d 行" % len(ENV_LINES))
    return True


def main():
    stamp = time.strftime("%y%m%d-%H%M%S")
    print("=" * 60)
    print("安全加固配置补丁  %s" % stamp)
    print("=" * 60)
    ok1 = patch_compose(stamp)
    ok2 = patch_env(stamp)

    print()
    if ok1 and ok2:
        print("RESULT=OK")
    else:
        print("RESULT=FAIL")


if __name__ == "__main__":
    main()
