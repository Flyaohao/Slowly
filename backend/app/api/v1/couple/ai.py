import logging
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.core import config
from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.limiter import limiter, get_request_key
from app.schemas.common import ApiResponse
from app.schemas.ai_schema import (
    AgentRequest,
    ChatRequest,
    FeedbackRequest,
    LetterUnderstandRequest,
    LetterRewriteRequest,
    LetterReplyRequest,
    RewriteRequest,
)
from app.services import ai_generation_service, ai_service, letter_ai_service
from app.services.sse import sse_encode
from app.repositories import ai_generation_repo, couple_repo, ai_repo

logger = logging.getLogger("couple.ai")

router = APIRouter(prefix="/ai", tags=["AI 翻译官"])

#: SSE 响应的公共响应头。
#:
#: `X-Accel-Buffering: no` 不是可选项：线上前面挂着 Nginx，默认会把响应攒满
#: 缓冲区才下发，流式效果会整个消失（表现为「等了 20 秒，然后整段蹦出来」）。
_SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


def _ai_limit():
    """AI 端点限流装饰器。`AI_RATE_LIMIT` 为空或 0 时完全关闭（identity 装饰器）。

    不能直接把 "0/hour" 交给 slowapi——那意味着「每小时 0 次」= 全部拒绝。
    """
    if config.AI_RATE_LIMIT and config.AI_RATE_LIMIT not in ("0", "0/hour"):
        return limiter.limit(config.AI_RATE_LIMIT, key_func=get_request_key)
    return lambda f: f


@router.get("/scenes", response_model=ApiResponse)
def get_scenes(db: Session = Depends(get_db)):
    scenes = ai_repo.get_all_scenes(db)
    return ApiResponse(data=[
        {"scene_key": s.scene_key, "name": s.name, "description": s.description}
        for s in scenes
    ])


@router.post("/chat", response_model=ApiResponse)
@_ai_limit()
def chat(
    request: Request,
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


@router.post("/chat/stream")
@_ai_limit()
def chat_stream(
    request: Request,
    req: ChatRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """AI 对话流式接口（SSE）。

    与 `/chat` 共用同一套前处理（安全护栏 / 画像 / RAG / 记忆 / Prompt），
    区别只有一处：正文走 token 流，先出字再完成落库。

    为什么用 POST：请求需要携带 session_id / scene_key / message，
    GET query string 放不下，且长文本会污染访问日志。
    """
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )

    # 前处理在请求级 db 存活期间完成；异常在此处就抛，避免流已经开始才报错
    try:
        prepared = ai_service.prepare_chat(
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

    return StreamingResponse(
        sse_encode(ai_service.stream_chat_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


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
@_ai_limit()
def rewrite_expression(
    request: Request,
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
@_ai_limit()
def understand_letter(
    request: Request,
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


@router.post("/understand-letter/stream")
@_ai_limit()
def understand_letter_stream(
    request: Request,
    req: LetterUnderstandRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """信件「AI 帮我理解」流式接口（SSE）。

    与同步版 `/understand-letter` 的差异，逐条对应产品的三个诉求：

    1. **不空等**：正文一个字一个字下发，推理过程走 `thinking` 帧喂给
       「正在深度思考」面板。同步版是「转圈十几秒 → 整段蹦出来」。
    2. **可中断**：`meta` 帧带回 `generation_id`，客户端点「停止生成」时
       调 `/generations/{id}/cancel`，服务端立刻断掉到模型的连接。
       客户端直接断开连接也行，只是要多等一个心跳周期。
    3. **可回读**：结果落 `ai_generation`，退出详情页再进来直接读上次的结果，
       不再重复扣一次模型调用。回读走 `GET /generations/letter_analysis`。

    旧端点原样保留：线上客户端还没切过来，直接改造会让线上立刻不可用。

    事件序列：`meta` → `thinking`* / `delta`* → `notice`（正文说完、整理
    结构化结果中）→ `result` → `done`；失败给 `error`。空行注释帧是心跳，
    客户端解析器应当忽略。
    """
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )

    # 前处理必须在请求级 db 存活期间完成：它要落一条 streaming 占位记录并
    # 返回 generation_id。异常也要在这里抛干净，别等流已经开始了才报错
    # （那时 HTTP 头已发出，改不了状态码，客户端只能看到一个空流）。
    try:
        prepared = letter_ai_service.prepare_understand_letter(
            db, current_user.id, relation.id, req.letter_id
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

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


@router.get("/generations/{kind}", response_model=ApiResponse)
def get_generation(
    kind: str,
    target_type: str = "none",
    target_id: Optional[int] = None,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """回读某次单次触发型 AI 生成已保存的结果。

    没有记录时返回 `data: null`，客户端据此决定要不要发起新的生成——
    这正是「退出再进来还能看到上次的解读」的实现方式：先回读，拿不到才调模型。

    例：`GET /ai/generations/letter_analysis?target_type=letter&target_id=88`
    """
    payload = ai_generation_service.get_saved(
        db, current_user.id, kind, target_type, target_id
    )
    return ApiResponse(data=payload)


@router.post("/generations/{generation_id}/cancel", response_model=ApiResponse)
def cancel_generation(
    generation_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """中断一次正在进行的生成。

    `cancelled=false` 不是错误：生成可能刚好自己结束了，或者服务端重启过
    （取消信号存在进程内存里，见 `ai_stream_registry` 的说明）。
    客户端拿到 false 只需照常收尾即可。
    """
    row = ai_generation_repo.get_generation_by_id(db, generation_id)
    if not row or row.user_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50004, "message": "无权操作此生成", "data": None},
        )
    return ApiResponse(data={"cancelled": ai_generation_service.cancel(generation_id)})


@router.post("/rewrite-letter", response_model=ApiResponse)
@_ai_limit()
def rewrite_letter(
    request: Request,
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
@_ai_limit()
def generate_reply(
    request: Request,
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


@router.post("/agent", response_model=ApiResponse)
@_ai_limit()
def agent_chat(
    request: Request,
    req: AgentRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Agent 路径：让模型自行决定要不要先查画像 / 检索理论，再给出答复。

    与 `/chat` 的定位差异——`/chat` 是"一次调用、结构化输出"的问答；
    这里是有工具调用循环的 Agent，返回体里额外带上 `tool_calls` 轨迹
    （工具名 / 入参 / 结果摘要），客户端可以据此展示
    「已查询关系画像 · 已检索依恋理论」这类过程提示，而不是只给一段文本。

    此前 `run_agent` 只被 `tests/test_agent.py` 引用、没有任何端点暴露，
    能力是真实存在的但在产品里用不到；这个端点把它接进主链路。
    """
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )

    history = [item.model_dump() for item in (req.history or [])]

    # Agent 依赖 LangChain，装了才可用；没装时明确告知而不是抛 500 堆栈
    try:
        from app.agent.executor import run_agent
    except ImportError:
        logger.exception("[AI] Agent 依赖缺失")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 50001, "message": "Agent 服务未就绪（缺少 LangChain 依赖）", "data": None},
        )

    try:
        result = run_agent(
            req.question,
            user_id=current_user.id,
            relation_id=relation.id,
            history=history or None,
        )
    except Exception:
        logger.exception("[AI] Agent 执行失败 user=%s", current_user.id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"code": 50000, "message": "Agent 服务异常，请稍后重试", "data": None},
        )

    return ApiResponse(data=result)
