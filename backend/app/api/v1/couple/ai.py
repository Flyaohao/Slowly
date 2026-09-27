import asyncio
import logging
import threading
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse
from starlette.background import BackgroundTask
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.dependencies import get_current_user
from app.core.features import require_feature
from app.core.limiter import ai_limit
from app.schemas.common import ApiResponse
from app.schemas.ai_schema import (
    AgentRequest,
    ChatRequest,
    FeedbackRequest,
    LetterUnderstandRequest,
    LetterRewriteRequest,
    LetterReplyRequest,
    RewriteRequest,
    ReviewRequest,
    ReviewOutcomeRequest,
    DualSummaryRequest,
    MemoryCardRequest,
    ViewpointAnalysisRequest,
)
from app.services import (
    ai_generation_service,
    ai_service,
    letter_ai_service,
    relationship_review_service,
)
from app.services.sse import sse_encode, SSE_HEADERS as _SSE_HEADERS
from app.repositories import ai_generation_repo, couple_repo, ai_repo

logger = logging.getLogger(__name__)

logger = logging.getLogger("couple.ai")

router = APIRouter(prefix="/ai", tags=["AI 军师"])


@router.get("/scenes", response_model=ApiResponse)
def get_scenes(db: Session = Depends(get_db)):
    scenes = ai_repo.get_all_scenes(db)
    return ApiResponse(data=[
        {"scene_key": s.scene_key, "name": s.name, "description": s.description}
        for s in scenes
    ])


@router.post("/chat", response_model=ApiResponse)
@ai_limit()
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
            chat_mode=req.chat_mode,
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
@ai_limit()
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
            chat_mode=req.chat_mode,
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

    # 客户端断开信号：后端在响应体阶段会启动一个后台线程做结构化提取
    # （§8.2），断连后没人再读它的结果，必须让提取线程能停下来，
    # 否则「首轮 → 重试 → JSON 兜底」会继续把 token 烧完。
    # prepared 是流式生成器的入参，这里挂上事件即可（不改事件协议）。
    disconnect_event = threading.Event()
    prepared["cancel_event"] = disconnect_event

    return StreamingResponse(
        _guarded_sse_stream(prepared, disconnect_event),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
        # 断连信号有两条来源，缺一不可，见 _disconnect_watcher。
        background=BackgroundTask(_disconnect_watcher, request, disconnect_event),
    )


async def _disconnect_watcher(request: Request, disconnect_event: threading.Event) -> None:
    """客户端断开时**立刻**置位取消信号。

    为什么不靠生成器关闭（``except GeneratorExit`` / ``finally``）：实测
    starlette 0.37.2 的 ``StreamingResponse.__call__`` 在收到 ``http.disconnect``
    后只是 ``cancel_scope.cancel()`` 掉 ``stream_response`` 的 await，同步生成器
    的那一帧要等到**下一次 gen0 GC 回收**才被关闭——实测断开 8 秒后 ``finally``
    仍未执行。靠它置位等于没置位：提取线程会继续跑完 45s 上限。

    这里改用 ``BackgroundTask``：starlette 在 ``task_group`` 退出后、**同一个
    ASGI 调用内** await 它（``responses.py`` 的 ``await self.background()``），
    所以拿得到 ``receive`` 并立刻读到断连消息。客户端正常收完也一样会置位——
    那时提取早已结束，置位无副作用。

    为什么还需要 ``_guarded_sse_stream`` 里的 finally：BackgroundTask 是
    starlette 的实现细节，一旦哪次升级改变了「同一 ASGI 调用内 await」的语义，
    生成器关闭这条兜底路径还在。
    """
    try:
        while not disconnect_event.is_set():
            if await request.is_disconnected():
                disconnect_event.set()
                return
            # 10ms 粒度足够：提取线程的每一次轮询都要等 LLM 调用返回，
            # 提前几十毫秒没有意义，而轮询太密会白占一个 asyncio 槽位。
            await asyncio.sleep(0.01)
    except asyncio.CancelledError:
        # 请求被整体取消；交由生成器的 finally 兜底，这里不要吞掉取消。
        raise
    except Exception:  # noqa: BLE001 —— 观察者自身绝不能让请求失败
        logger.warning("[AI] 断连观察任务异常，改由生成器关闭兜底", exc_info=True)


