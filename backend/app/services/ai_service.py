from sqlalchemy.orm import Session
from typing import Optional, List, Dict

from app.repositories import (
    ai_repo,
    profile_repo,
    couple_repo,
)
from app.services.prompt_builder import build_prompt
from app.services.scene_router import get_scene_config
from app.services.output_validator import validate_structured_output
from app.services.safety_service import (
    check_input_safety,
    check_output_safety,
    get_safety_response,
    merge_risk_levels,
)
from app.services.rag_service import retrieve_chunks, build_rag_context
from app.services.memory_service import get_memory_context


def chat(
    db: Session,
    user_id: int,
    relation_id: int,
    session_id: Optional[int],
    scene_key: str,
    user_input: str,
) -> dict:
    scene = ai_repo.get_scene_by_key(db, scene_key)
    if not scene:
        raise ValueError("50001")

    input_risk = check_input_safety(user_input)
    if input_risk != "normal":
        safety_resp = get_safety_response(input_risk)
        return {
            "session_id": session_id or 0,
            "message": {
                "id": 0,
                "role": "assistant",
                "content": safety_resp,
                "structured_output": {"risk_level": input_risk},
                "risk_level": input_risk,
                "created_at": None,
            },
            "blocked": True,
        }

    user_profile = profile_repo.get_latest_profile(db, user_id)
    partner_id = _get_partner_id(db, relation_id, user_id)
    partner_profile = profile_repo.get_latest_profile(db, partner_id) if partner_id else None

    user_scores: Dict[str, float] = {}
    partner_scores: Dict[str, float] = {}
    conflict_pattern = None

    if user_profile:
        dims = profile_repo.get_dimension_scores(db, user_profile.id)
        user_scores = {d.dimension_key: d.score for d in dims}

    if partner_profile:
        dims = profile_repo.get_dimension_scores(db, partner_profile.id)
        partner_scores = {d.dimension_key: d.score for d in dims}

    couple_prof = profile_repo.get_latest_couple_profile(db, relation_id)
    if couple_prof:
        conflict_pattern = couple_prof.conflict_pattern

    if not session_id:
        scene_config = get_scene_config(scene_key)
        privacy = scene_config.get("privacy_level", "private")
        session = ai_repo.create_session(
            db, user_id, relation_id, scene_key, title=scene.name, privacy_level=privacy
        )
        session_id = session.id
    else:
        session = ai_repo.get_session_by_id(db, session_id)
        if not session or session.user_id != user_id:
            raise ValueError("50002")

    history = ai_repo.get_messages_by_session(db, session_id)
    recent_history = history[-20:] if len(history) > 20 else history
    history_text = "\n".join(
        f"{m.role}: {m.content}" for m in recent_history
    )

    user_profile_text = _format_profile(user_profile, user_scores)
    partner_profile_text = _format_profile(partner_profile, partner_scores)

    rag_chunks = retrieve_chunks(db, user_input)
    rag_context = build_rag_context(rag_chunks)

    memory_context = get_memory_context(db, user_id, relation_id)

    prompt = build_prompt(
        scene_key=scene_key,
        user_profile=user_profile_text,
        partner_profile=partner_profile_text,
        conflict_pattern=conflict_pattern or "未确定",
        user_input=user_input,
        history=history_text,
        rag_context=rag_context,
        memory_context=memory_context,
    )

    ai_repo.create_message(db, session_id, "user", user_input)
    ai_repo.save_prompt_version(db, user_id, relation_id, scene_key, prompt)

    ai_response = _call_llm(prompt, scene_key)

    structured = validate_structured_output(ai_response, scene_key)

    output_risk = check_output_safety(str(ai_response))
    if output_risk != "normal":
        structured["risk_level"] = output_risk
        safety_resp = get_safety_response(output_risk)
        structured["safety_notice"] = safety_resp

    risk_level = structured.get("risk_level", "normal")

    assistant_msg = ai_repo.create_message(
        db,
        session_id,
        "assistant",
        ai_response.get("raw_text", ""),
        structured_output=structured,
        risk_level=risk_level,
    )

    session.title = session.title or scene.name
    db.commit()

    return {
        "session_id": session_id,
        "message": {
            "id": assistant_msg.id,
            "role": "assistant",
            "content": assistant_msg.content,
            "structured_output": structured,
            "risk_level": risk_level,
            "created_at": assistant_msg.created_at,
        },
    }


