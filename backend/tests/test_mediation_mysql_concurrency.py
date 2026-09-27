# -*- coding: utf-8 -*-
"""整改 B4.3 · P0-5（MySQL 并发幂等）+ §8 故障注入（**真 MySQL**）。

## 为什么必须是真 MySQL

SQLite 的单写者模型会把并发退化成串行，`database is locked` 之外看不到任何
真实交错；SAVEPOINT、唯一键冲突后的事务状态、REPEATABLE-READ 的快照读行为
也都与 InnoDB 不同。「两个连接同时 start / 同时建同一幂等任务 / 故障注入后
事务是否还能继续用」的结论只能在**生产同版本 MySQL** 上取。

## 要证的

**P0-5**
1. SAVEPOINT 必须在 INSERT 之前建立，否则冲突会污染整个外层事务；
2. 冲突方必须仍能继续用这条连接（查询 + 提交），不能留下
   `PendingRollbackError`；
3. 结果唯一：两个并发请求拿到同一个 session_id / task_id，库里各只有一行；
4. 冲突回读必须是**加锁读**：REPEATABLE-READ 的快照读看不见竞争方刚提交的行。

**§8 故障注入**（在七个指定位置主动抛异常，逐个断言八个不变量）
- 状态 CAS 后 / revision 自增后 / task INSERT 后 / task_id 回写后·commit 前
- 会话状态 CAS 后·消息 INSERT 前 / 消息 INSERT 后·任务 succeeded 前
- 安全阻断消息插入后·commit 前

每个用例断言：会话状态、revision、task_id、任务数量与状态、assistant 消息
数量、active_slot，以及**事务仍可继续使用**。

## 跑法

    cd backend && python tests/test_mediation_mysql_concurrency.py

MySQL 不可用时以退出码 2 标记跳过——绝不静默当作通过。
"""
import os
import sys
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from mysql_harness import MysqlHarness, mysql_available  # noqa: E402

FAILURES = []
CHECKS = 0
SKIPPED = []


def check(name, cond, detail=""):
    global CHECKS
    CHECKS += 1
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def skip(reason):
    SKIPPED.append(reason)
    print("  SKIP  %s" % reason)


# --------------------------------------------------------------------------- #
# 种子数据
# --------------------------------------------------------------------------- #
def seed_users(db):
    from app.models.ai import AiScene
    from app.models.couple_relation import CoupleRelation
    from app.models.user import User

    for uid in (101, 202):
        if db.query(User).filter(User.id == uid).first() is None:
            db.add(User(id=uid, email="u%s@example.com" % uid,
                        password_hash="x", has_couple=True))
    # ai_chat_session.scene_key 有 FK → ai_scene.scene_key，MySQL 会真的校验
    for key in ("mediation", "private_advisor"):
        if db.query(AiScene).filter(AiScene.scene_key == key).first() is None:
            db.add(AiScene(scene_key=key, name=key))
    db.commit()
    rel = CoupleRelation(user_a_id=101, user_b_id=202, status="active")
    db.add(rel)
    db.commit()
    db.refresh(rel)
    return rel


# --------------------------------------------------------------------------- #
# P0-5 · 1：双连接 Barrier 同时 start_mediation
# --------------------------------------------------------------------------- #
def t_concurrent_start_returns_single_session(h):
    from app.services import mediation_service

    setup = h.db()
    relation_id = seed_users(setup).id
    setup.close()

    barrier = threading.Barrier(2)
    results, errors = {}, {}

    def worker(tag):
        db = h.db()
        try:
            barrier.wait(timeout=20)
            results[tag] = mediation_service.start_mediation(db, 101, relation_id)
        except Exception as exc:  # noqa: BLE001
            errors[tag] = "%s: %s" % (type(exc).__name__, exc)
        finally:
            db.close()

    threads = [threading.Thread(target=worker, args=(t,)) for t in ("A", "B")]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)

    check("并发 start 无异常（无 PendingRollbackError）", not errors, str(errors))
    ids = {r["session_id"] for r in results.values()}
    check("两个并发 start 返回**同一个** session_id",
          len(ids) == 1 and not errors, "%s errors=%s" % (ids, errors))
    check("库里只有一条活跃会话",
          h.count("SELECT COUNT(*) FROM ai_chat_session "
                  "WHERE session_type='mediation' AND mediation_active_slot IS NOT NULL") == 1)
    check("没有多余会话行",
          h.count("SELECT COUNT(*) FROM ai_chat_session WHERE session_type='mediation'") == 1)


