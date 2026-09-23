"""把 backend/ 打成部署包。

刻意排除的东西及其原因：

    .env            服务器上那份是生产库密码和真实 Key，绝不能被本地
                    指向 localhost 的版本覆盖 —— 这是本仓库踩过的坑。
    data/           本地向量库、上传文件，体积大且与本机路径绑定，
                    线上由 entrypoint 重建。
    __pycache__/   机器码，跨机器无意义。
    *.log           日志不搬家。

保留 tests/：容器里能直接跑它们做上线后验证，体积可以忽略。
"""

import os
import tarfile

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "backend")
OUT = os.path.join(ROOT, "backend_deploy.tar.gz")

EXCLUDE_DIRS = {"__pycache__", ".git", "data", ".pytest_cache", ".venv", "venv"}
EXCLUDE_FILES = {".env", ".env.local"}


def keep(path, name):
    if name in EXCLUDE_FILES:
        return False
    if name.endswith((".pyc", ".pyo", ".log")):
        return False
    return True


def main():
    count = 0
    with tarfile.open(OUT, "w:gz") as tar:
        for dirpath, dirnames, filenames in os.walk(SRC):
            dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
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


if __name__ == "__main__":
    main()
