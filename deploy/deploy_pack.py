"""把 backend/ 打成部署包。

刻意排除的东西及其原因：

    .env            服务器上那份是生产库密码和真实 Key，绝不能被本地
                    指向 localhost 的版本覆盖 —— 这是本仓库踩过的坑。
    data/           本地向量库、上传文件，体积大且与本机路径绑定，
                    线上由 entrypoint 重建。
    static/apk/     安卓安装包（20MB 起）。线上是宿主 bind mount，不进镜像，
                    因此也不能进部署包 —— 否则每次部署都白传 20MB。
    __pycache__/   机器码，跨机器无意义。
    *.log           日志不搬家。

保留 tests/：容器里能直接跑它们做上线后验证，体积可以忽略。
保留 static/app/：宣传页是单文件 HTML（约 40KB），随镜像发布。
"""

import os
import tarfile

# 本脚本位于 deploy/ 子目录，源码与产物都在仓库根目录。
# ⚠️ 若按脚本自身目录取 ROOT，会去找 deploy/backend（不存在）→ 打出**空包**，
#    后续上传/解包/重建全部"成功"却什么都没换 —— 静默失效，务必保持指向根目录。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "backend")
OUT = os.path.join(ROOT, "backend_deploy.tar.gz")

EXCLUDE_DIRS = {"__pycache__", ".git", "data", ".pytest_cache", ".venv", "venv"}
EXCLUDE_FILES = {".env", ".env.local"}
# 相对 backend/ 的路径（统一用 / 分隔），整棵子树都不进包
EXCLUDE_RELPATHS = ("static/apk",)


def _excluded_relpath(dirpath):
    rel = os.path.relpath(dirpath, SRC).replace(os.sep, "/")
    return any(rel == p or rel.startswith(p + "/") for p in EXCLUDE_RELPATHS)


def keep(path, name):
    if name in EXCLUDE_FILES:
        return False
    if name.endswith((".pyc", ".pyo", ".log")):
        return False
    return True


def main():
    count = 0
    if not os.path.isdir(SRC):
        raise SystemExit("找不到源码目录：%s" % SRC)
    with tarfile.open(OUT, "w:gz") as tar:
        for dirpath, dirnames, filenames in os.walk(SRC):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
            if _excluded_relpath(dirpath):
                continue
            for f in filenames:
                if not keep(dirpath, f):
                    continue
                full = os.path.join(dirpath, f)
                arc = os.path.join("backend", os.path.relpath(full, SRC))
                tar.add(full, arcname=arc)
                count += 1

    size = os.path.getsize(OUT)
    print(f"已生成 {OUT}")
    print(f"  文件数 {count}，压缩后 {size / 1024:.0f} KB")
    if os.path.exists(os.path.join(SRC, ".env")):
        print("  已排除 backend/.env（服务器上的那份不会被覆盖）")
    if count == 0:
        raise SystemExit("[FAIL] 包内 0 文件，立即中止（否则会静默部署成旧代码）")


if __name__ == "__main__":
    main()
