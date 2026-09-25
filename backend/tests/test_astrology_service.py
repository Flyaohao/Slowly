"""
画像补强验收：星座 / 星盘（含出生地精算） / MBTI 注入（不连数据库、不需要 API Key）。

断言六件事：
  a) 星座分界逐日正确（12 个边界各测前后一天，含 12-22~01-19 的摩羯跨年段）
  b) 星座缺失/时辰缺失时不编数据：生日缺 → 全空；时辰缺 → 不出上升
  c) MBTI 只认 16 型，大小写归一，未知代号不硬塞
  d) 性格辅助块带参考权重口径（问卷画像 > MBTI > 星座/星盘），且全空时
     返回空串——不塞占位给模型当事实
  e) 出生地本地城市表匹配（精确/子串/最长命中/匹配不到不猜坐标）
  f) 命中出生地 → 上升按地方恒星时精算（地平高度 ≈0 独立校验），
     块内按两档如实标注精算/简化推算

运行：cd backend && python tests/test_astrology_service.py
"""
import math
import os
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.astrology_service import (  # noqa: E402
    MBTI_TYPES,
    REFERENCE_WEIGHT_NOTE,
    SIGNS,
    _ascendant_longitude,
    _julian_day_ut,
    build_personality_block,
    get_mbti_label,
    get_moon_sign,
    get_rising_sign,
    get_zodiac,
)
from app.services.birthplace_service import lookup_birth_place  # noqa: E402
from app.schemas.user_schema import UserProfileUpdateRequest  # noqa: E402

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def case_zodiac_boundaries():
    print("\n[1] 星座分界（每个分界前后各一天）")
    # (分界日, 该日起的星座, 分界前一天的星座)
    boundaries = [
        ((1, 20), "水瓶", "摩羯"),
        ((2, 19), "双鱼", "水瓶"),
        ((3, 21), "白羊", "双鱼"),
        ((4, 20), "金牛", "白羊"),
        ((5, 21), "双子", "金牛"),
        ((6, 22), "巨蟹", "双子"),
        ((7, 23), "狮子", "巨蟹"),
        ((8, 23), "处女", "狮子"),
        ((9, 23), "天秤", "处女"),
        ((10, 24), "天蝎", "天秤"),
        ((11, 23), "射手", "天蝎"),
        ((12, 22), "摩羯", "射手"),
    ]
    for (month, day), on_day, prev_day in boundaries:
        check(
            f"{month:02d}-{day:02d} 起 {on_day} / 前一天 {prev_day}",
            get_zodiac(date(2000, month, day)) == on_day
            and get_zodiac(date(2000, month, day) - _one_day()) == prev_day,
            f"{get_zodiac(date(2000, month, day))} / "
            f"{get_zodiac(date(2000, month, day) - _one_day())}",
        )
    check("无生日 → None", get_zodiac(None) is None)


def _one_day():
    from datetime import timedelta

    return timedelta(days=1)


def case_chart_never_fabricates():
    print("\n[2] 星盘不编数据（缺时辰不出上升，缺生日全空）")
    birthday = date(1998, 11, 3)
    check("有生日有时辰 → 出上升", get_rising_sign(birthday, 8) is not None)
    check("缺时辰 → 上升 None（不拿正午当默认）", get_rising_sign(birthday, None) is None)
    check("缺生日 → 月亮 None", get_moon_sign(None) is None)
    check("缺生日 → 上升 None", get_rising_sign(None, 8) is None)
    # 同一生日不同时辰，上升应不同（12 宫 / 24 小时）
    hours = [get_rising_sign(birthday, h) for h in (0, 6, 12, 18)]
    check("不同时辰上升不同", len(set(hours)) == 4, str(hours))
    # 月亮黄经是平均值，逐日推进；只查取值落在十二宫内
    moon = get_moon_sign(birthday)
    check("月亮落在十二宫内", moon in SIGNS, str(moon))


