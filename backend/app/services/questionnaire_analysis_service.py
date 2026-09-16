"""量表分析：把测评结果解读成一份用户读得下去的报告。

## 两条路径，同一份 Prompt 与同一个输出模型

- **同步路径** `analyze_for_user`：交卷那一刻由 `questionnaire_service` 顺带调用，
  结果直接写进 submission，用户进结果页不用等。走 `llm.invoke_structured`
  （Function Calling 承载 Schema）。
- **流式路径** `prepare_analysis`：结果页发现这次测评还没有分析时现场生成，
  走 `ai_generation_service` 的「正文 + 分隔符 + JSON」双出口协议——
  正文打字机可见，结构化字段落 `ai_generation` 并回写 submission。

两条路的结果都要过 `_normalize_analysis`：**分数以系统算出的为准，
模型只负责解读文字**。少了这道归一化，模型偶尔会把 62 分写成 65 分，
同一个页面上的柱状图（读系统分数）和文字（读模型分数）就会互相打架。
"""

import logging
from typing import Any, Dict

from sqlalchemy.orm import Session

from app.core.database import SessionLocal
from app.repositories import profile_repo, questionnaire_repo
from app.schemas.ai_output import QuestionnaireAnalysisOutput
from app.services import ai_generation_service
from app.services.llm_client import llm
from app.services.prompt_builder import build_structured_stream_prompt

logger = logging.getLogger("couple.ai.questionnaire")

#: 落 `ai_generation` 时用的生成类型，同时也是回读端点里的 kind 参数值。
GENERATION_KIND_ANALYSIS = "questionnaire_analysis"
#: 生成结果挂靠的实体类型
TARGET_TYPE_QUESTIONNAIRE = "questionnaire"

DIMENSION_LABELS = {
    "attachment_anxiety": "依恋焦虑",
    "attachment_avoidance": "依恋回避",
    "conflict_pursue": "冲突追问倾向",
    "conflict_withdraw": "冲突退缩倾向",
    "defensive_response": "防御反驳倾向",
    "emotional_validation_need": "情绪确认需求",
    "factual_explanation_need": "事实解释需求",
    "personal_space_need": "独处冷静需求",
    "reassurance_need": "安全感确认需求",
    "directness_preference": "直接表达偏好",
    "softness_preference": "柔和表达偏好",
}

PROFILE_TYPE_LABELS = {
    "secure": "安全型依恋",
    "anxious": "焦虑依恋型",
    "dismissive": "疏离回避型",
    "fearful": "恐惧回避型",
}

ANALYSIS_SYSTEM_PROMPT = (
    "你是一位专业的亲密关系心理咨询师，擅长把测评数据讲成人话。"
    "语气温暖、非评判性、有共情。"
)

#: 注意「请以 JSON 格式回复」这句必须原样保留：`build_structured_stream_prompt`
#: 靠它定位截断点，把下面的字段说明换成由 Pydantic 模型生成的 JSON Schema。
ANALYSIS_PROMPT = """你是一位专业的亲密关系心理咨询师。请根据以下问卷测评结果，为用户生成一份详细、温暖、有洞察力的分析报告。

## 测评结果
- 依恋类型: {profile_label}（置信度: {confidence}）
- 概要: {summary}

## 各维度得分 (0-100分, 越高越强烈)
{dimension_text}

## 输出格式
请以 JSON 格式回复，包含以下字段：

- profile_analysis：2-3 段文字解读用户的依恋类型，包括这种类型的核心特点、在亲密关系中的典型表现、可能的形成原因。语气温暖，像朋友聊天一样自然。
- dimension_analyses：数组，每个维度一条，按分数从高到低排序。每项含 key（维度英文 key，照抄上文括号里的）、label（维度中文名）、score（分数，照抄上文，不要自己改）、level（高/中/低，照抄上文）、analysis（1-2 句话通俗解读，要具体、有画面感，让用户觉得「说的就是我」）。
- strengths：2-3 句话总结用户在关系中的优势和积极特质。
- growth_tips：3 条具体可执行的建议。
- communication_guide：根据用户的依恋类型和沟通偏好，给出 2-3 条与伴侣沟通的实用建议。
- risk_level：normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk。

## 注意
- dimension_analyses 必须覆盖上面列出的**每一个**维度，一个都不能漏；分数与等级直接照抄，不要自行调整
- analysis 要通俗易懂，像朋友聊天，不要堆专业术语
- 语气温暖、非评判性、有共情"""


def analyze_for_user(db: Session, user_id: int) -> dict:
    """Look up user profile and run AI analysis. Raises ValueError('40004') if no profile."""
    profile = profile_repo.get_latest_profile(db, user_id)
    if not profile:
        raise ValueError("40004")

    scores = profile_repo.get_dimension_scores(db, profile.id)
    dimension_scores = {s.dimension_key: s.score for s in scores}

    return analyze_questionnaire(
        profile_type=profile.profile_type,
        confidence=profile.confidence,
        dimension_scores=dimension_scores,
        summary=profile.summary or "",
    )


