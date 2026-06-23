from sqlalchemy.orm import Session
from typing import Optional, List

from app.models.questionnaire import (
    Questionnaire,
    QuestionnaireQuestion,
    QuestionnaireOption,
    QuestionnaireAnswer,
    QuestionnaireProgress,
    QuestionnaireSubmission,
)


def get_active_questionnaire(db: Session) -> Optional[Questionnaire]:
    return (
        db.query(Questionnaire)
        .filter(Questionnaire.status == "active")
        .order_by(Questionnaire.version.desc())
        .first()
    )


def get_questionnaire_by_id(db: Session, qid: int) -> Optional[Questionnaire]:
    return db.query(Questionnaire).filter(Questionnaire.id == qid).first()


def get_questions_with_options(db: Session, questionnaire_id: int) -> List[QuestionnaireQuestion]:
    return (
        db.query(QuestionnaireQuestion)
        .filter(QuestionnaireQuestion.questionnaire_id == questionnaire_id)
        .order_by(QuestionnaireQuestion.sort_order)
        .all()
    )


def get_answers_by_user(
    db: Session, user_id: int, questionnaire_id: int
) -> List[QuestionnaireAnswer]:
    return (
        db.query(QuestionnaireAnswer)
        .filter(
            QuestionnaireAnswer.user_id == user_id,
            QuestionnaireAnswer.questionnaire_id == questionnaire_id,
        )
        .all()
    )


def get_answer_by_user_question(
    db: Session, user_id: int, questionnaire_id: int, question_id: int
) -> Optional[QuestionnaireAnswer]:
    return (
        db.query(QuestionnaireAnswer)
        .filter(
            QuestionnaireAnswer.user_id == user_id,
            QuestionnaireAnswer.questionnaire_id == questionnaire_id,
            QuestionnaireAnswer.question_id == question_id,
        )
        .first()
    )


def upsert_answer(
    db: Session, user_id: int, questionnaire_id: int, question_id: int, answer_value: dict
) -> QuestionnaireAnswer:
    answer = get_answer_by_user_question(db, user_id, questionnaire_id, question_id)
    if answer:
        answer.answer_value = answer_value
    else:
        answer = QuestionnaireAnswer(
            user_id=user_id,
            questionnaire_id=questionnaire_id,
            question_id=question_id,
            answer_value=answer_value,
        )
        db.add(answer)
    db.flush()
    return answer


def save_answers(
    db: Session, user_id: int, questionnaire_id: int, answers: List[dict]
) -> List[QuestionnaireAnswer]:
    result = []
    for item in answers:
        ans = upsert_answer(
            db, user_id, questionnaire_id, item["question_id"], item["answer_value"]
        )
        result.append(ans)
    # Don't commit here - let the caller handle the transaction
    db.flush()
    return result


def get_question_ids(db: Session, questionnaire_id: int) -> List[int]:
    rows = (
        db.query(QuestionnaireQuestion.id)
        .filter(QuestionnaireQuestion.questionnaire_id == questionnaire_id)
        .all()
    )
    return [r[0] for r in rows]


def get_required_question_ids(db: Session, questionnaire_id: int) -> List[int]:
    rows = (
        db.query(QuestionnaireQuestion.id)
        .filter(
            QuestionnaireQuestion.questionnaire_id == questionnaire_id,
            QuestionnaireQuestion.is_required == True,
        )
        .all()
    )
    return [r[0] for r in rows]


def save_progress_index(
    db: Session, user_id: int, questionnaire_id: int, current_index: int
) -> QuestionnaireProgress:
    progress = (
        db.query(QuestionnaireProgress)
        .filter(
            QuestionnaireProgress.user_id == user_id,
            QuestionnaireProgress.questionnaire_id == questionnaire_id,
        )
        .first()
    )
    if progress:
        progress.current_question_index = current_index
    else:
        progress = QuestionnaireProgress(
            user_id=user_id,
            questionnaire_id=questionnaire_id,
            current_question_index=current_index,
        )
        db.add(progress)
    db.flush()
    return progress


def get_progress_index(db: Session, user_id: int, questionnaire_id: int) -> int:
    progress = (
        db.query(QuestionnaireProgress)
        .filter(
            QuestionnaireProgress.user_id == user_id,
            QuestionnaireProgress.questionnaire_id == questionnaire_id,
        )
        .first()
    )
    return progress.current_question_index if progress else 0


def delete_progress_index(db: Session, user_id: int, questionnaire_id: int) -> None:
    progress = (
        db.query(QuestionnaireProgress)
        .filter(
            QuestionnaireProgress.user_id == user_id,
            QuestionnaireProgress.questionnaire_id == questionnaire_id,
        )
        .first()
    )
    if progress:
        db.delete(progress)
        db.flush()


def create_submission(
    db: Session,
    user_id: int,
    questionnaire_id: int,
    questionnaire_title: str,
    total_questions: int,
    answered_count: int,
    profile_type: str = None,
    profile_summary: str = None,
    dimension_scores: dict = None,
    analysis_text: str = None,
    couple_profile_ready: bool = False,
    profile_analysis: str = None,
    dimension_analyses: dict = None,
    strengths: str = None,
    growth_tips: dict = None,
    communication_guide: str = None,
) -> QuestionnaireSubmission:
    sub = QuestionnaireSubmission(
        user_id=user_id,
        questionnaire_id=questionnaire_id,
        questionnaire_title=questionnaire_title,
        total_questions=total_questions,
        answered_count=answered_count,
        profile_type=profile_type,
        profile_summary=profile_summary,
        dimension_scores=dimension_scores,
        analysis_text=analysis_text,
        couple_profile_ready=couple_profile_ready,
        profile_analysis=profile_analysis,
        dimension_analyses=dimension_analyses,
        strengths=strengths,
        growth_tips=growth_tips,
        communication_guide=communication_guide,
    )
    db.add(sub)
    db.flush()
    return sub


def get_submissions_by_user(db: Session, user_id: int) -> List[QuestionnaireSubmission]:
    return (
        db.query(QuestionnaireSubmission)
        .filter(QuestionnaireSubmission.user_id == user_id)
        .order_by(QuestionnaireSubmission.created_at.desc())
        .all()
    )


def has_submission_for_questionnaire(db: Session, user_id: int, questionnaire_id: int) -> bool:
    """Check if submission exists, with row-level lock to prevent race conditions."""
    existing = (
        db.query(QuestionnaireSubmission.id)
        .filter(
            QuestionnaireSubmission.user_id == user_id,
            QuestionnaireSubmission.questionnaire_id == questionnaire_id,
        )
        .with_for_update(nowait=True)
        .first()
    )
    return existing is not None


def get_submission_by_id(db: Session, submission_id: int, user_id: int) -> Optional[QuestionnaireSubmission]:
    return (
        db.query(QuestionnaireSubmission)
        .filter(
            QuestionnaireSubmission.id == submission_id,
            QuestionnaireSubmission.user_id == user_id,
        )
        .first()
    )


def delete_submission(db: Session, submission_id: int, user_id: int) -> bool:
    sub = get_submission_by_id(db, submission_id, user_id)
    if not sub:
        return False
    db.delete(sub)
    db.flush()
    return True
