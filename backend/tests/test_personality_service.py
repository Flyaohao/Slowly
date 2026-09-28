"""人格画像页「性格辅助信息」服务层验收（不连数据库、不需要 API Key）。

断言五件事：
  a) build_personality_entry：MBTI → 别称/解读正确，未知/空代号不硬塞
  b) 生日缺 → 星座三项全空且 filled=false；只有 MBTI → filled=true
  c) 时辰缺 → 上升 None 但太阳/月亮有值（不拿正午当默认）
  d) 全空 → 字段全 null 且 filled=false
  e) 不泄漏生日原值（响应 dict 里没有 birthday 字段）

运行：cd backend && python tests/test_personality_service.py
"""
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.astrology_service import MBTI_TYPES, SIGN_TRAITS  # noqa: E402
from app.services.personality_service import build_personality_entry  # noqa: E402

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def case_mbti():
    print("\n[1] MBTI 别称与解读")
    entry = build_personality_entry("INTP", None, None, None)
    check("INTP → 别称「逻辑学家」", entry["mbti_name"] == "逻辑学家", str(entry["mbti_name"]))
    check("解读非空", bool(entry["mbti_description"]))
    check("mbti 归一为大写", entry["mbti"] == "INTP")
    check("只有 MBTI → filled=true", entry["filled"] is True)

    # 2026-09-28 用户反馈：一句话形容不了一个人的性格 → 16 型全部扩成 3 句左右，
    # 措辞留余地（有句号分句、有「不等于/只是」类的去刻板化表述）。
    thin = [code for code, (_, desc) in MBTI_TYPES.items() if desc.count("。") < 2]
    check("16 型解读都是多句（≥2 个句号）", not thin, str(thin))
    short = [code for code, (_, desc) in MBTI_TYPES.items() if len(desc) < 40]
    check("16 型解读篇幅达标（≥40 字）", not short, str(short))
    check("正好 16 型", len(MBTI_TYPES) == 16, str(len(MBTI_TYPES)))
    check("星座分角色解读表覆盖 12 星座 × 3 角色", (
        len(SIGN_TRAITS) == 12
        and all(set(v) == {"sun", "moon", "rising"} for v in SIGN_TRAITS.values())
    ), str(sorted(SIGN_TRAITS)))

    lower = build_personality_entry("intp", None, None, None)
    check("小写归一为 INTP", lower["mbti"] == "INTP")

    unknown = build_personality_entry("XXXX", date(1998, 11, 3), None, None)
    check("未知代号 → mbti/name/description 全 None", (
        unknown["mbti"] is None
        and unknown["mbti_name"] is None
        and unknown["mbti_description"] is None
    ), str(unknown))
    check("未知 MBTI 但有生日 → filled=true", unknown["filled"] is True)

    blank = build_personality_entry("   ", None, None, None)
    check("空白 MBTI 视为未填", blank["mbti"] is None and blank["filled"] is False)


def case_zodiac():
    print("\n[2] 星座推算与缺失兜底")
    entry = build_personality_entry(None, date(1998, 11, 3), 8, "杭州")
    check("有生日 → 太阳/月亮有值", entry["zodiac"] is not None and entry["moon_sign"] is not None, str(entry))
    check("有时辰有出生地 → 上升有值", entry["rising_sign"] is not None, str(entry["rising_sign"]))
    check("有生日 → filled=true", entry["filled"] is True)
    interp = entry["zodiac_interpretation"] or ""
    check("星盘解读含太阳行", "太阳" in interp, interp)
    check("星盘解读含月亮行", "月亮" in interp, interp)
    check("星盘解读含上升行", "上升" in interp, interp)
    check("解读逐行成句", interp.count("。") >= 3, interp)

    no_hour = build_personality_entry(None, date(1998, 11, 3), None, None)
    check("缺时辰 → 上升 None（不拿正午当默认）", no_hour["rising_sign"] is None)
    check("缺时辰但太阳/月亮仍有值", no_hour["zodiac"] is not None and no_hour["moon_sign"] is not None)
    check("缺时辰 → 解读只有两行", (no_hour["zodiac_interpretation"] or "").count("\n") == 1, str(no_hour["zodiac_interpretation"]))

    no_birthday = build_personality_entry(None, None, 8, "杭州")
    check("缺生日 → 星座三项全 None", (
        no_birthday["zodiac"] is None
        and no_birthday["moon_sign"] is None
        and no_birthday["rising_sign"] is None
    ), str(no_birthday))
    check("缺生日 → 解读为 None", no_birthday["zodiac_interpretation"] is None)
    check("缺生日 → filled=false", no_birthday["filled"] is False)


def case_all_empty():
    print("\n[3] 全空兜底")
    empty = build_personality_entry(None, None, None, None)
    check("字段全 None", all(
        empty[k] is None
        for k in ("mbti", "mbti_name", "mbti_description", "zodiac", "moon_sign", "rising_sign")
    ), str(empty))
    check("filled=false", empty["filled"] is False)


def case_privacy():
    print("\n[4] 隐私：不泄漏生日原值")
    entry = build_personality_entry("INTP", date(1998, 11, 3), 8, "杭州")
    check("响应 dict 无 birthday 字段", "birthday" not in entry and "birth_hour" not in entry and "birth_place" not in entry, str(sorted(entry)))


def main() -> int:
    print("=" * 72)
    print("人格画像页「性格辅助信息」服务层")
    print("=" * 72)
    case_mbti()
    case_zodiac()
    case_all_empty()
    case_privacy()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
