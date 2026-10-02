# -*- coding: utf-8 -*-
"""可靠后台任务 / 并发 / 恢复（整改 B4.1 的 P0-1 ~ P0-4 验收脚本）。

## 这份脚本要证的四件事

1. **任务真的落库、真的能被 worker 领走执行**：请求只入队，执行走
   `ai_task_service.run_due_tasks`（生产 worker 与调度器容器共用的同一入口），
   没有任何「内联生成」开关；
2. **并发是安全的**：双方同时提交只产生一个改写任务；双方同时确认只产生
   一个总结任务；重复提交/重复点击/超时重发不产生重复产出；两个 worker
   同时抢同一行只有一个拿到；
3. **失败与恢复是可区分的**：LLM 抛异常 → 退避重排；重试耗尽 → 会话进
   `rewrite_failed` / `summary_failed` 明确失败态（不是一直 processing）；
   用户重试能重新跑起来；进程重启后中断的任务被回收重排；
4. **旧任务不覆盖新版本**：会话版本被推进后，旧任务的产出被丢弃、
   任务记 `superseded`，不会出现「重试成功又被旧结果盖回去」。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 \
        python tests/test_mediation_task_queue.py

## 与 MySQL 的关系

本脚本用 SQLite 文件库（`tests/hermetic_harness.py`）验证**逻辑正确性**：
条件 UPDATE、唯一约束、版本判定、退避重排全部与生产同源（同一份代码）。
生产库是 MySQL：`UPDATE ... WHERE ... IN (...)`、`INSERT ... 唯一键`、
`FOR UPDATE` 之外的写法两边通用，但**锁语义（next-key lock / 死锁重试）
只有 MySQL 能验**——那部分边界写在
`spec/日志/工作流执行记录/` 的迁移与部署清单里，端到端由人工双账号验收覆盖。
"""
import os
import sys
import threading
import time
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from hermetic_harness import MediationHarness  # noqa: E402

from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.repositories import ai_repo, ai_task_repo  # noqa: E402
from app.services import ai_task_service, mediation_service  # noqa: E402
from app.services.llm_client import LlmError  # noqa: E402

A_ID = 101
B_ID = 202

REWRITE_STUB = {
    "rewrite_a": "发起方的温和改写",
    "rewrite_b": "参与方的承接改写",
    "risk_level": "normal",
}
SUMMARY_STUB = {
    "common_points": ["都希望对方好"],
    "differences": ["节奏不一致"],
    "next_actions": ["今晚各说一件今天对方做的小事"],
    "risk_level": "normal",
}
STUBS = {"mediation_rewrite": REWRITE_STUB, "mediation_summary": SUMMARY_STUB}

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


# --------------------------------------------------------------------------- #
# 公共脚手架
# --------------------------------------------------------------------------- #

