"""行动行路由与风险等级的**跨端契约**测试（静态分析，不需要 API Key / 数据库）。

## 为什么需要这个测试

整改 B4.3 的 P0-4 与 P1-7 各新增了一组**必须在两端逐字一致**的字面量：

- `intent`：后端 `ai_output.ActionIntent` 产出，客户端 `AiIntent.fromWire` 消费，
  再喂进 `AiChatViewModel.orderedActionsFor` 的路由表；
- `risk_level`：后端 `ai_output.RiskLevel` 产出，客户端 `AiRiskLevel.fromWire`
  消费，再喂进 `mediationBlockedByRisk` 的放行/阻断判定。

`tests/test_client_dto_contract.py` 只查「后端给了字段，客户端接得住吗」——
它接得住一个**取值对不上**的字段：Kotlin 侧 `fromWire` 认不出后端新加的
`intent` 值时会静默回落到 `UNKNOWN`（动作行只剩「复制」），认不出新加的
风险档位时会静默落到 `UNKNOWN`（安全侧，但会把正常语境也拦掉）。

也就是说，两端各自都「能跑、不报错、测试全绿」，只是行为悄悄变了。
这正是本仓库 2026-09-14 那两次 P0 的同一种形状（冷战页六区块全空、
AI 记忆页永远空白），所以按同一套办法处理：把两边的字面量放在一起比。

## 检查项

- **A. 意图取值集合**：后端 `ActionIntent` 的每个 value，客户端 `AiIntent.fromWire`
  必须能认出来（认不出来的值会静默回落到兜底意图）。
- **B. 路由表覆盖**：客户端 `AiIntent` 的每个值，`orderedActionsFor` 的
  `when (intent)` 必须有一个分支。少一个分支，那个意图就永远只剩最保守的
  那组动作——而且 Kotlin 不会报错（`when` 对 enum 是穷尽检查，但
  `orderedActionsFor` 的默认参数会把它藏起来，见该函数签名）。
- **C. 风险取值集合**：后端 `RiskLevel` 与客户端 `AiRiskLevel.WIRE_VALUES`
  逐值相等（集合相等，不是包含——多一个少一个都算漂移）。
- **D. 放行集合一致**：`normal` 与 `heated_conflict` 是仅有的两个「允许双人动作」
  的档位，两端必须给出同一个判定；其余（含 `unknown`）两端都阻断。

## 不做的事

不检查动作的具体排序——那是产品决策，且已在
`android/.../AiActionRoutingTest.kt` 里逐个意图断言过了。
本测试只保证**契约层面**不断裂。

运行：
    cd backend
    python tests/test_action_routing_contract.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import List, Set

BACKEND_DIR = Path(__file__).resolve().parents[1]
ANDROID_DIR = BACKEND_DIR.parent / "android"
sys.path.insert(0, str(BACKEND_DIR))

from app.schemas.ai_output import ActionIntent, RiskLevel  # noqa: E402
from app.services import mediation_service  # noqa: E402
from app.services.safety_service import RISK_LEVEL_ORDER  # noqa: E402

AI_CHAT_VM_REL = (
    "feature/couple/src/main/java/com/couple/translator/feature/couple/ai/AiChatViewModel.kt"
)
AI_RISK_LEVEL_REL = (
    "core/src/main/java/com/couple/translator/core/ui/components/AiRiskLevel.kt"
)

#: 客户端明确放行双人动作的两个档位。**写死在这里**而不是从 Kotlin 里解析，
#: 正是为了让「客户端把某一档悄悄改成放行」这件事必须改测试才能通过。
CLIENT_ALLOWED_RISK = {"normal", "heated_conflict"}


def _read(rel: str) -> str:
    path = ANDROID_DIR / rel
    if not path.is_file():
        raise AssertionError("找不到客户端文件：%s" % path)
    return path.read_text(encoding="utf-8")


def _block(text: str, start_marker: str) -> str:
    """取从 start_marker 起、括号配平的一段（粗粒度，够用即可）。"""
    idx = text.index(start_marker)
    depth = 0
    out: List[str] = []
    for ch in text[idx:]:
        out.append(ch)
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                break
    return "".join(out)


def client_intent_enum_values() -> Set[str]:
    """客户端 `enum class AiIntent` 里的取值名（大写蛇形）。"""
    block = _block(_read(AI_CHAT_VM_REL), "enum class AiIntent")
    body = block.split("{", 1)[1].split("companion object", 1)[0]
    return set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\s*[,;]?\s*$", body, flags=re.M))


def client_from_wire_intent_map() -> Set[str]:
    """客户端 `AiIntent.fromWire` 认得的 wire 字面量。"""
    block = _block(_read(AI_CHAT_VM_REL), "fun fromWire(raw: String?): AiIntent")
    return set(re.findall(r'"([a-z_]+)"\s*->', block))


def client_intent_wire_values() -> Set[str]:
    """客户端 `AiIntent.WIRE_VALUES` 里声明的字面量。"""
    text = _read(AI_CHAT_VM_REL)
    idx = text.index("val WIRE_VALUES = listOf(")
    end = text.index(")", idx)
    return set(re.findall(r'"([a-z_]+)"', text[idx:end]))


def client_routing_branches() -> Set[str]:
    """`orderedActionsFor` 里 `when (intent)` 覆盖到的意图分支。"""
    text = _read(AI_CHAT_VM_REL)
    idx = text.index("return when (intent)")
    # 取到函数结束（下一个顶层 `}` 之前的 3 个 tab 缩进的 `}`）
    tail = text[idx:]
    end = tail.index("\n}\n")
    return set(re.findall(r"AiIntent\.([A-Z][A-Z0-9_]*)", tail[:end]))


def client_risk_wire_values() -> Set[str]:
    """客户端 `AiRiskLevel.WIRE_VALUES` 里的字面量。"""
    block = _read(AI_RISK_LEVEL_REL)
    idx = block.index("val WIRE_VALUES = listOf(")
    end = block.index(")", idx)
    return set(re.findall(r'"([a-z_]+)"', block[idx:end]))


def client_risk_allows(level: str) -> bool:
    """客户端对某个风险档位的放行判定（只认 Kotlin 里写死的字面量）。"""
    return level in CLIENT_ALLOWED_RISK


def backend_risk_allows(level: str) -> bool:
    """服务端对某个风险档位的放行判定：能不能产出双人调解产物。"""
    return level not in mediation_service.BLOCKING_RISK_LEVELS


def main() -> int:
    failures: List[str] = []
    checks = 0

    def check(cond: bool, msg: str) -> None:
        nonlocal checks
        checks += 1
        if not cond:
            failures.append(msg)

    # ---- A. 意图取值集合 ---------------------------------------------- #
    backend_intents = {m.value for m in ActionIntent}
    recognized = client_from_wire_intent_map()
    declared = client_intent_wire_values()
    check(
        backend_intents == declared,
        "意图 wire 取值集合两端不一致：后端 %s / 客户端 WIRE_VALUES %s"
        % (sorted(backend_intents), sorted(declared)),
    )
    for value in sorted(backend_intents):
        check(
            value in recognized,
            "后端 ActionIntent 有 %r，客户端 AiIntent.fromWire 认不出来"
            "（会静默回落到兜底意图）" % value,
        )
    for value in sorted(recognized):
        check(
            value in backend_intents,
            "客户端认 %r，但后端 ActionIntent 里没有这个取值" % value,
        )

    # ---- B. 路由表覆盖 ------------------------------------------------ #
    enum_values = client_intent_enum_values()
    branches = client_routing_branches()
    check(
        enum_values == {
            "EXPRESSION_REWRITE", "PARTNER_TRANSLATE", "PRIVATE_ADVISOR",
            "EMOTION_SUPPORT", "COLD_WAR", "RELATIONSHIP_REVIEW", "UNKNOWN",
        },
        "客户端 AiIntent 取值集合变了：%s" % sorted(enum_values),
    )
    for value in sorted(enum_values):
        check(
            value in branches,
            "AiIntent.%s 在 orderedActionsFor 里没有分支——该意图永远只剩最保守的动作"
            % value,
        )

    # ---- C. 风险取值集合 ---------------------------------------------- #
    backend_risks = {m.value for m in RiskLevel}
    client_risks = client_risk_wire_values()
    check(
        backend_risks == client_risks,
        "风险取值集合两端不一致：后端 %s / 客户端 %s"
        % (sorted(backend_risks), sorted(client_risks)),
    )
    check(
        mediation_service.RISK_LEVEL_UNKNOWN in backend_risks,
        "unknown 必须是客户端可见的取值，不能只是服务端内部字符串",
    )
    check(
        mediation_service.RISK_LEVEL_UNKNOWN not in RISK_LEVEL_ORDER,
        "unknown 不是危险度档位，不得混进 RISK_LEVEL_ORDER",
    )

    # ---- D. 放行集合一致 ---------------------------------------------- #
    for level in sorted(backend_risks):
        check(
            backend_risk_allows(level) == client_risk_allows(level),
            "风险 %r 的放行判定两端不一致：后端=%s 客户端=%s"
            % (level, backend_risk_allows(level), client_risk_allows(level)),
        )
    check(
        client_risk_allows("unknown") is False,
        "unknown 必须阻断（fail closed）",
    )
    check(
        mediation_service.RISK_LEVEL_UNKNOWN in mediation_service.BLOCKING_RISK_LEVELS,
        "unknown 必须列入 BLOCKING_RISK_LEVELS",
    )
    # 模型自造值 / 大小写混杂 / 空串一律归 unknown —— 也就是说，归一化之后的
    # 落点必须是上面这个被两端一致阻断的档位，而不是 normal。
    for raw in (None, "", "  ", "HIGH", "高风险", "3", "very_high", "Normal"):
        got = mediation_service.normalize_risk_level(raw)
        expected = "normal" if raw == "Normal" else mediation_service.RISK_LEVEL_UNKNOWN
        check(
            got == expected,
            "normalize_risk_level(%r) = %r，期望 %r" % (raw, got, expected),
        )

    print("=" * 72)
    if failures:
        print("契约漂移 %d 处（共 %d 项检查）：" % (len(failures), checks))
        for item in failures:
            print("  [FAIL] %s" % item)
        print("=" * 72)
        return 1
    print("行动行路由与风险等级契约两端一致（共 %d 项检查）" % checks)
    print("=" * 72)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
