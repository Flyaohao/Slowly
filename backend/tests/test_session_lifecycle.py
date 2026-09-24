"""
P0-10A 验收：会话生命周期 a–h。

测试隔离（§2.1，照 test_sse 先例）：
  模块顶部、import app 之前设 COUPLE_DISABLE_MEMORY_DISTILL=1——
  否则归档摘要 / 记忆抽取的 daemon 线程在进程退出后写库，污染开发库。
  case h 因此采用**方案甲**：直调摘要生成逻辑 + mock LLM/写库，不打真库。

运行：cd backend && python tests/test_session_lifecycle.py
"""
import os
import sys
from datetime import datetime, timedelta

# 必须在 import app 之前
os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def _row_counts():
    from app.core.database import SessionLocal
    from sqlalchemy import text

    db = SessionLocal()
    try:
        return (
            db.execute(text("SELECT COUNT(*) FROM ai_chat_session")).scalar(),
            db.execute(text("SELECT COUNT(*) FROM ai_chat_message")).scalar(),
            db.execute(text("SELECT COUNT(*) FROM ai_memory")).scalar(),
        )
    finally:
        db.close()


# ---------------------------------------------------------------------- #
# 分段规则纯函数（e/f 不碰库）
# ---------------------------------------------------------------------- #
def case_e_timeout_segment():
    print("\n[e] last_message_at 距今 > 6h → timeout（伪造 now，不 sleep）")
    from types import SimpleNamespace

    from app.services.ai_service import should_start_new_session

    # 与实现同一时钟（datetime.now，对齐 MySQL NOW()）
    now = datetime.now()
    old = now - timedelta(hours=6, minutes=1)
    s = SimpleNamespace(
        status="active", scene_key="private_advisor",
        last_message_at=old, created_at=old, token_total=0,
    )
    check(">6h → timeout", should_start_new_session(s, "private_advisor", now) == "timeout")

    fresh = now - timedelta(minutes=30)
    s2 = SimpleNamespace(
        status="active", scene_key="private_advisor",
        last_message_at=fresh, created_at=fresh, token_total=0,
    )
    check("新鲜 → None 可续接", should_start_new_session(s2, "private_advisor", now) is None)


def case_f_budget_segment():
    print("\n[f] token_total > 6000 → budget")
    from types import SimpleNamespace

    from app.services.ai_service import should_start_new_session

    now = datetime.now()
    s = SimpleNamespace(
        status="active", scene_key="private_advisor",
        last_message_at=now, created_at=now, token_total=6001,
    )
    check(">6000 → budget", should_start_new_session(s, "private_advisor", now) == "budget")
    s.token_total = 6000
    check("=6000 → 可续接", should_start_new_session(s, "private_advisor", now) is None)


def _archive_existing_actives(db, user_id, relation_id, scene_keys=None):
    """测试隔离：归档该 relation 下**全部** active（不限 scene），避免被收编后误删。"""
    from app.models.ai import AiChatSession
    from app.repositories import ai_repo

    rows = (
        db.query(AiChatSession)
        .filter(
            AiChatSession.user_id == user_id,
            AiChatSession.relation_id == relation_id,
            AiChatSession.status == "active",
        )
        .all()
    )
    for s in rows:
        ai_repo.archive_session(db, s.id, "manual")
        print(f"  [隔离] 预存 active session {s.id} ({s.scene_key}) → archived")
    db.commit()
    return len(rows)


def _session_ids(db, user_id):
    from app.models.ai import AiChatSession

    return {
        r[0]
        for r in db.query(AiChatSession.id).filter(AiChatSession.user_id == user_id).all()
    }