def case_mbti_strict():
    print("\n[3] MBTI 只认 16 型")
    check("正好 16 型", len(MBTI_TYPES) == 16, str(len(MBTI_TYPES)))
    check("大小写归一", get_mbti_label("intj") is not None)
    check("未知代号 → None", get_mbti_label("XXXX") is None)
    check("空值 → None", get_mbti_label(None) is None and get_mbti_label("  ") is None)

    ok = UserProfileUpdateRequest(mbti="infj", birth_hour=13)
    check("请求体归一为大写 INFJ", ok.mbti == "INFJ")
    blank = UserProfileUpdateRequest(mbti="   ")
    check("空白视为未填 → None", blank.mbti is None)
    try:
        UserProfileUpdateRequest(mbti="XYZ")
        check("非法 MBTI 被拒绝", False)
    except Exception:
        check("非法 MBTI 被拒绝", True)
    try:
        UserProfileUpdateRequest(birth_hour=24)
        check("时辰越界被拒绝", False)
    except Exception:
        check("时辰越界被拒绝", True)
    check("时辰 0 合法", UserProfileUpdateRequest(birth_hour=0).birth_hour == 0)


def case_block():
    print("\n[4] 辅助块内容与参考权重口径")
    block = build_personality_block(date(1998, 11, 3), 8, "INTJ")
    check("含太阳星座", "天蝎座" in block, block)
    check("含上升与出生时辰", "上升" in block and "08:00" in block, block)
    check("标注简化推算（不冒充精算）", "简化推算" in block, block)
    check("含 MBTI 与中文别称", "INTJ" in block and "建筑师" in block, block)
    check("带参考权重口径", REFERENCE_WEIGHT_NOTE in block, block)
    check("口径点明问卷画像为主", "问卷画像为主" in block, block)

    only_mbti = build_personality_block(None, None, "INTP")
    check(
        "只有 MBTI 也出块（不硬造星座行）",
        "INTP" in only_mbti and "星座·星盘：" not in only_mbti,
        only_mbti,
    )
    check("全空 → 空串（不塞占位）", build_personality_block(None, None, None) == "")


def case_birth_place_lookup():
    print("\n[5] 出生地本地城市表匹配（不出网、不猜坐标）")
    exact = lookup_birth_place("杭州")
    check("精确命中", exact is not None and exact.name == "杭州", str(exact))
    check(
        "省前缀子串命中（浙江省杭州市 → 杭州）",
        (lambda p: p is not None and p.name == "杭州")(lookup_birth_place("浙江省杭州市")),
    )
    check(
        "首尾空白容忍（  上海  → 上海）",
        (lambda p: p is not None and p.name == "上海")(lookup_birth_place("  上海  ")),
    )
    check(
        "反向子串取最长命中（鲁木齐 → 乌鲁木齐）",
        (lambda p: p is not None and p.name == "乌鲁木齐")(lookup_birth_place("鲁木齐")),
    )
    check("匹配不到 → None（不猜坐标）", lookup_birth_place("乌有之乡") is None)
    check("空串/空白 → None", lookup_birth_place(None) is None and lookup_birth_place("   ") is None)
    london = lookup_birth_place("伦敦")
    check(
        "海外城市带时区/西经为负",
        london is not None and london.utc_offset == 0.0 and london.longitude < 0,
        str(london),
    )


def _altitude_deg(lam_deg: float, jd: float, lon_east: float, lat: float) -> float:
    """独立地平线校验：给定黄经点此刻的地平高度（度）。≈0 → 确在地平线上。"""
    t = (jd - 2451545.0) / 36525.0
    eps = math.radians(23.439291 - 0.0130042 * t)
    lam = math.radians(lam_deg)
    dec = math.asin(math.sin(lam) * math.sin(eps))
    ra = math.atan2(math.sin(lam) * math.cos(eps), math.cos(lam))
    d = jd - 2451545.0
    gmst = math.radians((280.46061837 + 360.98564736629 * d + 0.000387933 * t * t + lon_east) % 360.0)
    hour_angle = gmst - ra
    lat_r = math.radians(lat)
    alt = math.asin(
        math.sin(lat_r) * math.sin(dec) + math.cos(lat_r) * math.cos(dec) * math.cos(hour_angle)
    )
    return math.degrees(alt)