def _guarded_sse_stream(prepared: dict, disconnect_event: threading.Event):
    """把事件流转成 SSE 串，并在**生成器关闭时**置位断连信号。

    抽成模块级函数而不是端点内闭包：这是「客户端断开 → 提取线程收工」这条
    取消链路的兜底实现处，必须能被测试直接调用（tests/test_feedback_loop.py
    的断连用例）。主链路是 [_disconnect_watcher]（见那里的说明：starlette
    0.37.2 下生成器关闭要等 GC，不能当主信号源）。
    """
    try:
        for chunk in sse_encode(ai_service.stream_chat_events(prepared)):
            yield chunk
    finally:
        disconnect_event.set()


@router.get("/sessions", response_model=ApiResponse)
def get_sessions(
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    sessions = ai_service.get_sessions(db, current_user.id)
    return ApiResponse(data=sessions)


@router.get("/sessions/active", response_model=ApiResponse)
def get_active_session(
    scene_key: str = Query(..., min_length=1),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """P0-10A：服务端权威判定「当前该续接哪段会话」。

    GET /ai/sessions/active?scene_key=xxx
    data: {session_id, title, message_count, last_message_at, resumable}
    - resumable=true → 客户端直接续接
    - resumable=false 且 session_id 非空 → 最近一段，供「上次聊到…」提示
    - session_id=null → 无 active 会话，新开
    业务错误 {code,message,data} + HTTP 200（与其它 AI 端点一致）；
    未登录仍走 get_current_user 的 401。
    """
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )
    data = ai_service.get_active_session_info(
        db, current_user.id, relation.id, scene_key
    )
    return ApiResponse(data=data)


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
    """提交/补全反馈（整改契约 §8.3）。

    - 不带 ``message_id``（旧客户端）→ 会话最后一条 AI 消息；
    - 带 ``message_id`` → 校验属于本会话且 role=assistant，否则 50002；
    - 服务端 upsert：同一用户同一消息的 outcome 更新原行，
      不再产生永久 ``outcome IS NULL`` 的旧行；
    - 响应回显落库后的反馈（data 只增不减，旧客户端忽略即可）。
    """
    messages = ai_repo.get_messages_by_session(db, session_id)
    assistant_messages = [m for m in messages if m.role == "assistant"]
    if not assistant_messages:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 50003, "message": "会话无 AI 消息", "data": None},
        )

    if req.message_id is not None:
        target = next((m for m in messages if m.id == req.message_id), None)
        if target is None or target.role != "assistant":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={"code": 50002, "message": "反馈目标消息无效", "data": None},
            )
        target_msg_id = target.id
    else:
        target_msg_id = assistant_messages[-1].id

    try:
        fb = ai_service.submit_feedback(
            db,
            current_user.id,
            session_id,
            target_msg_id,
            {
                "rating": req.rating,
                "feedback_tag": req.feedback_tag,
                "feedback_text": req.feedback_text,
                # 契约 §3.4：只增不减——旧客户端不传这两项 → None（不改）
                "adopted": req.adopted,
                "outcome": req.outcome,
            },
        )
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权操作此会话", "data": None},
        )
    return ApiResponse(data=fb)


