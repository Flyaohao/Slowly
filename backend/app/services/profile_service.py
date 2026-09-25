import logging
from datetime import datetime, timedelta

from sqlalchemy.orm import Session
from typing import Optional, List, Dict

from app.repositories import profile_repo, questionnaire_repo, avatar_repo, couple_repo
from app.models.questionnaire import QuestionnaireAnswer
from app.models.ai import AiChatMessage, AiChatSession

logger = logging.getLogger("couple.profile")


# bands 四档阈值：high >=75 / mid_high >=50 / mid_low >=25 / low <25。
# 文案要求是「行为化解读」——写"会做什么"，不写量表语言（反例见
# _explain_dimension 的"水平较高（72分）"）。label / source 是既有字段，
# profile_service 内部与 seed 脚本在用，不得改动语义。
DIMENSION_DEFINITIONS = {
    "attachment_anxiety": {
        "label": "依恋焦虑", "source": "ECR-R",
        "bands": {
            "high":     (75, "消息回慢了会反复确认「你是不是在忙」；需要明确的「我还在」"),
            "mid_high": (50, "在意回应速度，偶尔会问「你是不是不想理我」"),
            "mid_low":  (25, "对回应速度不太敏感，偶尔需要一点确认"),
            "low":      (0,  "对回应速度不敏感，独处也很自在"),
        },
        "advice": {"do": "先给确定性结论（「我在，我们没变」）", "dont": "说「你想多了」"},
    },
    "attachment_avoidance": {
        "label": "依恋回避", "source": "ECR-R",
        "bands": {
            "high":     (75, "冲突一上来就想拉开距离，被追得越紧退得越远；需要自己消化的空间"),
            "mid_high": (50, "亲密到一定浓度会想喘口气，不是不爱，是需要独处回血"),
            "mid_low":  (25, "大体能接受亲近，偶尔需要一点个人空间"),
            "low":      (0,  "享受亲密接触，不太需要刻意拉开距离"),
        },
        "advice": {"do": "给台阶、给时间，让 TA 自己回来", "dont": "在 TA 退缩时步步紧逼"},
    },
    "conflict_pursue": {
        "label": "冲突追问倾向", "source": "Demand-Withdraw",
        "bands": {
            "high":     (75, "吵架时停不下来，一定要当场说清楚；停下来对 TA 来说等于被放弃"),
            "mid_high": (50, "有分歧会想追着谈完，最怕问题被搁置"),
            "mid_low":  (25, "能忍一忍，等双方冷静了再谈"),
            "low":      (0,  "不太主动挑起争论，能避则避"),
        },
        "advice": {"do": "主动说「我们等下好好谈」并给明确时间点", "dont": "冷处理、假装无事发生"},
    },
    "conflict_withdraw": {
        "label": "冲突退缩倾向", "source": "Demand-Withdraw",
        "bands": {
            "high":     (75, "一吵架就沉默或离开现场，不是不在乎，是需要时间整理情绪"),
            "mid_high": (50, "情绪上头时会先躲一躲，缓过来才谈得了"),
            "mid_low":  (25, "多数时候能留在对话里，实在受不了才走开"),
            "low":      (0,  "冲突中不逃避，愿意当场把话说开"),
        },
        "advice": {"do": "允许 TA 先冷静，再约时间谈", "dont": "把沉默解读成「你不爱我了」"},
    },
    "defensive_response": {
        "label": "防御反驳倾向", "source": "Gottman",
        "bands": {
            "high":     (75, "被指出问题第一反应是解释和反驳，先处理情绪才听得进建议"),
            "mid_high": (50, "会先辩两句，缓过来才能承认部分问题"),
            "mid_low":  (25, "能听进批评，但涉及核心能力时仍会防御"),
            "low":      (0,  "能平静接受意见，就事论事不升级"),
        },
        "advice": {"do": "先说感受再提具体行为（「我感到…」）", "dont": "翻旧账、贴「你总是」的标签"},
    },
    "emotional_validation_need": {
        "label": "情绪确认需求", "source": "自有",
        "bands": {
            "high":     (75, "先要一句「我懂你委屈」，再讲道理；顺序反了就听不进去"),
            "mid_high": (50, "希望情绪被接住，急着给方案会觉得不被理解"),
            "mid_low":  (25, "能接受简短共情，重点是解决问题"),
            "low":      (0,  "更看重解决方案本身，不需要太多情绪铺垫"),
        },
        "advice": {"do": "先复述 TA 的感受再给建议", "dont": "一上来就「你应该…」"},
    },
    "factual_explanation_need": {
        "label": "事实解释需求", "source": "自有",
        "bands": {
            "high":     (75, "要「为什么」，先给逻辑和事实再谈感受；含糊其辞会激怒 TA"),
            "mid_high": (50, "需要知道事情的来龙去脉，不喜欢被敷衍"),
            "mid_low":  (25, "关键处给个解释就行，不必事无巨细"),
            "low":      (0,  "更在意你的态度，细节说不说无所谓"),
        },
        "advice": {"do": "摆事实、给原因，再说感受", "dont": "只说「别问了，听我的」"},
    },
    "personal_space_need": {
        "label": "独处冷静需求", "source": "自有",
        "bands": {
            "high":     (75, "说「让我静一静」是真需要空间，不是冷暴力；给个时限 TA 会回来"),
            "mid_high": (50, "偶尔需要一个人待着回血，被打断会烦躁"),
            "mid_low":  (25, "大多数时候愿意共享时间，偶尔想自己待会"),
            "low":      (0,  "喜欢随时有人在，独处反而不安"),
        },
        "advice": {"do": "问「你需要多久」并尊重这个时限", "dont": "把独处请求当威胁"},
    },
    "reassurance_need": {
        "label": "安全感确认需求", "source": "自有",
        "bands": {
            "high":     (75, "需要反复确认关系是稳的；一句「我还在」比一百句情话管用"),
            "mid_high": (50, "偶尔需要被肯定，关系里的小冷淡会放大不安"),
            "mid_low":  (25, "基本有安全感，特定时刻需要一点确认"),
            "low":      (0,  "自带安全感，不太需要外在确认"),
        },
        "advice": {"do": "主动报备、及时回消息", "dont": "故意冷落「考验」TA"},
    },
    "directness_preference": {
        "label": "直接表达偏好", "source": "自有",
        "bands": {
            "high":     (75, "有话直说，讨厌猜来猜去；「没事」通常是有事"),
            "mid_high": (50, "多数时候直接，涉及敏感话题会绕一下"),
            "mid_low":  (25, "习惯先试探再开口，怕直接伤人"),
            "low":      (0,  "不会直接说需求，等你自己发现；需要主动问"),
        },
        "advice": {"do": "直接问「你想要什么」", "dont": "让 TA 猜你的心思"},
    },
    "softness_preference": {
        "label": "柔和表达偏好", "source": "自有",
        "bands": {
            "high":     (75, "吃软不吃硬，语气比内容重要；严厉批评会让 TA 关闭耳朵"),
            "mid_high": (50, "希望被温和对待，太冲的话会记很久"),
            "mid_low":  (25, "能接受直接指出，但场合不对也会受伤"),
            "low":      (0,  "对语气不敏感，就事论事效率优先"),
        },
        "advice": {"do": "私下说、先肯定再提不足", "dont": "当众指责、用质问语气"},
    },
}

