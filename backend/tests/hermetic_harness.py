# -*- coding: utf-8 -*-
"""调解链路的 hermetic 测试基座（SQLite 文件库 + 线程替身 + 通知桩）。

## 为什么需要它

整改 B4.2 之后生成全部走「持久化任务 + 独立 worker」：API 进程**绝不**执行
任何 LLM（`kick()` 是空唤醒占位），执行只能由 `run_due_tasks` 承担——
测试里由用例显式调用，等价于生产里 `ai-task-worker` 容器走的那条路。
唯一还起线程的是 `notification_service.send_push_notification`（邮件
fire-and-forget）。测试里直接跑真线程有两个问题：

1. **结果不可断言**：断言只能靠 sleep 赌线程调度，PASS 可能只是「还没跑到」；
2. 线程里的异常打到 stderr，`python -X dev` 下尤其吵，而且没人接住。

所以这里提供一个 `threading.Thread` 替身：`start()` 时**同步执行** `run()`，
异常原样抛给调用方。被测的仍是**生产那条路径**（`run_due_tasks`），没有开任何
内联开关。因为「请求只入队、执行靠 worker」就是生产语义，观察「入队后、
执行前」的中间态不需要任何开关——入队后不调 `run_due_tasks` 就是那个中间态。

## 库为什么是文件而不是 `:memory:`

worker 用**另一个连接**（`SessionLocal`）读写，`:memory:` 的每个连接都是
独立的空库。所以建一个临时文件库，把 `SessionLocal` configure 到它上面，
再 `use_session_factory` 指向同一个 sessionmaker——一条数据链路，一个库。

## 用法

    with MediationHarness() as h:
        h.driving({...})          # 接上 LLM 桩
        db = h.db(); ...          # 业务调用
    h.destroy()                   # 关 engine + 删临时库文件（Windows 必须显式）
"""

import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from sqlalchemy import BigInteger, Integer, create_engine, event  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

import app.core.database as core_db  # noqa: E402
import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata
from app.core.database import Base  # noqa: E402

# SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）。
# 必须在 create_all 之前完成类型改写，否则建出来的表主键不自增。
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()


def _install_busy_retry(engine) -> None:
    """让并发用例真的能并发：WAL + 30s busy_timeout。

    默认的 rollback-journal 模式下，一个连接持写锁时另一个连接**立刻**报
    `database is locked`，并发用例会退化成「谁先写完谁赢」而不是「两个请求
    同时在跑」。开 WAL 并把 busy_timeout 拉到 30s，第二个连接会等锁而不是
    直接失败——条件 UPDATE 的竞争逻辑才真正被跑到。
    """

    @event.listens_for(engine, "connect")
    def _set_sqlite_pragma(dbapi_conn, _record):  # noqa: ANN001
        cursor = dbapi_conn.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA busy_timeout=30000")
        cursor.close()


class ThreadShim:
    """`threading.Thread` 的同步替身：`start()` 立刻跑完 `run()`。"""

    def __init__(self, target=None, name=None, daemon=None, args=(), kwargs=None):
        self._target = target
        self._args = args
        self._kwargs = kwargs or {}
        self.name = name or "ThreadShim"
        self.daemon = bool(daemon)

    def start(self):
        if self._target is not None:
            self._target(*self._args, **self._kwargs)

    def join(self, timeout=None):
        return None

    def is_alive(self):
        return False


class _ThreadingProxy:
    """模块级替身：只换掉 `Thread`，其余属性透传给真正的 threading 模块。

    为什么不能直接 `module.threading.Thread = ThreadShim`：`module.threading`
    **就是** threading 模块对象本身，那样赋值等于改全局——测试自己起的并发
    线程也会被换成同步执行，「并发」断言就变成了串行。所以这里替换的是
    被测模块**对 threading 这个名字的引用**，全局不受影响。
    """

    def __init__(self, real):
        self._real = real
        self.Thread = ThreadShim

    def __getattr__(self, name):
        return getattr(self._real, name)


