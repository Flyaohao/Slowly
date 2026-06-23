from sqlalchemy.orm import Session
from typing import Optional, List, Dict

from app.repositories import questionnaire_repo, profile_repo
from app.models.questionnaire import QuestionnaireAnswer
from app.services.profile_service import generate_profile
from app.repositories.couple_repo import get_active_relation_by_user
from app.services.conflict_detector import detect_conflict_pattern


def get_active_questionnaire(db: Session) -> dict:
    q = questionnaire_repo.get_active_questionnaire(db)
    if not q:
        return None
    return {
        "id": q.id,
        "title": q.title,
        "description": q.description,
        "version": q.version,
        "status": q.status,
        "created_at": q.created_at,
    }


def get_questionnaire_questions(db: Session, questionnaire_id: int) -> List[dict]:
    questions = questionnaire_repo.get_questions_with_options(db, questionnaire_id)
    result = []
    for q in questions:
        options = sorted(q.options, key=lambda o: o.sort_order)
        result.append({
            "id": q.id,
            "questionnaire_id": q.questionnaire_id,
            "question_text": q.question_text,
            "question_type": q.question_type,
            "dimension_key": q.dimension_key,
            "is_required": q.is_required,
            "weight": q.weight,
            "sort_order": q.sort_order,
            "options": [
                {
                    "id": o.id,
                    "option_text": o.option_text,
                    "score_value": o.score_value,
                    "sort_order": o.sort_order,
                }
                for o in options
            ],
        })
    return result


def save_answers(db: Session, user_id: int, questionnaire_id: int, answers: List[dict]) -> List[dict]:
    saved = questionnaire_repo.save_answers(db, user_id, questionnaire_id, answers)
    return [
        {
            "id": a.id,
            "question_id": a.question_id,
            "answer_value": a.answer_value,
        }
        for a in saved
    ]


def save_current_index(db: Session, user_id: int, questionnaire_id: int, current_index: int) -> dict:
    progress = questionnaire_repo.save_progress_index(db, user_id, questionnaire_id, current_index)
    return {
        "questionnaire_id": questionnaire_id,
        "current_question_index": progress.current_question_index,
    }


def get_progress(db: Session, user_id: int) -> dict:
    q = questionnaire_repo.get_active_questionnaire(db)
    if not q:
        raise ValueError("40001")

    all_qids = questionnaire_repo.get_question_ids(db, q.id)
    answers = questionnaire_repo.get_answers_by_user(db, user_id, q.id)
    answered_ids = {a.question_id for a in answers}

    total = len(all_qids)
    answered = len(answered_ids)
    pct = (answered / total * 100) if total > 0 else 0

    current_index = questionnaire_repo.get_progress_index(db, user_id, q.id)

    # Check if user has already submitted this questionnaire
    is_submitted = questionnaire_repo.has_submission_for_questionnaire(db, user_id, q.id)

    return {
        "questionnaire_id": q.id,
        "total_questions": total,
        "answered_count": answered,
        "progress_percent": round(pct, 1),
        "current_question_index": current_index,
        "is_submitted": is_submitted,
        "answers": [
            {
                "id": a.id,
                "question_id": a.question_id,
                "answer_value": a.answer_value,
            }
            for a in answers
        ],
    }


