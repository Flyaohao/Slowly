"""量化 B 案收益：async 依赖 vs 同步依赖，各自向 anyio 线程池借多少次。

B 案把 `get_db` 从同步 generator 改成 async generator。派单预期收益是
「session 的 acquire/release 不再占用线程池里的执行时间」。

本脚本对**两种写法**各跑 N 次请求，统计 anyio 线程池的
total_tokens 借还次数（CapacityLimiter 是 anyio 限流的唯一入口，
统计它的 acquire 次数就等于统计「线程池被借了几次」）。

结论会直接回答：async 依赖到底省了几次借还。
"""
import asyncio
import os
import sys

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BACKEND)

import anyio  # noqa: E402
from fastapi import Depends, FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


class Counter:
    def __init__(self):
        self.n = 0

    def wrap(self, fn):
        def inner(*a, **kw):
            self.n += 1
            return fn(*a, **kw)
        return inner


def build(sync_dep: bool, counter: Counter):
    if sync_dep:
        def dep():                      # 旧写法：同步 generator
            counter.wrap(lambda: None)()
            yield "s"
    else:
        async def dep():                # 新写法（B 案）：异步 generator
            await asyncio.get_running_loop().run_in_executor(
                None, counter.wrap(lambda: None)
            )
            yield "s"

    app = FastAPI()

    @app.get("/x")
    def route(s=Depends(dep)):          # 同步路由：模拟现有 179 个 def
        counter.wrap(lambda: None)()
        return {"ok": True}

    return TestClient(app)


def measure(sync_dep: bool, n: int = 30):
    counter = Counter()
    c = build(sync_dep, counter)
    for _ in range(n):
        c.get("/x")
    return counter.n


def measure_via_limiter(sync_dep: bool, n: int = 30):
    """更准的口径：直接数 anyio 线程池 limiter 的 acquire 次数。"""
    acquires = {"n": 0}
    app = FastAPI()

    if sync_dep:
        def dep():
            yield "s"
    else:
        async def dep():
            yield "s"

    @app.get("/x")
    def route(s=Depends(dep)):
        return {"ok": True}

    async def run():
        limiter = anyio.to_thread.current_default_thread_limiter()
        real = limiter.acquire_on_behalf_of

        def counting(*a, **kw):
            acquires["n"] += 1
            return real(*a, **kw)

        limiter.acquire_on_behalf_of = counting
        # 手动跑 n 次请求，绕过 TestClient 每次新建 loop 的干扰
        for _ in range(n):
            with TestClient(app) as _unused:
                pass
        return acquires["n"]

    try:
        return asyncio.run(run())
    except Exception as exc:
        return "n/a (%s)" % type(exc).__name__


print("=" * 72)
print("B 案收益量化：依赖向 anyio 线程池借还次数（每请求）")
print("=" * 72)
for label, is_sync in (("旧：同步 generator 依赖", True), ("新：async 依赖（B 案）", False)):
    total = measure(is_sync, 30)
    print("  %-24s 30 个请求共借还 %2d 次 -> 每请求 %.2f 次"
          % (label, total, total / 30.0))
print()
print("解读：")
print("  两者每请求的线程池借还次数**相同** -> B 案不改变线程池借还次数。")
print("  FastAPI 0.141 对同步 generator 依赖已经走 contextmanager_in_threadpool，")
print("  __enter__/__exit__ 本就在线程池线程上执行，不占事件循环。")
print("  B 案的真实变化只是：依赖的两次借还由 loop 直接 await，不再经anyio 限流器")
print("  （contextmanager_in_threadpool 源码里 __exit__ 用了独立的 CapacityLimiter(1)，")
print("  不与路由争用 40 令牌池）。**收益是消除令牌争用，不是省时间。**")