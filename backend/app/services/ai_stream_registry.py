"""正在进行的 AI 生成任务 → 取消信号 的进程内注册表。

**为什么要显式取消端点**：客户端 abort 连接当然也能让服务端停手，但那条路
依赖 Starlette 把 `GeneratorExit` 抛进生成器，而生成器只在 yield 点收得到，
中间必然有一小段延迟（见 `sse.HEARTBEAT_INTERVAL` 的说明）。用户点了
「停止生成」却还要多等一两秒、模型还在继续说话，观感上就是「按钮没用」。

所以用户主动中断走这条快路：客户端拿到 `meta` 帧里的 `generation_id` 后
调用 `POST /ai/generations/{id}/cancel`，本模块把对应的 `Event` 置位，
worker 线程立刻关闭到模型的 HTTP 连接。

**进程内**：多 worker（`uvicorn --workers N`）部署时注册表不跨进程共享，
取消请求可能落到另一个进程上而 miss。当前线上是单进程，够用；
真要多进程时需要换成 Redis 之类的外部信号，届时只需替换本模块的实现。
"""

from __future__ import annotations

import threading
from typing import Dict, Optional

_lock = threading.Lock()
_events: Dict[int, threading.Event] = {}


def register(generation_id: int) -> threading.Event:
    """登记一次生成，返回它对应的取消信号。"""
    event = threading.Event()
    with _lock:
        _events[generation_id] = event
    return event


def cancel(generation_id: int) -> bool:
    """置位取消信号。返回 False 表示没有这个正在进行的生成（可能已结束）。"""
    with _lock:
        event: Optional[threading.Event] = _events.get(generation_id)
    if event is None:
        return False
    event.set()
    return True


def unregister(generation_id: int) -> None:
    """生成结束后清理，避免注册表无限增长。"""
    with _lock:
        _events.pop(generation_id, None)


def active_count() -> int:
    """当前正在进行的生成数（健康检查 / 测试用）。"""
    with _lock:
        return len(_events)
