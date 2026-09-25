"""谓词注册表与 fact_key（v3.2 §2，封闭 key——LLM 不得自由生成）。

纯函数模块：无 DB、无 I/O，可被 pipeline / create_memory / 校验器共用。

- `PREDICATES`：封闭 enum（Function Calling 侧用同值 Literal 约束）。
- `OBJECT_KEY_LEXICON`：object_key 词典（中文词 → 小写点路径）；未命中落
  `other` + registry_miss=True，**不阻塞写入**（§2.2，进评测观察扩词典）。
- `build_fact_key()`：fact_key 只管归组与冲突（§2.1），永不做任务幂等。
- `CARDINALITY_BY_PREDICATE`：single = 同 key 新断言 supersedes 旧；
  multi = 同 key 并存。registry_miss（other）不自动 supersede（§8）。
"""
from typing import Optional, Tuple

#: 封闭谓词 enum（§2.2）。蒸馏输出 schema 的 Literal 必须与此同值。
PREDICATES: Tuple[str, ...] = (
    "preference",
    "behavior",
    "goal",
    "constraint",
    "trait",
    "event",
    "pattern",
    "state",
    "other",
)

#: 词典：中文/口语词 → 规范 object_key（小写点路径）。命中即免 miss。
#: registry_miss 高发说明本表要扩（§2.2 同义归一）。
OBJECT_KEY_LEXICON = {
    # 饮食
    "辣": "food.spicy",
    "吃辣": "food.spicy",
    "口味": "food.taste",
    "不吃香菜": "food.coriander",
    "咖啡": "food.coffee",
    "奶茶": "food.milk_tea",
    # 沟通
    "回复速度": "comm.response_speed",
    "回复": "comm.response_speed",
    "秒回": "comm.response_speed",
    "冷战": "comm.silent_treatment",
    "吵架": "comm.conflict_style",
    "道歉": "comm.apology_style",
    "称呼": "comm.appellation",
    "语气": "comm.tone",
    # 时间与陪伴
    "纪念日": "time.anniversary",
    "约会": "time.date_night",
    "陪伴": "time.companionship",
    "睡前": "time.bedtime",
    "早安": "time.morning",
    # 情绪与边界
    "安全感": "emotion.security",
    "仪式感": "emotion.ritual",
    "礼物": "emotion.gift",
    "惊喜": "emotion.surprise",
    "隐私": "boundary.privacy",
    "空间": "boundary.space",
    "手机": "boundary.phone",
    # 生活习惯
    "运动": "life.exercise",
    "作息": "life.schedule",
    "睡眠": "life.sleep",
    "家务": "life.chores",
    "花钱": "life.spending",
    "存钱": "life.saving",
}

#: 同义词表（写入时先归一再查词典，§2.2 同义归一）
SYNONYMS = {
    "吃不了辣": "辣",
    "喜欢吃辣": "辣",
    "嗜辣": "辣",
    "回消息速度": "回复速度",
    "回信速度": "回复速度",
    "不回消息": "回复速度",
}

#: single = 同 fact_key 新断言 supersede 旧；multi = 同 key 并存。
#: other（registry_miss）恒 multi + 不自动 supersede（§8：miss 不进高风险结论）
CARDINALITY_BY_PREDICATE = {
    "preference": "single",
    "behavior": "single",
    "goal": "single",
    "constraint": "single",
    "trait": "single",
    "state": "single",
    "event": "multi",
    "pattern": "multi",
    "other": "multi",
}


def normalize_object_key(raw: Optional[str]) -> Tuple[str, bool]:
    """词典归一 → `(object_key, registry_miss)`。

    未命中返回 `("other", True)`——**不阻塞写入**（§2.2）。
    输入已是点路径（含 `.` 且全小写段）时视为规范值直接采用。
    """
    if not raw or not raw.strip():
        return "other", True
    text = raw.strip().lower()
    # 已是规范点路径：形如 food.spicy（段为 [a-z0-9_]+）
    if "." in text and all(
        seg and all(c.isalnum() or c == "_" for c in seg) for seg in text.split(".")
    ):
        return text, False
    # 同义归一 → 词典
    canonical = SYNONYMS.get(text, text)
    key = OBJECT_KEY_LEXICON.get(canonical)
    if key is None:
        # 词典再试一次原文（SYNONYMS 只是加速路径）
        key = OBJECT_KEY_LEXICON.get(text)
    if key is None:
        return "other", True
    return key, False


def build_fact_key(
    subject_type: Optional[str],
    subject_user_id: Optional[int],
    predicate: str,
    object_key: str,
) -> str:
    """fact_key = `subject_type:subject_user_id / predicate / object_key`（§2.1）。

    subject_user_id 为空（relationship/event 主体）时省略 id 段：
    `relationship / preference / ...`。fact_key 只管归组与冲突判定，
    **不做任务幂等**（幂等是 pipeline 两级键的职责）。
    """
    subject_type = (subject_type or "user").strip()
    if subject_type == "user" and subject_user_id is None:
        subject_type = "relationship"  # user 主体缺 id 不允许——降级为关系级
    subject = (
        "%s:%s" % (subject_type, subject_user_id)
        if subject_user_id is not None
        else subject_type
    )
    return "%s / %s / %s" % (subject, predicate or "other", object_key or "other")


def cardinality_for(predicate: str) -> str:
    """谓词 → cardinality（未知谓词按 other 处理：multi + 不自动 supersede）。"""
    return CARDINALITY_BY_PREDICATE.get(predicate, "multi")
