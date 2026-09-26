"""AI 后台任务的数据访问层。

职责边界：本文件只做 SQLAlchemy 访问与**原子状态转换**，不做业务编排
（谁该建什么任务、失败后会话进什么状态，都在 `app/services/ai_task_service.py`）。
"""

import hashlib
from datetime import datetime, timedelta
from typing import List, Optional, Sequence

from sqlalchemy import func, or_, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models.ai_task import (
    CLAIMABLE_STATES,
    DEFAULT_MAX_ATTEMPTS,
    STATE_FAILED,
    STATE_PENDING,
    STATE_RUNNING,
    STATE_SUCCEEDED,
    STATE_SUPERSEDED,
    AiTask,
)

#: 一次领取时最多探测多少个候选（避免高并发下退化成表扫描）
CLAIM_BATCH = 10

#: 租约默认时长。必须显著大于单次 LLM 调用上限（120s），否则会出现
#: 「任务还在跑、租约先到期、第二个 worker 又领一遍」的重复执行。
DEFAULT_LEASE_SECONDS = 600


def idempotency_key(task_type: str, session_id: int, revision: int) -> str:
    """幂等键：同类型 + 同会话 + 同版本 = 同一个任务。

    不含 attempt/时间戳——重试是同一行上的 `attempt` 自增，不是新任务。
    """
    raw = "%s|%s|%s" % (task_type, session_id, revision)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


#: 任务表的读路径一律绕过 identity map。
#:
#: 本模块全部状态转换走 Core UPDATE + commit，而会话是
#: `expire_on_commit=False`——commit 不会让内存对象失效。若读路径用普通
#: `db.query(...)`，同一 Session 第二次读到的会是**更新前**的旧对象
#: （例如任务已经 succeeded，读回来还是 running）。`populate_existing()`
#: 强制用当前行覆盖内存值，让「读回来的就是库里的」。
def get_by_idempotency_key(db: Session, key: str) -> Optional[AiTask]:
    return (
        db.query(AiTask)
        .filter(AiTask.idempotency_key == key)
        .populate_existing()
        .first()
    )


def get_task_by_id(db: Session, task_id: int) -> Optional[AiTask]:
    return (
        db.query(AiTask)
        .filter(AiTask.id == task_id)
        .populate_existing()
        .first()
    )


def create_task(
    db: Session,
    *,
    task_type: str,
    session_id: int,
    revision: int,
    requested_by_user_id: Optional[int] = None,
    payload: Optional[dict] = None,
    max_attempts: int = 3,
) -> AiTask:
    """建任务（幂等，**不提交**——事务由顶层业务操作统一控制）。

    并发下两个请求可能同时走到 INSERT：唯一约束会让后到的那个抛
    IntegrityError。这里用 SAVEPOINT（`begin_nested`）把 INSERT 的失败
    **只回滚到本次插入**，不动调用方外层事务里已经完成的「状态 CAS +
    revision 自增」——这正是「IntegrityError 只回滚当前原子事务，不破坏
    已经存在的正确任务」的落点。回读既有行后返回，调用方拿到的永远是
    同一个任务（「双方同时提交只创建一个改写任务」的存储层保证）。

    幂等插入完成后 `task.id` 已由 flush 填充（autoincrement），供调用方
    回写 `rewrite_task_id` / `summary_task_id`。
    """
    key = idempotency_key(task_type, session_id, revision)
    existing = get_by_idempotency_key(db, key)
    if existing is not None:
        return existing

    task = AiTask(
        task_type=task_type,
        session_id=session_id,
        requested_by_user_id=requested_by_user_id,
        revision=revision,
        idempotency_key=key,
        state=STATE_PENDING,
        attempt=0,
        max_attempts=max_attempts,
        payload=payload,
    )
    db.add(task)
    try:
        with db.begin_nested():
            db.flush()
    except IntegrityError:
        # 只回滚本次 INSERT 的 SAVEPOINT；外层事务（状态 CAS、revision 自增）不受影响
        existing = get_by_idempotency_key(db, key)
        if existing is None:
            raise
        return existing
    return task