def case_rising_with_birth_place():
    print("\n[6] 出生地精算上升（地方恒星时，地平高度≈0 独立校验）")
    # 手工复核过的两个锚点：杭州 2000-01-01 12:00 → 黄经 14.9°（白羊，
    # 此时 LST≈280≈太阳黄经，恰是正午）；09:00 → 312.5°（水瓶）。
    # 用独立的地平线公式再验证「此刻真在地平线上」。
    for hour, expect_sign, expect_lon in ((12, "白羊", 14.92), (9, "水瓶", 312.53)):
        lam = _ascendant_longitude(date(2000, 1, 1), hour, 30.27, 120.16, 8.0)
        ok_lon = lam is not None and abs((lam - expect_lon + 180) % 360 - 180) < 0.5
        check(f"杭州 2000-01-01 {hour:02d}:00 上升黄经 ≈ {expect_lon}°", ok_lon, str(lam))
        if lam is not None:
            jd = _julian_day_ut(2000, 1, 1, hour - 8.0)
            alt = _altitude_deg(lam, jd, 120.16, 30.27)
            check(f"该点地平高度 ≈0（{hour:02d}:00 alt={alt:.2f}°）", abs(alt) < 0.5)
        sign = get_rising_sign(date(2000, 1, 1), hour, "杭州")
        check(f"上升落 {expect_sign}", sign == expect_sign, str(sign))

    # 同一时刻不同出生地 → 地方恒星时差 ~116°，上升必不同
    morning_hz = get_rising_sign(date(1998, 11, 3), 8, "杭州")
    morning_ldn = get_rising_sign(date(1998, 11, 3), 8, "伦敦")
    check(
        "杭州 vs 伦敦（同时辰）上升不同",
        morning_hz != morning_ldn and morning_hz in SIGNS and morning_ldn in SIGNS,
        f"{morning_hz} / {morning_ldn}",
    )

    # 命中失败 → 与不填出生地走同一简化推算
    birthday = date(1998, 11, 3)
    check(
        "出生地未收录 → 退回简化推算（与不填一致）",
        get_rising_sign(birthday, 8, "乌有之乡") == get_rising_sign(birthday, 8),
    )
    check("缺时辰即使有出生地也不出上升", get_rising_sign(birthday, None, "杭州") is None)


def case_block_birth_place():
    print("\n[7] 块内出生地两档标注")
    precise = build_personality_block(date(1998, 11, 3), 8, "INTJ", "杭州")
    check("精算档：标注按经纬度推算", "按出生地经纬度推算" in precise, precise)
    check("精算档：不冒充简化推算", "简化推算" not in precise, precise)
    check("精算档：出生地入块", "出生地：杭州" in precise, precise)

    fallback = build_personality_block(date(1998, 11, 3), 8, "INTJ", "乌有之乡")
    check("未收录档：标注未收录+简化推算", "出生地未收录，简化推算" in fallback, fallback)

    only_place = build_personality_block(None, None, None, "杭州")
    check(
        "只有出生地也出块（无占星/MBTI 结论时不带权重口径）",
        "出生地：杭州" in only_place and REFERENCE_WEIGHT_NOTE not in only_place,
        only_place,
    )
    check(
        "全空（含出生地）→ 空串",
        build_personality_block(None, None, None, None) == ""
        and build_personality_block(None, None, None, "  ") == "",
    )

    req = UserProfileUpdateRequest(birth_place="  杭州 ")
    check("请求体出生地去空白", req.birth_place == "杭州")
    check("出生地空白视为未填 → None", UserProfileUpdateRequest(birth_place="  ").birth_place is None)
    try:
        UserProfileUpdateRequest(birth_place="长" * 51)
        check("出生地超长被拒绝", False)
    except Exception:
        check("出生地超长被拒绝", True)


def main() -> int:
    print("=" * 72)
    print("星座 / 星盘（含出生地精算） / MBTI 性格辅助块")
    print("=" * 72)
    case_zodiac_boundaries()
    case_chart_never_fabricates()
    case_mbti_strict()
    case_block()
    case_birth_place_lookup()
    case_rising_with_birth_place()
    case_block_birth_place()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