# bands 档位 → 中文等级名（画像卡展示用）
BAND_LABELS = {
    "high": "高",
    "mid_high": "中高",
    "mid_low": "中低",
    "low": "低",
}

PROFILE_TYPE_LABELS = {
    "secure": "安全型依恋",
    "anxious": "焦虑依恋型",
    "dismissive": "疏离回避型",
    "fearful": "恐惧回避型",
    "mixed": "混合型依恋",
}


def generate_profile(
    db: Session,
    user_id: int,
    questionnaire_id: int,
    answers_with_meta: List[dict],
):
    dimension_scores = _calculate_dimension_scores(answers_with_meta)

    anxiety = dimension_scores.get("attachment_anxiety", 50)
    avoidance = dimension_scores.get("attachment_avoidance", 50)
    profile_type = _classify_attachment(anxiety, avoidance)
    confidence = _calculate_confidence(anxiety, avoidance)

    version = profile_repo.get_next_version(db, user_id)

    summary = _build_summary(profile_type, dimension_scores)

    profile = profile_repo.create_profile(
        db,
        user_id=user_id,
        questionnaire_id=questionnaire_id,
        profile_type=profile_type,
        confidence=confidence,
        summary=summary,
        version=version,
    )

    score_entries = []
    for dim_key, score in dimension_scores.items():
        label = DIMENSION_DEFINITIONS.get(dim_key, {}).get("label", dim_key)
        explanation = _explain_dimension(dim_key, score)
        score_entries.append({
            "dimension_key": dim_key,
            "score": score,
            "explanation": explanation,
        })

    profile_repo.add_dimension_scores(db, profile.id, score_entries)

    # P-C3 §5：画像落库后按画像自动选一次语气（仅 voice_style_source='auto'
    # 生效）。触发点只此一处——进页面/每条消息都调等于天天改用户语气。
    try:
        recompute_voice_style(db, user_id)
    except Exception:
        logger.warning("[VOICE] 画像自动选语气失败（不影响画像落库）", exc_info=True)

    return profile


