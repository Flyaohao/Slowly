# -*- coding: utf-8 -*-
"""观点 → 军师记忆 开关 + 「计入画像」的用户覆盖（2026-09-27 用户裁决）。

跑法：`python tests/test_viewpoint_memory.py`（backend 目录下，需本地 MySQL）。
MySQL 不可用时**跳过并返回 0**，不伪装通过。

覆盖四件事：

  A. 记忆类型白名单：模型自造的类型必须被挡回默认值；
  B. 记忆文本压缩：标题+正文拼接、超长截断；
  C. 按来源查/删记忆——详情页开关的**初始状态**与**撤除**都靠它；
  D. 计入画像：用户明确覆盖时放行低置信度；同一条观点不重复计入（70008）。

为什么 D 必须连真 MySQL：它验的是**版本派生链**上的幂等——「当前版本是不是由
这条观点派生的」这个判断读的是最新版本的列，SQLite 的单写者模型验不出
并发下的版本号分配问题。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
for p in (BACKEND_DIR, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

from mysql_harness import MysqlHarness, mysql_available  # noqa: E402
from app.api.v1.couple.memory import (  # noqa: E402
    _compose_memory_text,
    _safe_memory_type,
)
from app.models.diary_entry import DiaryEntry  # noqa: E402
from app.repositories import profile_repo  # noqa: E402
from app.services import memory_service, profile_service  # noqa: E402

CHECKS = 0
FAILURES = []


def check(name, cond, detail=""):
    global CHECKS
    CHECKS += 1
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


DIMS = {
    "attachment_anxiety": 62.0,
    "attachment_avoidance": 38.0,
    "reassurance_need": 60.0,
    "values_orientation": 50.0,
}


# ------------------------------------------------------- A. 记忆类型白名单

def test_memory_type_whitelist():
    print("\n[A] 记忆类型白名单")

    for ok in ("偏好", "关系事实", "沟通雷区", "核心诉求"):
        check("白名单内保留：%s" % ok, _safe_memory_type(ok) == ok, _safe_memory_type(ok))

    check("None 兜到默认", _safe_memory_type(None) == "核心诉求", _safe_memory_type(None))
    check("空串兜到默认", _safe_memory_type("") == "核心诉求", _safe_memory_type(""))
    check("模型自造值兜到默认", _safe_memory_type("事件") == "核心诉求", _safe_memory_type("事件"))
    check("英文值兜到默认", _safe_memory_type("preference") == "核心诉求", _safe_memory_type("preference"))
    check("前后空格不匹配 → 兜到默认", _safe_memory_type(" 偏好 ") == "核心诉求", _safe_memory_type(" 偏好 "))


# ------------------------------------------------------- B. 记忆文本压缩

def test_compose_text():
    print("\n[B] 记忆文本压缩")

    e1 = DiaryEntry(user_id=1, title="关于陪伴", content="再忙也要留时间说话")
    check("标题 + 正文", _compose_memory_text(e1) == "关于陪伴：再忙也要留时间说话",
          _compose_memory_text(e1))

    e2 = DiaryEntry(user_id=1, title="只有标题", content="")
    check("只有标题", _compose_memory_text(e2) == "只有标题", _compose_memory_text(e2))

    e3 = DiaryEntry(user_id=1, title="", content="只有正文")
    check("只有正文", _compose_memory_text(e3) == "只有正文", _compose_memory_text(e3))

    e4 = DiaryEntry(user_id=1, title="长", content="字" * 2000)
    check("超长截断到 500 字", len(_compose_memory_text(e4)) == 500, len(_compose_memory_text(e4)))

    e5 = DiaryEntry(user_id=1, title="", content="")
    check("全空返回空串", _compose_memory_text(e5) == "", repr(_compose_memory_text(e5)))


# ------------------------------------------------- C. 按来源查/删记忆

def test_memory_by_source(h):
    print("\n[C] 按来源查/删记忆")

    from app.models.user import User
    from app.models.couple_relation import CoupleRelation

    db = h.db()
    try:
        a = User(email="vpmem-a@test.local", password_hash="x")
        b = User(email="vpmem-b@test.local", password_hash="x")
        db.add(a)
        db.add(b)
        db.commit()
        rel = CoupleRelation(user_a_id=a.id, user_b_id=b.id)
        db.add(rel)
        db.commit()

        check("初始没有来源记忆",
              memory_service.get_memories_by_source(db, a.id, "diary", 101) == [])

        memory_service.create_memory(
            db,
            a.id,
            rel.id,
            memory_type="核心诉求",
            memory_text="再忙也要留出说话的时间",
            visibility="private",
            source="diary",
            source_id=101,
        )
        got = memory_service.get_memories_by_source(db, a.id, "diary", 101)
        check("写入后能按来源查到", len(got) == 1, len(got))
        check("默认仅自己可见（观点是个人资产）",
              bool(got) and got[0]["visibility"] == "private", got)

        check("其它 source_id 查不到",
              memory_service.get_memories_by_source(db, a.id, "diary", 999) == [])
        check("其它 source 查不到",
              memory_service.get_memories_by_source(db, a.id, "letter", 101) == [])

        deleted = memory_service.delete_memories_by_source(db, a.id, "diary", 101)
        check("撤除返回删除条数 1", deleted == 1, deleted)
        check("撤除后查不到",
              memory_service.get_memories_by_source(db, a.id, "diary", 101) == [])
        check("重复撤除返回 0",
              memory_service.delete_memories_by_source(db, a.id, "diary", 101) == 0)
    finally:
        db.close()


# ------------------------------------------- D. 计入画像：用户覆盖 + 幂等

def test_enrich_force_and_idempotent(h):
    print("\n[D] 计入画像：用户覆盖 + 幂等")

    from app.models.user import User
    from app.models.questionnaire import Questionnaire

    db = h.db()
    try:
        u = User(email="vpmem-enrich@test.local", password_hash="x")
        q = Questionnaire(title="测试问卷")
        db.add(u)
        db.add(q)
        db.commit()
        uid, qid = u.id, q.id

        p = profile_repo.create_profile(
            db,
            user_id=uid,
            questionnaire_id=qid,
            profile_type="secure",
            confidence=0.7,
            summary="初始化画像",
            version=1,
            origin="questionnaire",
        )
        profile_repo.add_dimension_scores(
            db,
            p.id,
            [{"dimension_key": k, "score": v, "explanation": "初始说明"} for k, v in DIMS.items()],
        )
        db.commit()

        args = dict(
            viewpoint_id=101,
            dimensions=["reassurance_need"],
            summary="我觉得再忙也要留出说话的时间",
            directions={"reassurance_need": {"direction": "up", "strength": "mild"}},
        )

        # 1. 低置信度且用户没覆盖 → 拦住
        try:
            profile_service.enrich_from_viewpoint(db, uid, confidence=0.3, **args)
            check("置信度 0.3 且未覆盖 → 拒绝", False, "没有抛异常")
        except ValueError as e:
            check("置信度 0.3 且未覆盖 → 拒绝（70003）", str(e) == "70003", str(e))

        # 2. 低置信度但用户明确覆盖 → 放行（AI 只是建议，决定权在用户）
        r = profile_service.enrich_from_viewpoint(db, uid, confidence=0.3, force=True, **args)
        check("用户明确覆盖 → 计入成功（v2）", r["version"] == 2, r.get("version"))
        check("返回撤回目标 = 补充前的版本",
              r.get("previous_profile_id") == p.id, r.get("previous_profile_id"))

        # 3. 同一条观点重复计入 → 幂等拒绝（否则连点几下历史列表就脏了）
        try:
            profile_service.enrich_from_viewpoint(db, uid, confidence=0.9, force=True, **args)
            check("同一观点重复计入 → 拒绝", False, "没有抛异常")
        except ValueError as e:
            check("同一观点重复计入 → 拒绝（70008）", str(e) == "70008", str(e))

        # 4. 换一条观点仍可继续计入（幂等只挡「当前版本就是它派生」这一种）
        args2 = dict(args)
        args2["viewpoint_id"] = 102
        r2 = profile_service.enrich_from_viewpoint(db, uid, confidence=0.9, force=True, **args2)
        check("换成另一条观点可以继续计入（v3）", r2["version"] == 3, r2.get("version"))
    finally:
        db.close()


def main():
    avail = mysql_available()
    if not avail:
        print("MySQL 不可用，跳过（不伪装通过）")
        return 0
    print("MySQL %s / %s" % avail)

    test_memory_type_whitelist()
    test_compose_text()

    with MysqlHarness("vpmem") as h:
        test_memory_by_source(h)
        test_enrich_force_and_idempotent(h)

    print("\n断言 %d 项" % CHECKS)
    if FAILURES:
        print("失败 %d 项：" % len(FAILURES))
        for f in FAILURES:
            print("  - %s" % f)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