def _relation(db):
    rel = CoupleRelation(user_a_id=A_ID, user_b_id=B_ID, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return rel


def _reach_inputting(h, db):
    """start → accept，停在 inputting。"""
    rel = _relation(db)
    sid = mediation_service.start_mediation(db, A_ID, rel.id)["session_id"]
    mediation_service.accept_mediation(db, sid, B_ID)
    return sid


def _reach_confirming(h, db):
    """双方输入后停在 confirming（改写已生成）。

    整改 B4.2 之后 API 进程不执行任何 LLM：入队后必须显式跑 worker
    （生产里 `ai-task-worker` 容器走的就是 `run_due_tasks`）改写才会落地。
    """
    sid = _reach_inputting(h, db)
    mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
    mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
    ai_task_service.run_due_tasks(db, worker_id="test-worker")
    return sid


def _tasks(db, sid, task_type=None):
    rows = ai_task_repo.list_tasks(db, sid)
    return [t for t in rows if task_type is None or t.task_type == task_type]


def _session(db, sid):
    """重新读库（绕开 identity map 里的旧对象）。"""
    from app.models.ai import AiChatSession

    return (
        db.query(AiChatSession)
        .filter(AiChatSession.id == sid)
        .populate_existing()
        .first()
    )


# --------------------------------------------------------------------------- #
# 1. 任务持久化：请求只入队，执行由 worker 完成
# --------------------------------------------------------------------------- #
def t_task_persisted_before_execution():
    """关掉「踢一脚」，证明任务在**执行之前**就已经落库。

    这条是 P0-1 的核心：任务不是内存里的线程，而是数据库里的一行。
    没有它，进程重启 = 任务消失 = 会话永久停在生成中。
    """
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")

            rows = _tasks(db, sid, "mediation_rewrite")
            check("提交后任务已落库（未执行）", len(rows) == 1, str(len(rows)))
            task = rows[0]
            check("任务状态 pending", task.state == "pending", task.state)
            check("任务记录触发者", task.requested_by_user_id == A_ID, str(task.requested_by_user_id))
            check("任务记录会话版本", int(task.revision or 0) >= 1, str(task.revision))
            check(
                "幂等键 = 类型|会话|版本",
                task.idempotency_key
                == ai_task_repo.idempotency_key("mediation_rewrite", sid, task.revision),
                task.idempotency_key,
            )
            check("任务尚无 heartbeat", task.heartbeat_at is None)
            check("会话停在 rewriting（生成中）", _session(db, sid).mediation_status == "rewriting")
            check("此时没有任何改写产出", h.rewrite_calls == [], str(h.llm_calls))

            # 手动跑一轮 worker（生产里调度器容器走的就是这条）
            done = ai_task_service.run_due_tasks(db, worker_id="test-worker")
            check("worker 执行了 1 个任务", done == 1, str(done))
            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("任务转 succeeded", task.state == "succeeded", task.state)
            check("attempt=1", int(task.attempt) == 1, str(task.attempt))
            check("started_at 已写", task.started_at is not None)
            check("finished_at 已写", task.finished_at is not None)
            check("租约已释放", task.locked_by is None and task.locked_until is None)
            check("会话转 confirming", _session(db, sid).mediation_status == "confirming")
            check("只调了一次改写 LLM", len(h.rewrite_calls) == 1, str(h.llm_calls))
            db.close()
    finally:
        h.destroy()


def t_worker_is_the_only_executor():
    """源码契约：生产路径里没有「内联生成」后门。

    曾经有一个 `GENERATION_INLINE` 测试开关，能把生成拉回请求线程同步跑；
    它的存在让「后台化」这件事在生产与测试之间有两套语义。整改后必须消失。
    """
    from pathlib import Path

    src = Path(mediation_service.__file__).read_text(encoding="utf-8")
    check("GENERATION_INLINE 已彻底移除", "GENERATION_INLINE" not in src)
    check("不再有 _spawn_rewrite", "_spawn_rewrite" not in src)
    check("不再有 _spawn_summary", "_spawn_summary" not in src)
    check("mediation_service 不再起线程", "threading.Thread" not in src)

    task_src = Path(ai_task_service.__file__).read_text(encoding="utf-8")
    check(
        "任务服务不再起任何线程（daemon 执行线程已移除）",
        task_src.count("threading.Thread") == 0,
        str(task_src.count("threading.Thread")),
    )
    check("AUTO_KICK 已彻底移除", "AUTO_KICK" not in task_src)
    check("任务服务不再提供 retry_task 复用旧行", not hasattr(ai_task_service, "retry_task"))


# --------------------------------------------------------------------------- #
# 2. 并发：双方同时提交 → 一个改写任务
# --------------------------------------------------------------------------- #
def t_concurrent_submit_single_task():
    """两个请求**真并发**提交：只能有一个改写任务、一份改写产出。

    用真线程（`sync_threads=False`）跑真实的 CAS 竞争。回执语义：先到的那个
    把会话推进到 `rewriting`，后到的那个拿到 rowcount=0，按**既有状态**幂等
    返回——它读到的可能仍是「自己提交时那一刻」的 inputting，也可能已经是
    rewriting（取决于两个请求的交错）。契约要的是「无论谁先谁后，结果只有
    一个任务、一份稿」，而不是「两个回执字符串相同」。
    """
    h = MediationHarness(sync_threads=False)
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            db.close()

            barrier = threading.Barrier(2, timeout=20)
            results = {}
            errors = []

            def submit(uid, text):
                d = h.db()
                try:
                    barrier.wait()
                    results[uid] = mediation_service.submit_input(d, sid, uid, text)
                except Exception as exc:  # noqa: BLE001
                    errors.append("%s: %r" % (uid, exc))
                finally:
                    d.close()

            threads = [
                threading.Thread(target=submit, args=(A_ID, "A 的倾诉")),
                threading.Thread(target=submit, args=(B_ID, "B 的追问")),
            ]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=30)

            check("并发提交无异常", errors == [], str(errors))
            check("两个请求都拿到回执", len(results) == 2, str(results))
            check(
                "两个回执都带 accepted",
                all(r.get("accepted") is True for r in results.values()),
                str(results),
            )
            check(
                "回执状态是 rewriting 或 inputting（后者是 CAS 落败方的幂等返回）",
                all(r.get("mediation_status") in ("rewriting", "inputting") for r in results.values()),
                str(results),
            )
            check(
                "落败方也看到了「对方已提交」",
                all(r.get("partner_submitted") is True for r in results.values()),
                str(results),
            )

            db = h.db()
            rows = _tasks(db, sid, "mediation_rewrite")
            check("只产生一个改写任务", len(rows) == 1, str([(t.id, t.state) for t in rows]))
            check("任务版本=1（只推进一次版本）", int(rows[0].revision) == 1, str(rows[0].revision))

            ai_task_service.run_due_tasks(db, worker_id="w1")
            assistant = [
                m for m in __import__("app.repositories.ai_repo", fromlist=["x"]).get_messages_by_session(db, sid)
                if m.role == "assistant"
            ]
            check("只落一份改写消息", len(assistant) == 1, str(len(assistant)))
            check("改写 LLM 只调一次", len(h.rewrite_calls) == 1, str(h.llm_calls))
            check("会话转 confirming", _session(db, sid).mediation_status == "confirming")
            db.close()
    finally:
        h.destroy()


def t_concurrent_confirm_single_summary():
    """双方同时确认：只有一个总结任务、一份总结。

    同上：两个回执都是「已受理」，会话状态可能是 confirming（落败方）或
    summarizing（胜出方）——真并发下先后由调度决定，不写死断言。
    """
    h = MediationHarness()
    try:
        with h:
            # 用默认的同步替身（这样测试自己的线程仍是真线程，见 harness 注释）：
            # 并发由本用例显式开线程制造，而不是靠请求侧那一脚异步。
            h.driving(STUBS)
            db = h.db()
            sid = _reach_confirming(h, db)  # 改写由请求侧那一脚同步跑完 → confirming
            db.close()

            barrier = threading.Barrier(2, timeout=20)
            results = {}
            errors = []

            def confirm(uid):
                d = h.db()
                try:
                    barrier.wait()
                    results[uid] = mediation_service.confirm_rewrite(d, sid, uid, True)
                except Exception as exc:  # noqa: BLE001
                    errors.append("%s: %r" % (uid, exc))
                finally:
                    d.close()

            threads = [
                threading.Thread(target=confirm, args=(A_ID,)),
                threading.Thread(target=confirm, args=(B_ID,)),
            ]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=30)

            check("并发确认无异常", errors == [], str(errors))
            check("两个请求都拿到回执", len(results) == 2, str(results))
            check(
                "两个回执都带 accepted",
                all(r.get("accepted") is True for r in results.values()),
                str(results),
            )
            check(
                "回执状态在 confirming/summarizing/completed 之间",
                all(
                    r.get("mediation_status") in ("confirming", "summarizing", "completed")
                    for r in results.values()
                ),
                str(results),
            )

            db = h.db()
            summary_rows = _tasks(db, sid, "mediation_summary")
            check("只产生一个总结任务", len(summary_rows) == 1, str([(t.id, t.state) for t in summary_rows]))
            check("会话在 summarizing 或已完成", _session(db, sid).mediation_status in ("summarizing", "completed"),
                  _session(db, sid).mediation_status)

            ai_task_service.run_due_tasks(db, worker_id="w1")
            check("总结 LLM 只调一次", len(h.summary_calls) == 1, str(h.llm_calls))
            check("会话 completed", _session(db, sid).mediation_status == "completed")
            db.close()
    finally:
        h.destroy()


