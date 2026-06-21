import httpx
import json
from typing import Dict, List

from sqlalchemy.orm import Session

from app.core.config import AI_API_KEY, AI_MODEL, AI_BASE_URL
from app.repositories import profile_repo


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

    # Build dimension table for prompt
    dimension_lines = []
    for k, v in sorted(dimension_scores.items(), key=lambda x: x[1], reverse=True):
        label = DIMENSION_LABELS.get(k, k)
        level = _score_level(v)
        dimension_lines.append(f"- {label}（{k}）: {v}分, {level}")
    dimension_text = "\n".join(dimension_lines)

    prompt = f"""你是一位专业的亲密关系心理咨询师。请根据以下问卷测评结果，为用户生成一份详细、温暖、有洞察力的分析报告。

## 测评结果
- 依恋类型: {profile_label}（置信度: {confidence}）
- 概要: {summary}

## 各维度得分 (0-100分, 越高越强烈)
{dimension_text}

## 输出要求

请严格按以下JSON格式输出，不要输出任何JSON之外的内容：

{{
  "profile_analysis": "用2-3段文字详细解读用户的依恋类型，包括：这种类型的核心特点、在亲密关系中的典型表现、可能的形成原因。语气温暖，像朋友聊天一样自然。",
  "dimension_analyses": [
    {{
      "key": "维度英文key",
      "label": "维度中文名",
      "score": 分数,
      "level": "高/中/低",
      "analysis": "用1-2句话通俗解读这个维度的含义和用户的得分。要具体、有画面感，让用户觉得'说的就是我'。例如：'你在冲突中倾向于追问对方，这说明你很在乎关系中的确定感。'"
    }}
  ],
  "strengths": "用2-3句话总结用户在关系中的优势和积极特质",
  "growth_tips": ["建议1", "建议2", "建议3"],
  "communication_guide": "根据用户的依恋类型和沟通偏好，给出2-3条与伴侣沟通的实用建议"
}}

## 注意
- dimension_analyses 必须包含所有维度，按分数从高到低排序
- 每个维度的 analysis 要通俗易懂，像朋友聊天，不要太多专业术语
- growth_tips 给出3条具体可执行的建议
- 语气温暖、非评判性、有共情"""

    try:
        response_text = _call_ai_api(prompt)
        result = _parse_structured_response(response_text, profile_type, profile_label, confidence, dimension_scores)
        return result
    except Exception:
        return _generate_fallback(profile_type, profile_label, confidence, dimension_scores)


def _call_ai_api(prompt: str) -> str:
    """Call DeepSeek API via OpenAI-compatible endpoint."""
    url = f"{AI_BASE_URL}/chat/completions"

    headers = {
        "Authorization": f"Bearer {AI_API_KEY}",
        "Content-Type": "application/json",
    }

    payload = {
        "model": AI_MODEL,
        "messages": [
            {
                "role": "system",
                "content": "你是一位专业的亲密关系心理咨询师。请严格按照要求的JSON格式回复，不要添加任何其他内容。",
            },
            {
                "role": "user",
                "content": prompt,
            },
        ],
        "temperature": 0.7,
        "max_tokens": 3000,
    }

    with httpx.Client(timeout=60.0) as client:
        resp = client.post(url, headers=headers, json=payload)
        resp.raise_for_status()
        data = resp.json()

    return data["choices"][0]["message"]["content"]


def _parse_structured_response(
    response_text: str,
    profile_type: str,
    profile_label: str,
    confidence: float,
    dimension_scores: Dict[str, float],
) -> dict:
    """Parse AI JSON response into structured result."""
    # Try to extract JSON from response (handle markdown code blocks)
    text = response_text.strip()
    if text.startswith("```"):
        # Remove code block markers
        lines = text.split("\n")
        text = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])

    parsed = json.loads(text)

    # Build dimension analyses, fill missing dimensions
    dim_analyses = []
    seen_keys = set()
    for item in parsed.get("dimension_analyses", []):
        key = item.get("key", "")
        dim_analyses.append({
            "key": key,
            "label": item.get("label", DIMENSION_LABELS.get(key, key)),
            "score": item.get("score", dimension_scores.get(key, 50)),
            "level": item.get("level", _score_level(dimension_scores.get(key, 50))),
            "analysis": item.get("analysis", ""),
        })
        seen_keys.add(key)

    # Add any missing dimensions
    for key, score in dimension_scores.items():
        if key not in seen_keys:
            dim_analyses.append({
                "key": key,
                "label": DIMENSION_LABELS.get(key, key),
                "score": score,
                "level": _score_level(score),
                "analysis": f"{DIMENSION_LABELS.get(key, key)}得分{int(score)}分。",
            })

    return {
        "profile_type": profile_type,
        "profile_label": profile_label,
        "confidence": confidence,
        "dimension_scores": dimension_scores,
        "profile_analysis": parsed.get("profile_analysis", ""),
        "dimension_analyses": dim_analyses,
        "strengths": parsed.get("strengths", ""),
        "growth_tips": parsed.get("growth_tips", []),
        "communication_guide": parsed.get("communication_guide", ""),
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
