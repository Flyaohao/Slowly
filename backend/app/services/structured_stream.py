"""结构化场景的流式切分。

## 要解决的问题

「结构化输出」和「流式输出」天然打架：

- 结构化输出依赖 Function Calling，模型必须**一次性**回填完整 JSON，
  半截 JSON 对前端毫无意义，所以没法逐 token 推给用户；
- 但信件解读、冷战开解这些场景，用户真正想先看到的是**能读的话**，
  而不是最后才「哐」地弹出一张卡片。

## 解法

一次模型调用，要求它按固定顺序输出：

```
（推理模型的 reasoning_content，走 thinking 通道，不在此处）
给用户看的自然语言解读正文……
<<<STRUCTURED>>>
{"summary": "...", "key_concerns": [...], ...}
```

切分器逐块吃进正文增量：
- 分隔符**之前**的文本实时下推给用户（打字机效果的来源）；
- 分隔符**之后**的文本只累积、不下推，最后一次性解析成结构化字段落库。

这样用户既拿到了「一个字一个字往外蹦」的即时反馈，业务层又拿到了完整的
结构化数据，且**只调用了一次模型**——比「先流式出一遍、再结构化调一次」
省掉一半的 token 与等待。

## 边界处理

分隔符完全可能被拆在两个 chunk 里（`<<<STRUC` + `TURED>>>`）。
因此不能见到 `find` 不中就直接下发——那样会把分隔符的一半漏给用户。
正确做法是：每次都把「可能是分隔符前缀」的尾部扣住不发，等下一个 chunk
到齐了再判断。`_suffix_prefix_len` 就是干这个的。
"""

from __future__ import annotations

import json
import re
from typing import Any, Dict, List, Optional, Tuple

#: 正文与结构化结果之间的分隔符。
#:
#: 选这个形状的理由：模型几乎不可能在自然语言里自发写出三段尖括号，
#: 而它又能被 `str.find` 精确匹配，不需要正则回溯。
STRUCTURED_MARKER = "<<<STRUCTURED>>>"


class StructuredStreamSplitter:
    """把「正文 + 分隔符 + JSON」的单条流切成两路输出。"""

    def __init__(self, marker: str = STRUCTURED_MARKER) -> None:
        self.marker = marker
        self.phase = "content"  #: content | structured
        self._content_parts: List[str] = []
        self._pending = ""      #: 可能是分隔符前缀的尾部，暂不下发
        self.structured_raw = ""

    # ------------------------------------------------------------------ #
    def feed(self, text: str) -> str:
        """吃进一段正文增量，返回**本次可以下发给用户**的可见文本。

        返回值可能为空串（增量全部被扣在缓冲区里等分隔符判明），
        调用方需要容忍并跳过空串。
        """
        if not text:
            return ""

        if self.phase == "structured":
            self.structured_raw += text
            return ""

        self._pending += text

        cut = self._pending.find(self.marker)
        if cut != -1:
            visible = self._pending[:cut]
            self.structured_raw = self._pending[cut + len(self.marker):]
            self._pending = ""
            self.phase = "structured"
            if visible:
                self._content_parts.append(visible)
            return visible

        keep = self._suffix_prefix_len(self._pending, self.marker)
        if keep:
            visible, self._pending = self._pending[:-keep], self._pending[-keep:]
        else:
            visible, self._pending = self._pending, ""
        if visible:
            self._content_parts.append(visible)
        return visible

    def finish(self) -> Tuple[str, Optional[Dict[str, Any]]]:
        """流结束后的收尾，返回 `(正文全文, 结构化字典或 None)`。

        模型可能压根没输出分隔符（没遵守格式，或者正文写到一半被中断）。
        此时把扣住的尾巴无条件放出来当正文，结构化结果留空由调用方决定怎么兜底。
        """
        if self.phase == "content" and self._pending:
            self._content_parts.append(self._pending)
            self._pending = ""

        content = "".join(self._content_parts)
        structured = parse_structured_payload(self.structured_raw) if self.structured_raw else None
        return content, structured

    @property
    def content_so_far(self) -> str:
        """当前已产出的可见正文（含尚在缓冲区里的尾巴），供中断时保存半成品。"""
        return "".join(self._content_parts) + (self._pending if self.phase == "content" else "")

    # ------------------------------------------------------------------ #
    @staticmethod
    def _suffix_prefix_len(text: str, marker: str) -> int:
        """`text` 末尾有多少个字符是 `marker` 的前缀（最多 len(marker)-1 个）。

        例：marker = `<<<S>`、text 末尾是 `<` → 返回 1，这 1 个字符先不发。
        """
        max_keep = min(len(text), len(marker) - 1)
        for k in range(max_keep, 0, -1):
            if text.endswith(marker[:k]):
                return k
        return 0


def parse_structured_payload(raw: str) -> Optional[Dict[str, Any]]:
    """把分隔符之后的原始文本解析成字典。

    模型经常会「手滑」把 JSON 包进 ```json 代码块，或者前后带一句
    「以下是结果：」，所以先剥围栏、再退化为「取第一个 `{` 到最后一个 `}`」。
    解析不出来就返回 `None`，由调用方决定降级策略，不在这里抛异常。
    """
    text = (raw or "").strip()
    if not text:
        return None

    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    candidate = fenced.group(1) if fenced else None
    if candidate is None:
        start, end = text.find("{"), text.rfind("}")
        if start == -1 or end <= start:
            return None
        candidate = text[start:end + 1]

    try:
        data = json.loads(candidate)
    except ValueError:
        return None
    return data if isinstance(data, dict) else None
