# -*- coding: utf-8 -*-
"""真调用例的统一守卫：**默认不打真模型**，显式开才打。

## 为什么需要（2026-10-02）

全量跑测试是唯一能发现回归的手段，但仓库里有 5 个套件会**真调LLM**
（`test_viewpoint_analysis` / `test_letter_stream` / `test_sse` /
`test_feedback_loop` / `test_mediation_safety_terminal`）。它们是仅有的
信号源，也是最大的坑：

1. **烧钱烧额度**。模型有免费额度上限（阿里云百炼 `AllocationQuota.FreeTierOnly`
   —— 2026-10-02 实测：连跑几轮全量套件就把当月免费额度打满，403 全线飘红）。
2. **信号被污染**。额度一耗尽，这些套件**必然 FAIL**，于是「23 个失败」里
   有 5 个其实是环境问题不是回归，排查时要先分清哪些是真bug—— 极易误判。
3. **慢**。真模型单次结构化调用约 30s，长 prompt 更久。

## 用法

真调用例在 `main()` 开头调一次：

```python
from live_llm_guard import live_llm_enabled, skip_reason

if not live_llm_enabled():
    print("跳过真调用例：%s" % skip_reason())
    return 0
```

默认跳过。显式要跑：

```bash
LIVE_LLM=1 python tests/test_viewpoint_analysis.py
```

或单个开关（`LIVE_LLM_VIEWPOINT=1` 可只放开这一个）。

**只有两种情况才该开**：① 用户明确要求真机/真模型验收 ② 额度刚恢复，
要确认「改了提示词后模型输出是否真的变对」。日常回归不要开。

## 为什么不直接改成 mock

这5 个套件的价值恰恰是「端到端真跑」——它们是唯一能验证
「提示词改了之后模型真的听话」的手段。全改成 mock 会让这层验证消失，
以后提示词回归（模型不认约束、又输出英文/编造对方立场）将无人发现。
所以：**默认关，而不是删掉**。
"""
import os

#: 全局总开关
_ENV_GLOBAL = "LIVE_LLM"

#: 单套件开关名 -> 环境变量名。值为 "1" 时该套件即使没开总开关也允许跑。
SUITE_SWITCHES = {
    "viewpoint_analysis": "LIVE_LLM_VIEWPOINT",
    "letter_stream": "LIVE_LLM_LETTER_STREAM",
    "sse": "LIVE_LLM_SSE",
    "feedback_loop": "LIVE_LLM_FEEDBACK_LOOP",
    "mediation_safety_terminal": "LIVE_LLM_MEDIATION_SAFETY",
}

_TRUTHY = ("1", "true", "yes", "on")


def _env(name):
    return os.getenv(name, "").strip().lower()


def live_llm_enabled(suite=None):
    """是否允许本套件真调 LLM。

    :param suite: 套件名（`SUITE_SWITCHES` 的键）。``None`` 表示只查总开关。
    :return: True=允许真调；False=应跳过。
    """
    if _env(_ENV_GLOBAL) in _TRUTHY:
        return True
    if suite and _env(SUITE_SWITCHES.get(suite, "")) in _TRUTHY:
        return True
    return False


def skip_reason(suite=None):
    """人可读的跳过原因，打印用。"""
    switch = SUITE_SWITCHES.get(suite) if suite else None
    if switch:
        return (
            "本套件会真调 LLM（烧额度且额度耗尽会伪装成失败）。"
            "确认要跑请设 %s=1 或 %s=1" % (switch, _ENV_GLOBAL)
        )
    return (
        "本套件会真调 LLM（烧额度且额度耗尽会伪装成失败）。"
        "确认要跑请设 %s=1" % _ENV_GLOBAL
    )