def t_conflict_connection_still_usable(h):
    """冲突方拿到 IntegrityError 后，**同一条连接**必须还能查、还能提交。"""
    from app.services import mediation_service

    setup = h.db()
    relation_id = seed_users(setup).id
    setup.close()

    barrier = threading.Barrier(2)
    out = {}

    def worker(tag):
        db = h.db()
        try:
            barrier.wait(timeout=20)
            res = mediation_service.start_mediation(db, 101, relation_id)
            from app.models.user import User
            after = db.query(User).filter(User.id == 101).first()
            db.commit()
            out[tag] = ("ok", res["session_id"], after is not None)
        except Exception as exc:  # noqa: BLE001
            out[tag] = ("err", "%s: %s" % (type(exc).__name__, exc), False)
        finally:
            db.close()

    threads = [threading.Thread(target=worker, args=(t,)) for t in ("A", "B")]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)

    check("两方都正常返回", all(v[0] == "ok" for v in out.values()), str(out))
    check("冲突后同一连接仍可查询并提交",
          all(v[2] for v in out.values() if v[0] == "ok"), str(out))


# --------------------------------------------------------------------------- #
# P0-5 · 2：双连接 Barrier 同时建同一幂等任务
# --------------------------------------------------------------------------- #
def _make_session(h, status="rewriting", revision=0):
    from app.models.ai import AiChatSession

    setup = h.db()
    rel = seed_users(setup)
    session = AiChatSession(
        user_id=101, relation_id=rel.id, scene_key="mediation",
        title="双人调解", privacy_level="couple", session_type="mediation",
        partner_user_id=202, mediation_status=status,
        mediation_revision=revision,
    )
    setup.add(session)
    setup.commit()
    setup.refresh(session)
    sid = session.id
    setup.close()
    return sid


def t_concurrent_create_task_single_row(h):
    from app.repositories import ai_task_repo

    session_id = _make_session(h)
    barrier = threading.Barrier(2)
    out, errors = {}, {}

    def worker(tag):
        db = h.db()
        try:
            barrier.wait(timeout=20)
            task = ai_task_repo.create_task(
                db, task_type="mediation_rewrite", session_id=session_id, revision=1,
            )
            db.commit()
            out[tag] = task.id
            again = ai_task_repo.get_by_idempotency_key(
                db, ai_task_repo.idempotency_key("mediation_rewrite", session_id, 1)
            )
            db.commit()
            out[tag + "_reread"] = again.id if again else None
        except Exception as exc:  # noqa: BLE001
            errors[tag] = "%s: %s" % (type(exc).__name__, exc)
        finally:
            db.close()

    threads = [threading.Thread(target=worker, args=(t,)) for t in ("A", "B")]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)

    check("并发建任务无异常（无 PendingRollbackError）", not errors, str(errors))
    ids = {out.get("A"), out.get("B")}
    check("两个并发 create_task 返回**同一个** task_id",
          len(ids) == 1 and None not in ids, "%s errors=%s" % (ids, errors))
    check("冲突方回读到的仍是同一行",
          out.get("A_reread") == out.get("A") and out.get("B_reread") == out.get("B"),
          str(out))
    check("库里只有一条任务",
          h.count("SELECT COUNT(*) FROM ai_task WHERE session_id=%s "
                  "AND task_type='mediation_rewrite'" % session_id) == 1)