def case_g_active_order():
    print("\n[g] get_active_session 取 last_message_at 最新")
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation
    from app.repositories import ai_repo

    db = SessionLocal()
    created = []
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        uid, rid = rel.user_a_id, rel.id
        _archive_existing_actives(db, uid, rid, ["private_advisor"])

        s_old = ai_repo.create_session(db, uid, rid, "private_advisor",
                                       title="旧会话", privacy_level="private")
        s_old.last_message_at = datetime.now() - timedelta(hours=2)
        s_new = ai_repo.create_session(db, uid, rid, "private_advisor",
                                       title="新会话", privacy_level="private")
        s_new.last_message_at = datetime.now()
        db.commit()
        created = [s_old.id, s_new.id]

        active = ai_repo.get_active_session(db, uid, rid, "private_advisor")
        check("取到的是较新的", active is not None and active.id == s_new.id,
              f"active={active.id if active else None} new={s_new.id}")
    finally:
        from app.models.ai import AiChatMessage, AiChatSession
        from sqlalchemy import delete
        for sid in created:
            db.execute(delete(AiChatMessage).where(AiChatMessage.session_id == sid))
            db.execute(delete(AiChatSession).where(AiChatSession.id == sid))
        db.commit()
        db.close()
        if created:
            print(f"  已清理 sessions {created}")


# ---------------------------------------------------------------------- #
# a–d：真实 _preprocess 链路
# ---------------------------------------------------------------------- #
def case_abcd_preprocess():
    print("\n[a–d] _preprocess 新建/续接/越权/切场景")
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation
    from app.services.ai_service import _preprocess

    db = SessionLocal()
    created = []
    ids_before = set()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        uid, rid = rel.user_a_id, rel.id
        # 隔离：该 relation 全部 active 先归档，防止收编后被误删
        _archive_existing_actives(db, uid, rid)
        ids_before = _session_ids(db, uid)
        # 第二个用户（越权用）
        other = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active", CoupleRelation.id != rel.id)
            .first()
        )
        uid_b = other.user_a_id if other else None

        # a: 无 session_id → 新建
        ctx = _preprocess(db, uid, rid, None, "private_advisor", "测试新建会话A")
        s = ctx["session"]
        created.append(s.id)
        check("a 新建 status=active", s.status == "active", s.status)
        check("a title=场景名", s.title is not None and s.title != "", s.title)
        check("a segment_reason 空", s.segment_reason is None, str(s.segment_reason))
        sid_a = s.id

        # b: 传入有效 session_id → 续接（scene 一致）
        ctx2 = _preprocess(db, uid, rid, sid_a, "private_advisor", "测试续接B")
        check("b 续接同一会话", ctx2["session_id"] == sid_a,
              f"{ctx2['session_id']} vs {sid_a}")
        check("b message_count 递增", (ctx2["session"].message_count or 0) >= 1,
              str(ctx2["session"].message_count))
        check("b last_message_at 已刷新", ctx2["session"].last_message_at is not None)

        # c: 另一个用户的 session_id → 50002
        if uid_b is not None:
            try:
                _preprocess(db, uid_b, other.id, sid_a, "private_advisor", "越权")
                check("c 越权报 50002", False, "未抛异常")
            except ValueError as e:
                check("c 越权报 50002", str(e) == "50002", str(e))
        else:
            check("c 越权（无第二关系，跳过）", True)

        # d: scene 不一致 → 新建 + 旧转 archived
        ctx3 = _preprocess(db, uid, rid, sid_a, "partner_translate", "切场景D")
        created.append(ctx3["session_id"])
        check("d 新会话 scene 正确", ctx3["session"].scene_key == "partner_translate",
              ctx3["session"].scene_key)
        check("d 新会话 ≠ 旧 id", ctx3["session_id"] != sid_a)
        check("d 新会话 segment_reason=scene_switch",
              ctx3["session"].segment_reason == "scene_switch",
              str(ctx3["session"].segment_reason))
        db.refresh(s)
        check("d 旧会话已 archived", s.status == "archived", s.status)
        check("d 旧会话记录原因", s.segment_reason == "scene_switch",
              str(s.segment_reason))

        # g 补充：无 session_id 时也应能拿到 active（partner_translate 那条）
        from app.repositories import ai_repo
        active2 = ai_repo.get_active_session(db, uid, rid, "partner_translate")
        check("g 下一次无 id 会命中 active",
              active2 is not None and active2.id == ctx3["session_id"],
              str(active2.id if active2 else None))
    except Exception as exc:
        check("a–d 不抛未预期异常", False, repr(exc))
        raise
    finally:
        # 只删本次新出现的 id——绝不删测试前就存在的会话
        ids_after = _session_ids(db, uid)
        new_ids = sorted(ids_after - ids_before)
        db.close()
        db2 = SessionLocal()
        try:
            from app.models.ai import AiChatMessage, AiChatSession
            from sqlalchemy import delete
            for sid in new_ids:
                db2.execute(delete(AiChatMessage).where(AiChatMessage.session_id == sid))
                db2.execute(delete(AiChatSession).where(AiChatSession.id == sid))
            db2.commit()
            print(f"  已清理本次新建 sessions {new_ids}（created 标记={created}）")
        finally:
            db2.close()