def claim_next_task(
    db: Session,
    worker_id: str,
    *,
    lease_seconds: int = DEFAULT_LEASE_SECONDS,
    task_types: Optional[Sequence[str]] = None,
) -> Optional[AiTask]:
    """原子领取一个到期任务；`rowcount == 1` 才真正获得。**内部 commit**。

    为什么不是「先 SELECT 再 UPDATE」：两个 worker 同时读到同一行后会同时
    去执行，同一场调解就会被改写两遍（第二条 assistant 消息、重复的总结）。
    条件 UPDATE 把「选谁」和「归谁」合成一个原子动作：只有一个 worker 的
    WHERE 会命中（另一个的 locked_until 条件已不成立），rowcount 决定胜负。

    领取即 `attempt += 1`，且立刻 commit 释放行锁——后面的 LLM 调用
    绝不能持有事务。
    """
    now = datetime.utcnow()
    query = db.query(AiTask.id).filter(
        AiTask.state.in_(CLAIMABLE_STATES),
        # P0-3：领取硬门槛——尝试次数未耗尽才可领取。耗尽的行只能靠用户
        # 显式重试产生新版本的新任务，绝不自动再跑（连续崩溃不得无限执行）。
        AiTask.attempt < AiTask.max_attempts,
        or_(AiTask.locked_until.is_(None), AiTask.locked_until < now),
        or_(AiTask.next_retry_at.is_(None), AiTask.next_retry_at <= now),
    )
    if task_types:
        query = query.filter(AiTask.task_type.in_(tuple(task_types)))

    candidates = query.order_by(AiTask.id.asc()).limit(CLAIM_BATCH).all()

    for (task_id,) in candidates:
        result = db.execute(
            update(AiTask)
            .where(
                AiTask.id == task_id,
                AiTask.state.in_(CLAIMABLE_STATES),
                AiTask.attempt < AiTask.max_attempts,
                or_(AiTask.locked_until.is_(None), AiTask.locked_until < now),
                or_(AiTask.next_retry_at.is_(None), AiTask.next_retry_at <= now),
            )
            .values(
                state=STATE_RUNNING,
                locked_by=worker_id,
                locked_until=now + timedelta(seconds=lease_seconds),
                # 只在第一次领取时记 started_at（重试/回收后重跑不覆盖首次时间）
                started_at=func.coalesce(AiTask.started_at, now),
                heartbeat_at=now,
                attempt=AiTask.attempt + 1,
            )
        )
        db.commit()
        if result.rowcount == 1:
            return (
                db.query(AiTask)
                .filter(AiTask.id == task_id)
                .populate_existing()
                .first()
            )
    return None


def latest_retryable_task(
    db: Session, session_id: int, task_type: str
) -> Optional[AiTask]:
    """最近一条「**已经退下来**」的任务——用户点重试时可复位的行。

    排除 `running`：任务可能正被某个 worker 领走（长 LLM 调用期间它就是
    running）。此时若退而求其次去复位，两个执行者会各产一份结果，
    用户看到「点了重试，页面闪回生成中然后又跳出两份稿」。

    整表没有这样的行（唯一一条正在跑）时返回 None，调用方走
    `ensure_task`——它命中唯一键后拿回的还是**同一行**，因此
    「重试时不会复制任务」依然成立，只是这一次不改变任何状态。
    """
    return (
        db.query(AiTask)
        .filter(
            AiTask.session_id == session_id,
            AiTask.task_type == task_type,
            AiTask.state != STATE_RUNNING,
        )
        .order_by(AiTask.id.desc())
        .populate_existing()
        .first()
    )


