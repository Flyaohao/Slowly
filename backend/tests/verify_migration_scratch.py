# -*- coding: utf-8 -*-
"""迁移验证：在同版本 MySQL 上建**一次性 scratch 库**，从 base 跑到 head。

为什么要有这一步：本轮新增的 `7f3a9c2e5b81`（feedback 去重 + 唯一索引）含
**数据合并**逻辑，而本地 dev 库那张表是空的——空表跑不出合并路径，等于没验。
这里在同一个 MySQL 实例上建一个隔离库：

  1) `upgrade ab12cd34ef56`（该迁移的前一版，全链从 base 起跑）；
  2) 手工插入**同 (message_id,user_id) 的重复行**，字段分布刻意覆盖
     「基底有 / 旧行有」的各种组合；
  3) `upgrade head` —— 真实执行合并 + 建唯一索引；
  4) 核对行数 / 唯一键数 / 合并数 / 索引 / 最终 alembic 版本；
  5) `downgrade` 再 `upgrade` 走一遍往返（回滚方案预演）。

## 实现要点：为什么自己 re-exec 一次

`alembic/env.py` 在 **import 时**就把 `app.core.config.DB_URL` 读进配置
（`config.set_main_option("sqlalchemy.url", DB_URL)`）。所以「先 import 拿到
原 URL，再改环境变量」是无效的——模块早缓存了。本脚本因此分两段：

- 父进程：读原库 URL → 算出 scratch URL → 建库 → 设 `DB_URL` 环境变量后
  **重新执行自己**（`SCRATCH_CHILD=1`）；
- 子进程：`app.core.config` 在全新进程里首次 import，读到的就是 scratch 库，
  alembic 走的也是它。

口令全程不出现在命令行参数里（只经环境变量传递），也不打印。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 \
        python tests/verify_migration_scratch.py
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.dirname(HERE)
SCRATCH_DB = "couple_migration_check"

# 与其它测试脚本同一口径：把 backend/ 放进 sys.path，`app.*` 才可导入
sys.path.insert(0, BACKEND)

FAILS = []


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILS.append(name)


def _scratch_url(base: str) -> str:
    """把原 URL 的库名换成 scratch 库名（保留 dialect/口令/参数）。"""
    head, sep, query = base.partition("?")
    prefix, _slash, _old = head.rpartition("/")
    url = "%s/%s" % (prefix, SCRATCH_DB)
    return "%s?%s" % (url, query) if sep else url


def _redact(url: str) -> str:
    """只留 dialect 与 host[:port]，**口令与用户名一律不回显**（日志红线）。"""
    head = url.partition("?")[0]
    scheme, _sep, rest = head.partition("://")
    host = rest.rpartition("@")[2]
    return "%s://%s" % (scheme, host)


# --------------------------------------------------------------------------- #
# 父进程：准备 scratch 库并把自己扔进子进程
# --------------------------------------------------------------------------- #

def parent_main() -> int:
    from app.core import config as cfg
    from sqlalchemy import create_engine, text

    base = cfg.DB_URL
    scratch = _scratch_url(base)
    admin_url = base.partition("?")[0].rpartition("/")[0] + "/"

    print("[0] 在同一个 MySQL 实例上建 scratch 库 %s（同名旧库先删掉）" % SCRATCH_DB)
    print("    实例：%s" % _redact(base))
    admin = create_engine(admin_url, isolation_level="AUTOCOMMIT")
    with admin.connect() as conn:
        conn.execute(text("DROP DATABASE IF EXISTS %s" % SCRATCH_DB))
        conn.execute(
            text(
                "CREATE DATABASE %s CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
                % SCRATCH_DB
            )
        )
    print("  PASS  scratch 库已就绪")

    env = dict(os.environ)
    env["DB_URL"] = scratch
    env["SCRATCH_CHILD"] = "1"
    env.setdefault("PYTHONUTF8", "1")
    env.setdefault("PYTHONIOENCODING", "utf-8")
    proc = subprocess.run([sys.executable, os.path.abspath(__file__)], env=env)
    return proc.returncode


# --------------------------------------------------------------------------- #
# 子进程：DB_URL 已是 scratch 库，跑真实迁移
# --------------------------------------------------------------------------- #

def child_main() -> int:
    from app.core import config as cfg
    from sqlalchemy import create_engine, text
    from alembic import command
    from alembic.config import Config

    assert SCRATCH_DB in cfg.DB_URL, "子进程没有拿到 scratch 库 URL，拒绝在真库上跑迁移"
    eng = create_engine(cfg.DB_URL)

    def alembic_cfg() -> Config:
        c = Config(os.path.join(BACKEND, "alembic.ini"))
        c.set_main_option("script_location", os.path.join(BACKEND, "alembic"))
        return c

    print("[1] upgrade 到 7f3a9c2e5b81 的前一版 ab12cd34ef56（全链从 base 起跑）")
    try:
        command.upgrade(alembic_cfg(), "ab12cd34ef56")
        check("全链升级到 ab12cd34ef56 成功", True)
    except Exception as exc:  # noqa: BLE001
        check("全链升级到 ab12cd34ef56 成功", False, repr(exc))
        return 1

    print("[2] 造最小真实数据（scratch 库是空的：外键需要真实的 user / couple_relation / ai_scene）")
    with eng.begin() as conn:
        # 直接用 Core INSERT，不走 ORM（ORM 会带上模型侧默认值，这里要最小可运行数据）。
        # 列名照 scratch 库的 information_schema 实测结果——不按模型名猜。
        for uid in (90001, 90002):
            conn.execute(
                text(
                    "INSERT INTO user (id, email, password_hash, status, has_couple) "
                    "VALUES (:id, :email, 'x', 'active', 0)"
                ),
                {"id": uid, "email": "migration-check-%d@example.test" % uid},
            )
        conn.execute(
            text(
                "INSERT INTO couple_relation (id, user_a_id, user_b_id, status, bind_time) "
                "VALUES (93001, 90001, 90002, 'active', NOW())"
            )
        )
        scene = conn.execute(
            text("SELECT scene_key FROM ai_scene WHERE scene_key='private_advisor'")
        ).scalar()
        if scene is None:
            # ai_scene 的实际列是 scene_key / name / description / id（无 status）
            conn.execute(
                text(
                    "INSERT INTO ai_scene (scene_key, name, description) "
                    "VALUES ('private_advisor', '私人军师', '迁移校验')"
                )
            )
        conn.execute(
            text(
                "INSERT INTO ai_chat_session "
                "(id, user_id, relation_id, scene_key, privacy_level, session_type, status) "
                "VALUES (91001, 90001, 93001, 'private_advisor', 'private', 'couple', 'active')"
            )
        )
        conn.execute(
            text(
                "INSERT INTO ai_chat_message (id, session_id, role, content) "
                "VALUES (92001, 91001, 'assistant', '迁移校验用消息')"
            )
        )
    with eng.connect() as conn:
        mid = conn.execute(text("SELECT id FROM ai_chat_message WHERE id=92001")).scalar()
        uid = conn.execute(text("SELECT id FROM user WHERE id=90001")).scalar()
    check("最小 fixture 落库成功（外键目标存在）", mid is not None and uid is not None,
          "mid=%s uid=%s" % (mid, uid))
    print("  PASS  message_id=%s user_id=%s" % (mid, uid))

    # 行序即语义：**每组 id 最大的那一行是基底**（迁移保留最新行，旧行的非空
    # 字段只用来补齐基底的空洞，绝不覆盖基底已有的值）。
    #
    # 组 1：三条，基底只有 feedback_tag/outcome → 其余四个字段必须从旧行回填
    # 组 2：两条，基底字段齐全 → 旧行的值一个都不许覆盖进来
    plant = [
        # 组 1（user 90001）：旧行 → 全空中间行 → 基底
        (mid, uid, 5, "old_tag", "old_text", 1, None),
        (mid, uid, None, None, None, None, None),
        (mid, uid, None, "base_tag", None, None, "聊开了"),
        # 组 2（user 90002）：旧行 → 基底（字段齐全）
        (mid, uid + 1, 1, "old_tag2", "old_text2", 0, "很糟"),
        (mid, uid + 1, 4, "base_tag2", "base_text2", 1, "还行"),
    ]
    # 该表只有 BigIntPKMixin（无 created_at/updated_at），列名照模型来
    insert_sql = text(
        "INSERT INTO ai_output_feedback "
        "(message_id, user_id, rating, feedback_tag, feedback_text, adopted, outcome) "
        "VALUES (:m, :u, :r, :t, :x, :a, :o)"
    )
    with eng.begin() as conn:
        for row in plant:
            conn.execute(
                insert_sql,
                {"m": row[0], "u": row[1], "r": row[2], "t": row[3],
                 "x": row[4], "a": row[5], "o": row[6]},
            )

    with eng.connect() as conn:
        total_before = conn.execute(text("SELECT COUNT(*) FROM ai_output_feedback")).scalar()
        uniq_before = conn.execute(
            text("SELECT COUNT(DISTINCT message_id, user_id) FROM ai_output_feedback")
        ).scalar()
        dups_before = conn.execute(
            text(
                "SELECT COUNT(*) FROM (SELECT message_id, user_id FROM ai_output_feedback "
                "GROUP BY message_id, user_id HAVING COUNT(*) > 1) t"
            )
        ).scalar()
    print("  迁移前：行数=%s 唯一键数=%s 重复组=%s" % (total_before, uniq_before, dups_before))
    check("迁移前确实存在重复键（否则这次验证没有意义）", dups_before == 2, "dup_groups=%s" % dups_before)
    check("迁移前行数与插入数一致", total_before == len(plant), "rows=%s" % total_before)

    print("[3] upgrade head —— 真实执行合并 + 建唯一索引")
    try:
        command.upgrade(alembic_cfg(), "head")
        check("合并 + 唯一索引迁移执行成功", True)
    except Exception as exc:  # noqa: BLE001
        check("合并 + 唯一索引迁移执行成功", False, repr(exc))
        return 1

    print("[4] 迁移后核对")
    # 基底行按 id 认准：合并保留的是**每组 id 最大**的那一行（组 1 的基底
    # 是第 3 条、组 2 的基底是第 5 条）。按 (message_id,user_id) 取虽然唯一，
    # 但读到的是哪一行取决于插入顺序——直接按 id 取才是在验证「保留最新行」。
    with eng.connect() as conn:
        id_rows = [
            r[0]
            for r in conn.execute(
                text(
                    "SELECT id FROM ai_output_feedback WHERE message_id=:m ORDER BY id ASC"
                ),
                {"m": mid},
            )
        ]
    check("每组只剩一行（组 1 / 组 2 各一）", len(id_rows) == 2, str(id_rows))
    g1_id, g2_id = (id_rows + [None, None])[:2]

    with eng.connect() as conn:
        total_after = conn.execute(text("SELECT COUNT(*) FROM ai_output_feedback")).scalar()
        uniq_after = conn.execute(
            text("SELECT COUNT(DISTINCT message_id, user_id) FROM ai_output_feedback")
        ).scalar()
        dups_after = conn.execute(
            text(
                "SELECT COUNT(*) FROM (SELECT message_id, user_id FROM ai_output_feedback "
                "GROUP BY message_id, user_id HAVING COUNT(*) > 1) t"
            )
        ).scalar()
        g1 = conn.execute(
            text(
                "SELECT rating, feedback_tag, feedback_text, adopted, outcome "
                "FROM ai_output_feedback WHERE id=:i"
            ),
            {"i": g1_id},
        ).fetchone()
        g2 = conn.execute(
            text(
                "SELECT rating, feedback_tag, feedback_text, adopted, outcome "
                "FROM ai_output_feedback WHERE id=:i"
            ),
            {"i": g2_id},
        ).fetchone()
        idx = {r[2]: r[1] for r in conn.execute(text("SHOW INDEX FROM ai_output_feedback"))}
        ver = conn.execute(text("SELECT version_num FROM alembic_version")).scalar()
        session_cols = {r[0] for r in conn.execute(text("SHOW COLUMNS FROM ai_chat_session"))}
        task_cols = {r[0] for r in conn.execute(text("SHOW COLUMNS FROM ai_task"))}

    print("  迁移后：行数=%s 唯一键数=%s 重复组=%s（合并掉 %s 行）"
          % (total_after, uniq_after, dups_after, total_before - total_after))
    check("重复组归零", dups_after == 0, "dup=%s" % dups_after)
    check("行数 = 唯一键数（每组只留一行）", total_after == uniq_after, "%s vs %s" % (total_after, uniq_after))
    check("合并恰好删掉 3 行（5 行 → 2 行）", total_after == 2, "rows=%s" % total_after)
    check(
        "组 1：基底的空洞被旧行补齐（rating/text/adopted），基底自己的值（tag/outcome）原封不动",
        tuple(g1) == (5, "base_tag", "old_text", 1, "聊开了"),
        str(tuple(g1)) if g1 else "无行",
    )
    check(
        "组 2：基底已有字段不被旧行覆盖",
        tuple(g2) == (4, "base_tag2", "base_text2", 1, "还行"),
        str(tuple(g2)) if g2 else "无行",
    )
    check(
        "唯一索引 uq_ai_output_feedback_msg_user 已建立且唯一",
        idx.get("uq_ai_output_feedback_msg_user") == 0,
        str(idx.get("uq_ai_output_feedback_msg_user")),
    )
    check("最终 alembic 版本 = e5f6a7b8c9d0（本轮 head）", ver == "e5f6a7b8c9d0", str(ver))
    check(
        "ai_task 表已建（含幂等键）",
        "id" in task_cols and "idempotency_key" in task_cols,
        str(sorted(task_cols)),
    )
    check(
        "ai_chat_session 本轮的 6 个新列齐全",
        {"mediation_revision", "mediation_failure_code", "mediation_last_error",
         "mediation_failed_at", "rewrite_task_id", "summary_task_id"} <= session_cols,
        str(sorted(c for c in session_cols if c.startswith("mediation") or c.endswith("task_id"))),
    )

    print("[5] 回滚方案预演：downgrade → upgrade 往返")
    try:
        command.downgrade(alembic_cfg(), "b5c6d7e8f9a0")
        with eng.connect() as conn:
            tables = {r[0] for r in conn.execute(text("SHOW TABLES"))}
        check("downgrade 到 b5c6d7e8f9a0 成功且 ai_task 已删", "ai_task" not in tables, str(sorted(tables)))
        command.upgrade(alembic_cfg(), "head")
        check("再次 upgrade head 成功（迁移可重入）", True)
    except Exception as exc:  # noqa: BLE001
        check("downgrade / upgrade 往返成功", False, repr(exc))

    print("[6] 已在 head 上再执行一次 upgrade（幂等复核）")
    try:
        command.upgrade(alembic_cfg(), "head")
        check("head 上重复执行无副作用", True)
    except Exception as exc:  # noqa: BLE001
        check("head 上重复执行无副作用", False, repr(exc))

    print("=" * 72)
    if FAILS:
        print("失败 %d 项：%s" % (len(FAILS), FAILS))
        return 1
    print("全部通过（scratch 库 %s 保留，供人工复核；确认后可 DROP DATABASE）" % SCRATCH_DB)
    return 0


if __name__ == "__main__":
    if os.environ.get("SCRATCH_CHILD") == "1":
        sys.exit(child_main())
    sys.exit(parent_main())