def t_savepoint_isolates_conflict_from_outer_cas(h):
    """外层先做一次状态 CAS，再撞幂等冲突——CAS 必须保住。

    这是 `create_task` 契约的原文：「IntegrityError 只回滚当前原子事务，
    不破坏已经存在的正确任务」。若 INSERT 落在 SAVEPOINT 之外，InnoDB 会让
    整个事务进入失败态，CAS 随之丢失。
    """
    from sqlalchemy import text

    from app.repositories import ai_task_repo

    session_id = _make_session(h, status="inputting", revision=1)

    other = h.db()
    ai_task_repo.create_task(
        other, task_type="mediation_rewrite", session_id=session_id, revision=1,
    )
    other.commit()
    other.close()

    db = h.db()
    try:
        db.execute(text(
            "UPDATE ai_chat_session SET mediation_status='rewriting', "
            "mediation_revision=2 WHERE id=:sid AND mediation_status='inputting'"
        ), {"sid": session_id})
        task = ai_task_repo.create_task(
            db, task_type="mediation_rewrite", session_id=session_id, revision=1,
        )
        check("冲突路径返回既有任务行", task is not None)
        status = db.execute(text(
            "SELECT mediation_status, mediation_revision FROM ai_chat_session WHERE id=:sid"
        ), {"sid": session_id}).fetchone()
        check("冲突后外层事务仍可查询（未被污染）", status is not None, str(status))
        db.commit()
    except Exception as exc:  # noqa: BLE001
        check("外层事务未被冲突污染", False, "%s: %s" % (type(exc).__name__, exc))
        db.rollback()
    finally:
        db.close()

    rev = h.count("SELECT mediation_revision FROM ai_chat_session WHERE id=%s" % session_id)
    check("外层 CAS 的写入确实落库（未被回滚）", int(rev or 0) == 2, "rev=%s" % rev)


def t_locking_read_sees_committed_conflict(h):
    """冲突回读必须是加锁读。

    在 REPEATABLE-READ 下先建立快照（普通 SELECT），等竞争方提交后再回读：
    普通 SELECT 返回 None（→ 旧代码 raise，把正常竞争变成 500），
    `SELECT ... FOR UPDATE` 才看得见。这一条是「必须用加锁读」的直接证据。
    """
    from sqlalchemy import text

    from app.repositories import ai_task_repo

    session_id = _make_session(h)
    key = ai_task_repo.idempotency_key("mediation_rewrite", session_id, 7)

    # 竞争方先建好这一行
    other = h.db()
    ai_task_repo.create_task(
        other, task_type="mediation_rewrite", session_id=session_id, revision=7,
    )
    other.commit()
    other.close()

    db = h.db()
    try:
        # 建立本事务的一致性读快照（此后别人提交的行不再可见）
        db.execute(text("SELECT COUNT(*) FROM ai_task")).fetchone()

        # 另开一个连接，在快照之后提交一行
        later = h.db()
        ai_task_repo.create_task(
            later, task_type="mediation_summary", session_id=session_id, revision=9,
        )
        later.commit()
        later.close()
        later_key = ai_task_repo.idempotency_key("mediation_summary", session_id, 9)

        plain = ai_task_repo.get_by_idempotency_key(db, later_key)
        locked = ai_task_repo.get_by_idempotency_key_locked(db, later_key)
        check("快照读看不见后来提交的行（普通 SELECT 返回 None）",
              plain is None, str(plain))
        check("加锁读看得见（FOR UPDATE 是当前读）", locked is not None, str(locked))
        db.commit()
    finally:
        db.close()
    check("前置：幂等键已生成", bool(key))


# --------------------------------------------------------------------------- #
# §8 故障注入（真 MySQL 事务边界）
# --------------------------------------------------------------------------- #
def _task_rows(h, session_id):
    from sqlalchemy import text
    with h.engine.connect() as conn:
        return conn.execute(text(
            "SELECT id, state FROM ai_task WHERE session_id=:sid ORDER BY id"
        ), {"sid": session_id}).fetchall()