def claim_for_retry(db: Session, session_id: int, task_type: str) -> Optional[AiTask]:
    """原子地把「属于当前版本的任务」复位回 pending（用户点重试 / 系统补偿）。

    返回 None 表示没有可复位的行——调用方应当按**当前版本**新建任务
    （`ensure_task` 命中唯一键时会拿回既有的那一行，因此同样不产生重复任务）。

    为什么必须是条件 UPDATE 而不是「读出来改字段」：两个客户端同时点重试时
    两个请求都会读到同一行，只有一条 UPDATE 命中；另一个拿到 None 也不会
    造出第二个任务（唯一键挡着）。若改成读-改-写，两次写都会「成功」，
    复位动作重复执行两次，中间夹着的领取会被自己的第二次复位抹掉。

    条件里保留了一次 `state != running` 复核：选行到 UPDATE 之间任务可能
    刚被 worker 领走，那一刻必须让路——否则会出现两个执行者各产一份结果。
    """
    now = datetime.utcnow()
    task = latest_retryable_task(db, session_id, task_type)
    if task is None:
        return None

    result = db.execute(
        update(AiTask)
        .where(
            AiTask.id == task.id,
            or_(
                AiTask.state != STATE_RUNNING,
                AiTask.locked_until.is_(None),
                AiTask.locked_until < now,
            ),
        )
        .values(
            state=STATE_PENDING,
            attempt=0,
            next_retry_at=None,
            last_error=None,
            locked_by=None,
            locked_until=None,
            started_at=None,
            finished_at=None,
            heartbeat_at=None,
        )
    )
    db.commit()
    if not result.rowcount:
        return None
    return get_task_by_id(db, task.id)


def recover_stale_tasks(
    db: Session, *, now: Optional[datetime] = None, limit: int = 50
):
    """回收租约过期仍停在 running 的任务（进程崩溃后的恢复）。

    **不清零 attempt**：崩溃本身也是一次真实的尝试失败，清零会让一个
    必然崩溃的任务无限重试。回收结果分两路（P0-3）：

    - 未耗尽（``attempt < max_attempts``）：恢复为 pending，重排执行；
    - 已耗尽（``attempt >= max_attempts``）：任务进入 failed 终态，
      由调用方（服务层）把会话推进到对应 ``*_failed``——不允许出现
      ``attempt > max_attempts`` 的无限重试。

    返回 ``(recovered, exhausted)``：recovered 为恢复为 pending 的数量，
    exhausted 为本次判为 failed 的任务列表（供服务层写会话失败态）。
    """
    now = now or datetime.utcnow()
    stale_rows = (
        db.query(AiTask.id, AiTask.attempt, AiTask.max_attempts)
        .filter(
            AiTask.state == STATE_RUNNING,
            AiTask.locked_until.isnot(None),
            AiTask.locked_until < now,
        )
        .order_by(AiTask.id.asc())
        .limit(limit)
        .all()
    )
    recovered = 0
    exhausted: List[AiTask] = []
    for task_id, attempt, max_attempts in stale_rows:
        if int(attempt or 0) >= int(max_attempts or DEFAULT_MAX_ATTEMPTS):
            result = db.execute(
                update(AiTask)
                .where(
                    AiTask.id == task_id,
                    AiTask.state == STATE_RUNNING,
                    AiTask.locked_until.isnot(None),
                    AiTask.locked_until < now,
                )
                .values(
                    state=STATE_FAILED,
                    locked_by=None,
                    locked_until=None,
                    next_retry_at=None,
                    last_error="worker 中断且重试耗尽",
                    heartbeat_at=None,
                    finished_at=now,
                )
            )
            db.commit()
            if result.rowcount == 1:
                exhausted.append(get_task_by_id(db, task_id))
        else:
            result = db.execute(
                update(AiTask)
                .where(
                    AiTask.id == task_id,
                    AiTask.state == STATE_RUNNING,
                    AiTask.locked_until.isnot(None),
                    AiTask.locked_until < now,
                )
                .values(
                    state=STATE_PENDING,
                    locked_by=None,
                    locked_until=None,
                    next_retry_at=None,
                    last_error="worker 中断，任务已回收重排",
                    heartbeat_at=None,
                    started_at=None,
                )
            )
            db.commit()
            recovered += int(result.rowcount or 0)
    return recovered, exhausted


