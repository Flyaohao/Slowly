import json

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.limiter import ai_limit
from app.schemas.common import ApiResponse
from app.schemas.questionnaire_schema import (
    QuestionnaireOut,
    SaveAnswersRequest,
    SaveProgressIndexRequest,
    AnalysisResponse,
)
from app.services import (
    ai_generation_service,
    questionnaire_service,
    questionnaire_analysis_service,
)
from app.services.sse import sse_encode, SSE_HEADERS
from app.repositories import questionnaire_repo


def _parse_analysis_text(analysis_text) -> dict:
    """解析 analysis_text JSON，返回结构化字段。兼容旧格式（纯文本）。"""
    if not analysis_text:
        return {}
    try:
        data = json.loads(analysis_text)
        if isinstance(data, dict):
            return data
    except (json.JSONDecodeError, TypeError):
        pass
    # 旧格式：纯文本
    return {"analysis": analysis_text}


def _build_submission_response(s) -> dict:
    """构建 submission 响应，包含结构化分析数据。"""
    analysis = _parse_analysis_text(s.analysis_text)
    return {
        "id": s.id,
        "questionnaire_id": s.questionnaire_id,
        "questionnaire_title": s.questionnaire_title,
        "total_questions": s.total_questions,
        "answered_count": s.answered_count,
        "profile_type": s.profile_type,
        "profile_summary": s.profile_summary,
        "dimension_scores": s.dimension_scores,
        "analysis_text": s.analysis_text,
        "couple_profile_ready": s.couple_profile_ready,
        "created_at": s.created_at.isoformat() if s.created_at else None,
        # 结构化分析字段
        "profile_analysis": analysis.get("profile_analysis", ""),
        "dimension_analyses": analysis.get("dimension_analyses", []),
        "strengths": analysis.get("strengths", ""),
        "growth_tips": analysis.get("growth_tips", []),
        "communication_guide": analysis.get("communication_guide", ""),
        # 置信度存在分析 JSON 里，历史记录页需要它；旧格式（纯文本）没有该字段，返回 0 表示"未知"
        "confidence": analysis.get("confidence", 0.0),
    }

router = APIRouter(prefix="/questionnaires", tags=["问卷"])


@router.get("/active", response_model=ApiResponse)
def get_active_questionnaire(db: Session = Depends(get_db)):
    q = questionnaire_service.get_active_questionnaire(db)
    if not q:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 40001, "message": "暂无活跃问卷", "data": None},
        )
    return ApiResponse(data=QuestionnaireOut(**q).model_dump())


@router.get("/{questionnaire_id}/questions", response_model=ApiResponse)
def get_questions(questionnaire_id: int, db: Session = Depends(get_db)):
    questions = questionnaire_service.get_questionnaire_questions(db, questionnaire_id)
    return ApiResponse(data=questions)


@router.post("/{questionnaire_id}/answers", response_model=ApiResponse)
def save_answers(
    questionnaire_id: int,
    req: SaveAnswersRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    answers = [{"question_id": a.question_id, "answer_value": a.answer_value} for a in req.answers]
    saved_answers = questionnaire_service.save_answers(db, current_user.id, questionnaire_id, answers)
    db.commit()
    return ApiResponse(data=saved_answers)


@router.post("/{questionnaire_id}/submit", response_model=ApiResponse)
def submit_questionnaire(
    questionnaire_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = questionnaire_service.submit_questionnaire(
            db, current_user.id, questionnaire_id
        )
    except ValueError as e:
        code = str(e)
        if code == "40002":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": 40002, "message": "必答题未完成", "data": None},
            )
        if code == "40006":
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={"code": 40006, "message": "该问卷已提交过", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40003, "message": "提交失败", "data": None},
        )
    return ApiResponse(data=result)


@router.get("/me/progress", response_model=ApiResponse)
def get_progress(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        progress = questionnaire_service.get_progress(db, current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 40001, "message": "暂无活跃问卷", "data": None},
        )
    return ApiResponse(data=progress)