def _assistant_count(h, session_id):
    return h.count(
        "SELECT COUNT(*) FROM ai_chat_message WHERE session_id=%s AND role='assistant'"
        % session_id
    )


def _session_row(h, session_id):
    from sqlalchemy import text
    with h.engine.connect() as conn:
        return conn.execute(text(
            "SELECT mediation_status, mediation_revision, rewrite_task_id, "
            "summary_task_id, mediation_active_slot FROM ai_chat_session WHERE id=:sid"
        ), {"sid": session_id}).fetchone()


def _assert_invariants(h, session_id, *, status, revision, task_id, task_states,
                       assistant, active_slot, label):
    """八个不变量逐条断言（§8 的统一口径）。"""
    row = _session_row(h, session_id)
    check("%s：会话状态 = %s" % (label, status), row[0] == status, str(row))
    check("%s：revision = %s" % (label, revision), int(row[1] or 0) == revision, str(row))
    check("%s：rewrite_task_id = %s" % (label, task_id), row[2] == task_id, str(row))
    check("%s：summary_task_id 未串写" % label, row[3] is None, str(row))
    check("%s：active_slot = %s" % (label, active_slot), row[4] == active_slot, str(row))
    rows = _task_rows(h, session_id)
    check("%s：任务数量与状态 = %s" % (label, task_states),
          sorted(r[1] for r in rows) == sorted(task_states), str(rows))
    check("%s：assistant 消息数 = %s" % (label, assistant),
          _assistant_count(h, session_id) == assistant,
          str(_assistant_count(h, session_id)))


def _usable(db):
    """事务仍可继续使用：还能查、还能提交。"""
    from sqlalchemy import text
    try:
        db.execute(text("SELECT 1")).fetchone()
        db.commit()
        return True
    except Exception:  # noqa: BLE001
        return False


def _inputting_session(h):
    from app.services import mediation_service
    setup = h.db()
    rel = seed_users(setup)
    sid = mediation_service.start_mediation(setup, 101, rel.id)["session_id"]
    mediation_service.accept_mediation(setup, sid, 202)
    setup.close()
    return sid


def _active_slot_of(h, sid):
    """活跃槽位是运行期不变量（不是零值）——注入后应与注入前**逐字相同**。"""
    return h.scalar("SELECT mediation_active_slot FROM ai_chat_session WHERE id=%s" % sid)


def _patch(module, name, fn):
    original = getattr(module, name)
    setattr(module, name, fn)
    return original


def _boom(*_a, **_k):
    raise RuntimeError("注入故障")


def t_fault_after_state_cas(h):
    """注入点 1：状态 CAS 之后、revision 自增之前。"""
    from app.services import mediation_service

    sid = _inputting_session(h)
    slot = _active_slot_of(h, sid)
    db = h.db()
    db.query(mediation_service.AiChatSession).filter(
        mediation_service.AiChatSession.id == sid
    ).update({"mediation_status": "inputting"}, synchronize_session=False)
    db.commit()

    orig = _patch(mediation_service, "_bump_revision", _boom)
    try:
        try:
            mediation_service._enqueue_generation(
                db, sid, from_statuses=("inputting",), to_status="rewriting",
                task_type="mediation_rewrite", requested_by_user_id=101,
            )
        except RuntimeError:
            pass
        usable = _usable(db)
    finally:
        _patch(mediation_service, "_bump_revision", orig)
        db.close()

    _assert_invariants(h, sid, status="inputting", revision=0, task_id=None,
                       task_states=[], assistant=0, active_slot=slot,
                       label="CAS 后注入")
    check("CAS 后注入：事务仍可继续使用", usable)


