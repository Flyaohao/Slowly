# -*- coding: utf-8 -*-
"""MySQL 测试基座：临时 schema + 应用全局指向它（整改 B4.3 P0-5）。

与 [`hermetic_harness.MediationHarness`] 的分工：

- **SQLite 基座**跑得快、无外部依赖，适合验「逻辑分支 / 状态机 / 事务回滚」；
- **本基座**跑在生产同版本 MySQL（9.0.1 / REPEATABLE-READ）上，是唯一能验
  「SAVEPOINT 顺序 / 唯一键并发 / 快照读 vs 加锁读 / 行锁」的地方。

两者结论**必须分开报告**：SQLite 的并发模型是单写者，拿它的结果去声称
「MySQL 并发下没问题」是无效结论。
"""
import uuid

from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

from app.core.config import DB_URL


def split_db_url(url: str):
    """把 DB_URL 拆成 (库名前的部分, 库名, 查询串含 '?')。"""
    head, sep, tail = url.partition("?")
    base, _slash, dbname = head.rpartition("/")
    return base, dbname, (sep + tail if sep else "")


def mysql_available(timeout: int = 3):
    """探测 MySQL；不可用返回 None，可用返回 (version, isolation)。"""
    base, _old, suffix = split_db_url(DB_URL)
    try:
        eng = create_engine(
            "%s/mysql%s" % (base, suffix), connect_args={"connect_timeout": timeout}
        )
        with eng.connect() as conn:
            version = conn.execute(text("SELECT VERSION()")).scalar()
            iso = conn.execute(text("SELECT @@transaction_isolation")).scalar()
        eng.dispose()
    except Exception:  # noqa: BLE001
        return None
    return version, iso


class MysqlHarness:
    """临时 schema + 应用全局指向它；退出时 drop。用法与 MediationHarness 同形。

        with MysqlHarness("tag") as h:
            db = h.Session()
            ...
    """

    def __init__(self, tag="b43"):
        self.tag = tag
        self.dbname = "couple_b43_%s_%s" % (tag, uuid.uuid4().hex[:8])

    def __enter__(self):
        import app.core.database as core_db
        from app.core.database import Base
        from app.services import ai_task_service

        base, _old, suffix = split_db_url(DB_URL)
        self.url = "%s/%s%s" % (base, self.dbname, suffix)
        self._base, self._suffix = base, suffix

        admin = create_engine("%s/mysql%s" % (base, suffix))
        with admin.connect() as conn:
            conn.execute(text(
                "CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
                % self.dbname
            ))
            conn.commit()
        admin.dispose()

        self.engine = create_engine(self.url, pool_size=10, max_overflow=20)
        Base.metadata.create_all(self.engine)
        self.Session = sessionmaker(bind=self.engine, expire_on_commit=False)

        self._orig_bind = core_db.SessionLocal.kw.get("bind")
        core_db.SessionLocal.configure(bind=self.engine)
        self._orig_engine = core_db.engine
        core_db.engine = self.engine
        self._orig_factory = ai_task_service._SESSION_FACTORY
        ai_task_service.use_session_factory(self.Session)
        self._admin_engine = create_engine("%s/mysql%s" % (base, suffix))
        return self

    def db(self):
        return self.Session()

    def scalar(self, sql):
        with self.engine.connect() as conn:
            return conn.execute(text(sql)).scalar()

    def count(self, sql):
        return self.scalar(sql)

    def __exit__(self, *exc):
        import app.core.database as core_db
        from app.services import ai_task_service

        ai_task_service.use_session_factory(self._orig_factory)
        core_db.engine = self._orig_engine
        core_db.SessionLocal.configure(bind=self._orig_bind)
        self.engine.dispose()
        try:
            with self._admin_engine.connect() as conn:
                conn.execute(text("DROP DATABASE IF EXISTS `%s`" % self.dbname))
                conn.commit()
        finally:
            self._admin_engine.dispose()
        return False