@router.get("/feedback/pending", response_model=ApiResponse)
def get_pending_feedback(
    days: int = Query(7, ge=1, le=90, description="回访窗口天数"),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """契约 §3.4：近 N 天「有建议但无 outcome」的反馈摘要。

    会话制过滤（session.user_id == 我）——单人模式无 AI 会话时返回空列表，
    不要求绑定关系（与本文件其余端点的 per-endpoint 自检风格一致）。
    """
    return ApiResponse(data=ai_service.list_pending_feedback(db, current_user.id, days))


@router.post("/sessions/{session_id}/close", response_model=ApiResponse)
def close_session(
    session_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """P0-10B：显式结束当前会话（归档 + 沉淀 session_summary）。

    客户端「新对话」必须先调本端点——否则下一句 sessionId=null 时服务端
    会静默续接仍为 active 的旧会话（见 P0-10B 执行 prompt §2.1）。
    幂等：已归档再调仍 200。
    """
    try:
        data = ai_service.close_session(db, current_user.id, session_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={"code": 50002, "message": "无权访问此会话", "data": None},
        )
    return ApiResponse(data=data)


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
@ai_limit()
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
@ai_limit()
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
@ai_limit()
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

    契约 §2.5-2（MEDIUM-3）：目标信是未解锁 future 时，本批发布前落库的
    解读/改写/回信派生内容同样拒绝回读——错误码与 detail 一致（60002/403）。
    """
    try:
        payload = ai_generation_service.get_saved(
            db, current_user.id, kind, target_type, target_id
        )
    except ValueError as e:
        code = str(e)
        if code == "60002":
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={"code": 60002, "message": "无权访问此信件", "data": None},
            )
        raise
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
@ai_limit()
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
@ai_limit()
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
@ai_limit()
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


# --------------------------------------------------------------------------- #
# 单次触发型 AI 生成的流式端点（v2.2 起全面切换）
#
# 与 /understand-letter/stream 完全同一套基建：prepare_* 在请求级 db 存活期间
# 完成校验与占位，stream_generation_events 推 SSE，结果落 ai_generation。
# 旧同步端点原样保留：线上客户端还没切过来。
# --------------------------------------------------------------------------- #

_LETTER_ERROR_MAP = {
    "60001": (404, "信件不存在"),
    "60002": (403, "无权访问此信件"),
    "30005": (400, "请先绑定情侣关系"),
}


def _raise_prepared_error(exc: ValueError, error_map: dict) -> None:
    """把 prepare_* 抛出的 ValueError 翻译成 HTTPException。

    必须在流开始之前抛干净：流一旦开始，HTTP 头已发出，状态码改不了。
    """
    code = str(exc)
    sc, msg = error_map.get(code, (500, "AI 服务异常"))
    raise HTTPException(
        status_code=sc,
        detail={"code": int(code) if code.isdigit() else 50000, "message": msg, "data": None},
    )


@router.post("/rewrite-letter/stream")
@ai_limit()
def rewrite_letter_stream(
    request: Request,
    req: LetterRewriteRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """信件改写流式接口（SSE）。事件序列与 /understand-letter/stream 一致。"""
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )
    try:
        prepared = letter_ai_service.prepare_rewrite_letter(
            db, current_user.id, relation.id, req.letter_id, req.style
        )
    except ValueError as e:
        _raise_prepared_error(e, _LETTER_ERROR_MAP)

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


@router.post("/generate-reply/stream")
@ai_limit()
def generate_reply_stream(
    request: Request,
    req: LetterReplyRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """AI 回信建议流式接口（SSE）。事件序列与 /understand-letter/stream 一致。"""
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )
    try:
        prepared = letter_ai_service.prepare_generate_reply(
            db, current_user.id, relation.id, req.letter_id
        )
    except ValueError as e:
        _raise_prepared_error(e, _LETTER_ERROR_MAP)

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


@router.post("/rewrite/stream")
@ai_limit()
def rewrite_expression_stream(
    request: Request,
    req: RewriteRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """表达改写（帮我表达）流式接口（SSE）。事件序列与 /understand-letter/stream 一致。"""
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )
    try:
        prepared = ai_service.prepare_rewrite_expression(
            db=db,
            user_id=current_user.id,
            relation_id=relation.id,
            original_text=req.text,
            context=req.context,
        )
    except ValueError as e:
        _raise_prepared_error(e, {"50001": (404, "场景不存在")})

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


@router.post("/review/stream")
@ai_limit()
def relationship_review_stream(
    request: Request,
    req: ReviewRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """关系复盘流式接口（SSE）。事件序列与 /rewrite/stream 一致。

    对应功能设计 六.9：输入一次争吵/冷战/和好的经过，输出触发点、双方真实需求、
    误解发生处、升级冲突的话语、降温话术、下次可提前使用的表达。
    """
    relation = couple_repo.get_active_relation_by_user(db, current_user.id)
    if not relation:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": 30005, "message": "请先绑定情侣关系", "data": None},
        )
    try:
        prepared = ai_service.prepare_relationship_review(
            db=db,
            user_id=current_user.id,
            relation_id=relation.id,
            description=req.description,
            context=req.context,
            event_time=_parse_event_time(req.event_time),
        )
    except ValueError as e:
        _raise_prepared_error(
            e, {"50001": (404, "场景不存在"), "20001": (400, "内容不适合分析，请换个说法")}
        )

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


def _parse_event_time(raw):
    """可选的事件发生时间。可解析则用之，否则返回 None（由留档回落创建时间）。

    刻意不报 422：格式写错不该让整次复盘发不出去——发生时间只是记录维度，
    而用户已经写好了一整段经过。
    """
    if not raw:
        return None
    from datetime import datetime

    text = raw.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    # 带时区的输入统一转成朴素本地时间，与库中其它时间列（naive）保持一致
    return parsed.replace(tzinfo=None) if parsed.tzinfo else parsed


@router.get("/review/history", response_model=ApiResponse)
def review_history(
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """整改 §8.7：复盘历史（可回看）。倒序分页，只给列表需要的字段。

    路径必须声明在 `/review/{review_id}` 之前：否则 "history" 会被当成
    路径参数去解析成整数而 422。
    """
    return ApiResponse(data=relationship_review_service.list_history(
        db, current_user.id, page, page_size
    ))


@router.get("/review/{review_id}", response_model=ApiResponse)
def review_detail(
    review_id: int,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """单次复盘详情（含六项留档字段与「重新复盘一次」恢复输入所需原文）。"""
    try:
        data = relationship_review_service.get_detail(db, current_user.id, review_id)
    except ValueError as e:
        _raise_prepared_error(e, {"70001": (404, "复盘记录不存在")})
    return ApiResponse(data=data)


@router.post("/review/{review_id}/outcome", response_model=ApiResponse)
def submit_review_outcome(
    review_id: int,
    req: ReviewOutcomeRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """回填「后来怎么样了」（§8.7 后续结果）。回填后回访任务随之消失。"""
    try:
        data = relationship_review_service.submit_outcome(db, current_user.id, review_id, req.outcome)
    except ValueError as e:
        _raise_prepared_error(e, {"70001": (404, "复盘记录不存在")})
    return ApiResponse(data=data)

@router.post("/dual-summary/stream")
@ai_limit()
def dual_summary_stream(
    request: Request,
    req: DualSummaryRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """双视角对照总结流式接口（SSE），纯 Markdown 长文。

    事件双方都已提交后才有意义：输出共识、分歧、各自真正在意的事、下次可以怎么说。
    """
    try:
        prepared = ai_service.prepare_dual_summary(db, current_user.id, req.event_id)
    except ValueError as e:
        _raise_prepared_error(
            e,
            {
                "70001": (404, "事件不存在"),
                "70002": (403, "无权查看该事件"),
                "70003": (400, "双方都写下并公开视角后才能生成总结"),
            },
        )

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


@router.post("/viewpoint-analysis/stream")
@ai_limit()
def viewpoint_analysis_stream(
    request: Request,
    req: ViewpointAnalysisRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """观点分析（SSE）：读懂用户主动写下的一段观点，判断能否写进画像。

    **只是建议**：真正的写入要用户在前端确认后调 `POST /profiles/me/enrich`，
    由 `profile_service` 按幅度规则落库。这里不碰画像。
    """
    try:
        prepared = ai_service.prepare_viewpoint_analysis(
            db, current_user.id, req.viewpoint_id
        )
    except ValueError as e:
        _raise_prepared_error(e, {"90011": (404, "观点不存在")})

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


# 收敛期冻结（契约 §1）：AI 回忆卡随纪念馆/愿望清单一并删除。
@router.post("/memory-card/stream", dependencies=[Depends(require_feature("memory_card"))])
@ai_limit()
def memory_card_stream(
    request: Request,
    req: MemoryCardRequest,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """纪念日/愿望回忆卡片流式接口（SSE），纯 Markdown 长文。

    一条条目一张卡片：一段有画面感的叙事 + 3 个适合两人一起聊的问题。
    """
    try:
        prepared = ai_service.prepare_memory_card(
            db, current_user.id, req.target_type, req.target_id
        )
    except ValueError as e:
        _raise_prepared_error(
            e,
            {
                "100001": (404, "条目不存在"),
                "100002": (403, "无权查看该条目"),
                "100003": (400, "不支持的条目类型"),
            },
        )

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )


@router.post("/profile-report/stream")
@ai_limit()
def profile_report_stream(
    request: Request,
    current_user=Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """AI 画像报告流式接口（SSE）。纯 Markdown 长文，无结构化字段（output_model=None）。"""
    try:
        prepared = ai_service.prepare_profile_report(db, current_user.id)
    except ValueError as e:
        _raise_prepared_error(e, {"40001": (400, "请先完成问卷")})

    return StreamingResponse(
        sse_encode(ai_generation_service.stream_generation_events(prepared)),
        media_type="text/event-stream",
        headers=_SSE_HEADERS,
    )