def rewrite_expression(
    db: Session,
    user_id: int,
    relation_id: int,
    original_text: str,
    context: Optional[str] = None,
) -> dict:
    """
    表达改写：将用户输入改写为5种不同风格版本
    - 温柔版：语气柔和，减少攻击性
    - 直接版：表达清晰但不伤人
    - 道歉版：真诚表达歉意
    - 解释版：理性解释而非辩解
    - 想和好版：表达修复关系的意愿
    """
    scene = ai_repo.get_scene_by_key(db, "expression_rewrite")
    if not scene:
        raise ValueError("50001")

    user_profile = profile_repo.get_latest_profile(db, user_id)
    partner_id = _get_partner_id(db, relation_id, user_id)
    partner_profile = profile_repo.get_latest_profile(db, partner_id) if partner_id else None

    partner_scores: Dict[str, float] = {}
    if partner_profile:
        dims = profile_repo.get_dimension_scores(db, partner_profile.id)
        partner_scores = {d.dimension_key: d.score for d in dims}

    partner_profile_text = _format_profile(partner_profile, partner_scores)

    prompt = f"""你是一位专业的沟通顾问。请将以下原始表达改写为5种不同风格的版本。

## 原始表达
{original_text}

## 补充背景
{context or "无"}

## 对方画像
{partner_profile_text}

## 改写要求
请生成以下5个版本，每个版本都要保持核心意思不变，但调整语气和表达方式：

1. **温柔版**：语气柔和温暖，减少攻击性，让对方更容易接受
2. **直接版**：表达清晰直接，但不带伤害性，保持尊重
3. **道歉版**：真诚表达歉意和反思，承认自己的不足
4. **解释版**：理性解释自己的想法和感受，不带辩解语气
5. **想和好版**：表达想要修复关系的意愿，给出具体行动建议

## 输出格式
请以JSON格式输出，包含以下字段：
- versions: 数组，包含5个对象，每个对象有 style（风格名称）和 content（改写内容）
- summary: 改写总结（一句话说明主要调整）
"""

    ai_response = _call_llm(prompt, "expression_rewrite")

    # 解析5个版本
    versions = ai_response.get("versions", [
        {"style": "温柔版", "content": f"[温柔版] {original_text}"},
        {"style": "直接版", "content": f"[直接版] {original_text}"},
        {"style": "道歉版", "content": f"[道歉版] {original_text}"},
        {"style": "解释版", "content": f"[解释版] {original_text}"},
        {"style": "想和好版", "content": f"[想和好版] {original_text}"},
    ])

    return {
        "original": original_text,
        "versions": versions,
        "summary": ai_response.get("summary", "已为你生成5种改写版本"),
    }


def get_sessions(db: Session, user_id: int) -> List[dict]:
    sessions = ai_repo.get_sessions_by_user(db, user_id)
    return [
        {
            "id": s.id,
            "scene_key": s.scene_key,
            "title": s.title,
            "privacy_level": s.privacy_level,
            "created_at": s.created_at,
            "updated_at": s.updated_at,
        }
        for s in sessions
    ]


def get_session_messages(db: Session, user_id: int, session_id: int) -> List[dict]:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session or session.user_id != user_id:
        raise ValueError("50002")

    messages = ai_repo.get_messages_by_session(db, session_id)
    return [
        {
            "id": m.id,
            "role": m.role,
            "content": m.content,
            "structured_output": m.structured_output,
            "risk_level": m.risk_level,
            "created_at": m.created_at,
        }
        for m in messages
    ]


def submit_feedback(
    db: Session, user_id: int, session_id: int, message_id: int, feedback: dict
) -> None:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session or session.user_id != user_id:
        raise ValueError("50002")

    ai_repo.create_feedback(
        db,
        message_id=message_id,
        user_id=user_id,
        rating=feedback["rating"],
        feedback_tag=feedback.get("feedback_tag"),
        feedback_text=feedback.get("feedback_text"),
    )
    db.commit()


def generate_profile_report(db: Session, user_id: int) -> str:
    """生成 AI 画像分析报告（Markdown 格式）"""
    from app.services.prompt_builder import build_profile_report_prompt
    from app.repositories import profile_repo

    profile = profile_repo.get_latest_profile(db, user_id)
    if not profile:
        raise ValueError("40001")

    dimensions = profile_repo.get_dimension_scores(db, profile.id)
    if not dimensions:
        raise ValueError("40001")

    dimension_names = {
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

    dim_lines = []
    for d in dimensions:
        name = dimension_names.get(d.dimension_key, d.dimension_key)
        explanation = d.explanation or ""
        dim_lines.append(f"- {name}：{d.score:.0f}分（{explanation}）")
    dimensions_text = "\n".join(dim_lines)

    prompt = build_profile_report_prompt(
        profile_type=profile.profile_type,
        confidence=profile.confidence,
        dimensions_data=dimensions_text,
    )

    # 调用 LLM 生成报告
    result = _call_llm(prompt, "profile_report")
    return result.get("raw_text", "")


def delete_session(db: Session, user_id: int, session_id: int) -> None:
    session = ai_repo.get_session_by_id(db, session_id)
    if not session or session.user_id != user_id:
        raise ValueError("50002")
    ai_repo.delete_session(db, session_id)


def _get_partner_id(db: Session, relation_id: int, user_id: int) -> Optional[int]:
    from app.models.couple_relation import CoupleRelation
    relation = db.query(CoupleRelation).filter(CoupleRelation.id == relation_id).first()
    if not relation:
        return None
    return relation.user_b_id if relation.user_a_id == user_id else relation.user_a_id


def _format_profile(profile, scores: Dict[str, float]) -> str:
    if not profile:
        return "未完成问卷"
    type_names = {
        "secure": "安全型",
        "anxious": "焦虑依恋型",
        "dismissive": "疏离回避型",
        "fearful": "恐惧回避型",
    }
    ptype = type_names.get(profile.profile_type, profile.profile_type)
    dim_parts = []
    for k, v in scores.items():
        dim_parts.append(f"{k}={v}")
    dims_str = ", ".join(dim_parts) if dim_parts else "无"
    return f"依恋类型: {ptype}, 置信度: {profile.confidence}, 维度分数: [{dims_str}]"


def _call_llm(prompt: str, scene_key: str) -> dict:
    return {
        "raw_text": "AI 回复占位（需接入真实 LLM API）",
        "summary": "这是一条测试回复",
        "emotion_validation": "我理解你现在的感受",
        "partner_possible_meaning": "对方可能想要一些空间",
        "suggested_reply": "我理解你的感受，我们可以慢慢聊",
        "do_not_say": "不要说'你想太多了'",
        "next_step": "建议先冷静一下，稍后再沟通",
        "risk_level": "normal",
    }
