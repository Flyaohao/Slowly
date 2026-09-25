# -*- coding: utf-8 -*-
"""分层上下文预算（P-C2 §2）——全链路**唯一裁剪层**。

六层内容与三档上限（矩阵来自《前端APP体检与改造设计文档》§2.2，单位沿用
现口径：1 汉字 ≈ 1 token 的**字符近似**，不引 tiktoken——红线：不加新依赖）：

| 层 | 内容                       | quick | deep | expert | 裁剪优先级 |
|----|----------------------------|-------|------|--------|-----------|
| S  | 场景 system + 人格         | 800   | 1200 | 1500   | 不可裁（超了记日志） |
| P  | 画像卡 + 冲突模式          | 800   | 1600 | 2400   | 永不裁（超了记日志） |
| U  | 本轮输入                   | 实占  | 实占 | 实占   | 永不裁     |
| H  | 历史消息                   | 6 条  | 20   | 40     | 第 3（砍最旧的） |
| E  | 召回事件/记忆              | 0     | 5    | 10     | 第 2       |
| M  | 理论片段                   | 0     | 3    | 5      | 第 1（先砍） |
| 合计上限 |                     | ≈2000 |≈6000 | ≈12000  | —          |

规则（prompt §2）：
  1. 按优先级从低到高裁：先砍理论(M)，再砍记忆(E)，再砍陈旧历史(H)；
     画像(P)与本轮(U)永不裁，S 不可裁。
  2. 裁完仍超 budget_total → **不硬塞**，返回 needs_archive=True，由
     `_preprocess` 归档会话（复用 `should_start_new_session` 的 "budget"
     分支），历史清零后重算一次；仍超则记日志放行（S/P 固定成本降不下来）。

与文档矩阵的两处**有意偏离**（注释留痕，判据以约束测试优先）：
  - budget_e = memory_limit（0/5/10），不是文档的 0/3/8——
    `test_memory_retrieval.case_evidence_same_as_prompt` 断言 evidence 与
    直接召回（limit=5）**同源同条数**，两处上限不一致会直接红。
  - budget_h = history_limit（6/20/40），与档位历史条数同一处配置，
    避免两份数字漂移。

约定：
  - sections 键：s/p/u 是 str；h/e/m 是 list（h 元素 str，e=记忆 dict，
    m=理论 chunk dict）。
  - 只按**条数**裁，不裁单条内部；h 保留**最新** budget_h 条（砍最旧的），
    e/m 保留 rank 靠前的（召回侧已按分排好）。
  - 返回新 dict，不改入参。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Tuple

logger = logging.getLogger("couple.context_budget")

#: 数量裁剪顺序（先砍谁在前）
CUT_ORDER = ("m", "e", "h")

#: 各层省略文案
_LAYER_LABEL = {"m": "理论片段", "e": "记忆", "h": "较早的历史消息"}


def _elem_text(elem: Any) -> str:
    """h/e/m 元素 → 可计长度的文本（str 原样；dict 取内容键）。"""
    if isinstance(elem, str):
        return elem
    if isinstance(elem, dict):
        return str(
            elem.get("content") or elem.get("chunk_text") or elem.get("text") or ""
        )
    return str(elem)


def layer_chars(sections: Dict[str, Any]) -> Dict[str, int]:
    """各层字符数（预算核算与测试共用的同一口径）。"""
    h = list(sections.get("h") or [])
    e = list(sections.get("e") or [])
    m = list(sections.get("m") or [])
    return {
        "s": len(str(sections.get("s") or "")),
        "p": len(str(sections.get("p") or "")),
        "u": len(str(sections.get("u") or "")),
        "h": sum(len(_elem_text(x)) for x in h),
        "e": sum(len(_elem_text(x)) for x in e),
        "m": sum(len(_elem_text(x)) for x in m),
    }


def fit_budget(
    sections: Dict[str, Any], mode_cfg: Dict[str, Any]
) -> Tuple[Dict[str, Any], List[str], bool]:
    """把六层裁到档位预算内。

    返回 `(fitted, omitted, needs_archive)`：

    - `fitted`：新 dict（入参不改）；
    - `omitted`：省略说明（直接喂 evidence 的「本轮省略了什么」）；
    - `needs_archive`：条数裁完仍超 `budget_total` → 调用方归档会话再重算。

    > 签名偏离说明：prompt §2 建议返回 2 元组，§7 测试③ 又要求拿到
    > 「超限触发归档」标志——故多返回第 3 位，语义即规则 2。
    """
    s = str(sections.get("s") or "")
    p = str(sections.get("p") or "")
    u = str(sections.get("u") or "")
    h = list(sections.get("h") or [])
    e = list(sections.get("e") or [])
    m = list(sections.get("m") or [])
    omitted: List[str] = []

    def _cap_count(
        items: List[Any], cap: int, label: str, keep: str = "head"
    ) -> List[Any]:
        """按条数截断到 cap；超出则记省略说明。

        keep="head" 保留前 cap 条（e/m 召回已按分排好，砍掉的是弱命中）；
        keep="tail" 保留后 cap 条（h 按时间升序，砍掉的是最旧历史）。
        """
        if cap < 0:
            cap = 0
        if len(items) <= cap:
            return items
        dropped = len(items) - cap
        omitted.append(f"省去 {dropped} 条{_LAYER_LABEL[label]}")
        if not cap:
            return []
        return items[:cap] if keep == "head" else items[len(items) - cap:]

    # ---- 规则 1：M → E → H 逐层裁（P/U/S 不进这个循环）----
    m = _cap_count(m, int(mode_cfg.get("budget_m", 0) or 0), "m")
    e = _cap_count(e, int(mode_cfg.get("budget_e", 0) or 0), "e")
    # H 保留**最新** budget_h 条（砍最旧的）
    h = _cap_count(h, int(mode_cfg.get("budget_h", 0) or 0), "h", keep="tail")

    fitted = {"s": s, "p": p, "u": u, "h": h, "e": e, "m": m}

    # ---- S / P 不可裁：超限只记日志（报错见 needs_archive 之后的 error）----
    budget_s = int(mode_cfg.get("budget_s", 0) or 0)
    if budget_s and len(s) > budget_s:
        logger.debug(
            "[BUDGET] S 层超限（不可裁） s=%d budget_s=%d", len(s), budget_s
        )
    budget_p = int(mode_cfg.get("budget_p", 0) or 0)
    if budget_p and len(p) > budget_p:
        logger.debug(
            "[BUDGET] P 层超限（永不裁） p=%d budget_p=%d", len(p), budget_p
        )

    # ---- 规则 2：条数裁完仍超总额 → 归档，不硬塞 ----
    total = sum(layer_chars(fitted).values())
    budget_total = int(mode_cfg.get("budget_total", 0) or 0)
    needs_archive = bool(budget_total) and total > budget_total
    if needs_archive:
        logger.info(
            "[BUDGET] 超上限，待归档 total=%d budget_total=%d omitted=%s",
            total, budget_total, omitted,
        )
    return fitted, omitted, needs_archive
