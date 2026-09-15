from sqlalchemy.orm import Session
from typing import Optional, Dict

from app.repositories import letter_repo, couple_repo, profile_repo
from app.schemas.ai_output import (
    LetterAnalysisOutput,
    LetterReplyOutput,
    LetterRewriteOutput,
)
from app.services import ai_generation_service
from app.services.ai_service import _call_llm, _format_profile, _get_partner_id
from app.services.prompt_builder import build_structured_stream_prompt

#: 落 `ai_generation` 时用的生成类型。同时也是回读端点里的 `kind` 参数值，
#: 客户端与后端两侧都靠它定位「某封信的解读」，改名会同时打断两边。
GENERATION_KIND_UNDERSTAND = "letter_analysis"
#: 信件改写（`/ai/rewrite-letter/stream`）
GENERATION_KIND_REWRITE = "letter_rewrite"
#: AI 回信建议（`/ai/generate-reply/stream`）
GENERATION_KIND_REPLY = "letter_reply"
#: 生成结果挂靠的实体类型
TARGET_TYPE_LETTER = "letter"


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


def _load_letter_in_relation(db: Session, relation_id: int, letter_id: int):
    """取信件并校验它属于当前关系。"""
    letter = letter_repo.get_letter_by_id(db, letter_id)
    if not letter:
        raise ValueError("60001")
    if letter.relation_id != relation_id:
        raise ValueError("60002")
    return letter


def _sender_profile_text(db: Session, sender_id: int) -> str:
    profile = profile_repo.get_latest_profile(db, sender_id)
    scores = {}
    if profile:
        dims = profile_repo.get_dimension_scores(db, profile.id)
        scores = {d.dimension_key: d.score for d in dims}
    return _format_profile(profile, scores)