def analyze_questionnaire(
    profile_type: str,
    confidence: float,
    dimension_scores: Dict[str, float],
    summary: str,
) -> dict:
    """Call AI to generate structured analysis with per-dimension insights."""
    profile_label = PROFILE_TYPE_LABELS.get(profile_type, profile_type)
    prompt = _build_prompt(profile_label, confidence, dimension_scores, summary)

    try:
        result = llm.invoke_structured(
            [
                {"role": "system", "content": ANALYSIS_SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
            ],
            QuestionnaireAnalysisOutput,
            scene=GENERATION_KIND_ANALYSIS,
            temperature=0.7,
            max_tokens=3000,
        )
        return _normalize_analysis(
            result.model_dump(mode="json"),
            profile_type=profile_type,
            profile_label=profile_label,
            confidence=confidence,
            dimension_scores=dimension_scores,
        )
    except Exception:
        # 交卷路径上不能因为模型抖动就让整个提交失败，降级成基础版解读。
        logger.exception("[AI] 量表分析生成失败 user=%s，降级为基础解读", profile_type)
        return _generate_fallback(profile_type, profile_label, confidence, dimension_scores)


def prepare_analysis(db: Session, user_id: int, questionnaire_id: int) -> dict:
    """流式版量表分析的前处理，与 `letter_ai_service.prepare_understand_letter` 同构。

    量表分析是**个人维度**的（只与本人依恋类型相关），因此 `relation_id` 传 None。
    """
    profile = profile_repo.get_latest_profile(db, user_id)
    if not profile:
        raise ValueError("40004")

    scores = profile_repo.get_dimension_scores(db, profile.id)
    dimension_scores = {s.dimension_key: s.score for s in scores}
    if not dimension_scores:
        raise ValueError("40004")

    # 提前取成局部变量：请求级 session 关闭后 ORM 实例会 detached，
    # 到落库/归一化阶段再去读属性就取不到了。
    profile_type = profile.profile_type
    confidence = profile.confidence
    summary = profile.summary or ""
    profile_label = PROFILE_TYPE_LABELS.get(profile_type, profile_type)

    base_prompt = _build_prompt(profile_label, confidence, dimension_scores, summary)
    prompt = build_structured_stream_prompt(
        base_prompt,
        QuestionnaireAnalysisOutput,
        content_instruction=(
            "先讲整体解读（分段），再按分数从高到低逐个维度讲，"
            "每个维度单独一段、以「维度中文名（分数分）」开头，"
            "最后依次讲优势、成长建议与沟通指南；"
            "不要出现「分数」「维度」这类表格术语，像面对面聊天那样讲"
        ),
        max_content_chars=1400,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=None,
        generation_kind=GENERATION_KIND_ANALYSIS,
        scene_key=GENERATION_KIND_ANALYSIS,
        target_type=TARGET_TYPE_QUESTIONNAIRE,
        target_id=questionnaire_id,
    )

    def _finalize(raw: Dict[str, Any]) -> Dict[str, Any]:
        return _normalize_analysis(
            raw,
            profile_type=profile_type,
            profile_label=profile_label,
            confidence=confidence,
            dimension_scores=dimension_scores,
        )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": GENERATION_KIND_ANALYSIS,
        "scene_key": GENERATION_KIND_ANALYSIS,
        "target_type": TARGET_TYPE_QUESTIONNAIRE,
        "target_id": questionnaire_id,
        "user_id": user_id,
        "relation_id": None,
        "prompt": prompt,
        "output_model": QuestionnaireAnalysisOutput,
        "temperature": 0.7,
        # 双出口协议下要容纳「思考 + 正文 + JSON」，与信件解读同规格
        "max_tokens": 4000,
        "finalize_structured": _finalize,
    }


def save_analysis_to_submission(
    user_id: int, questionnaire_id: int, analysis: Dict[str, Any]
) -> None:
    """流式生成结束后把分析写回 submission。

    必须在响应体阶段调用：那时请求级 db 已销毁，只能用独立会话，
    且任何异常都不该影响已经推给用户的内容，所以全程吞掉。
    """
    db = SessionLocal()
    try:
        for sub in questionnaire_repo.get_submissions_by_user(db, user_id):
            if sub.questionnaire_id == questionnaire_id:
                sub.profile_analysis = analysis.get("profile_analysis") or ""
                sub.dimension_analyses = analysis.get("dimension_analyses") or []
                sub.strengths = analysis.get("strengths") or ""
                sub.growth_tips = analysis.get("growth_tips") or []
                sub.communication_guide = analysis.get("communication_guide") or ""
                break
        db.commit()
    except Exception:
        db.rollback()
        logger.exception(
            "[AI] 量表分析回写 submission 失败 user=%s questionnaire=%s",
            user_id,
            questionnaire_id,
        )
    finally:
        db.close()