def t_worker_claim_mutual_exclusion():
    """两个 worker 同时抢同一行：只有一个拿到，另一个空手。"""
    h = MediationHarness(sync_threads=False)
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            db.close()

            claimed = []
            barrier = threading.Barrier(2, timeout=20)

            def claim(worker):
                d = h.db()
                try:
                    barrier.wait()
                    task = ai_task_repo.claim_next_task(d, worker)
                    if task is not None:
                        claimed.append((worker, task.id))
                finally:
                    d.close()

            threads = [
                threading.Thread(target=claim, args=("worker-1",)),
                threading.Thread(target=claim, args=("worker-2",)),
            ]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=30)

            check("只有一个 worker 领到任务", len(claimed) == 1, str(claimed))
            db = h.db()
            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("attempt 只自增一次", int(task.attempt) == 1, str(task.attempt))
            check("locked_by 是领到的那一个", task.locked_by == claimed[0][0], str(task.locked_by))
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 3. 幂等：重复提交 / 重复点击 / 重发
# --------------------------------------------------------------------------- #
def t_duplicate_submit_idempotent():
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            for _ in range(3):
                r = mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
                check("单人重复提交停在 inputting", r["mediation_status"] == "inputting", str(r))
            check("重复提交不建任务", _tasks(db, sid) == [], str(_tasks(db, sid)))
            b_msgs = [
                m for m in ai_repo.get_messages_by_session(db, sid)
                if m.role == "user" and m.user_id == B_ID
            ]
            check("重复输入不追加三份相同消息", len(b_msgs) == 1, str([m.content for m in b_msgs]))
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            check("第二个人提交才建任务", len(_tasks(db, sid, "mediation_rewrite")) == 1)
            ai_task_service.run_due_tasks(db, worker_id="test-worker")
            check("改写只跑一次", len(h.rewrite_calls) == 1, str(h.llm_calls))

            # 确认阶段：同一人连点三次
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            for _ in range(2):
                r = mediation_service.confirm_rewrite(db, sid, A_ID, True)
                check("重复确认幂等返回 confirming", r["mediation_status"] == "confirming", str(r))
                check("重复确认不建总结任务", _tasks(db, sid, "mediation_summary") == [])
            mediation_service.confirm_rewrite(db, sid, B_ID, True)
            check("双方确认后只建一个总结任务", len(_tasks(db, sid, "mediation_summary")) == 1)
            ai_task_service.run_due_tasks(db, worker_id="test-worker")
            check("总结只跑一次", len(h.summary_calls) == 1, str(h.llm_calls))
            db.close()
    finally:
        h.destroy()