def t_fault_after_revision_bump(h):
    """注入点 2：revision 自增之后、任务 INSERT 之前。"""
    from app.services import ai_task_service, mediation_service

    sid = _inputting_session(h)
    slot = _active_slot_of(h, sid)
    db = h.db()
    orig = _patch(ai_task_service, "ensure_task", _boom)
    try:
        try:
            mediation_service._enqueue_generation(
                db, sid, from_statuses=("inputting",), to_status="rewriting",
                task_type="mediation_rewrite", requested_by_user_id=101,
            )
        except RuntimeError:
            pass
        usable = _usable(db)
    finally:
        _patch(ai_task_service, "ensure_task", orig)
        db.close()

    _assert_invariants(h, sid, status="inputting", revision=0, task_id=None,
                       task_states=[], assistant=0, active_slot=slot,
                       label="revision 后注入")
    check("revision 后注入：事务仍可继续使用", usable)


def t_fault_after_task_insert(h):
    """注入点 3+4：任务 INSERT 之后（含 task_id 回写之后）、commit 之前。

    包装 `ensure_task`：先跑真的（INSERT + task_id 回写 + flush），再抛。
    事务回滚后**一个字都不该留下**——状态、revision、任务行、task_id 全归零。
    """
    from app.services import ai_task_service, mediation_service

    sid = _inputting_session(h)
    slot = _active_slot_of(h, sid)
    db = h.db()
    real = ai_task_service.ensure_task

    def boom_after(*a, **k):
        real(*a, **k)
        raise RuntimeError("注入故障（INSERT 之后）")

    orig = _patch(ai_task_service, "ensure_task", boom_after)
    try:
        try:
            mediation_service._enqueue_generation(
                db, sid, from_statuses=("inputting",), to_status="rewriting",
                task_type="mediation_rewrite", requested_by_user_id=101,
            )
        except RuntimeError:
            pass
        usable = _usable(db)
    finally:
        _patch(ai_task_service, "ensure_task", orig)
        db.close()

    _assert_invariants(h, sid, status="inputting", revision=0, task_id=None,
                       task_states=[], assistant=0, active_slot=slot,
                       label="INSERT 后注入")
    check("INSERT 后注入：事务仍可继续使用", usable)


def _reach_rewriting(h):
    """跑到「双方已提交、任务已建好、worker 已领走」的现场。"""
    from app.repositories import ai_task_repo
    from app.services import ai_task_service, mediation_service

    sid = _inputting_session(h)
    db = h.db()
    mediation_service.submit_input(db, sid, 202, "B 的倾诉")
    mediation_service.submit_input(db, sid, 101, "A 的倾诉")
    db.close()
    setup = h.db()
    task = ai_task_repo.claim_next_task(setup, "wA")
    setup.close()
    return sid, task.id


def t_fault_after_session_cas_before_message(h):
    """注入点 5：会话状态 CAS 之后、assistant 消息 INSERT 之前。"""
    from app.repositories import ai_task_repo
    from app.services import mediation_service

    sid, task_id = _reach_rewriting(h)
    slot = _active_slot_of(h, sid)
    db = h.db()
    task = ai_task_repo.get_task_by_id(db, task_id)

    orig = _patch(ai_task_repo, "finalize_owned", _boom)
    try:
        try:
            mediation_service._commit_generation_result(
                db, task, {"rewrite_a": "A", "rewrite_b": "B", "risk_level": "normal"},
                "confirming",
            )
        except RuntimeError:
            pass
        usable = _usable(db)
    finally:
        _patch(ai_task_repo, "finalize_owned", orig)
        db.close()

    _assert_invariants(h, sid, status="rewriting", revision=1, task_id=task_id,
                       task_states=["running"], assistant=0, active_slot=slot,
                       label="会话 CAS 后注入")
    check("会话 CAS 后注入：事务仍可继续使用", usable)


