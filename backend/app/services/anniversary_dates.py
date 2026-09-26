"""纪念日的日期语义（整改契约 §8.8）。

必须区分**一次性**与**每年重复**：

- 每年重复（默认）：取今天的下一次月日，跨年自动滚动；
- 一次性：只在当年发生，过去就是过去了，不再编造一个「下一次」。

抽成纯函数（不接 Session、不查库）是因为这条规则同时被首页与纪念日接口使用，
而且边界（跨年、闰日）必须能用断言钉住，而不是靠肉眼看代码。

## 顺带修掉的两个真实缺陷

1. 原先首页对每条纪念日都做 `anniv_date.replace(year=today.year)`，
   用户只要填过 2 月 29 日，**平年首页就整页 500**（`ValueError: day is out of
   range for month`）。这里把闰日夹到当年 2 月最后一天。
2. 原先按「原始日期」排序后取第一条能算出来的，于是「一次性且已过去」的纪念日
   也会被顶到下一年，渲染成「还有 55 天」，和它旁边写着的年份互相打架——
   正是契约点名要禁止的年份冲突。
"""

import calendar
from datetime import date
from typing import Optional


def safe_replace_year(d: date, year: int) -> date:
    """换年份；闰日 2/29 落在平年时夹到 2/28。

    `date(2024, 2, 29).replace(year=2026)` 直接抛 ValueError——纪念日表单
    允许用户填 2 月 29 日，所以这不是理论问题。
    """
    try:
        return d.replace(year=year)
    except ValueError:
        return date(year, 2, calendar.monthrange(year, 2)[1])


def next_occurrence(d: date, repeat_annually: bool, today: date) -> Optional[date]:
    """下一次发生的日期；`None` = 不会再发生（一次性且已经过去）。

    当天算「就是今天」（`>= today`），不是「已过」。
    """
    if not repeat_annually:
        return d if d >= today else None
    candidate = safe_replace_year(d, today.year)
    if candidate < today:
        candidate = safe_replace_year(d, today.year + 1)
    return candidate


def days_until(d: date, repeat_annually: bool, today: date) -> Optional[int]:
    """距下一次发生还有几天；不会再发生时返回 `None`。"""
    occurrence = next_occurrence(d, repeat_annually, today)
    return None if occurrence is None else (occurrence - today).days