def _build_prompt(
    profile_label: str,
    confidence: float,
    dimension_scores: Dict[str, float],
    summary: str,
) -> str:
    """把测评数据铺成 Prompt 里的维度清单。

    每个维度后面带上英文 key：模型要把 key 原样写回 `dimension_analyses[].key`，
    靠它才能与系统里的分数一一对上。
    """
    dimension_lines = []
    for k, v in sorted(dimension_scores.items(), key=lambda x: x[1], reverse=True):
        label = DIMENSION_LABELS.get(k, k)
        dimension_lines.append(f"- {label}（{k}）: {v}分, {_score_level(v)}")
    dimension_text = "\n".join(dimension_lines)

    return ANALYSIS_PROMPT.format(
        profile_label=profile_label,
        confidence=confidence,
        summary=summary,
        dimension_text=dimension_text,
    )


def _normalize_analysis(
    raw: Dict[str, Any],
    *,
    profile_type: str,
    profile_label: str,
    confidence: float,
    dimension_scores: Dict[str, float],
) -> dict:
    """把模型输出对齐到系统里的真实分数与完整维度列表。

    三件事，缺一不可：
    1. **分数与等级一律用系统算的**，模型的数字只当没看见；
    2. 按分数从高到低排序，且只保留真实存在的维度（模型多编的 key 丢掉）；
    3. 模型漏写解读的维度用兜底文案补上，页面上不会出现空条目。
    """
    items = raw.get("dimension_analyses") or []
    provided: Dict[str, Dict[str, Any]] = {}
    if isinstance(items, list):
        for item in items:
            if isinstance(item, dict) and item.get("key"):
                provided[str(item["key"])] = item

    ordered = []
    for key, score in sorted(dimension_scores.items(), key=lambda x: x[1], reverse=True):
        item = provided.get(key) or {}
        text = str(item.get("analysis") or "").strip()
        ordered.append(
            {
                "key": key,
                "label": DIMENSION_LABELS.get(key, key),
                "score": score,
                "level": _score_level(score),
                "analysis": text or _fallback_dim_analysis(key, score),
            }
        )

    tips = raw.get("growth_tips") or []
    if isinstance(tips, str):
        tips = [tips]

    return {
        "profile_type": profile_type,
        "profile_label": profile_label,
        "confidence": confidence,
        "dimension_scores": dimension_scores,
        "profile_analysis": raw.get("profile_analysis") or "",
        "dimension_analyses": ordered,
        "strengths": raw.get("strengths") or "",
        "growth_tips": list(tips),
        "communication_guide": raw.get("communication_guide") or "",
    }


def _score_level(score: float) -> str:
    if score >= 70:
        return "高"
    elif score >= 40:
        return "中"
    else:
        return "低"


def _generate_fallback(
    profile_type: str,
    profile_label: str,
    confidence: float,
    dimension_scores: Dict[str, float],
) -> dict:
    """Generate basic structured analysis when AI call fails."""

    type_descriptions = {
        "secure": "安全型依恋的人在亲密关系中通常感到舒适，既能享受亲密也能保持独立。你倾向于信任伴侣，善于表达需求，也能敏锐感知伴侣的情绪变化。",
        "anxious": "焦虑依恋型的人在亲密关系中渴望亲密和确认，有时会担心被抛弃。你可能对伴侣的行为变化特别敏感，需要更多的安全感确认。",
        "dismissive": "疏离回避型的人在亲密关系中重视独立和自主，可能不太习惯表达情感。你倾向于自己解决问题，有时可能显得情感上保持距离。",
        "fearful": "恐惧回避型的人在亲密关系中既渴望亲密又害怕受伤。你可能在靠近和退缩之间摇摆，这反映了内心对亲密关系的复杂感受。",
    }

    dim_analyses = []
    for key in sorted(dimension_scores, key=dimension_scores.get, reverse=True):
        score = dimension_scores[key]
        dim_analyses.append({
            "key": key,
            "label": DIMENSION_LABELS.get(key, key),
            "score": score,
            "level": _score_level(score),
            "analysis": _fallback_dim_analysis(key, score),
        })

    high_dims = sorted(dimension_scores.items(), key=lambda x: x[1], reverse=True)[:2]
    tips = [
        f"关注你的「{DIMENSION_LABELS.get(k, k)}」特质，觉察它如何影响你的关系互动"
        for k, _ in high_dims
    ]
    tips.append("练习用「我感到…」的方式向伴侣表达需求，而不是指责")

    return {
        "profile_type": profile_type,
        "profile_label": profile_label,
        "confidence": confidence,
        "dimension_scores": dimension_scores,
        "profile_analysis": type_descriptions.get(profile_type, ""),
        "dimension_analyses": dim_analyses,
        "strengths": "你在关系中有自我觉察的意愿，这是改善关系的重要基础。",
        "growth_tips": tips,
        "communication_guide": "尝试在情绪平静时与伴侣分享你的感受，用具体的事例而非笼统的指责来表达需求。",
    }


def _fallback_dim_analysis(key: str, score: float) -> str:
    """Generate fallback text for a single dimension."""
    label = DIMENSION_LABELS.get(key, key)
    if score >= 70:
        return f"你的{label}水平较高。这意味着在亲密关系中，这个特质对你影响比较大，值得多加觉察。"
    elif score >= 40:
        return f"你的{label}处于中等水平。这个特质在你身上有一定体现，但不会过度主导你的关系行为。"
    else:
        return f"你的{label}水平较低。说明在亲密关系中，这个特质对你影响不大。"