@router.post("/{questionnaire_id}/progress-index", response_model=ApiResponse)
def save_progress_index(
    questionnaire_id: int,
    req: SaveProgressIndexRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if req.current_index < 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 40007, "message": "无效的题目索引", "data": None},
        )
    result = questionnaire_service.save_current_index(
        db, current_user.id, questionnaire_id, req.current_index
    )
    db.commit()
    return ApiResponse(data=result)


@router.post("/{questionnaire_id}/analyze", response_model=ApiResponse)
def analyze_questionnaire(
    questionnaire_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = questionnaire_analysis_service.analyze_for_user(db, current_user.id)
    except ValueError as e:
        code = str(e)
        if code == "40004":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": 40004, "message": "请先完成问卷", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 50003, "message": "分析生成失败，请稍后重试", "data": None},
        )
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 50003, "message": "分析生成失败，请稍后重试", "data": None},
        )

    # Save analysis to the matching submission
    submissions = questionnaire_repo.get_submissions_by_user(db, current_user.id)
    for sub in submissions:
        if sub.questionnaire_id == questionnaire_id:
            sub.analysis_text = json.dumps(result, ensure_ascii=False)
            break
    db.commit()

    return ApiResponse(data=result)


@router.post("/{questionnaire_id}/analyze/stream")
@ai_limit()
def analyze_questionnaire_stream(
    questionnaire_id: int,
    request: Request,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """量表分析流式接口（SSE）。

    事件序列与 `/ai/understand-letter/stream` 完全一致：
    `meta → thinking → delta → (notice) → result → done`。

    为什么这里比别的流式端点多包一层生成器：量表分析的结果除了落
    `ai_generation`，还要回写 `questionnaire_submission`——结果页与画像页读的
    都是 submission。回写只能发生在流结束之后，那时请求级 db 已销毁，
    所以由 `questionnaire_analysis_service.save_analysis_to_submission` 自开会话。
    """
    try:
        prepared = questionnaire_analysis_service.prepare_analysis(
            db, current_user.id, questionnaire_id
        )
    except ValueError as e:
        code = str(e)
        if code == "40004":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": 40004, "message": "请先完成问卷", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 50003, "message": "分析生成失败，请稍后重试", "data": None},
        )

    return StreamingResponse(
        _stream_and_save_analysis(prepared, current_user.id, questionnaire_id),
        media_type="text/event-stream",
        headers=SSE_HEADERS,
    )


def _stream_and_save_analysis(prepared: dict, user_id: int, questionnaire_id: int):
    """透传 SSE 事件，并在生成正常收尾后把结果写回 submission。

    只有拿到结构化结果才写：被中断时（用户点停止、断网、退到后台被杀）
    JSON 多半还没吐完，写一份半成品上去反而会覆盖掉上一次的完整分析。
    """
    result: dict = {}
    try:
        for event in ai_generation_service.stream_generation_events(prepared):
            if event.get("event") == "done":
                data = event.get("data") or {}
                result = data.get("structured_output") or {}
            yield event
    finally:
        # 客户端在 done 帧之后断开时，生成器会在 yield 处收到 GeneratorExit，
        # finally 仍然会执行——这正是我们要的：结果已经拿到就一定要落库。
        if result:
            questionnaire_analysis_service.save_analysis_to_submission(
                user_id, questionnaire_id, result
            )


@router.get("/history", response_model=ApiResponse)
def get_submission_history(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    submissions = questionnaire_repo.get_submissions_by_user(db, current_user.id)
    result = [_build_submission_response(s) for s in submissions]
    return ApiResponse(data=result)


@router.get("/history/{submission_id}", response_model=ApiResponse)
def get_submission_detail(
    submission_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    sub = questionnaire_repo.get_submission_by_id(db, submission_id, current_user.id)
    if not sub:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 40005, "message": "记录不存在", "data": None},
        )
    return ApiResponse(data=_build_submission_response(sub))


@router.delete("/history/{submission_id}", response_model=ApiResponse)
def delete_submission(
    submission_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    deleted = questionnaire_repo.delete_submission(db, submission_id, current_user.id)
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": 40005, "message": "记录不存在", "data": None},
        )
    db.commit()
    return ApiResponse(data={"deleted": True})
