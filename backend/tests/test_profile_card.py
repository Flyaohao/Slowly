"""
P0-1 验收：画像卡中文语义注入。

断言三件事：
  a) build_profile_card 输出不含任何英文 snake_case key（11 个维度逐一查）
  b) 高分维度出现在输出里，且带中文行为化描述（非量表语言）
  c) profile=None 时 _format_profile 返回「未完成问卷」

运行：cd backend && python tests/test_profile_card.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.profile_service import (  # noqa: E402
    BAND_LABELS,
    DIMENSION_DEFINITIONS,
    PROFILE_TYPE_LABELS,
    _pick_band,
    build_profile_card,
)

ALL_DIM_KEYS = list(DIMENSION_DEFINITIONS.keys())

# 与 scripts/seed_test_profile.py 同一套分布：覆盖 4 档 + 焦虑型
TEST_SCORES = {
    "attachment_anxiety": 72.0,
    "attachment_avoidance": 30.0,
    "conflict_pursue": 65.0,
    "conflict_withdraw": 40.0,
    "defensive_response": 48.0,
    "emotional_validation_need": 78.0,
    "factual_explanation_need": 45.0,
    "personal_space_need": 32.0,
    "reassurance_need": 82.0,
    "directness_preference": 55.0,
    "softness_preference": 18.0,
}

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def case_no_english_keys():
    print("\n[1] 输出不含英文 snake_case key")
    card = build_profile_card("anxious", TEST_SCORES, 0.77)
    for key in ALL_DIM_KEYS:
        check(f"不含 {key}", key not in card, card)
    # 顺带确认没有 key=value 形态的残留
    check("不含 key=value 形态", "=72" not in card and "attachment" not in card)


def case_high_dims_with_behavior():
    print("\n[2] 高分维度带中文行为化描述")
    card = build_profile_card("anxious", TEST_SCORES, 0.77)

    # 离 50 最远的 4 个：82/78/72/18
    top4_keys = sorted(
        TEST_SCORES.items(),
        key=lambda kv: abs(kv[1] - 50),
        reverse=True,
    )[:4]
    top4_labels = [DIMENSION_DEFINITIONS[k]["label"] for k, _ in top4_keys]
    for label in top4_labels:
        check(f"top4 维度 [{label}] 出现", label in card)

    # 高分维度必须带行为化文案（取 high 档原文核对）
    anxiety_behavior = DIMENSION_DEFINITIONS["attachment_anxiety"]["bands"]["high"][1]
    reassurance_behavior = DIMENSION_DEFINITIONS["reassurance_need"]["bands"]["high"][1]
    # 72 分落在 mid_high 档，82 分落在 high 档
    anxiety_mid_high = DIMENSION_DEFINITIONS["attachment_anxiety"]["bands"]["mid_high"][1]
    check("依恋焦虑 72 → mid_high 行为文案", anxiety_mid_high in card, card)
    check("安全感 82 → high 行为文案", reassurance_behavior in card, card)
    # 反例：量表语言不得出现
    check("不含「水平较高」量表语言", "水平较高" not in card)
    check("不含「分）」量表尾巴", "分）" not in card)

    # 头部格式
    check("以【画像】开头", card.startswith("【画像】"))
    check("含依恋类型中文名", PROFILE_TYPE_LABELS["anxious"] in card)
    check("含置信度", "置信度 0.77" in card)
    check("含【沟通宜忌】", "【沟通宜忌】" in card)
    # 行数：1 头 + 4 维度 + 1 宜忌
    check("恰好 6 行（头+4维+宜忌）", len(card.splitlines()) == 6, card)


def case_band_selection():
    print("\n[3] 四档归档边界")
    check("75 → high", _pick_band(75) == "high")
    check("74.9 → mid_high", _pick_band(74.9) == "mid_high")
    check("50 → mid_high", _pick_band(50) == "mid_high")
    check("49.9 → mid_low", _pick_band(49.9) == "mid_low")
    check("25 → mid_low", _pick_band(25) == "mid_low")
    check("24.9 → low", _pick_band(24.9) == "low")
    check("BAND_LABELS 齐全", set(BAND_LABELS) == {"high", "mid_high", "mid_low", "low"})


def case_all_11_have_bands_advice():
    print("\n[4] 11 个维度均有 bands×4 + advice")
    for key in ALL_DIM_KEYS:
        defn = DIMENSION_DEFINITIONS[key]
        bands = defn.get("bands", {})
        ok_bands = set(bands) == {"high", "mid_high", "mid_low", "low"} and all(
            isinstance(v, tuple) and len(v) == 2 and v[1] for v in bands.values()
        )
        check(f"{key} bands×4 非空", ok_bands)
        advice = defn.get("advice", {})
        check(
            f"{key} advice 有 do/dont",
            bool(advice.get("do")) and bool(advice.get("dont")),
        )
        # label / source 既有字段未被破坏
        check(f"{key} label/source 保留", bool(defn.get("label")) and bool(defn.get("source")))


def case_format_profile_fallback():
    print("\n[5] _format_profile 降级与接线")
    from app.services.ai_service import _format_profile

    check("profile=None → 未完成问卷", _format_profile(None, {}) == "未完成问卷")

    class _FakeProfile:
        profile_type = "anxious"
        confidence = 0.77

    card = _format_profile(_FakeProfile(), TEST_SCORES)
    check("真实 profile → 走 build_profile_card", card.startswith("【画像】焦虑依恋型"))
    check("无英文 key", "attachment_anxiety" not in card)


def main() -> int:
    print("=" * 72)
    print("P0-1 画像卡中文语义注入")
    print("=" * 72)
    case_no_english_keys()
    case_high_dims_with_behavior()
    case_band_selection()
    case_all_11_have_bands_advice()
    case_format_profile_fallback()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
