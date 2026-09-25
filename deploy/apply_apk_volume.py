"""在服务器上为「静态资源」补宿主 bind mount（幂等，可重复执行）。

为什么需要单独这一步：
    仓库里的 docker-compose.yml 与线上那份**并不相同** —— 线上多了一段 default
    网络的 subnet 钉子（172.19.0.0/16，宿主 nftables 只放行 172.16.0.0/12 访问
    宿主 3306）。用仓库版本覆盖会把这段抹掉，容器随即连不上宿主 MySQL。
    所以只能就地在线上那份文件里追加。

挂两个目录：
    ./apk                 -> /app/static/apk   安卓安装包
    ./backend/static/app  -> /app/static/app   宣传页

为什么宣传页也要挂：
    宿主 Nginx（宝塔）已经把这两个目录当静态根直接对外服务。容器这边挂同一份，
    「80 端口」与「8000 端口」两个入口读到的内容就永远一致；
    改文案只需重跑构建脚本 + 重新解包，**不必重建镜像**。
    若只在镜像里留一份，两条入口迟早会漂移。

用法（在 /usr/src/couple-deploy 下执行）：
    python3 apply_apk_volume.py

执行后会自动备份原文件为 *.bak-mnt<时间戳>。
"""

import io
import os
import shutil
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
COMPOSE = os.path.join(ROOT, "docker-compose.yml")

# (锚点, 插入内容, 幂等判据)。锚点必须与线上文件逐字一致（6 空格缩进）。
MOUNTS = [
    (
        "      - uploads_data:/app/uploads",
        [
            "      # 安卓安装包目录（宿主 bind mount）：换包只需替换宿主 ./apk 下的文件，",
            "      # 不必重建镜像；20MB 的 APK 也不会进镜像层与部署包。",
            "      - ./apk:/app/static/apk",
        ],
        "static/apk",
    ),
    (
        "      - uploads_data:/app/uploads",
        [
            "      # 宣传页同样走宿主目录：宿主 Nginx 直接发它，容器挂同一份，",
            "      # 保证 80 与 8000 两个入口内容一致，改文案不必重建镜像。",
            "      - ./backend/static/app:/app/static/app",
        ],
        "static/app:/app/static/app",
    ),
]

DIRS = [os.path.join(ROOT, "apk"), os.path.join(ROOT, "backend", "static", "app")]


def backup(path, stamp):
    dst = "%s.bak-mnt%s" % (path, stamp)
    shutil.copy2(path, dst)
    print("  已备份 -> %s" % os.path.basename(dst))


def patch_compose(stamp):
    print("[1/2] docker-compose.yml")
    if not os.path.isfile(COMPOSE):
        print("  [FAIL] 找不到 %s" % COMPOSE)
        return False

    text = io.open(COMPOSE, encoding="utf-8").read()
    added = 0
    for anchor, insert, mark in MOUNTS:
        if mark in text:
            print("  已存在挂载（%s），跳过" % mark)
            continue
        if anchor not in text:
            print("  [FAIL] 未找到锚点行，文件结构与预期不符，未做任何修改：")
            print("         %s" % anchor)
            return False
        if added == 0:
            backup(COMPOSE, stamp)
        text = text.replace(anchor, anchor + "\n" + "\n".join(insert), 1)
        added += 1
        print("  已追加挂载（%s）" % mark)

    if added == 0:
        print("  无需改动")
    else:
        io.open(COMPOSE, "w", encoding="utf-8").write(text)
    return True


def ensure_dirs():
    print("[2/2] 宿主目录")
    for d in DIRS:
        if os.path.isdir(d):
            print("  已存在：%s（%d 个文件）" % (d, len(os.listdir(d))))
        else:
            os.makedirs(d)
            print("  已创建：%s" % d)
    return True


def main():
    stamp = time.strftime("%y%m%d-%H%M%S")
    print("=" * 60)
    print("静态资源挂载补丁  %s" % stamp)
    print("=" * 60)
    ok1 = patch_compose(stamp)
    ok2 = ensure_dirs()

    print()
    print("RESULT=%s" % ("OK" if (ok1 and ok2) else "FAIL"))


if __name__ == "__main__":
    main()