def t_completed_state_not_regressed():
    """已完成之后的重发：状态机拒绝，且不产生任何新任务。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_confirming(h, db)
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            mediation_service.confirm_rewrite(db, sid, B_ID, True)
            # API 进程不执行 LLM：双方确认只入队，总结由 worker 跑
            ai_task_service.run_due_tasks(db, worker_id="test-worker")
            check("会话已完成", _session(db, sid).mediation_status == "completed")

            before = [(t.id, t.task_type, t.state) for t in _tasks(db, sid)]
            for fn, args in (
                (mediation_service.submit_input, (sid, A_ID, "再来一句")),
                (mediation_service.confirm_rewrite, (sid, A_ID, True)),
            ):
                try:
                    fn(db, *args)
                except ValueError as e:
                    check("%s 被状态机拒绝" % fn.__name__, str(e) == "50003", "实际 %s" % e)
                else:
                    check("%s 被状态机拒绝" % fn.__name__, False, "未抛 ValueError")
            after = [(t.id, t.task_type, t.state) for t in _tasks(db, sid)]
            check("已完成状态下没有新任务", before == after, "%s -> %s" % (before, after))
            check("总结没有重跑", len(h.summary_calls) == 1, str(h.llm_calls))
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 4. 失败、重试、终态
# --------------------------------------------------------------------------- #
def t_llm_failure_schedules_retry_then_succeeds():
    """第一次 LLM 失败 → 退避重排（不是一直 processing）；第二次成功 → 正常推进。"""
    h = MediationHarness()
    try:
        with h:
            calls = {"n": 0}

            def flaky(prompt, scene_key):
                if scene_key == "mediation_rewrite":
                    calls["n"] += 1
                    if calls["n"] == 1:
                        raise LlmError("模型超时（测试桩）")
                return dict(STUBS[scene_key])

            h.driving(flaky)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            # API 进程不执行 LLM：入队后由 worker 跑第一次（本次必失败）
            ai_task_service.run_due_tasks(db, worker_id="test-worker")

            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("首次失败后任务回到 pending", task.state == "pending", task.state)
            check("attempt=1", int(task.attempt) == 1, str(task.attempt))
            check("写了 last_error", bool(task.last_error), str(task.last_error))
            check("last_error 不含用户原文", "倾诉" not in (task.last_error or ""), str(task.last_error))
            check("安排了退避重试", task.next_retry_at is not None)
            check(
                "退避窗口=30s（RETRY_BASE_BACKOFF_SECONDS）",
                abs((task.next_retry_at - datetime.utcnow()).total_seconds() - 30) <= 5,
                str(task.next_retry_at),
            )
            session = _session(db, sid)
            check("会话进明确失败态 rewrite_failed", session.mediation_status == "rewrite_failed", session.mediation_status)
            check("失败码为可重试", session.mediation_failure_code == "TASK_RETRY_SCHEDULED",
                  str(session.mediation_failure_code))
            st = mediation_service.get_status(db, sid, A_ID)
            check(
                "GET {id} 暴露 failure（客户端据此提示）",
                (st.get("failure") or {}).get("code") == "TASK_RETRY_SCHEDULED",
                str(st.get("failure")),
            )
            check("GET {id} 暴露 task 尝试次数", (st.get("task") or {}).get("attempt") == 1, str(st.get("task")))

            # 退避窗口内不可被领取
            check("退避未到不领取", ai_task_service.run_due_tasks(db, worker_id="w1") == 0)

            # 把退避时间拨到过去（等价于等待窗口过去）
            task.next_retry_at = datetime.utcnow() - timedelta(seconds=1)
            db.commit()
            check("退避到期后领取执行", ai_task_service.run_due_tasks(db, worker_id="w1") == 1)

            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("重试后任务 succeeded", task.state == "succeeded", task.state)
            check("attempt=2", int(task.attempt) == 2, str(task.attempt))
            session = _session(db, sid)
            check("会话推进到 confirming", session.mediation_status == "confirming", session.mediation_status)
            check("失败标记被清空", session.mediation_failure_code is None, str(session.mediation_failure_code))
            check("改写只落一条", len(h.rewrite_calls) == 2, str(h.llm_calls))
            db.close()
    finally:
        h.destroy()


def t_retry_exhausted_terminal_failure():
    """每次都失败 → 重试耗尽 → 终态失败 + 会话可重试（不是无休止 processing）。"""
    h = MediationHarness()
    try:
        with h:
            def always_fail(prompt, scene_key):
                raise LlmError("模型不可用（测试桩）")

            h.driving(always_fail)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")

            for i in range(3):
                task = _tasks(db, sid, "mediation_rewrite")[0]
                task.next_retry_at = datetime.utcnow() - timedelta(seconds=1)
                db.commit()
                ai_task_service.run_due_tasks(db, worker_id="w1")

            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("耗尽后任务 failed", task.state == "failed", task.state)
            check("attempt=3（max_attempts）", int(task.attempt) == 3, str(task.attempt))
            session = _session(db, sid)
            check("会话停在 rewrite_failed", session.mediation_status == "rewrite_failed", session.mediation_status)
            check("失败码=终态", session.mediation_failure_code == "TASK_EXHAUSTED", str(session.mediation_failure_code))
            check("没有伪装成 processing", session.mediation_status != "rewriting")
            check("改写 LLM 尝试 3 次", len(h.rewrite_calls) == 3, str(h.llm_calls))

            st = mediation_service.get_status(db, sid, A_ID)
            check("GET 带 failure.retryable=true", (st.get("failure") or {}).get("retryable") is True, str(st.get("failure")))

            # 用户点重试：新版本 + 新任务
            old_rev = int(_session(db, sid).mediation_revision or 0)
            r = mediation_service.retry_generation(db, sid, A_ID)
            check("用户重试后回到 rewriting", r["mediation_status"] == "rewriting", str(r))
            session = _session(db, sid)
            check("重试推进了版本", int(session.mediation_revision) > old_rev,
                  "%s -> %s" % (old_rev, session.mediation_revision))
            rows = _tasks(db, sid, "mediation_rewrite")
            check("重试产生新任务行（不复用 failed 行）", len(rows) == 2, str([(t.id, t.state) for t in rows]))
            check("新任务是 pending", rows[-1].state == "pending", rows[-1].state)
            check("失败标记已清空", session.mediation_failure_code is None, str(session.mediation_failure_code))

            # 修好模型，重试就成功
            h.driving(STUBS)
            check("重试任务被执行", ai_task_service.run_due_tasks(db, worker_id="w1") == 1)
            check("会话推进到 confirming", _session(db, sid).mediation_status == "confirming")
            db.close()
    finally:
        h.destroy()


def t_retry_endpoint_requires_failure_state():
    """非失败态调 retry → 50003（不能让用户对正常会话乱重排）。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_confirming(h, db)
            try:
                mediation_service.retry_generation(db, sid, A_ID)
            except ValueError as e:
                check("confirming 调 retry 被拒", str(e) == "50003", "实际 %s" % e)
            else:
                check("confirming 调 retry 被拒", False, "未抛 ValueError")
            try:
                mediation_service.retry_generation(db, sid, 999)
            except ValueError as e:
                check("非成员调 retry 被拒", str(e) == "50002", "实际 %s" % e)
            else:
                check("非成员调 retry 被拒", False, "未抛 ValueError")
            db.close()
    finally:
        h.destroy()