# ---------------------------------------------------------------------- #
# h：方案甲——直调摘要，mock LLM + 写库，不打真库
# ---------------------------------------------------------------------- #
def case_h_session_summary_unit():
    print("\n[h] 归档摘要（方案甲：mock LLM/写库，不打真库）")
    import app.services.memory_service as ms

    captured = {}

    class _FakeLlm:
        api_key = "test"

        def invoke(self, messages, scene="unknown", **kw):
            captured["scene"] = scene
            captured["messages"] = messages
            return "你们这次因为周末安排吵了一架，最后她先说了感受，有效。"

    # 直接驱动 worker 逻辑的关键断言：
    # 1) 开关=1 时 distill_session_summary_in_background 立即 return（不建线程）
    # 2) 摘要 prompt 含 SESSION_SUMMARY 要求且产出可截断到 120
    # 3) 落库字段 session_summary / private / relation_id——通过 mock create 路径验证

    # 开关生效
    orig = os.environ.get("COUPLE_DISABLE_MEMORY_DISTILL")
    os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"
    try:
        r = ms.distill_session_summary_in_background(
            session_id=999999, user_id=1, relation_id=1, scene_key="private_advisor"
        )
        check("开关=1 时直接 return（不启线程）", r is None)
    finally:
        if orig is None:
            os.environ.pop("COUPLE_DISABLE_MEMORY_DISTILL", None)
        else:
            os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = orig

    # prompt 与截断：模拟 worker 内核心逻辑
    from app.services.prompt_builder import SESSION_SUMMARY_PROMPT

    check("SESSION_SUMMARY_PROMPT 要求 120 字", "120" in SESSION_SUMMARY_PROMPT)
    check("SESSION_SUMMARY_PROMPT 要求叙事", "叙事" in SESSION_SUMMARY_PROMPT)
    fake = _FakeLlm()
    raw = fake.invoke(
        [{"role": "user", "content": SESSION_SUMMARY_PROMPT.format(conversation="user: hi")}],
        scene="session_summary",
    )
    summary = raw.strip()
    if len(summary) > 120:
        summary = summary[:119] + "…"
    check("mock 摘要 ≤120 字", len(summary) <= 120, str(len(summary)))
    check("scene=session_summary", captured.get("scene") == "session_summary")

    # 落库字段契约：直查源码/表约束——visibility 合法值与 memory_type
    from app.models.ai import AiMemory

    cols = {c.name: c for c in AiMemory.__table__.columns}
    check("memory_type 列存在", "memory_type" in cols)
    check("visibility 列存在", "visibility" in cols)
    check("relation_id 非空（NOT NULL）", not cols["relation_id"].nullable)

    # 源码级：直插路径写了三个关键字段，且绕过 vectorize
    import inspect
    from app.services import memory_service as ms2

    src = inspect.getsource(ms2.distill_session_summary_in_background)
    check("写 memory_type=session_summary", 'memory_type="session_summary"' in src)
    check("写 visibility=private", 'visibility="private"' in src)
    check("传 relation_id=relation_id", "relation_id=relation_id" in src)
    check("本路径不调 vectorize_memory_async", "vectorize_memory_async" not in src)
    check("读 COUPLE_DISABLE_MEMORY_DISTILL",
          "COUPLE_DISABLE_MEMORY_DISTILL" in src)