def prepare_understand_letter(
    db: Session, user_id: int, relation_id: int, letter_id: int
) -> dict:
    """流式版「AI 帮我理解」的前处理：校验权限 → 拼 Prompt → 占位生成记录。

    和同步版 `understand_letter` 的关系：**共用同一份 Prompt 文本和同一个
    输出模型**（`LETTER_UNDERSTAND_PROMPT` + `LetterAnalysisOutput`），
    只在输出协议上分叉——同步版走 Function Calling 一次性拿 JSON，
    这里用 `build_structured_stream_prompt` 改成「先流式正文、后附 JSON」。

    必须在请求级 db 存活时调用：`begin()` 要落一条 `streaming` 占位记录，
    并把它的 id 作为 `generation_id` 交给客户端用于中断。
    """
    letter = _load_letter_in_relation(db, relation_id, letter_id)
    sender_profile_text = _sender_profile_text(db, letter.sender_id)

    base_prompt = LETTER_UNDERSTAND_PROMPT.format(
        sender_profile=sender_profile_text,
        letter_title=letter.title,
        letter_content=letter.content,
    )
    prompt = build_structured_stream_prompt(
        base_prompt,
        LetterAnalysisOutput,
        content_instruction=(
            "像在跟用户逐层拆解这封信：先说清对方此刻的情绪，"
            "再说他最在意的是什么、期待你怎样回应，最后给出几条可以直接用的回信方向"
        ),
        max_content_chars=600,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=relation_id,
        generation_kind=GENERATION_KIND_UNDERSTAND,
        scene_key="letter_analysis",
        target_type=TARGET_TYPE_LETTER,
        target_id=letter_id,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": GENERATION_KIND_UNDERSTAND,
        "scene_key": "letter_analysis",
        "target_type": TARGET_TYPE_LETTER,
        "target_id": letter_id,
        "user_id": user_id,
        "relation_id": relation_id,
        "prompt": prompt,
        "output_model": LetterAnalysisOutput,
        "temperature": 0.7,
        # 双出口协议下，这条上限要同时容纳「思考 + 正文 + 结构化 JSON」。
        # 实测推理模型的思考能到 1.1 万字，若上限卡在 2000，正文之后的 JSON
        # 会被截断，结构化字段整批丢失（2026-09-16 实测）。留足额度。
        "max_tokens": 4000,
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


def prepare_rewrite_letter(
    db: Session, user_id: int, relation_id: int, letter_id: int, style: str
) -> dict:
    """流式版「信件改写」的前处理，与 `prepare_understand_letter` 同构。

    共用同步版的 Prompt（LETTER_REWRITE_PROMPT）和输出模型（LetterRewriteOutput），
    仅输出协议分叉：正文流直接输出改写后的信件全文（用户最关心的东西，
    打字机逐字可见），分隔符后的 JSON 承载标题/改动说明等结构化字段。
    """
    letter = _load_letter_in_relation(db, relation_id, letter_id)
    if letter.sender_id != user_id:
        raise ValueError("60002")

    partner_id = _get_partner_id(db, relation_id, user_id)
    partner_profile = profile_repo.get_latest_profile(db, partner_id) if partner_id else None
    partner_scores = {}
    if partner_profile:
        dims = profile_repo.get_dimension_scores(db, partner_profile.id)
        partner_scores = {d.dimension_key: d.score for d in dims}
    partner_profile_text = _format_profile(partner_profile, partner_scores)

    base_prompt = LETTER_REWRITE_PROMPT.format(
        partner_profile=partner_profile_text,
        letter_title=letter.title,
        letter_content=letter.content,
        style=style,
    )
    prompt = build_structured_stream_prompt(
        base_prompt,
        LetterRewriteOutput,
        content_instruction=(
            "直接输出改写后的完整信件正文，像替用户重写这封信一样，"
            "不要复述原文、不要解释改写思路"
        ),
        max_content_chars=1200,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=relation_id,
        generation_kind=GENERATION_KIND_REWRITE,
        scene_key="letter_rewrite",
        target_type=TARGET_TYPE_LETTER,
        target_id=letter_id,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": GENERATION_KIND_REWRITE,
        "scene_key": "letter_rewrite",
        "target_type": TARGET_TYPE_LETTER,
        "target_id": letter_id,
        "user_id": user_id,
        "relation_id": relation_id,
        "prompt": prompt,
        "output_model": LetterRewriteOutput,
        "temperature": 0.7,
        # 双出口协议下要容纳「思考 + 正文 + JSON」，理由同 prepare_understand_letter
        "max_tokens": 4000,
    }


def prepare_generate_reply(
    db: Session, user_id: int, relation_id: int, letter_id: int
) -> dict:
    """流式版「AI 回信建议」的前处理，与 `prepare_understand_letter` 同构。"""
    letter = _load_letter_in_relation(db, relation_id, letter_id)
    sender_profile_text = _sender_profile_text(db, letter.sender_id)

    base_prompt = LETTER_REPLY_PROMPT.format(
        sender_profile=sender_profile_text,
        letter_title=letter.title,
        letter_content=letter.content,
    )
    prompt = build_structured_stream_prompt(
        base_prompt,
        LetterReplyOutput,
        content_instruction=(
            "像替用户起草回信一样，按不同风格逐段输出可直接发送的回信全文，"
            "每个风格前用一行标注风格名"
        ),
        max_content_chars=1200,
    )

    generation_id, cancel_event = ai_generation_service.begin(
        db,
        user_id=user_id,
        relation_id=relation_id,
        generation_kind=GENERATION_KIND_REPLY,
        scene_key="letter_reply",
        target_type=TARGET_TYPE_LETTER,
        target_id=letter_id,
    )

    return {
        "generation_id": generation_id,
        "cancel_event": cancel_event,
        "generation_kind": GENERATION_KIND_REPLY,
        "scene_key": "letter_reply",
        "target_type": TARGET_TYPE_LETTER,
        "target_id": letter_id,
        "user_id": user_id,
        "relation_id": relation_id,
        "prompt": prompt,
        "output_model": LetterReplyOutput,
        "temperature": 0.7,
        "max_tokens": 4000,
    }
