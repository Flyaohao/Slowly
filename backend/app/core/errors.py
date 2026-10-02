"""业务错误码的统一安全出口。

## 为什么需要这个模块

项目约定：service 层用 `raise ValueError("60001")` 抛业务码，路由层
`except ValueError` 接住后 `int(code)` 转成响应里的 `code`。

问题在于 **`str(e)` 不保证是纯数字**。`int()` 一旦炸，这个新异常会
**绕过**刚写好的业务错误路径（连 `error_map` 的兜底文案都丢掉），
一路冒到 `main.py` 的全局兜底，变成 `code 10000`「服务器内部错误」。
本来是「信件不存在」，用户看到的是「服务器内部错误」。

非数字来源有四类，全部实测确认过：

1. **service 抛中文消息**。例：`memory_service.py` 的
   `raise ValueError("记忆不存在")`、`room_advisor_service.py` 的
   `raise ValueError("room_not_found")`。这些是给人看的，不是业务码。
2. **Pydantic `ValidationError`**。它**继承自 `ValueError`**，所以
   `except ValueError` 会把它一起吃掉，而 `str(e)` 是一长串英文
   （`1 validation error for M\\na\\n  Input should be...`），`int()` 必炸。
   （`llm_client._validate` 在兜底重试耗尽后就是 `raise` 出去的。）
3. **`json.JSONDecodeError`**。同样是 `ValueError` 子类。
4. **`int()` / `float()` 转换失败**。DB 里有脏数据时
   `int(row.mediation_revision)` 会抛，消息是
   `invalid literal for int() with base 10: 'abc'`。

结论：**`except ValueError` + `int(code)` 这个写法本身就是脆弱契约**，
不能靠「service 只抛数字码」这个约定兜住 —— 约定已经在现实代码里被破了。

## 用法

```python
except ValueError as e:
    code = str(e)
    sc, msg = error_map.get(code, (400, "获取失败"))
    raise HTTPException(
        status_code=sc,
        detail={"code": safe_business_code(code, sc), "message": msg, "data": None},
    )
```

`status` 传路由已经决定要抛的 HTTP 状态码，非数字时用它反查业务码兜底，
保证「状态码 / 业务码 / 文案」三者口径一致（复用 `main.py` 的映射表）。
"""

import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)


#: HTTP 状态 → 业务码兜底。**必须与 `main.py` 的 `_STATUS_TO_BUSINESS_CODE` 保持一致**，
#: 否则同一个错误会因出口不同返回两个码，客户端按 code 分支就会漏掉。
_STATUS_TO_BUSINESS_CODE = {
    400: 10001,
    404: 10004,
    405: 10005,
    409: 10009,
    415: 10015,
    422: 10002,
    429: 10029,
    500: 10000,
}

#: 兜底的兜底：不属于任何已知 HTTP 语义的通用内部错误。
DEFAULT_BUSINESS_CODE = 10000


def safe_business_code(
    raw: Any,
    status: Optional[int] = None,
    default: Optional[int] = None,
) -> int:
    """把 `str(exc)` 安全转成业务码，永不抛异常。

    Args:
        raw: 通常是 `str(e)`。非字符串（None / int / 异常对象）也安全。
        status: 路由层已决定的 HTTP 状态码。非数字码时用它反查兜底业务码。
        default: 显式兜底业务码，优先于 `status` 推导。

    Returns:
        纯数字字符串 → 对应 int；否则 → 兜底业务码（默认 10000）。

    非数字时会打一条 warning：这不是静默降级，而是**契约破损的信号**——
    某个 service 抛了人话消息、或 Pydantic/JSON/int 转换异常漏进了
    `except ValueError`。留日志才能定位到具体是哪个 service。
    """
    text = str(raw).strip()

    if text.isdigit():
        return int(text)

    if default is not None:
        fallback = default
    elif status is not None:
        fallback = _STATUS_TO_BUSINESS_CODE.get(status, DEFAULT_BUSINESS_CODE)
    else:
        fallback = DEFAULT_BUSINESS_CODE

    logger.warning(
        "[业务码] 捕获到非数字错误码，已回退业务码 %s（HTTP %s）：%r",
        fallback,
        status,
        text,
    )
    return fallback