def _calculate_dimension_scores(answers_with_meta: List[dict]) -> Dict[str, float]:
    totals: Dict[str, float] = {}
    weights: Dict[str, float] = {}

    for item in answers_with_meta:
        dim = item["dimension_key"]
        weight = item["weight"]
        answer = item["answer_value"]
        options = item["options"]

        score = 0.0
        if isinstance(answer, dict):
            # Single choice: {"selected_option_id": 123}
            selected_id = answer.get("selected_option_id")
            if selected_id:
                opt = options.get(selected_id)
                if opt:
                    score = opt.score_value
                    if opt.dimension_delta:
                        for k, v in opt.dimension_delta.items():
                            totals[k] = totals.get(k, 0) + v
                            weights[k] = weights.get(k, 0) + weight

            # Multi choice: {"selected_option_ids": [1, 2, 3]}
            selected_ids = answer.get("selected_option_ids")
            if selected_ids:
                valid_scores = []
                for oid in selected_ids:
                    opt = options.get(oid)
                    if opt:
                        valid_scores.append(opt.score_value)
                if valid_scores:
                    score = sum(valid_scores) / len(valid_scores)

            # Sort: {"ordered_option_ids": [3, 1, 2]}
            ordered_ids = answer.get("ordered_option_ids")
            if ordered_ids:
                valid_scores = []
                for idx, oid in enumerate(ordered_ids):
                    opt = options.get(oid)
                    if opt:
                        # Higher position = higher score
                        position_score = (idx + 1) * (100.0 / len(ordered_ids))
                        valid_scores.append(position_score)
                if valid_scores:
                    score = sum(valid_scores) / len(valid_scores)

        elif isinstance(answer, (int, float)):
            # Likert fallback: raw value (1-7), try to find matching option
            raw_value = float(answer)
            # Try to find option with matching score_value
            for opt in options.values():
                if abs(opt.score_value - raw_value) < 0.01:
                    score = opt.score_value
                    break
            else:
                # If no matching option found, use raw value (legacy behavior)
                score = raw_value

        elif isinstance(answer, list):
            # Legacy format: list of option ids (multi-choice or sort)
            valid_scores = []
            for oid in answer:
                if isinstance(oid, (int, float)):
                    opt = options.get(int(oid))
                    if opt:
                        valid_scores.append(opt.score_value)
            if valid_scores:
                score = sum(valid_scores) / len(valid_scores)

        totals[dim] = totals.get(dim, 0) + score * weight
        weights[dim] = weights.get(dim, 0) + weight

    result = {}
    for dim in totals:
        if weights[dim] > 0:
            avg = totals[dim] / weights[dim]
            result[dim] = min(100.0, max(0.0, round(avg, 1)))
        else:
            result[dim] = 50.0

    for dim in DIMENSION_DEFINITIONS:
        if dim not in result:
            result[dim] = 50.0

    return result


def _classify_attachment(anxiety: float, avoidance: float) -> str:
    high_anxiety = anxiety >= 50
    high_avoidance = avoidance >= 50

    # 混合型：两个维度都贴着阈值（45~55 的中间地带），焦虑与回避特质
    # 交织且谁都压不过谁——硬塞进单一类型会丢掉「时而是 X 时而是 Y」的真实形态。
    if 45 <= anxiety < 55 and 45 <= avoidance < 55:
        return "mixed"

    if not high_anxiety and not high_avoidance:
        return "secure"
    elif high_anxiety and not high_avoidance:
        return "anxious"
    elif not high_anxiety and high_avoidance:
        return "dismissive"
    else:
        return "fearful"


def _calculate_confidence(anxiety: float, avoidance: float) -> float:
    dist_anxiety = abs(anxiety - 50) / 50
    dist_avoidance = abs(avoidance - 50) / 50
    avg_dist = (dist_anxiety + dist_avoidance) / 2
    return round(0.6 + avg_dist * 0.4, 2)


def _build_summary(profile_type: str, scores: Dict[str, float]) -> str:
    label = PROFILE_TYPE_LABELS.get(profile_type, profile_type)
    anxiety = scores.get("attachment_anxiety", 50)
    avoidance = scores.get("attachment_avoidance", 50)
    return (
        f"您的依恋类型为{label}（焦虑维度：{anxiety}，回避维度：{avoidance}）。"
        f"该结果基于您的问卷回答生成，仅供参考。"
    )