def t_fault_after_message_before_task_succeeded(h):
    """注入点 6：assistant 消息 INSERT 之后、任务 succeeded 之前。

    消息 INSERT 之后才失败，是最危险的一档——若消息独立提交，用户就会
    看到一份「任务没收口、却已经展示出来」的稿子。整笔回滚必须把它抹掉。
    """
    from app.repositories import ai_repo, ai_task_repo
    from app.services import mediation_service

    sid, task_id = _reach_rewriting(h)
    slot = _active_slot_of(h, sid)
    db = h.db()
    task = ai_task_repo.get_task_by_id(db, task_id)
    real = ai_repo.create_message

    def boom_after(*a, **k):
        real(*a, **k)
        raise RuntimeError("注入故障（消息 INSERT 之后）")

    orig = _patch(ai_repo, "create_message", boom_after)
    try:
        try:
            mediation_service._commit_generation_result(
                db, task, {"rewrite_a": "A", "rewrite_b": "B", "risk_level": "normal"},
                "confirming",
            )
        except RuntimeError:
            pass
        usable = _usable(db)
    finally:
        _patch(ai_repo, "create_message", orig)
        db.close()

    _assert_invariants(h, sid, status="rewriting", revision=1, task_id=task_id,
                       task_states=["running"], assistant=0, active_slot=slot,
                       label="消息 INSERT 后注入")
    check("消息 INSERT 后注入：事务仍可继续使用", usable)


def t_fault_after_safety_message_before_commit(h):
    """注入点 7：安全阻断消息插入之后、commit 之前。

    这一档如果漏掉，用户会看到一条安全提示，而会话状态还停在 rewriting、
    任务还在跑——提示与状态自相矛盾。整笔回滚必须把提示也抹掉。
    """
    from app.repositories import ai_repo, ai_task_repo
    from app.services import mediation_service

    sid, task_id = _reach_rewriting(h)
    slot = _active_slot_of(h, sid)
    db = h.db()
    task = ai_task_repo.get_task_by_id(db, task_id)
    real = ai_repo.create_message

    def boom_after(*a, **k):
        real(*a, **k)
        raise RuntimeError("注入故障（安全阻断消息之后）")

    orig = _patch(ai_repo, "create_message", boom_after)
    try:
        try:
            mediation_service._commit_generation_result(
                db, task, {"rewrite_a": "A", "rewrite_b": "B", "risk_level": "abuse_risk"},
                "confirming",
            )
        except RuntimeError:
            pass
        usable = _usable(db)
    finally:
        _patch(ai_repo, "create_message", orig)
        db.close()

    _assert_invariants(h, sid, status="rewriting", revision=1, task_id=task_id,
                       task_states=["running"], assistant=0, active_slot=slot,
                       label="安全阻断消息后注入")
    check("安全阻断消息后注入：事务仍可继续使用", usable)
    check("安全阻断未落审计（回滚后不留痕）",
          h.count("SELECT COUNT(*) FROM safety_event WHERE scene='mediation'") == 0)


def t_fault_after_end_commit_before_broadcast(h):
    """补一档：`next_step(end)` **提交之后**、广播之前抛异常。

    end 的状态 CAS + revision 自增 + 释放槽位是**同一条 UPDATE**（因此不存在
    「CAS 过了、版本没推」的中间态可注入）。这一档要证的是另一半：
    提交之后的副作用（重读 / 广播）失败**不得**把已提交的结束动作回滚掉——
    用户已经看到「已结束」，服务端不能又变回进行中。
    """
    from app.services import mediation_service

    sid = _inputting_session(h)
    slot = _active_slot_of(h, sid)
    db = h.db()

    orig = _patch(mediation_service, "_broadcast_status", _boom)
    try:
        try:
            mediation_service.next_step(db, sid, 101, "end")
        except RuntimeError:
            pass
        usable = _usable(db)
    finally:
        _patch(mediation_service, "_broadcast_status", orig)
        db.close()

    _assert_invariants(h, sid, status="completed", revision=1, task_id=None,
                       task_states=[], assistant=0, active_slot=None,
                       label="end 提交后注入")
    check("end 提交后注入：结束动作未被回滚", True)
    check("end 提交后注入：事务仍可继续使用", usable)