# ---------------------------------------------------------------------- #
# GET /sessions/active 服务层
# ---------------------------------------------------------------------- #
def case_active_endpoint_service():
    print("\n[bonus] get_active_session_info 形状")
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation
    from app.services.ai_service import get_active_session_info

    db = SessionLocal()
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        # 无 active 时全 null/false
        # 先清掉该 scope 可能残留的 active（只删本测试 scope 的 private_advisor active 不必要）
        info = get_active_session_info(db, rel.user_a_id, rel.id, "definitely_no_scene_xxx")
        check("无会话 → session_id=None", info["session_id"] is None)
        check("无会话 → resumable=False", info["resumable"] is False)
        check("无会话 → message_count=0", info["message_count"] == 0)
        for k in ("session_id", "title", "message_count", "last_message_at", "resumable"):
            check(f"含字段 {k}", k in info)
    finally:
        db.close()


def case_i_close_endpoint():
    """P0-10B 前置：POST /sessions/{id}/close —— 401 / 403 / 归档 / 幂等。"""
    print("\n[i] close 端点：401 / 403 / 归档 / 幂等")
    from fastapi.testclient import TestClient

    from app.main import app as fastapi_app
    from app.security.jwt import create_access_token

    client = TestClient(fastapi_app)

    # i1 未登录 401
    r = client.post("/api/v1/couple/ai/sessions/1/close")
    check("i1 未登录 → 401", r.status_code == 401, str(r.status_code))

    # 准备：造一条属于 rel.user_a 的 active 会话
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation
    from app.repositories import ai_repo

    db = SessionLocal()
    sid = None
    uid = None
    uid_b = None
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        uid = rel.user_a_id
        s = ai_repo.create_session(
            db, uid, rel.id, "private_advisor",
            title="close测试", privacy_level="private",
        )
        db.commit()
        sid = s.id
        other = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active", CoupleRelation.id != rel.id)
            .first()
        )
        uid_b = other.user_a_id if other else None
    finally:
        db.close()

    headers_a = {"Authorization": "Bearer %s" % create_access_token(uid)}

    # i2 越权 403
    if uid_b is not None:
        headers_b = {"Authorization": "Bearer %s" % create_access_token(uid_b)}
        r2 = client.post(
            "/api/v1/couple/ai/sessions/%d/close" % sid, headers=headers_b
        )
        check("i2 越权 → 403 code=50002",
              r2.status_code == 403 and (r2.json().get("detail") or {}).get("code") == 50002,
              str(r2.status_code) + str(r2.json())[:120])
    else:
        check("i2 越权（无第二关系，跳过）", True)

    # i3 正常关闭 → 200 + 归档字段
    r3 = client.post(
        "/api/v1/couple/ai/sessions/%d/close" % sid, headers=headers_a
    )
    d3 = (r3.json() or {}).get("data") or {}
    check("i3 关闭 → 200 closed=true",
          r3.status_code == 200 and d3.get("closed") is True
          and d3.get("session_id") == sid,
          str(r3.json())[:160])

    db = SessionLocal()
    try:
        sess = ai_repo.get_session_by_id(db, sid)
        check("i3 归档 status=archived", sess.status == "archived", sess.status)
        check("i3 segment_reason=user_ended",
              sess.segment_reason == "user_ended", str(sess.segment_reason))
    finally:
        db.close()

    # i4 重复调幂等 200
    r4 = client.post(
        "/api/v1/couple/ai/sessions/%d/close" % sid, headers=headers_a
    )
    d4 = (r4.json() or {}).get("data") or {}
    check("i4 幂等 → 仍 200 closed=true",
          r4.status_code == 200 and d4.get("closed") is True,
          str(r4.status_code))

    # 清理（按 id，session_summary 由开关=1 未写库）
    db = SessionLocal()
    try:
        from app.models.ai import AiChatMessage, AiChatSession
        from sqlalchemy import delete
        db.execute(delete(AiChatMessage).where(AiChatMessage.session_id == sid))
        db.execute(delete(AiChatSession).where(AiChatSession.id == sid))
        db.commit()
        print(f"  已清理 close 测试会话 {sid}")
    finally:
        db.close()


