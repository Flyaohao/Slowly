"""P-C2 §7：分层预算 fit_budget 纯函数用例（不调模型、不碰真库）。

判据（prompt §7 test_context_budget）：
  ① 三档各裁到上限内；
  ② 优先级正确（理论 M 先被砍，画像 P / 本轮 U 不动）；
  ③ 超上限返回需归档标志（needs_archive）。

顺带守住：CHAT_MODE_CONFIG 六字段齐全（budget_total/s/p/h/e/m），
S/P 超限不裁也不误报省略说明。

运行：cd backend && python tests/test_context_budget.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from app.services.context_budget import (  # noqa: E402
    fit_budget,
    layer_chars,
)
from app.services.prompt_builder import CHAT_MODE_CONFIG  # noqa: E402

FAILURES = []

#: 六字段（P-C2 §2 坑③：矩阵配置在 CHAT_MODE_CONFIG，不另建配置表）
BUDGET_KEYS = ("budget_total", "budget_s", "budget_p", "budget_h", "budget_e", "budget_m")


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def _big_sections(mode: str, scale: int = 5):
    """远超三档上限的六层样本（s/p/u 控制成正常尺寸，便于断言 count 裁剪）。"""
    cfg = CHAT_MODE_CONFIG[mode]
    return {
        "s": "场景系统与人格" * 20,               # ~140 字
        "p": "画像卡与冲突模式" * 20,
        "u": "本轮输入，永不裁",
        "h": [f"user: 历史消息第 {i} 条，内容随便写" for i in range(cfg["budget_h"] * scale)],
        "e": [{"content": f"记忆事件第 {i} 条"} for i in range(cfg["budget_e"] * scale or 10)],
        "m": [{"chunk_text": f"理论片段第 {i} 条"} for i in range(cfg["budget_m"] * scale or 10)],
    }


def case_config_six_keys():
    print("\n[0] CHAT_MODE_CONFIG 六字段齐全")
    for mode in ("quick", "deep", "expert"):
        cfg = CHAT_MODE_CONFIG[mode]
        missing = [k for k in BUDGET_KEYS if k not in cfg]
        check(f"{mode} 含六字段", not missing, f"missing={missing}")
        # 条数字段与档位矩阵同数（同一件事不许两份数字漂移）
        check(f"{mode} budget_h == history_limit",
              cfg["budget_h"] == cfg["history_limit"],
              f"{cfg['budget_h']} vs {cfg['history_limit']}")
        check(f"{mode} budget_e == memory_limit",
              cfg["budget_e"] == cfg["memory_limit"],
              f"{cfg['budget_e']} vs {cfg['memory_limit']}")
        check(f"{mode} budget_m == rag_top_k",
              cfg["budget_m"] == cfg["rag_top_k"],
              f"{cfg['budget_m']} vs {cfg['rag_top_k']}")


def case_fits_within_limits():
    print("\n[1] 三档各裁到上限内")
    for mode in ("quick", "deep", "expert"):
        cfg = CHAT_MODE_CONFIG[mode]
        fitted, omitted, needs_archive = fit_budget(_big_sections(mode), cfg)
        check(f"{mode} h<=budget_h", len(fitted["h"]) <= cfg["budget_h"],
              f"{len(fitted['h'])}>{cfg['budget_h']}")
        check(f"{mode} e<=budget_e", len(fitted["e"]) <= cfg["budget_e"],
              f"{len(fitted['e'])}>{cfg['budget_e']}")
        check(f"{mode} m<=budget_m", len(fitted["m"]) <= cfg["budget_m"],
              f"{len(fitted['m'])}>{cfg['budget_m']}")
        total = sum(layer_chars(fitted).values())
        check(f"{mode} 裁后 total<=budget_total", total <= cfg["budget_total"],
              f"total={total} budget={cfg['budget_total']}")
        check(f"{mode} 无需归档", needs_archive is False)


def case_cut_priority():
    print("\n[2] 优先级：理论 M 先砍 → 记忆 E → 历史 H；P/U/S 不动")
    mode = "deep"
    cfg = CHAT_MODE_CONFIG[mode]
    sections = _big_sections(mode, scale=6)
    # 把 P/U 塞大也不许被裁（永不裁）
    sections["p"] = "画像卡内容" * 400          # 2000 字 > budget_p=1600
    sections["u"] = "本轮输入很长但永不裁" * 50  # 500 字
    p_before, u_before, s_before = sections["p"], sections["u"], sections["s"]

    fitted, omitted, needs_archive = fit_budget(sections, cfg)

    check("P 未被裁", fitted["p"] == p_before)
    check("U 未被裁", fitted["u"] == u_before)
    check("S 未被裁", fitted["s"] == s_before)
    check("省略说明按 M→E→H 顺序",
          len(omitted) == 3
          and omitted[0].endswith("理论片段")
          and omitted[1].endswith("记忆")
          and omitted[2].endswith("较早的历史消息"),
          str(omitted))
    check("省略说明不提画像/本轮", not any(("画像" in o) for o in omitted), str(omitted))
    # H 砍最旧、留最新：最后一条应是原列表末尾
    check("H 保留最新一条", fitted["h"][-1] == sections["h"][-1])
    # E 保留 rank 最前（召回序头部）
    check("E 保留头部命中", fitted["e"][0] == sections["e"][0])
    # 只超 budget_p、未超 budget_total → 不归档（P 超限仅记日志）
    check("P 超 budget_p 但总额内 → 不归档", needs_archive is False,
          f"total={sum(layer_chars(fitted).values())}")


def case_needs_archive():
    print("\n[3] 超上限返回需归档标志（条数裁完仍超总额）")
    mode = "deep"
    cfg = CHAT_MODE_CONFIG[mode]

    # 3a. 条数都在限内，但 U 巨大（如超长粘贴）→ 归档也降不下来，仍须报标志
    sections = {
        "s": "系统" * 100,
        "p": "画像" * 100,
        "u": "超长输入" * 1500,  # 7500 字 > 6000
        "h": [], "e": [], "m": [],
    }
    fitted, omitted, needs_archive = fit_budget(sections, cfg)
    check("U 巨大 → needs_archive=True", needs_archive is True)
    check("U 不被裁", fitted["u"] == sections["u"])

    # 3b. S 巨大（模板膨胀）→ S 不可裁，同样报归档标志（由上层记日志/处理）
    sections = {
        "s": "膨胀的系统模板" * 1000,
        "p": "", "u": "你好", "h": [], "e": [], "m": [],
    }
    fitted, omitted, needs_archive = fit_budget(sections, cfg)
    check("S 超限不被裁", fitted["s"] == sections["s"])
    check("S 超总额 → needs_archive=True", needs_archive is True)
    check("S 超限不进省略说明（不可裁≠省略）", not any("系统" in o for o in omitted),
          str(omitted))

    # 3c. 总额内 → False
    sections = {
        "s": "系统" * 50, "p": "画像" * 50, "u": "你好",
        "h": ["user: 一条"], "e": [{"content": "一条记忆"}],
        "m": [{"chunk_text": "一条理论"}],
    }
    fitted, omitted, needs_archive = fit_budget(sections, cfg)
    check("总额内 → needs_archive=False", needs_archive is False)
    check("无需裁剪时省略说明为空", omitted == [], str(omitted))


def case_immutable_input():
    print("\n[4] 入参不被修改（返回新 dict）")
    cfg = CHAT_MODE_CONFIG["deep"]
    sections = _big_sections("deep")
    snapshot = {k: (list(v) if isinstance(v, list) else v) for k, v in sections.items()}
    fit_budget(sections, cfg)
    check("h 未被原地截断", sections["h"] == snapshot["h"])
    check("e 未被原地截断", sections["e"] == snapshot["e"])
    check("m 未被原地截断", sections["m"] == snapshot["m"])


def main() -> int:
    print("=" * 72)
    print("P-C2 §7 分层预算 context_budget.fit_budget")
    print("=" * 72)
    case_config_six_keys()
    case_fits_within_limits()
    case_cut_priority()
    case_needs_archive()
    case_immutable_input()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