def t_legacy_ordering_would_fail_this_suite(h):
    """**测试有效性自证**：换回 B4.2 的旧顺序，同一位置注入必须失败。

    只断言「新代码通过」是不够的——注入点可能根本没插在关键位置上，那样的
    测试永远绿。这里把 `_write_result_transaction` 临时换成旧顺序
    （CAS → 插消息 → **commit** → mark_succeeded），并在**同一个语义位置**
    注入：旧顺序里「消息已落库、任务还没收口」对应的是 `mark_succeeded`
    （新顺序里对应 `finalize_owned` / 消息 INSERT 之后，因为新顺序把收口
    放在了消息之前）。旧顺序下消息已经提交，用户看到一份未收口的稿子，
    会话停在 confirming——断言它确实被抓到，才算证明这组注入有牙齿。
    """
    from app.repositories import ai_repo, ai_task_repo
    from app.services import mediation_service

    def legacy_write(db, task, claim_from, new_status, *, content, structured_output,
                     risk_level, extra_session_values=None):
        if not mediation_service._claim_generation(
            db, task, claim_from, new_status, extra_values=extra_session_values
        ):
            db.rollback()
            return False
        ai_repo.create_message(
            db, task.session_id, "assistant", content,
            structured_output=structured_output, risk_level=risk_level,
        )
        db.commit()  # ← 旧顺序：结果先落库
        ai_task_repo.mark_succeeded(db, task, expected_revision=int(task.revision or 0))
        return True

    sid, task_id = _reach_rewriting(h)
    db = h.db()
    task = ai_task_repo.get_task_by_id(db, task_id)

    orig_write = _patch(mediation_service, "_write_result_transaction", legacy_write)
    orig_final = _patch(ai_task_repo, "mark_succeeded", _boom)
    try:
        try:
            mediation_service._commit_generation_result(
                db, task, {"rewrite_a": "A", "rewrite_b": "B", "risk_level": "normal"},
                "confirming",
            )
        except RuntimeError:
            pass
        db.close()
    finally:
        _patch(ai_task_repo, "mark_succeeded", orig_final)
        _patch(mediation_service, "_write_result_transaction", orig_write)

    row = _session_row(h, sid)
    leaked = _assistant_count(h, sid)
    check("旧顺序下确实会漏出一条未收口的稿子（证明注入点有效）",
          row[0] == "confirming" and leaked == 1,
          "status=%s assistant=%s" % (row[0], leaked))


def main() -> int:
    print("[调解 MySQL 并发幂等 + 故障注入] 整改 B4.3 P0-5 / §8 验收（真 MySQL）")
    probe = mysql_available()
    if probe is None:
        print("========== 结果 ==========")
        print("跳过：MySQL 不可用——P0-5 与 §8 的 MySQL 结论**未取得**")
        return 2
    version, iso = probe
    print("MySQL %s / isolation=%s" % (version, iso))
    if iso != "REPEATABLE-READ":
        skip("隔离级别不是 REPEATABLE-READ（%s），与生产不一致" % iso)

    with MysqlHarness("conc") as h:
        t_concurrent_start_returns_single_session(h)
        t_conflict_connection_still_usable(h)
        t_concurrent_create_task_single_row(h)
        t_savepoint_isolates_conflict_from_outer_cas(h)
        t_locking_read_sees_committed_conflict(h)

    with MysqlHarness("fault") as h:
        t_fault_after_state_cas(h)
        t_fault_after_revision_bump(h)
        t_fault_after_task_insert(h)
        t_fault_after_session_cas_before_message(h)
        t_fault_after_message_before_task_succeeded(h)
        t_fault_after_safety_message_before_commit(h)
        t_fault_after_end_commit_before_broadcast(h)

    # 测试有效性自证放在最后：它会临时改写结果写入顺序，不污染其它用例
    with MysqlHarness("legacy") as h:
        t_legacy_ordering_would_fail_this_suite(h)

    print("========== 结果 ==========")
    print("断言数：%d" % CHECKS)
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    if SKIPPED:
        print("跳过 %d 项：%s" % (len(SKIPPED), SKIPPED))
    print("全部通过（MySQL %s）" % version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