def _explain_dimension(dim_key: str, score: float) -> str:
    label = DIMENSION_DEFINITIONS.get(dim_key, {}).get("label", dim_key)
    if score >= 75:
        return f"{label}水平较高（{score}分）"
    elif score >= 50:
        return f"{label}水平中等偏高（{score}分）"
    elif score >= 25:
        return f"{label}水平中等偏低（{score}分）"
    else:
        return f"{label}水平较低（{score}分）"


def _pick_band(score: float) -> str:
    """按 high>=75 / mid_high>=50 / mid_low>=25 / low<25 归档。"""
    if score >= 75:
        return "high"
    if score >= 50:
        return "mid_high"
    if score >= 25:
        return "mid_low"
    return "low"


def _format_score(score: float) -> str:
    """72.0 → '72'，65.5 → '65.5'（画像卡里不带多余的 .0）。"""
    if score == int(score):
        return str(int(score))
    return f"{score:g}"


def build_profile_card(
    profile_type: str,
    scores: Dict[str, float],
    confidence: float,
    title: str = "画像",
) -> str:
    """把结构化画像翻译成给模型看的中文语义卡。

    返回格式固定为（`title` 默认「画像」，伴侣侧传「TA 的画像」）::

        【画像】焦虑依恋型 · 置信度 0.77
        - 安全感确认需求 82（高）：需要反复确认关系是稳的…
        - 情绪确认需求 78（高）：先要一句「我懂你委屈」…
        【沟通宜忌】宜：…；忌：…

    规则：
      - 只列离中性（50）最远的 4 个维度，避免 token 过长
      - 行为化文案取自 DIMENSION_DEFINITIONS[dim]["bands"]
      - 末尾聚合这 4 个维度的沟通宜忌
      - 输出中不得出现任何英文 snake_case key
    """
    type_label = PROFILE_TYPE_LABELS.get(profile_type, profile_type)
    lines = [f"【{title}】{type_label} · 置信度 {confidence}"]

    ranked = sorted(
        scores.items(),
        key=lambda kv: abs(kv[1] - 50),
        reverse=True,
    )[:4]

    dos: List[str] = []
    donts: List[str] = []
    for dim_key, score in ranked:
        defn = DIMENSION_DEFINITIONS.get(dim_key)
        if not defn:
            continue
        band = _pick_band(score)
        behavior = defn.get("bands", {}).get(band, (0, ""))[1]
        if not behavior:
            continue
        lines.append(
            f"- {defn['label']} {_format_score(score)}"
            f"（{BAND_LABELS[band]}）：{behavior}"
        )
        advice = defn.get("advice", {})
        if advice.get("do") and advice["do"] not in dos:
            dos.append(advice["do"])
        if advice.get("dont") and advice["dont"] not in donts:
            donts.append(advice["dont"])

    if dos or donts:
        parts = []
        if dos:
            parts.append("宜：" + "；".join(dos))
        if donts:
            parts.append("忌：" + "；".join(donts))
        lines.append("【沟通宜忌】" + "；".join(parts))

    return "\n".join(lines)


def derive_relationship_pattern(
    user_a_scores: Dict[str, float], user_b_scores: Dict[str, float]
) -> tuple:
    """从双方维度分推出关系模式，返回 (模式名, 一句话解释)。

    判定基准是「更焦虑方」（attachment_anxiety 更高的一侧记为 A）：
    单看一方的焦虑/回避组合就能读出互动方向——高焦虑+低回避是追的一方，
    低焦虑+高回避是退的一方。若两侧同时焦虑高、回避高，优先判为
    「双高拉扯」（双方都在自保，谁也不像单纯的追或退）。

    与 conflict_detector.detect_conflict_pattern 产出的英文枚举
    （pursue_withdraw 等，客户端 conflictPatternName 在用）互不影响：
    本函数只服务 prompt 注入，不写库、不动 API 字段。
    """
    a_anx = user_a_scores.get("attachment_anxiety", 50)
    a_avoid = user_a_scores.get("attachment_avoidance", 50)
    b_anx = user_b_scores.get("attachment_anxiety", 50)
    b_avoid = user_b_scores.get("attachment_avoidance", 50)

    # 两侧都偏高（焦虑与回避均 >=50）→ 双高拉扯优先
    if a_anx >= 50 and a_avoid >= 50 and b_anx >= 50 and b_avoid >= 50:
        return ("双高拉扯", "双方都在自保，先降温再谈事")

    # A = 更焦虑方
    if b_anx > a_anx:
        a_anx, a_avoid = b_anx, b_avoid

    high_anx = a_anx >= 50
    high_avoid = a_avoid >= 50

    if high_anx and not high_avoid:
        return ("追与退", "你越追问 TA 越退，TA 越退你越追问，双方都是受害者")
    if not high_anx and high_avoid:
        return ("沉默的墙", "需要有人先开第一口")
    if high_anx and high_avoid:
        return ("双高拉扯", "双方都在自保，先降温再谈事")
    return ("安全基地", "关系健康，重点是加分而不是救火")


