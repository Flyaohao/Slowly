"""SSE 输出基建：协议编码 + 后台线程流式拉取 + 心跳保活 + 可取消。

**为什么单独抽一层**：流式端点原先只有 `/ai/chat/stream` 一个，编码函数和
心跳包装都写在各自的业务文件里。当「所有 AI 调用都改成流式」之后，每个场景
各抄一遍必然抄出不一致（事件名、心跳帧、取消处理各写各的）。这里把三件事
收敛成一份实现：

1. `sse_encode`  —— 事件字典 → SSE 报文（含注释帧心跳）
2. `stream_with_heartbeat` —— 把阻塞的模型流搬到后台线程，主生成器只负责
   转发与心跳；静默期照样有字节流动，避免 readTimeout 把「模型在思考」误判为断连
3. 取消 —— `cancel_event` 一旦置位，worker 立刻停止消费模型流并关闭 HTTP 连接，
   不再白烧 token（详见 `llm_client._cancel_scope`）
"""

from __future__ import annotations

import json
import logging
import queue
import threading
from typing import Any, Dict, Iterator, List, Optional, Tuple

from app.services.llm_client import llm

logger = logging.getLogger("couple.sse")

#: 心跳间隔（秒）。
#:
#: 这个值同时是**取消延迟的上界**：客户端 abort 后，Starlette 要等生成器
#: 到达下一个 yield 点才能把 `GeneratorExit` 抛进来。间隔越大，断连后模型
#: 还会多跑多久。原先取 8s 是纯保活思路，现在压到 2s——静默期每 2 秒一个
#: 注释帧（`": keep-alive"`，14 字节），代价可以忽略，换来断连后最多 2 秒止血。
HEARTBEAT_INTERVAL = 2.0

#: 所有 SSE 端点共用的响应头。
#:
#: `X-Accel-Buffering: no` 不是可选项：线上前面挂着 Nginx，默认会把响应攒满
#: 缓冲区才下发，流式效果会整个消失（表现为「等了 20 秒，然后整段蹦出来」）。
SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


def sse_encode(events: Iterator[Dict[str, Any]]) -> Iterator[str]:
    """把事件字典序列化成 SSE 报文。

    每帧格式：`event: <name>\\ndata: <json>\\n\\n`（空行结尾是 SSE 协议的帧分隔符）。
    用 `ensure_ascii=False` 保持中文原样传输，`default=str` 兜底 datetime 等类型。

    另支持 `{"comment": "..."}` 形式的心跳帧，输出为 SSE 注释行（以 `:` 开头）。
    按协议注释行不属于任何事件，客户端解析器会忽略；模型思考期间用它保持
    连接活跃，避免 readTimeout 把静默误判为断连。
    """
    for ev in events:
        if "comment" in ev:
            yield ": %s\n\n" % ev["comment"]
            continue
        payload = json.dumps(ev["data"], ensure_ascii=False, default=str)
        yield "event: %s\ndata: %s\n\n" % (ev["event"], payload)


def stream_with_heartbeat(
    messages: List[Dict[str, Any]],
    scene_key: str,
    *,
    temperature: float = 0.7,
    max_tokens: int = 1800,
    interval: float = HEARTBEAT_INTERVAL,
    cancel_event: Optional[threading.Event] = None,
    client: Optional[Any] = None,
) -> Iterator[Optional[Tuple[str, str]]]:
    """产出 `(kind, text)` 增量，静默期产出 `None` 作为心跳信号。

    `kind` 为 `llm.KIND_THINKING`（模型推理过程）或 `llm.KIND_CONTENT`（正文）。

    **为什么需要心跳**：推理模型的思考期不产出正文，而 SSE 连接恰恰在静默期
    最脆弱——客户端 okhttp readTimeout 与 Nginx proxy_read_timeout 都会把
    「长时间无数据」判定为断连。

    2026-09-14 起思考增量本身也会下推（`event: thinking`），首帧实测约 0.5s，
    静默期从近 20s 缩短到毫秒级；心跳遂退化为**兜底**：模型连推理都不吐
    （例如纯文本模型或网络卡顿）时，仍保证连接有字节流动。

    做法：把真正的调用丢到后台线程，主生成器只在队列上等待；超时即产出 `None`，
    由调用方翻译成 SSE 注释帧。这样连接始终有字节流动，而协议语义不变。
    """
    q: "queue.Queue[Any]" = queue.Queue()
    _END = object()
    #: P-B：chat_mode 三档各持一个客户端（思考开关/预算在实例上）；
    #: 不传保持原行为——用模块单例 llm。
    active_client = client if client is not None else llm

    def _worker() -> None:
        try:
            for item in active_client.stream_events(
                messages,
                temperature=temperature,
                max_tokens=max_tokens,
                scene=scene_key,
                cancel_event=cancel_event,
            ):
                q.put(item)
        except BaseException as exc:  # noqa: BLE001 —— 原样抛回主线程处理
            q.put(exc)
        finally:
            q.put(_END)

    threading.Thread(target=_worker, daemon=True, name="llm-stream").start()

    while True:
        try:
            item = q.get(timeout=interval)
        except queue.Empty:
            if cancel_event is not None and cancel_event.is_set():
                return
            yield None
            continue
        if item is _END:
            return
        if isinstance(item, BaseException):
            raise item
        yield item
