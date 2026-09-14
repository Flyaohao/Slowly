"""迁移链完整性校验

背景
----
本项目的历史库里存在"表由临时脚本直接建出、迁移链里没有记录"的情况，
导致 `alembic upgrade head` 建不出完整的库，而 `--autogenerate` 又会
反向提议把那些表删掉。这个脚本用一个**全新空库**来验证迁移链是否自洽。

做法
----
1. 在同一个 MySQL 实例上建一个临时库（默认 couple_mig_check）；
2. 用 `DB_URL` 环境变量把 alembic 指向它，执行 `alembic upgrade head`；
3. 用 alembic 的 `compare_metadata` 把建出来的库与 `Base.metadata` 对比；
4. 期望差异为 0（alembic_version 自身除外），最后删除临时库。

运行：
    cd backend
    python scripts/verify_migrations.py            # 校验后删除临时库
    python scripts/verify_migrations.py --keep     # 保留临时库以便人工查看
"""

import argparse
import os
import subprocess
import sys
from urllib.parse import urlsplit, urlunsplit

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sqlalchemy import create_engine, text  # noqa: E402

from alembic.autogenerate import compare_metadata  # noqa: E402
from alembic.migration import MigrationContext  # noqa: E402

from app.core.config import DB_URL  # noqa: E402
from app.core.database import Base  # noqa: E402
import app.models  # noqa: F401,E402

SCRATCH_DB = "couple_mig_check"
BACKEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def split_db_url(url: str):
    """把 DB_URL 拆成 (服务器级 URL, 数据库名)。"""
    parts = urlsplit(url)
    db_name = parts.path.lstrip("/")
    return urlunsplit((parts.scheme, parts.netloc, "", parts.query, parts.fragment)), db_name


def make_url(server_url: str, db_name: str) -> str:
    parts = urlsplit(server_url)
    return urlunsplit((parts.scheme, parts.netloc, "/" + db_name, parts.query, ""))


def rebuild_scratch(server_url: str) -> None:
    engine = create_engine(server_url, isolation_level="AUTOCOMMIT")
    with engine.connect() as conn:
        conn.execute(text("DROP DATABASE IF EXISTS `%s`" % SCRATCH_DB))
        conn.execute(
            text("CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" % SCRATCH_DB)
        )
    engine.dispose()


def drop_scratch(server_url: str) -> None:
    engine = create_engine(server_url, isolation_level="AUTOCOMMIT")
    with engine.connect() as conn:
        conn.execute(text("DROP DATABASE IF EXISTS `%s`" % SCRATCH_DB))
    engine.dispose()


def run_upgrade(scratch_url: str) -> tuple:
    env = dict(os.environ, DB_URL=scratch_url)
    proc = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        cwd=BACKEND_DIR, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    return proc.returncode, proc.stdout


def diff_schema(scratch_url: str) -> list:
    engine = create_engine(scratch_url)
    with engine.connect() as conn:
        ctx = MigrationContext.configure(conn)
        raw = compare_metadata(ctx, Base.metadata)
    engine.dispose()
    # alembic_version 是 alembic 自己的记账表，不属于业务模型，忽略
    return [d for d in raw if "alembic_version" not in str(d)]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留临时库不删除")
    args = parser.parse_args()

    server_url, real_db = split_db_url(DB_URL)
    scratch_url = make_url(server_url, SCRATCH_DB)

    print("=" * 72)
    print("迁移链完整性校验")
    print("=" * 72)
    print("业务库        : %s" % real_db)
    print("临时校验库    : %s" % SCRATCH_DB)
    print("Base.metadata 表数: %d" % len(Base.metadata.tables))

    print("\n[1/4] 重建空库 ...")
    rebuild_scratch(server_url)

    print("[2/4] 执行 alembic upgrade head（全新库）...")
    code, output = run_upgrade(scratch_url)
    for line in output.strip().splitlines():
        print("      " + line)
    if code != 0:
        print("\n❌ 迁移执行失败，返回码 %d" % code)
        return 1

    print("\n[3/4] 比对建出来的 schema 与模型定义 ...")
    diffs = diff_schema(scratch_url)
    if diffs:
        print("  ❌ 存在 %d 处不一致：" % len(diffs))
        for d in diffs:
            print("     - %s" % (d,))
    else:
        print("  ✅ 完全一致，迁移链可以独立重建出与模型匹配的库")

    print("\n[4/4] 清理 ...")
    if args.keep:
        print("  已保留临时库 %s（--keep）" % SCRATCH_DB)
    else:
        drop_scratch(server_url)
        print("  已删除临时库 %s" % SCRATCH_DB)

    print("\n" + "=" * 72)
    ok = not diffs
    print("✅ 迁移链校验通过" if ok else "❌ 迁移链校验未通过")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
