"""在服务器上为「安装包下载」补一个宿主 bind mount（幂等，可重复执行）。

为什么需要单独这一步：
    仓库里的 docker-compose.yml 与线上那份**并不相同** —— 线上多了一段 default
    网络的 subnet 钉子（172.19.0.0/16，宿主 nftables 只放行 172.16.0.0/12 访问
    宿主 3306）。用仓库版本覆盖会把这段抹掉，容器随即连不上宿主 MySQL。
    所以只能就地在线上那份文件里追加。

为什么用 bind mount 而不是命名卷：
    命名卷得 docker cp 或进容器才能放文件；bind mount 直接 scp 到宿主目录即可，
    换包不用重建镜像。APK 也因此不进镜像、不进部署包（20MB 起）。

用法（在 /usr/src/couple-deploy 下执行）：
    python3 apply_apk_volume.py

执行后会自动备份原文件为 *.bak-apk<时间戳>。
"""

import io
import os
import shutil
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
COMPOSE = os.path.join(ROOT, "docker-compose.yml")
APK_DIR = os.path.join(ROOT, "apk")

# 插入锚点必须与线上文件逐字一致（6 空格缩进）
ANCHOR = "      - uploads_data:/app/uploads"
INSERT = [
    "      # 安卓安装包目录（宿主 bind mount）：换包只需替换宿主 ./apk 下的文件，",
    "      # 不必重建镜像；20MB 的 APK 也不会进镜像层与部署包。",
    "      # 宣传页在镜像内（backend/static/app），无需挂载。",
    "      - ./apk:/app/static/apk",
]
# 幂等判据
MARK = "static/apk"


def backup(path, stamp):
    dst = "%s.bak-apk%s" % (path, stamp)
    shutil.copy2(path, dst)
    print("  已备份 -> %s" % os.path.basename(dst))


def patch_compose(stamp):
    print("[1/2] docker-compose.yml")
    if not os.path.isfile(COMPOSE):
        print("  [FAIL] 找不到 %s" % COMPOSE)
        return False
    text = io.open(COMPOSE, encoding="utf-8").read()

    if MARK in text:
        print("  已包含安装包挂载，跳过")
        return True
    if ANCHOR not in text:
        print("  [FAIL] 未找到锚点行，文件结构与预期不符，未做任何修改：")
        print("         %s" % ANCHOR)
        return False

    backup(COMPOSE, stamp)
    new = text.replace(ANCHOR, ANCHOR + "\n" + "\n".join(INSERT), 1)
    io.open(COMPOSE, "w", encoding="utf-8").write(new)
    print("  已追加 %d 行（挂载 ./apk -> /app/static/apk）" % len(INSERT))
    return True


def ensure_dir():
    print("[2/2] 宿主安装包目录")
    if os.path.isdir(APK_DIR):
        print("  已存在：%s" % APK_DIR)
    else:
        os.makedirs(APK_DIR)
        print("  已创建：%s" % APK_DIR)
    print("  当前内容：%s" % (os.listdir(APK_DIR) or "空"))
    return True


def main():
    stamp = time.strftime("%y%m%d-%H%M%S")
    print("=" * 60)
    print("安装包挂载补丁  %s" % stamp)
    print("=" * 60)
    ok1 = patch_compose(stamp)
    ok2 = ensure_dir()

    print()
    print("RESULT=%s" % ("OK" if (ok1 and ok2) else "FAIL"))


if __name__ == "__main__":
    main()