def heartbeat(
    db: Session,
    task_id: int,
    worker_id: str,
    *,
    lease_seconds: int = DEFAULT_LEASE_SECONDS,
) -> bool:
    """续租。返回 False 表示这一行已经不属于当前执行者（被回收/作废）。

    P0-2：必须校验 `task_id + state + locked_by` 三者——旧执行者不得给
    新执行者续租：任务被回收后 `locked_by` 已被清空或改写，旧执行者手里
    的 worker_id 不再命中条件，续租失败。
    """
    now = datetime.utcnow()
    result = db.execute(
        update(AiTask)
        .where(
            AiTask.id == task_id,
            AiTask.state == STATE_RUNNING,
            AiTask.locked_by == worker_id,
        )
        .values(heartbeat_at=now, locked_until=now + timedelta(seconds=lease_seconds))
    )
    db.commit()
    return bool(result.rowcount)

def mark_succeeded(
    db: Session,
    task: AiTask,
    *,
    expected_revision: Optional[int] = None,
    commit: bool = True,
) -> bool:
    """标记成功。返回 False 表示**没有真正收口**，调用方不得认为结果已生效。

    两种 False：

    - `expected_revision`（会话当前版本）与任务自身的版本不一致：说明会话被
      推进过——产出作废，任务记 `superseded` 而不是 `succeeded`，这样
      「任务表里有一行成功、业务上却没有它的结果」这种矛盾不会出现。
    - 任务行已经不属于本次执行（租约过期后被回收、甚至已被别的 worker 重新
      领走）：本执行者的产出同样是过期的，什么都不写。

    `commit=False` 供「会话 CAS + assistant 消息 + 任务 succeeded」同一事务的
    生成结果写回路径使用（见 mediation_service._commit_generation_result）。
    """
    if expected_revision is not None and int(task.revision or 0) != int(expected_revision):
        _finalize(
            db, task.id, STATE_SUPERSEDED,
            error="结果已过期，被更新版本取代",
            locked_by=task.locked_by,
            commit=commit,
        )
        return False
    if not _finalize(db, task.id, STATE_SUCCEEDED, locked_by=task.locked_by, commit=commit):
        return False
    return True


def mark_superseded(db: Session, task: AiTask) -> None:
    """产出过期（会话版本已被推进）：作废但不计为失败。"""
    _finalize(
        db, task.id, STATE_SUPERSEDED, error="结果已过期，被更新版本取代",
        locked_by=task.locked_by,
    )


def mark_failed(db: Session, task: AiTask, error: str) -> bool:
    """重试耗尽：终态失败。会话侧的失败状态由服务层同步写入。

    返回 False 表示这一行已不归本次执行（被回收/被接管）——调用方不得再
    去写会话的失败态，否则会把一个正在别处重跑的会话打成失败。
    """
    return _finalize(db, task.id, STATE_FAILED, error=error[:480], locked_by=task.locked_by)


def schedule_retry(db: Session, task: AiTask, error: str, backoff_seconds: int) -> bool:
    """未耗尽：打回 pending，并在退避窗口之后才可再被领取。

    返回 False 表示任务行已不归本次执行所有（被回收/被取代），调用方应当
    放弃这次失败收口——否则会把一个正在别处正常重跑的会话打成失败。

    只重置状态与退避时间，**不动 `attempt`**：领取时已经自增过一次，
    失败重排不是新一轮尝试，而是同一次尝试的失败结论。
    """
    result = db.execute(
        update(AiTask)
        .where(
            AiTask.id == task.id,
            # 归属判据（P0-2）：本执行者领走时写下的值仍在、且任务仍在运行，
            # 才算这次执行还有发言权
            AiTask.state == STATE_RUNNING,
            AiTask.locked_by == task.locked_by,
        )
        .values(
            state=STATE_PENDING,
            last_error=error[:480],
            next_retry_at=datetime.utcnow() + timedelta(seconds=backoff_seconds),
            locked_by=None,
            locked_until=None,
        )
    )
    db.commit()
    return bool(result.rowcount)


