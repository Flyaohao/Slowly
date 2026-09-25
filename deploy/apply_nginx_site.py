"""在服务器上安装对外站点（宿主 Nginx），并停用废弃的 8080 站点。

背景见 deploy/nginx/couple-app.conf 头部注释。要点：
    这台机器早已跑着宝塔的 Nginx（占 80/8080/888），没必要再加一个 Nginx 容器。
    复用现成的，把 /app 与 /download 交给它直接发静态文件。

本脚本做的事（全部幂等，可重复执行）：
    1. 备份要动的文件到 nginx-backup-<时间戳>/
    2. 停用废弃站点 coupleapp-admin.conf（改名，不删除，可随时改回）
       —— 它的 root /usr/src/CoupleAPP/admin 已不存在、上游 9091 也没进程
    3. 安装 couple-app.conf 到 vhost 目录
    4. nginx -t 校验；**失败则整体回滚**并返回非 0，不留下坏配置
    5. reload

用法（在 /usr/src/couple-deploy 下执行，需 root）：
    python3 apply_nginx_site.py                 # 用同目录的 couple-app.conf
    python3 apply_nginx_site.py /path/to.conf   # 指定源文件
"""

import io
import os
import shutil
import subprocess
import sys
import time

VHOST_DIR = "/www/server/panel/vhost/nginx"
TARGET_NAME = "couple-app.conf"
STALE_NAME = "coupleapp-admin.conf"
BACKUP_ROOT = "/usr/src/couple-deploy"

NGINX_BIN = "/www/server/nginx/sbin/nginx"


def run(cmd):
    """执行外部命令。

    ⚠️ 服务器的 python3 是 **3.6**，`subprocess.run(capture_output=..., text=...)`
    要 3.7+ 才有，在 3.6 上会直接 TypeError。这里用 Popen 兼容写法 —— 踩过一次：
    脚本崩在 `nginx -t` 那一步，导致「旧站点已停用、新配置已写入，但从未校验也
    从未 reload」，磁盘状态与运行状态不一致。Popen 必须保留。
    """
    p = subprocess.Popen(
        cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )
    out = p.communicate()[0]
    if isinstance(out, bytes):
        out = out.decode("utf-8", "replace")
    return p.returncode, out


def nginx_test():
    return run("%s -t" % NGINX_BIN)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), TARGET_NAME
    )
    stamp = time.strftime("%y%m%d-%H%M%S")
    backup_dir = os.path.join(BACKUP_ROOT, "nginx-backup-%s" % stamp)
    os.makedirs(backup_dir, exist_ok=True)

    print("=" * 62)
    print("安装对外站点  %s" % stamp)
    print("=" * 62)

    if not os.path.isdir(VHOST_DIR):
        print("[FAIL] 找不到 vhost 目录：%s（这台机器不是宝塔 Nginx？）" % VHOST_DIR)
        return 2
    if not os.path.isfile(src):
        print("[FAIL] 找不到源配置：%s" % src)
        return 2

    new_text = io.open(src, encoding="utf-8").read()
    target = os.path.join(VHOST_DIR, TARGET_NAME)
    stale = os.path.join(VHOST_DIR, STALE_NAME)

    # 记录改动前状态，供回滚
    changed = []

    # ── 步骤 1：停用废弃站点 ────────────────────────────────────────────
    print("[1/4] 停用废弃站点 %s" % STALE_NAME)
    if os.path.isfile(stale):
        shutil.copy2(stale, os.path.join(backup_dir, STALE_NAME))
        disabled = stale + ".disabled-" + stamp
        os.rename(stale, disabled)
        changed.append(("restore", stale, disabled))
        print("  已备份并改名为 %s" % os.path.basename(disabled))
    else:
        print("  不存在或已停用，跳过")

    # ── 步骤 2：安装新站点 ──────────────────────────────────────────────
    print("[2/4] 安装 %s" % TARGET_NAME)
    if os.path.isfile(target):
        old = io.open(target, encoding="utf-8").read()
        if old == new_text:
            print("  内容一致，无需改动")
        else:
            shutil.copy2(target, os.path.join(backup_dir, TARGET_NAME))
            io.open(target, "w", encoding="utf-8").write(new_text)
            changed.append(("rewrite", target, old))
            print("  已覆盖（旧文件已备份）")
    else:
        io.open(target, "w", encoding="utf-8").write(new_text)
        changed.append(("remove", target, None))
        print("  已写入新文件")

    # ── 步骤 3：语法校验，失败即回滚 ────────────────────────────────────
    print("[3/4] nginx -t")
    rc, out = nginx_test()
    if rc != 0:
        print("  [FAIL] 配置校验未通过，开始回滚：")
        for line in out.strip().splitlines():
            print("      %s" % line)
        for action, path, extra in reversed(changed):
            if action == "restore":
                os.rename(extra, path)
            elif action == "rewrite":
                io.open(path, "w", encoding="utf-8").write(extra)
            elif action == "remove" and os.path.isfile(path):
                os.remove(path)
        rc2, out2 = nginx_test()
        print("  回滚后 nginx -t：%s" % ("通过" if rc2 == 0 else "仍失败！"))
        if rc2 != 0:
            print(out2)
        print("  备份目录：%s" % backup_dir)
        return 1
    print("  通过")

    # ── 步骤 4：reload ─────────────────────────────────────────────────
    print("[4/4] reload")
    rc, out = run("%s -s reload" % NGINX_BIN)
    if rc != 0:
        print("  [FAIL] reload 失败：")
        print(out)
        return 1
    print("  已完成")

    print()
    print("备份目录：%s" % backup_dir)
    print("RESULT=OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