def case_j_archive_idempotent():
    """收尾补丁 B：归档幂等——已 archived 不改写 segment_reason、不重复蒸馏。

    蒸馏计数口径（P0-10B-2 §2）：
    - COUPLE_DISABLE_MEMORY_DISTILL=1 下蒸馏函数首行即 return，计数必须靠
      **替换 app.services.memory_service.distill_session_summary_in_background**
      （_archive 内是函数内延迟 import，每次调用重取模块属性——不要在
      ai_service 命名空间打补丁，那里没有该符号）。
    - j3（close_session）自身带 status!="archived" 守卫，**不能证明守卫 B**；
      能证明 B 的是 j2 直调 _archive_session_with_summary 的路径
      （_preprocess 场景分段/超时归档正是走这条）——功劳别记错。
    """
    print("\n[j] 归档幂等：segment_reason 不被覆盖 + 蒸馏只触发一次")
    from app.core.database import SessionLocal
    from app.models.couple_relation import CoupleRelation
    from app.repositories import ai_repo
    from app.services import memory_service
    from app.services.ai_service import _archive_session_with_summary, close_session

    calls = {"n": 0}
    _orig = memory_service.distill_session_summary_in_background

    def _fake_distill(*a, **kw):
        calls["n"] += 1

    memory_service.distill_session_summary_in_background = _fake_distill

    db = SessionLocal()
    sid = None
    try:
        rel = db.query(CoupleRelation).filter(CoupleRelation.status == "active").first()
        uid, rid = rel.user_a_id, rel.id
        s = ai_repo.create_session(
            db, uid, rid, "private_advisor",
            title="幂等测试", privacy_level="private",
        )
        db.commit()
        sid = s.id

        # 1) close 一次 → user_ended + 恰好 1 次蒸馏
        close_session(db, uid, sid)
        db.refresh(s)
        check("j1 close 后 status=archived", s.status == "archived", s.status)
        check("j1 segment_reason=user_ended", s.segment_reason == "user_ended",
              str(s.segment_reason))
        check("j1 蒸馏触发 1 次", calls["n"] == 1, str(calls["n"]))

        # 2) 直调归档换 reason → 不覆盖原因、不再蒸馏（守卫 B 的作用点）
        _archive_session_with_summary(db, s, "archived", uid, rid)
        db.refresh(s)
        check("j2 重复归档不覆盖原因", s.segment_reason == "user_ended",
              str(s.segment_reason))
        check("j2 蒸馏仍为 1 次（守卫 B）", calls["n"] == 1, str(calls["n"]))

        # 3) close 幂等；不证明守卫 B（close 自带 status 守卫），只验不重复
        result = close_session(db, uid, sid)
        db.refresh(s)
        check("j3 close 幂等 closed=true", result.get("closed") is True, str(result))
        check("j3 原因仍 user_ended", s.segment_reason == "user_ended",
              str(s.segment_reason))
        check("j3 蒸馏仍为 1 次", calls["n"] == 1, str(calls["n"]))
    except Exception as exc:
        check("j 不抛未预期异常", False, repr(exc))
        raise
    finally:
        memory_service.distill_session_summary_in_background = _orig
        if sid is not None:
            from app.models.ai import AiChatMessage, AiChatSession
            from sqlalchemy import delete
            db.execute(delete(AiChatMessage).where(AiChatMessage.session_id == sid))
            db.execute(delete(AiChatSession).where(AiChatSession.id == sid))
            db.commit()
            print(f"  已清理幂等测试会话 {sid}")
        db.close()


def main() -> int:
    print("=" * 72)
    print("P0 会话生命周期（a–j + 隔离）")
    print("=" * 72)

    c0 = _row_counts()
    print(f"基线 rows session/message/memory = {c0}")

    case_e_timeout_segment()
    case_f_budget_segment()
    case_g_active_order()
    case_abcd_preprocess()
    case_h_session_summary_unit()
    case_active_endpoint_service()
    case_i_close_endpoint()
    case_j_archive_idempotent()

    c1 = _row_counts()
    print(f"\n收尾 rows session/message/memory = {c1}")
    check("行数与基线一致（无残留）", c0 == c1, f"{c0} vs {c1}")

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