class MediationHarness:
    """把一个 SQLite 测试库接到应用上：SessionLocal / 任务 worker / 通知全绑过去。

    `sync_threads=True`（默认）时把 `threading.Thread` 换成同步替身——
    邮件通知这类 fire-and-forget 在调用点同步跑完，断言有确定的时间点。
    并发用例要的是**真并发**，那时必须 `sync_threads=False`：
    由测试自己开线程。生成任务一律由用例显式调用 `run_due_tasks`
    （生产里 `ai-task-worker` 容器走的也是这条路）。
    """

    def __init__(self, prefix="mediation_test_", sync_threads=True):
        self.sync_threads = sync_threads
        self.original_engine = core_db.engine  # 生产 engine 的引用（dispose 时必须还回去）
        fd, self.sqlite_path = tempfile.mkstemp(prefix=prefix, suffix=".sqlite3")
        os.close(fd)
        os.remove(self.sqlite_path)  # 交给 SQLAlchemy 建，避免空文件被当既有库
        self.engine = create_engine(
            "sqlite:///%s" % self.sqlite_path,
            connect_args={"check_same_thread": False, "timeout": 30},
        )
        _install_busy_retry(self.engine)
        self.Session = sessionmaker(bind=self.engine, expire_on_commit=False)
        Base.metadata.create_all(self.engine)
        self.llm_calls = []
        self.frames = []
        self.notify_calls = []

    # ---- 生命周期 ---------------------------------------------------------- #

    def __enter__(self):
        import threading

        from app.services import ai_task_service, mediation_service, notification_service

        # 1) 应用全局的 SessionLocal 指到测试库（worker / kick / 广播都用它）
        # 测试期间把「engine + SessionLocal」这对不变量整体指到测试库
        # （dispose 的兜底路由在 worker 里用的就是 core_db.engine）
        self._orig_bind = core_db.SessionLocal.kw.get("bind")
        core_db.SessionLocal.configure(bind=self.engine)
        core_db.engine = self.engine

        # 2) 任务层的会话工厂（worker 每个任务用的 Session 也指到测试库）
        self._orig_factory = ai_task_service._SESSION_FACTORY
        ai_task_service.use_session_factory(self.Session)

        # 3) LLM 桩（各用例自己接）
        self._orig_call_llm = mediation_service._call_llm

        # 4) 通知：只留调用点可观测，不做真实投递（邮件 / WS）
        self._orig_notify_invite = mediation_service.notify_mediation_invite
        self._orig_broadcast = mediation_service.manager.broadcast_to_session

        harness = self

        async def fake_notify(partner_id, session_id, inviter_name):
            harness.notify_calls.append((partner_id, session_id, inviter_name))

        async def fake_broadcast(user_ids, message):
            harness.frames.append((list(user_ids), dict(message)))

        mediation_service.notify_mediation_invite = fake_notify
        mediation_service.manager.broadcast_to_session = fake_broadcast

        # 5) 线程替身：这些模块**引用**的 threading 换成代理（全局不受影响）。
        # 整改 B4.2 之后 ai_task_service 已不 import threading（daemon 线程移除），
        # 只有 notification_service（邮件 fire-and-forget）还需要同步化。
        self._orig_threading = []
        if self.sync_threads:
            for module in (notification_service,):
                self._orig_threading.append((module, module.threading))
                module.threading = _ThreadingProxy(threading)
        return self

    def __exit__(self, *exc):
        from app.services import ai_task_service, mediation_service

        for module, original in self._orig_threading:
            module.threading = original
        self._orig_threading = []

        mediation_service.notify_mediation_invite = self._orig_notify_invite
        mediation_service.manager.broadcast_to_session = self._orig_broadcast
        mediation_service._call_llm = self._orig_call_llm

        ai_task_service.use_session_factory(self._orig_factory)
        core_db.engine = self.original_engine
        core_db.SessionLocal.configure(bind=self._orig_bind)
        return False

    def destroy(self):
        """释放连接并删掉临时库文件。

        Windows 上文件被占用就删不掉（WinError 32），所以先 dispose。
        **dispose 之前必须把 `core_db.engine` 还回去**：否则生产 engine
        （指向 MySQL）的连接池永远不被释放——本进程里每次跑测试都漏一次，
        跑满几轮就会把 MySQL 的 max_connections 吃光，后续所有用例
        连库直接失败。
        """
        core_db.engine = self.original_engine
        try:
            self.engine.dispose()
        finally:
            if os.path.exists(self.sqlite_path):
                try:
                    os.remove(self.sqlite_path)
                except OSError:
                    pass

    # ---- 便捷方法 ---------------------------------------------------------- #

    def db(self):
        return self.Session()

    def driving(self, stubs):
        """接上「按 scene_key 返回固定结构」的 LLM 桩，并记录调用。

        `stubs` 形如 `{"mediation_rewrite": {...}, "mediation_summary": {...}}`；
        也可以是 callable：`callable(prompt, scene_key) -> dict`，
        用于「第一次失败、第二次成功」这类需要按调用次数变化的场景。

        ## 为什么桩签名带 `client=None`（2026-10-02）

        `ai_service._call_llm` 在 v5.0 改成 `(prompt, scene_key, client=None)`，
        `client` 由调用方经 `user_ai_config_service.build_chat_client` 解析。
        但**测试桩不需要真的模型 client**——它只关心 scene_key。

        若桩写成 `def fake_llm(prompt, scene_key)`，生产代码一传`client=`
        就抛 `TypeError: got an unexpected keyword argument 'client'`，
        套件全挂（曾一次性挂 6 个 mediation/advisor 套件）。

        故统一补`client=None` 吸收该参数。`client` 刻意不传给 stubs 的
        callable —— 桩的职责是产出固定结构，不需要真实 client。
        """
        from app.services import mediation_service

        harness = self

        def fake_llm(prompt, scene_key, client=None):
            harness.llm_calls.append(scene_key)
            if callable(stubs):
                return stubs(prompt, scene_key)
            return dict(stubs[scene_key])

        mediation_service._call_llm = fake_llm
        return fake_llm

    @property
    def llm_calls_by_scene(self):
        return {
            scene: [s for s in self.llm_calls if s == scene]
            for scene in set(self.llm_calls)
        }

    @property
    def rewrite_calls(self):
        return [s for s in self.llm_calls if s == "mediation_rewrite"]

    @property
    def summary_calls(self):
        return [s for s in self.llm_calls if s == "mediation_summary"]

    @property
    def statuses_broadcast(self):
        return [f[1].get("status") for f in self.frames]
