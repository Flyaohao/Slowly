from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.schemas.common import ApiResponse
from app.schemas.ai_schema import ChatRequest, FeedbackRequest, LetterUnderstandRequest, LetterRewriteRequest, LetterReplyRequest, RewriteRequest
from app.services import ai_service, letter_ai_service
from app.repositories import couple_repo, ai_repo

router = APIRouter(prefix="/ai", tags=["AI 翻译官"])


@router.get("/scenes", response_model=ApiResponse)
def get_scenes(db: Session = Depends(get_db)):
    scenes = ai_repo.get_all_scenes(db)
    return ApiResponse(data=[
        {"scene_key": s.scene_key, "name": s.name, "description": s.description}
        for s in scenes
    ])


@router.post("/chat", response_model=ApiResponse)
def chat(
    req: ChatRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )

    try:
        result = ai_service.chat(
            db=db,
            user_id=current_user.id,
            relation_id=relation.id,
            session_id=req.session_id,
            scene_key=req.scene_key,
            user_input=req.message,
        )
    except ValueError as e:
        code = str(e)
        if code == "50001":
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": 50001, "message": "场景不存在", "data": None},
            )
        if code == "50002":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={"code": 50002, "message": "无权访问此会话", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 50000, "message": "AI 服务异常", "data": None},
        )

    return ApiResponse(data=result)


@router.get("/sessions", response_model=ApiResponse)
def get_sessions(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    sessions = ai_service.get_sessions(db, current_user.id)
    return ApiResponse(data=sessions)


@router.get("/sessions/{session_id}/messages", response_model=ApiResponse)
def get_session_messages(
    session_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        messages = ai_service.get_session_messages(db, current_user.id, session_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权访问此会话", "data": None},
        )
    return ApiResponse(data=messages)


@router.post("/sessions/{session_id}/feedback", response_model=ApiResponse)
def submit_feedback(
    session_id: int,
    req: FeedbackRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    messages = ai_repo.get_messages_by_session(db, session_id)
    assistant_messages = [m for m in messages if m.role == "assistant"]
    if not assistant_messages:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 50003, "message": "会话无 AI 消息", "data": None},
        )
    last_msg = assistant_messages[-1]

    try:
        ai_service.submit_feedback(
            db,
            current_user.id,
            session_id,
            last_msg.id,
            {
                "rating": req.rating,
                "feedback_tag": req.feedback_tag,
                "feedback_text": req.feedback_text,
            },
        )
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权操作此会话", "data": None},
        )
    return ApiResponse()


@router.delete("/sessions/{session_id}", response_model=ApiResponse)
def delete_session(
    session_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        ai_service.delete_session(db, current_user.id, session_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权删除此会话", "data": None},
        )
    return ApiResponse()


@router.post("/rewrite", response_model=ApiResponse)
def rewrite_expression(
    req: RewriteRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """表达改写：将用户输入改写为5种不同风格版本"""
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )

    try:
        result = ai_service.rewrite_expression(
            db=db,
            user_id=current_user.id,
            relation_id=relation.id,
            original_text=req.text,
            context=req.context,
        )
    except ValueError as e:
        code = str(e)
        if code == "50001":
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": 50001, "message": "场景不存在", "data": None},
            )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 50000, "message": "AI 服务异常", "data": None},
        )
    return ApiResponse(data=result)


@router.post("/understand-letter", response_model=ApiResponse)
def understand_letter(
    req: LetterUnderstandRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = letter_ai_service.understand_letter(db, current_user.id, req.letter_id)
    except ValueError as e:
        code = str(e)
        error_map = {
            "60001": (404, "信件不存在"),
            "60002": (403, "无权访问此信件"),
            "30005": (400, "请先绑定情侣关系"),
        }
        sc, msg = error_map.get(code, (500, "AI 服务异常"))
        raise HTTPException(
            status_code=sc,
            detail={"code": int(code), "message": msg, "data": None},
        )
    return ApiResponse(data=result)


@router.post("/rewrite-letter", response_model=ApiResponse)
def rewrite_letter(
    req: LetterRewriteRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = letter_ai_service.rewrite_letter(
            db, current_user.id, req.letter_id, req.style
        )
    except ValueError as e:
        code = str(e)
        error_map = {
            "60001": (404, "信件不存在"),
            "60002": (403, "无权访问此信件"),
            "30005": (400, "请先绑定情侣关系"),
        }
        sc, msg = error_map.get(code, (500, "AI 服务异常"))
        raise HTTPException(
            status_code=sc,
            detail={"code": int(code), "message": msg, "data": None},
        )
    return ApiResponse(data=result)


@router.post("/generate-reply", response_model=ApiResponse)
def generate_reply(
    req: LetterReplyRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    try:
        result = letter_ai_service.generate_reply(db, current_user.id, req.letter_id)
    except ValueError as e:
        code = str(e)
        error_map = {
            "60001": (404, "信件不存在"),
            "60002": (403, "无权访问此信件"),
            "30005": (400, "请先绑定情侣关系"),
        }
        sc, msg = error_map.get(code, (500, "AI 服务异常"))
        raise HTTPException(
            status_code=sc,
            detail={"code": int(code), "message": msg, "data": None},
        )
    return ApiResponse(data=result)
