from sqlalchemy.orm import Session
from typing import Optional, List, Dict

from app.repositories import profile_repo, questionnaire_repo
from app.models.questionnaire import QuestionnaireAnswer


DIMENSION_DEFINITIONS = {
    "attachment_anxiety": {"label": "依恋焦虑", "source": "ECR-R"},
    "attachment_avoidance": {"label": "依恋回避", "source": "ECR-R"},
    "conflict_pursue": {"label": "冲突追问倾向", "source": "Demand-Withdraw"},
    "conflict_withdraw": {"label": "冲突退缩倾向", "source": "Demand-Withdraw"},
    "defensive_response": {"label": "防御反驳倾向", "source": "Gottman"},
    "emotional_validation_need": {"label": "情绪确认需求", "source": "自有"},
    "factual_explanation_need": {"label": "事实解释需求", "source": "自有"},
    "personal_space_need": {"label": "独处冷静需求", "source": "自有"},
    "reassurance_need": {"label": "安全感确认需求", "source": "自有"},
    "directness_preference": {"label": "直接表达偏好", "source": "自有"},
    "softness_preference": {"label": "柔和表达偏好", "source": "自有"},
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
