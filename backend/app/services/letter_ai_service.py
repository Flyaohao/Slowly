from sqlalchemy.orm import Session
from typing import Optional, Dict

from app.repositories import letter_repo, couple_repo, profile_repo
from app.services.ai_service import _call_llm, _format_profile, _get_partner_id


LETTER_UNDERSTAND_PROMPT = """你是一位专业的信件解读师，用户想深入理解伴侣写的一封信。

## 发件人画像
{sender_profile}

## 信件标题
{letter_title}

## 信件内容
{letter_content}

## 指导原则
1. 逐段分析信件内容
2. 提取对方最在意的点
3. 分析对方写信时的情绪状态
4. 标注可能被误解的句子
5. 给出回信建议
6. 使用"可能""倾向于"等表达，不把推测说成事实

请以 JSON 格式回复，包含以下字段：
- summary: 一句话摘要
- key_concerns: 对方最在意的点（字符串数组）
- emotion: 对方情绪描述
- expected_response: 对方期待的回应
- misunderstandable: 可能被误解的句子（数组，每个包含 sentence 和 note）
- reply_suggestions: 建议回信内容（字符串数组）
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk"""

LETTER_REWRITE_PROMPT = """你是一位专业的表达改写助手，用户想改写一封信的表达方式。

## 伴侣画像
{partner_profile}

## 信件标题
{letter_title}

## 信件原文
{letter_content}

## 改写风格要求
{style}

## 指导原则
1. 结合伴侣画像，生成符合要求的改写版本
2. 保留用户的核心诉求和情感
3. 避免伴侣的表达雷区
4. 语言自然真诚，不要过于书面化

请以 JSON 格式回复，包含以下字段：
- summary: 一句话摘要
- rewritten_title: 改写后的标题
- rewritten_content: 改写后的正文
- changes: 改动说明
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk"""

LETTER_REPLY_PROMPT = """你是一位专业的信件回信助手，用户想生成一封回信建议。

## 发件人画像
{sender_profile}

## 原信标题
{letter_title}

## 原信内容
{letter_content}

## 指导原则
1. 基于原信内容生成有针对性的回信建议
2. 考虑发件人画像中的性格特点和沟通偏好
3. 提供多个不同风格的回信版本
4. 语言真诚自然

请以 JSON 格式回复，包含以下字段：
- summary: 一句话摘要
- replies: 回信版本数组，每个包含 style（风格）和 content（内容）
- do_not_say: 避免说的话
- risk_level: normal/heated_conflict/manipulation_risk/abuse_risk/self_harm_risk"""


def understand_letter(db: Session, user_id: int, letter_id: int) -> dict:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")

    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.relation_id != relation.id:
        raise ValueError("60002")

    sender_profile = profile_repo.get_latest_profile(db, letter.sender_id)
    sender_scores = {}
    if sender_profile:
        dims = profile_repo.get_dimension_scores(db, sender_profile.id)
        sender_scores = {d.dimension_key: d.score for d in dims}
    sender_profile_text = _format_profile(sender_profile, sender_scores)

    prompt = LETTER_UNDERSTAND_PROMPT.format(
        sender_profile=sender_profile_text,
        letter_title=letter.title,
        letter_content=letter.content,
    )

    ai_response = _call_llm(prompt, "letter_analysis")
    return {
        "letter_id": letter_id,
        "analysis": {
            "summary": ai_response.get("summary", ""),
            "key_concerns": ai_response.get("key_concerns", []),
            "emotion": ai_response.get("emotion", ""),
            "expected_response": ai_response.get("expected_response", ""),
            "misunderstandable": ai_response.get("misunderstandable", []),
            "reply_suggestions": ai_response.get("reply_suggestions", []),
            "risk_level": ai_response.get("risk_level", "normal"),
        },
    }


def rewrite_letter(
    db: Session, user_id: int, letter_id: int, style: str
) -> dict:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")

    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.relation_id != relation.id:
        raise ValueError("60002")
    if letter.sender_id != user_id:
        raise ValueError("60002")

    partner_id = _get_partner_id(db, relation.id, user_id)
    partner_profile = profile_repo.get_latest_profile(db, partner_id) if partner_id else None
    partner_scores = {}
    if partner_profile:
        dims = profile_repo.get_dimension_scores(db, partner_profile.id)
        partner_scores = {d.dimension_key: d.score for d in dims}
    partner_profile_text = _format_profile(partner_profile, partner_scores)

    prompt = LETTER_REWRITE_PROMPT.format(
        partner_profile=partner_profile_text,
        letter_title=letter.title,
        letter_content=letter.content,
        style=style,
    )

    ai_response = _call_llm(prompt, "letter_rewrite")
    return {
        "letter_id": letter_id,
        "rewrite": {
            "summary": ai_response.get("summary", ""),
            "rewritten_title": ai_response.get("rewritten_title", ""),
            "rewritten_content": ai_response.get("rewritten_content", ""),
            "changes": ai_response.get("changes", ""),
            "risk_level": ai_response.get("risk_level", "normal"),
        },
    }


def generate_reply(db: Session, user_id: int, letter_id: int) -> dict:
    relation = couple_repo.get_active_relation_by_user(db, user_id)
    if not relation:
        raise ValueError("30005")

    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.relation_id != relation.id:
        raise ValueError("60002")

    sender_profile = profile_repo.get_latest_profile(db, letter.sender_id)
    sender_scores = {}
    if sender_profile:
        dims = profile_repo.get_dimension_scores(db, sender_profile.id)
        sender_scores = {d.dimension_key: d.score for d in dims}
    sender_profile_text = _format_profile(sender_profile, sender_scores)

    prompt = LETTER_REPLY_PROMPT.format(
        sender_profile=sender_profile_text,
        letter_title=letter.title,
        letter_content=letter.content,
    )

    ai_response = _call_llm(prompt, "letter_reply")
    return {
        "letter_id": letter_id,
        "reply": {
            "summary": ai_response.get("summary", ""),
            "replies": ai_response.get("replies", []),
            "do_not_say": ai_response.get("do_not_say", ""),
            "risk_level": ai_response.get("risk_level", "normal"),
        },
    }