def supersede_stale_tasks(
    db: Session, session_id: int, task_type: str, keep_revision: int
) -> int:
    """把同一会话同类型、版本更旧的任务置为 superseded。

    在推进 `mediation_revision` 之后调用：旧任务即便已经在重试队列里，
    也不会再把过期内容写回会话。

    **只取 pending 的**：`running` 的旧任务留着不动——掐掉一行的 ownership
    会让执行者回来时以「行不归我」判定放弃（见 `_finalize` 的归属条件），
    它手上的产出确实不该写回，这一步没错；但执行者若失败重排就会撞上
    「行已经被置 superseded」而写不进去，于是这次失败收口静默丢失。
    让 running 的那一行自然收口（写回时按 revision 判定为过期 → superseded），
    语义完全一致且不留半成品。
    """
    result = db.execute(
        update(AiTask)
        .where(
            AiTask.session_id == session_id,
            AiTask.task_type == task_type,
            AiTask.revision < keep_revision,
            AiTask.state == STATE_PENDING,
        )
        .values(
            state=STATE_SUPERSEDED,
            last_error="已被更新版本取代",
            finished_at=datetime.utcnow(),
        )
    )
    db.commit()
    return int(result.rowcount or 0)


def _finalize(
    db: Session,
    task_id: int,
    state: str,
    *,
    error: Optional[str] = None,
    locked_by: Optional[str],
    commit: bool = True,
) -> bool:
    """写终态。返回是否真的由本次调用写成（rowcount==1）。

    `locked_by` 是**归属判据**：任务行上的 `locked_by` 在领取时写成本执行者，
    被回收（`recover_stale_tasks`）后清空、被别的 worker 领走后又写成对方。
    条件里带上它，就能保证「只有还在持有这一行的执行者才能给它写终态」——
    否则一次慢调用回到现场时，会把已经被别人接管的任务行改成 failed/succeeded。

    调用方传 `None` 时条件退化成 `locked_by IS NULL`（对应用户重试复位后
    的「无主」任务），语义一致：没主的行谁都能收口。

    P0-2：额外限定 `state == running`——终态只由「仍在运行」的任务收口，
    已经 succeeded/failed/superseded 的行不再被反复改写。

    `commit=False` 供「业务结果与任务收口同一事务」的路径使用（生成结果
    写回），此时 UPDATE 留在调用方事务里，由调用方统一 commit/rollback。
    """
    conditions = [
        AiTask.id == task_id,
        AiTask.state == STATE_RUNNING,
    ]
    if locked_by is None:
        conditions.append(AiTask.locked_by.is_(None))
    else:
        conditions.append(AiTask.locked_by == locked_by)
    values = {
        "state": state,
        "finished_at": datetime.utcnow(),
        "locked_by": None,
        "locked_until": None,
    }
    if error is not None:
        values["last_error"] = error
    result = db.execute(update(AiTask).where(*conditions).values(**values))
    if commit:
        db.commit()
    return bool(result.rowcount)


def list_tasks(db: Session, session_id: int) -> List[AiTask]:
    """会话下的任务列表（可观测性：接口 → 任务状态 → 会话状态三者可对账）。"""
    return (
        db.query(AiTask)
        .filter(AiTask.session_id == session_id)
        .order_by(AiTask.id.asc())
        .populate_existing()
        .all()
    )


def latest_task(db: Session, session_id: int, task_type: str) -> Optional[AiTask]:
    return (
        db.query(AiTask)
        .filter(AiTask.session_id == session_id, AiTask.task_type == task_type)
        .order_by(AiTask.id.desc())
        .populate_existing()
        .first()
    )