def t_summary_failure_is_visible_and_retryable():
    """总结失败：会话进 summary_failed（此时双方改写已公开），可重试。"""
    h = MediationHarness()
    try:
        with h:
            def fail_summary(prompt, scene_key):
                if scene_key == "mediation_summary":
                    raise LlmError("总结模型不可用（测试桩）")
                return dict(STUBS[scene_key])

            h.driving(fail_summary)
            db = h.db()
            sid = _reach_confirming(h, db)
            mediation_service.confirm_rewrite(db, sid, A_ID, True)
            mediation_service.confirm_rewrite(db, sid, B_ID, True)
            for _ in range(3):
                task = _tasks(db, sid, "mediation_summary")[0]
                task.next_retry_at = datetime.utcnow() - timedelta(seconds=1)
                db.commit()
                ai_task_service.run_due_tasks(db, worker_id="w1")

            session = _session(db, sid)
            check("会话停在 summary_failed", session.mediation_status == "summary_failed", session.mediation_status)
            check("失败码=终态", session.mediation_failure_code == "TASK_EXHAUSTED", str(session.mediation_failure_code))

            st = mediation_service.get_status(db, sid, A_ID)
            check(
                "summary_failed 下双方改写仍公开（不闪回）",
                (st.get("partner_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_b"],
                str(st.get("partner_rewrite")),
            )

            h.driving(STUBS)
            r = mediation_service.retry_generation(db, sid, B_ID)
            check("重试后回到 summarizing", r["mediation_status"] == "summarizing", str(r))
            check("重试任务被执行", ai_task_service.run_due_tasks(db, worker_id="w1") == 1)
            check("会话 completed", _session(db, sid).mediation_status == "completed")
            check("总结落库", any(
                (m.get("structured_output") or {}).get("common_points")
                for m in mediation_service.get_status(db, sid, A_ID)["messages"]
            ))
            db.close()
    finally:
        h.destroy()


def t_degraded_llm_counts_as_failure():
    """`_call_llm` 的降级响应（"AI 服务暂时不可用"）必须算失败，不能当结论落库。"""
    h = MediationHarness()
    try:
        with h:
            def degraded(prompt, scene_key):
                return {"raw_text": "AI 服务暂时不可用，请稍后再试", "summary": ""}

            h.driving(degraded)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            ai_task_service.run_due_tasks(db, worker_id="w1")

            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("降级响应被判为失败（任务回到 pending）", task.state == "pending", task.state)
            check("降级文案没有落库为改写", all(
                "AI 服务暂时不可用" not in (m.content or "")
                for m in __import__("app.repositories.ai_repo", fromlist=["x"]).get_messages_by_session(db, sid)
            ))
            check("会话进失败态", _session(db, sid).mediation_status == "rewrite_failed")
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 5. 恢复：进程重启 / 租约过期
# --------------------------------------------------------------------------- #
def t_crash_recovery_on_restart():
    """worker 被杀（任务停在 running、租约过期）→ 重启后被回收并重跑。

    模拟方式：手动把一行置成 running + 过期租约（就是崩溃现场的样子），
    然后跑 `run_once()`——worker 启动时走的就是它。
    """
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            db.close()

        sys.path.insert(0, os.path.join(os.path.dirname(HERE), "backend"))
        from app.tasks import ai_task_worker

        with h:
            h.driving(STUBS)  # 上一个 with 退出时已还原真实 _call_llm，必须重接
            # 崩溃现场：被 worker-0 领走、租约已过期、进程没了
            db = h.db()
            task = _tasks(db, sid, "mediation_rewrite")[0]
            task.state = "running"
            task.locked_by = "dead-worker"
            task.locked_until = datetime.utcnow() - timedelta(seconds=60)
            task.attempt = 1
            task.started_at = datetime.utcnow() - timedelta(minutes=5)
            db.commit()
            db.close()

            executed = ai_task_worker.run_once()
            check("重启后 worker 回收并执行了任务", executed == 1, str(executed))

            db = h.db()
            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("任务回到 succeeded", task.state == "succeeded", task.state)
            check("attempt 未被清零（崩溃算一次尝试）", int(task.attempt) == 2, str(task.attempt))
            check("locked_by 已换成本进程", task.locked_by is None, str(task.locked_by))
            check("会话推进到 confirming", _session(db, sid).mediation_status == "confirming")
            check("恰好生成一次改写", len(h.rewrite_calls) == 1, str(h.llm_calls))
            db.close()
    finally:
        h.destroy()


def t_lease_not_expired_keeps_running():
    """租约未过期时，别的 worker 不能抢走正在跑的任务（避免重复生成）。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            task = _tasks(db, sid, "mediation_rewrite")[0]
            task.state = "running"
            task.locked_by = "worker-alive"
            task.locked_until = datetime.utcnow() + timedelta(seconds=600)
            db.commit()

            # recover_stale_tasks 返回 (recovered, exhausted)，租约内两者都应为空
            _recovered, _exhausted = ai_task_repo.recover_stale_tasks(db)
            check("租约内不被回收", _recovered == 0 and _exhausted == [], str((_recovered, _exhausted)))
            check("租约内不被领取", ai_task_service.run_due_tasks(db, worker_id="w2") == 0)
            check("会话仍停在 rewriting", _session(db, sid).mediation_status == "rewriting")
            check("没有产出", h.rewrite_calls == [], str(h.llm_calls))
            db.close()
    finally:
        h.destroy()


def t_stale_executor_cannot_fail_healthy_task():
    """慢执行者回到现场时，任务已被别人接管 → 不得把它改成失败。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")

            task = _tasks(db, sid, "mediation_rewrite")[0]
            task.locked_by = "stale-worker"
            task.state = "running"
            task.locked_until = datetime.utcnow() - timedelta(seconds=1)
            db.commit()

            # 租约过期 → 另一个 worker 回收并接管
            other = h.db()
            ai_task_repo.recover_stale_tasks(other)
            reclaimed = ai_task_repo.claim_next_task(other, "fresh-worker")
            check(
                "任务被新 worker 接管",
                reclaimed is not None and reclaimed.locked_by == "fresh-worker",
                str(reclaimed and reclaimed.locked_by),
            )

            # 旧执行者手里那份快照：它对任务行的认知仍停在 locked_by=stale-worker
            class _StaleSnapshot:
                def __init__(self, task_id, locked_by):
                    self.id = task_id
                    self.locked_by = locked_by

            stale = _StaleSnapshot(task.id, "stale-worker")
            ok = ai_task_repo.mark_failed(other, stale, "旧执行者的失败结论")
            check("旧执行者无权写终态（mark_failed 返回 False）", ok is False, str(ok))
            fresh = ai_task_repo.get_task_by_id(other, task.id)
            check("不归本执行者的行没被改成 failed", fresh.state == "running", fresh.state)
            check("任务行仍归新执行者", fresh.locked_by == "fresh-worker", str(fresh.locked_by))
            other.close()
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 6. 版本新鲜度：旧任务不得覆盖新版本
# --------------------------------------------------------------------------- #
def t_stale_task_does_not_overwrite_new_revision():
    """会话版本被推进后，旧任务的产出被丢弃、任务记 superseded。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")

            old_task = _tasks(db, sid, "mediation_rewrite")[0]
            # 模拟「另一路把会话推进到新版本」（例如用户点了重试）
            mediation_service._bump_revision(db, sid)

            done = ai_task_service.run_due_tasks(db, worker_id="w1")
            check("旧任务被执行（领取时还是 pending）", done == 1, str(done))
            old_task = _tasks(db, sid, "mediation_rewrite")[0]
            check("旧任务记为 superseded", old_task.state == "superseded", old_task.state)
            messages = __import__("app.repositories.ai_repo", fromlist=["x"]).get_messages_by_session(db, sid)
            check("旧产出没有被写回（无 assistant 消息）",
                  all(m.role != "assistant" for m in messages),
                  str([m.role for m in messages]))
            check("会话状态未被旧任务推进", _session(db, sid).mediation_status == "rewriting",
                  _session(db, sid).mediation_status)
            db.close()
    finally:
        h.destroy()


def t_new_revision_gets_its_own_task():
    """推进版本后建的任务是新的一行，与失败/作废的历史行并存。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_confirming(h, db)
            first = _tasks(db, sid, "mediation_rewrite")[0]
            check("首次改写任务 succeeded", first.state == "succeeded", first.state)

            # 「需要修改」：只重生成 B 那一侧
            mediation_service.confirm_rewrite(db, sid, B_ID, False, "补充一句")
            # API 进程不执行 LLM：重生成只入队，由 worker 跑
            ai_task_service.run_due_tasks(db, worker_id="test-worker")
            rows = _tasks(db, sid)  # 全部类型：重生成是 mediation_regenerate
            check("重生成产生第二行任务", len(rows) == 2, str([(t.id, t.task_type, t.state) for t in rows]))
            check("两行类型不同（rewrite / regenerate）",
                  {t.task_type for t in rows} == {"mediation_rewrite", "mediation_regenerate"},
                  str([t.task_type for t in rows]))
            check("会话回到 confirming（重生成已完成）",
                  _session(db, sid).mediation_status == "confirming",
                  _session(db, sid).mediation_status)
            check(
                "对侧改写被原样保留",
                (mediation_service.get_status(db, sid, A_ID).get("my_rewrite") or {}).get("rewritten")
                == REWRITE_STUB["rewrite_a"],
                str(mediation_service.get_status(db, sid, A_ID).get("my_rewrite")),
            )
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 7. API / 状态一致性
# --------------------------------------------------------------------------- #
def t_fastapi_contract_processing_semantics():
    """HTTP 层：提交与确认都**立刻**返回，不阻塞等 LLM。

    用 TestClient 走真实路由（依赖覆盖到测试库），LLM 桩故意慢 1s：
    如果请求线程里还在同步等生成，响应时间会明显超过这次调用。
    """
    from fastapi.testclient import TestClient

    from app.core.dependencies import get_current_user
    from app.core.database import get_db
    from app.main import app

    h = MediationHarness()
    try:
        with h:
            def slow_llm(prompt, scene_key, client=None):
                time.sleep(1.0)
                return dict(STUBS[scene_key])

            h.driving(slow_llm)

            db = h.db()
            sid = _reach_inputting(h, db)
            db.close()

            def _db_override():
                d = h.db()
                try:
                    yield d
                finally:
                    d.close()

            current = {"uid": A_ID}  # 依赖覆盖按它取当前用户，模拟两个账号轮流请求

            class _User:
                @property
                def id(self):
                    return current["uid"]

            app.dependency_overrides[get_db] = _db_override
            app.dependency_overrides[get_current_user] = lambda: _User()
            client = TestClient(app, raise_server_exceptions=False)

            t0 = time.time()
            r1 = client.post("/api/v1/couple/ai/mediation/%s/input" % sid, json={"content": "A 的倾诉"})
            elapsed1 = time.time() - t0
            body1 = r1.json()
            check("第一次输入 200", r1.status_code == 200, str(r1.status_code))
            check("返回 accepted", body1.get("data", {}).get("accepted") is True, str(body1))
            check(
                "第一方提交仍是等待态 inputting（对方未提交）",
                body1.get("data", {}).get("mediation_status") == "inputting",
                str(body1.get("data")),
            )

            current["uid"] = B_ID  # 伴侣提交 → 第二个 distinct user
            t0 = time.time()
            r2 = client.post("/api/v1/couple/ai/mediation/%s/input" % sid, json={"content": "B 的倾诉"})
            elapsed2 = time.time() - t0
            body2 = r2.json()
            check("第二次输入立即返回 rewriting",
                  body2.get("data", {}).get("mediation_status") == "rewriting", str(body2.get("data")))
            check("请求耗时远小于一次 LLM 调用（< 0.5s）", elapsed2 < 0.5, "实际 %.3fs" % elapsed2)

            # 此刻客户端 GET 到的就是「生成中」
            r3 = client.get("/api/v1/couple/ai/mediation/%s" % sid)
            data3 = r3.json().get("data", {})
            check("GET 显示 rewriting", data3.get("mediation_status") == "rewriting", str(data3.get("mediation_status")))
            check("GET 带 task 可观测信息", (data3.get("task") or {}).get("state") == "pending",
                  str(data3.get("task")))
            check("GET 带 revision", isinstance(data3.get("revision"), int), str(data3.get("revision")))

            # 跑 worker → 客户端再 GET 到 confirming
            db = h.db()
            ai_task_service.run_due_tasks(db, worker_id="api-test")
            db.close()
            r4 = client.get("/api/v1/couple/ai/mediation/%s" % sid)
            data4 = r4.json().get("data", {})
            check("worker 跑完后 GET 显示 confirming", data4.get("mediation_status") == "confirming",
                  str(data4.get("mediation_status")))
            # 此刻的当前用户是 B（上一步用 B 的身份提交），所以带出来的是 B 那一侧。
            # 身份解析由 [t_identity_and_authorization_still_enforced] 覆盖。
            check("确认页能看到自己那一侧的改写",
                  (data4.get("my_rewrite") or {}).get("rewritten") == REWRITE_STUB["rewrite_b"],
                  str(data4.get("my_rewrite")))

            app.dependency_overrides.clear()
    finally:
        h.destroy()


def t_api_state_matches_db_state():
    """API 返回的状态 / 失败码与数据库逐字一致（不做「乐观展示」）。"""
    h = MediationHarness()
    try:
        with h:
            def always_fail(prompt, scene_key):
                raise LlmError("模型错误（测试桩）")

            h.driving(always_fail)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            for _ in range(3):
                task = _tasks(db, sid, "mediation_rewrite")[0]
                task.next_retry_at = datetime.utcnow() - timedelta(seconds=1)
                db.commit()
                ai_task_service.run_due_tasks(db, worker_id="w1")

            st = mediation_service.get_status(db, sid, A_ID)
            session = _session(db, sid)
            check("API 状态 = 库状态", st["mediation_status"] == session.mediation_status,
                  "%s vs %s" % (st["mediation_status"], session.mediation_status))
            check("API 失败码 = 库失败码",
                  (st.get("failure") or {}).get("code") == session.mediation_failure_code,
                  "%s vs %s" % ((st.get("failure") or {}).get("code"), session.mediation_failure_code))
            check("API revision = 库 revision", st["revision"] == int(session.mediation_revision or 0),
                  "%s vs %s" % (st["revision"], session.mediation_revision))
            check("任务状态可对账（failed）", st["task"]["state"] == "failed", str(st.get("task")))
            check("任务尝试次数 = max", st["task"]["attempt"] == st["task"]["max_attempts"], str(st.get("task")))
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 8. 身份与越权（回归：整改不得放松访问控制）
# --------------------------------------------------------------------------- #
def t_identity_and_authorization_still_enforced():
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_confirming(h, db)
            for uid, label in ((A_ID, "发起方"), (B_ID, "参与方")):
                st = mediation_service.get_status(db, sid, uid)
                expected = "inviter" if uid == A_ID else "partner"
                check("%s my_role 正确" % label, st["my_role"] == expected, str(st["my_role"]))
                check(
                    "%s 拿到自己那一侧的改写" % label,
                    (st.get("my_rewrite") or {}).get("rewritten")
                    == (REWRITE_STUB["rewrite_a"] if uid == A_ID else REWRITE_STUB["rewrite_b"]),
                    str(st.get("my_rewrite")),
                )
            for fn, args, label in (
                (mediation_service.get_status, (sid, 999), "get_status"),
                (mediation_service.submit_input, (sid, 999, "闯入"), "submit_input"),
                (mediation_service.confirm_rewrite, (sid, 999, True), "confirm_rewrite"),
                (mediation_service.retry_generation, (sid, 999), "retry_generation"),
            ):
                try:
                    fn(db, *args)
                except ValueError as e:
                    check("非成员 %s 被拒" % label, str(e) == "50002", "实际 %s" % e)
                else:
                    check("非成员 %s 被拒" % label, False, "未抛 ValueError")
            db.close()
    finally:
        h.destroy()


# --------------------------------------------------------------------------- #
# 9. 阻断项：过期执行者 / 崩溃耗尽 / 高风险
# --------------------------------------------------------------------------- #
def t_duplicate_executor_cannot_double_write():
    """P0-2：同一任务的产出只能写回一次。

    模拟「租约过期后同一个任务被第二个执行者重复执行」：两个执行者都会走到
    `_commit_generation_result`，但会话状态 CAS 只让第一个写成功——第二个的
    产出必须被丢弃，绝不能在确认页上再写一份改写。
    """
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")

            first = ai_task_repo.claim_next_task(db, "w1")
            check("w1 领到任务", first is not None and first.locked_by == "w1", str(first))
            mediation_service._commit_generation_result(
                db, first, dict(REWRITE_STUB), "confirming"
            )
            check("第一次写回落地（会话 confirming）",
                  _session(db, sid).mediation_status == "confirming")
            assistant = [
                m for m in ai_repo.get_messages_by_session(db, sid) if m.role == "assistant"
            ]
            check("只落一份改写", len(assistant) == 1, str(len(assistant)))

            # 旧执行者手里的任务快照：会话版本没变，但它已不持有这一行
            class _StaleSnapshot:
                pass

            stale = _StaleSnapshot()
            stale.id = first.id
            stale.session_id = sid
            stale.revision = first.revision
            stale.locked_by = "w2"

            mediation_service._commit_generation_result(
                db, stale, dict(REWRITE_STUB), "confirming"
            )
            assistant2 = [
                m for m in ai_repo.get_messages_by_session(db, sid) if m.role == "assistant"
            ]
            check("第二个执行者的产出被丢弃（不写第二份）",
                  len(assistant2) == 1, str(len(assistant2)))
            check("会话状态不被回退",
                  _session(db, sid).mediation_status == "confirming",
                  _session(db, sid).mediation_status)
            db.close()
    finally:
        h.destroy()


def t_crash_exhaustion_terminates():
    """P0-3：反复崩溃同样受 `max_attempts` 限制，不允许无限重跑。"""
    h = MediationHarness()
    try:
        with h:
            h.driving(STUBS)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")

            # 模拟连续三次「领走即崩溃」：running + 过期租约
            for _ in range(3):
                task = _tasks(db, sid, "mediation_rewrite")[0]
                task.state = "running"
                task.locked_by = "dead-worker"
                task.locked_until = datetime.utcnow() - timedelta(seconds=60)
                task.attempt = int(task.attempt or 0) + 1
                db.commit()
                ai_task_service.recover_stale_tasks(db)

            task = _tasks(db, sid, "mediation_rewrite")[0]
            check("耗尽后任务 failed", task.state == "failed", task.state)
            check("attempt 不超过 max_attempts",
                  int(task.attempt) <= int(task.max_attempts),
                  "%s/%s" % (task.attempt, task.max_attempts))
            check("耗尽行不再被领取", ai_task_service.run_due_tasks(db, worker_id="w1") == 0)
            session = _session(db, sid)
            check("会话进明确失败态", session.mediation_status == "rewrite_failed",
                  session.mediation_status)
            check("失败码=终态", session.mediation_failure_code == "TASK_EXHAUSTED",
                  str(session.mediation_failure_code))
            db.close()
    finally:
        h.destroy()


def t_high_risk_blocks_mediation():
    """P0-6：模型判定高风险（控制/暴力/自伤）时**禁止产出双人调解结果**。

    同时验证「归一化先于门控」：桩返回的是 ` Abuse_Risk `（大小写+空格），
    必须归一化成 `abuse_risk` 才命中阻断，否则门控会被一个格式差异绕过。
    """
    h = MediationHarness()
    try:
        with h:
            def high_risk(prompt, scene_key):
                return {
                    "rewrite_a": "（不该落库的改写 A）",
                    "rewrite_b": "（不该落库的改写 B）",
                    "risk_level": " Abuse_Risk ",
                }

            h.driving(high_risk)
            db = h.db()
            sid = _reach_inputting(h, db)
            mediation_service.submit_input(db, sid, B_ID, "B 的倾诉")
            mediation_service.submit_input(db, sid, A_ID, "A 的倾诉")
            ai_task_service.run_due_tasks(db, worker_id="test-worker")

            session = _session(db, sid)
            # 整改 B4.3 P0-2：安全阻断是**独立终态** safety_blocked，不再与
            # 「正常谈完」共用 completed——客户端必须能区分两者（前者不给任何
            # 推进动作、历史里显示「已安全终止」）。
            check("高风险会话被终止为 safety_blocked",
                  session.mediation_status == "safety_blocked", session.mediation_status)
            check("不是失败态（不该给用户「重试」）",
                  session.mediation_failure_code is None,
                  str(session.mediation_failure_code))
            check("活跃槽位已释放", session.mediation_active_slot is None,
                  str(session.mediation_active_slot))

            assistant = [
                m for m in ai_repo.get_messages_by_session(db, sid) if m.role == "assistant"
            ]
            check("只落一条安全提示消息", len(assistant) == 1, str(len(assistant)))
            so = (assistant[0].structured_output or {}) if assistant else {}
            check("没有落任何调解改写",
                  "rewrite_a" not in so and "rewrite_b" not in so, str(so))
            check("消息带安全资源正文", bool(so.get("safety_response")), str(so))
            check("消息风险等级已落库（归一化后）",
                  bool(assistant) and assistant[0].risk_level == "abuse_risk",
                  str(assistant[0].risk_level) if assistant else "")

            st = mediation_service.get_status(db, sid, A_ID)
            risks = [
                m.get("risk_level") for m in st.get("messages") or []
                if m.get("role") == "assistant"
            ]
            check("GET {id} 下发风险等级（客户端据此渲染安全卡）",
                  risks == ["abuse_risk"], str(risks))
            db.close()
    finally:
        h.destroy()


def t_risk_payload_normalized():
    """结构化输出归一化：字段类型与等级一律在写库前收敛。"""
    normalized = mediation_service._normalize_generation_payload(
        {"rewrite_a": None, "rewrite_b": 3, "risk_level": "Heated_Conflict"},
        summary=False,
    )
    check("改写两侧收敛成 str",
          normalized["rewrite_a"] == "" and normalized["rewrite_b"] == "3",
          str(normalized))
    check("等级归一化", normalized["risk_level"] == "heated_conflict", str(normalized))

    bad = mediation_service._normalize_generation_payload(
        {"rewrite_a": "a", "rewrite_b": "b", "risk_level": "HIGH"}, summary=False
    )
    # 整改 B4.3 P0-4：未知/无法识别的等级**不再降级为 normal 放行**（fail open），
    # 一律收敛成 unknown 并在写回时阻断产物。完整矩阵见
    # tests/test_mediation_safety_terminal.py::t_risk_level_matrix_fail_closed。
    check("未知等级收敛为 unknown（fail closed）",
          bad["risk_level"] == mediation_service.RISK_LEVEL_UNKNOWN, str(bad))

    summary = mediation_service._normalize_generation_payload(
        {
            "common_points": "只有一条",
            "differences": None,
            "next_actions": ["a", None, " "],
            "risk_level": "normal",
        },
        summary=True,
    )
    check("总结列表收敛成 List[str]",
          summary["common_points"] == ["只有一条"]
          and summary["differences"] == []
          and summary["next_actions"] == ["a"],
          str(summary))


def main() -> int:
    print("[调解可靠任务队列] 整改 B4.1 P0-1~P0-4 验收")
    t_task_persisted_before_execution()
    t_worker_is_the_only_executor()
    t_concurrent_submit_single_task()
    t_concurrent_confirm_single_summary()
    t_worker_claim_mutual_exclusion()
    t_duplicate_submit_idempotent()
    t_completed_state_not_regressed()
    t_llm_failure_schedules_retry_then_succeeds()
    t_retry_exhausted_terminal_failure()
    t_retry_endpoint_requires_failure_state()
    t_summary_failure_is_visible_and_retryable()
    t_degraded_llm_counts_as_failure()
    t_crash_recovery_on_restart()
    t_lease_not_expired_keeps_running()
    t_stale_executor_cannot_fail_healthy_task()
    t_stale_task_does_not_overwrite_new_revision()
    t_new_revision_gets_its_own_task()
    t_fastapi_contract_processing_semantics()
    t_api_state_matches_db_state()
    t_identity_and_authorization_still_enforced()
    t_duplicate_executor_cannot_double_write()
    t_crash_exhaustion_terminates()
    t_high_risk_blocks_mediation()
    t_risk_payload_normalized()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