def submit_questionnaire(db: Session, user_id: int, questionnaire_id: int) -> dict:
    # Check for duplicate submission with row-level lock to prevent race condition
    if questionnaire_repo.has_submission_for_questionnaire(db, user_id, questionnaire_id):
        raise ValueError("40006")

    required_ids = questionnaire_repo.get_required_question_ids(db, questionnaire_id)
    answers = questionnaire_repo.get_answers_by_user(db, user_id, questionnaire_id)
    answered_ids = {a.question_id for a in answers}

    missing = set(required_ids) - answered_ids
    if missing:
        raise ValueError("40002")

    # Build answer map (reuse the same query, LOW-1 fix)
    all_qids = questionnaire_repo.get_question_ids(db, questionnaire_id)
    answered_map = {a.question_id: a.answer_value for a in answers}

    questions = questionnaire_repo.get_questions_with_options(db, questionnaire_id)
    question_map = {q.id: q for q in questions}

    answers_with_meta = []
    for qid in all_qids:
        answer_val = answered_map.get(qid)
        q_obj = question_map.get(qid)
        if answer_val is not None and q_obj is not None:
            answers_with_meta.append({
                "question_id": qid,
                "dimension_key": q_obj.dimension_key,
                "weight": q_obj.weight,
                "answer_value": answer_val,
                "options": {opt.id: opt for opt in q_obj.options},
            })

    profile = generate_profile(db, user_id, questionnaire_id, answers_with_meta)

    relation = get_active_relation_by_user(db, user_id)
    couple_profile_ready = False
    if relation:
        _try_generate_couple_profile(db, relation.id, user_id)
        couple_profile = profile_repo.get_latest_couple_profile(db, relation.id)
        couple_profile_ready = couple_profile is not None

    # Create submission record
    q_obj = questionnaire_repo.get_questionnaire_by_id(db, questionnaire_id)
    q_title = q_obj.title if q_obj else "关系画像问卷"
    total_q = len(all_qids)
    answered_count = len(answered_ids)

    profile_data = profile_repo.get_latest_profile(db, user_id)
    dim_scores = {}
    profile_type = None
    profile_summary = None
    if profile_data:
        profile_type = profile_data.profile_type
        profile_summary = profile_data.summary
        scores = profile_repo.get_dimension_scores(db, profile_data.id)
        dim_scores = {s.dimension_key: s.score for s in scores}

    # 生成 AI 分析报告
    profile_analysis = ""
    dimension_analyses = []
    strengths = ""
    growth_tips = []
    communication_guide = ""
    if profile_type and dim_scores:
        try:
            from app.services import questionnaire_analysis_service
            analysis_result = questionnaire_analysis_service.analyze_for_user(db, user_id)
            profile_analysis = analysis_result.get("profile_analysis", "")
            dimension_analyses = analysis_result.get("dimension_analyses", [])
            strengths = analysis_result.get("strengths", "")
            growth_tips = analysis_result.get("growth_tips", [])
            communication_guide = analysis_result.get("communication_guide", "")
        except Exception:
            pass  # 如果生成失败，继续创建submission

    questionnaire_repo.create_submission(
        db,
        user_id=user_id,
        questionnaire_id=questionnaire_id,
        questionnaire_title=q_title,
        total_questions=total_q,
        answered_count=answered_count,
        profile_type=profile_type,
        profile_summary=profile_summary,
        dimension_scores=dim_scores,
        couple_profile_ready=couple_profile_ready,
        profile_analysis=profile_analysis,
        dimension_analyses=dimension_analyses,
        strengths=strengths,
        growth_tips=growth_tips,
        communication_guide=communication_guide,
    )

    # Clean up progress record (LOW-3 fix)
    questionnaire_repo.delete_progress_index(db, user_id, questionnaire_id)

    db.commit()

    return {
        "questionnaire_id": questionnaire_id,
        "profile_generated": True,
        "couple_profile_ready": couple_profile_ready,
    }


def _try_generate_couple_profile(db: Session, relation_id: int, current_user_id: int) -> None:
    from app.models.couple_relation import CoupleRelation

    relation = db.query(CoupleRelation).filter(CoupleRelation.id == relation_id).first()
    if not relation:
        return

    profile_a = profile_repo.get_latest_profile(db, relation.user_a_id)
    profile_b = profile_repo.get_latest_profile(db, relation.user_b_id)

    if not profile_a or not profile_b:
        return

    existing = profile_repo.get_latest_couple_profile(db, relation_id)
    if existing:
        return

    scores_a = profile_repo.get_dimension_scores(db, profile_a.id)
    scores_b = profile_repo.get_dimension_scores(db, profile_b.id)
    dim_a = {s.dimension_key: s.score for s in scores_a}
    dim_b = {s.dimension_key: s.score for s in scores_b}

    conflict = detect_conflict_pattern(dim_a, dim_b)

    summary = _build_couple_summary(profile_a, profile_b, conflict)

    profile_repo.create_couple_profile(
        db,
        relation_id=relation_id,
        user_a_profile_id=profile_a.id,
        user_b_profile_id=profile_b.id,
        conflict_pattern=conflict,
        summary=summary,
    )


def _build_couple_summary(profile_a, profile_b, conflict_pattern: str) -> str:
    type_names = {
        "secure": "安全型",
        "anxious": "焦虑依恋型",
        "dismissive": "疏离回避型",
        "fearful": "恐惧回避型",
    }
    pattern_names = {
        "pursue_withdraw": "追问-退缩循环",
        "emotional_escalation": "情绪升级循环",
        "mutual_withdrawal": "双向冷处理循环",
        "explanation_misunderstanding": "解释-不被理解循环",
        "suppress_explode": "压抑-爆发循环",
        "no_conflict": "无明显冲突模式",
    }
    a_type = type_names.get(profile_a.profile_type, profile_a.profile_type)
    b_type = type_names.get(profile_b.profile_type, profile_b.profile_type)
    pattern = pattern_names.get(conflict_pattern, conflict_pattern)
    return f"用户A为{a_type}，用户B为{b_type}，主要冲突模式为{pattern}。"