# --------------------------------------------------------------------------
# P-C3 §5：画像 → 语气自动选择（设计文档 §7.4）
#
# 约束：只动 voice_style_source=='auto' 的行；manual 直接返回；
#       voice_style 取值恒为 gentle/calm/direct（不碰既有五档枚举与 DB 值）；
#       映射不到保持现值——不硬塞默认档。
# --------------------------------------------------------------------------

#: 修复期判定：近 7 天冲突消息数达到该阈值视为「近期冲突事件多」
_REPAIR_WINDOW_DAYS = 7
_REPAIR_CONFLICT_MIN = 3


def decide_voice_style(
    anxiety: float,
    avoidance: float,
    conflict_pattern: Optional[str],
    both_secure: bool,
    recent_conflict_count: int,
) -> Optional[str]:
    """纯决策（P-C3 §5，便于无库测试）。返回 None = 映射不到，保持现值。

    优先级：修复期 gentle > 焦虑+追逃 gentle > 回避 calm > 双方安全 direct。
    """
    if recent_conflict_count >= _REPAIR_CONFLICT_MIN:
        return "gentle"
    if anxiety >= 50 and conflict_pattern and "pursue" in conflict_pattern:
        return "gentle"
    if avoidance >= 50:
        return "calm"
    if both_secure:
        return "direct"
    return None


def _count_recent_conflicts(db: Session, relation_id: int) -> int:
    """近 N 天该关系下的冲突消息数（risk_level='heated_conflict'）。"""
    since = datetime.now() - timedelta(days=_REPAIR_WINDOW_DAYS)
    return (
        db.query(AiChatMessage)
        .join(AiChatSession, AiChatMessage.session_id == AiChatSession.id)
        .filter(
            AiChatSession.relation_id == relation_id,
            AiChatMessage.risk_level == "heated_conflict",
            AiChatMessage.created_at >= since,
        )
        .count()
    )


def recompute_voice_style(db: Session, user_id: int) -> Optional[str]:
    """画像生成后按画像自动选语气（P-C3 §5）。返回被写入的档位，None = 未动。

    只在画像生成/更新时被调用一次（generate_profile 尾部）。
    """
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if relation is None:
        return None  # 单身模式无 avatar
    avatar = avatar_repo.get_avatar_by_relation_id(db, relation.id)
    if avatar is None or avatar.voice_style_source != "auto":
        return None  # manual 或从未进过形象页：不越界
    profile = profile_repo.get_latest_profile(db, user_id)
    if profile is None:
        return None
    scores = {
        s.dimension_key: s.score
        for s in profile_repo.get_dimension_scores(db, profile.id)
    }
    anxiety = scores.get("attachment_anxiety", 50)
    avoidance = scores.get("attachment_avoidance", 50)

    conflict_pattern = None
    both_secure = False
    couple_profile = profile_repo.get_latest_couple_profile(db, relation.id)
    if couple_profile is not None:
        conflict_pattern = couple_profile.conflict_pattern
        pa = profile_repo.get_profile_by_id(db, couple_profile.user_a_profile_id)
        pb = profile_repo.get_profile_by_id(db, couple_profile.user_b_profile_id)
        both_secure = bool(
            pa and pb
            and pa.profile_type == "secure"
            and pb.profile_type == "secure"
        )

    recent = _count_recent_conflicts(db, relation.id)
    mapped = decide_voice_style(anxiety, avoidance, conflict_pattern, both_secure, recent)
    return _apply_voice_style(db, avatar, mapped)


def _apply_voice_style(db: Session, avatar, mapped: Optional[str]) -> Optional[str]:
    """写入守卫（P-C3 §5）：manual 不动、映射不到不动、值相同不写。"""
    if mapped is None or avatar.voice_style_source != "auto":
        return None
    if mapped == avatar.voice_style:
        return None
    # 直写列，不走 set_voice_style（那会把 source 置 manual）
    avatar.voice_style = mapped
    db.commit()
    logger.info("[VOICE] 画像自动选语气 -> %s", mapped)
    return mapped
