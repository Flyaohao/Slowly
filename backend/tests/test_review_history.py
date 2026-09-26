# -*- coding: utf-8 -*-
"""整改契约 §8.7：关系复盘修复（SQLite 内存库，不需要 MySQL / API Key）。

## 覆盖什么

1. **禁止覆盖式只存最新一次**：连做两次复盘 → 留档表两行、历史列表两条，
   且 `ai_generation` 也各占一行（target_type='review'，不再互相覆盖）；
2. **契约要求的六项**：事件摘要 / 触发点 / 双方需求 / 建议表达 / 发生时间 /
   后续结果——全部是真实列，落库后可逐项断言；
3. **「重新复盘一次」能恢复输入状态**：详情端点回传 description / context，
   前端据此还原表单（后端只保证数据在）；
4. **稍后回访任务**：复盘完成后 recall_at 到点 → 首页出卡；回填结果 → 卡消失；
5. **越权**：他人复盘详情 / 回填结果 → 70001（当作不存在，不泄露存在性）；
6. **失败/中断的复盘不排回访**（否则首页会催用户回访一次空复盘）。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_review_history.py
"""
import os
import sys
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

from sqlalchemy import BigInteger, Integer, create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app.core.database import Base  # noqa: E402
import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata

# SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()

from app.models.user import User  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.models.relationship_review import AiRelationshipReview  # noqa: E402
from app.models.ai import AiScene  # noqa: E402
from app.models.ai_generation import AiGeneration  # noqa: E402
from app.repositories import relationship_review_repo  # noqa: E402

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []

A_ID = 101  # 我
B_ID = 202  # 伴侣
C_ID = 303  # 无关第三方


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def _seed(db):
    db.add_all([
        User(id=A_ID, email="a@example.com", password_hash="x", has_couple=True),
        User(id=B_ID, email="b@example.com", password_hash="x", has_couple=True),
        User(id=C_ID, email="c@example.com", password_hash="x", has_couple=True),
    ])
    db.flush()
    db.add(CoupleRelation(id=9, user_a_id=A_ID, user_b_id=B_ID, status="active"))
    # prepare_relationship_review 先查场景注册表：缺行会直接 50001，
    # 那测的就不是「复盘留档」而是「夹具没搭好」。
    db.add(AiScene(id=1, scene_key="relationship_review", name="关系复盘"))
    db.commit()


def _fresh_client(db, uid: int):
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.database import get_db
    from app.core.dependencies import get_current_user

    current = type("U", (), {"id": uid})()
    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_current_user] = lambda: current
    return TestClient(app, raise_server_exceptions=False), app, get_db, get_current_user


def _drop_client(app, get_db, get_current_user):
    app.dependency_overrides.pop(get_db, None)
    app.dependency_overrides.pop(get_current_user, None)


def _make_review(db, *, description, context=None, status="done", user_id=A_ID, **fields):
    """直接落一条「已完成」的复盘留档（绕过流式引擎，测读写语义）。"""
    row = relationship_review_repo.create_review(
        db,
        user_id=user_id,
        relation_id=9,
        description=description,
        context=context,
        event_time=fields.pop("event_time", None),
    )
    relationship_review_repo.save_review_result(
        db,
        review_id=row.id,
        status=status,
        content=fields.pop("content", "正文"),
        structured_output=fields.pop("structured_output", None),
        risk_level=fields.pop("risk_level", "normal"),
        **fields,
    )
    db.commit()
    db.refresh(row)
    return row


# --------------------------------------------------------------------- #
# 1. 追加而非覆盖（§8.7 第一条）
# --------------------------------------------------------------------- #
def t_append_not_overwrite(db):
    print("\n[1] 复盘追加保存 / 历史可回看")
    first = _make_review(
        db,
        description="第一次：因为回消息慢吵架",
        summary="第一次摘要",
        trigger="等太久",
        own_need="被回应",
        partner_need="空间",
        suggested_expression="我想十分钟内收到一句在忙",
    )
    second = _make_review(
        db,
        description="第二次：周末安排分歧",
        summary="第二次摘要",
        trigger="计划不一致",
        own_need="被重视",
        partner_need="自主",
        suggested_expression="我们周六上午对一下这周安排",
    )
    check("两次复盘是两条留档（非覆盖）", first.id != second.id, "%s vs %s" % (first.id, second.id))
    check(
        "库里共 2 行",
        db.query(AiRelationshipReview).filter(AiRelationshipReview.user_id == A_ID).count() == 2,
    )

    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.get("/api/v1/couple/ai/review/history")
        body = r.json()
        data = body.get("data") or {}
        check(
            "GET /review/history → 200 code 0",
            r.status_code == 200 and body.get("code") == 0,
            "%s %s" % (r.status_code, str(body)[:160]),
        )
        check("历史共 2 条", data.get("total") == 2, str(data.get("total")))
        ids = [i.get("review_id") for i in (data.get("items") or [])]
        check("历史倒序（最新在前）", ids[:2] == [second.id, first.id], str(ids))
        check(
            "列表项带摘要与发生时间",
            all(i.get("summary") and i.get("event_time") for i in data.get("items")),
            str(data.get("items")),
        )

        # 详情：六项 + 恢复输入所需的原文
        r2 = client.get("/api/v1/couple/ai/review/%d" % first.id)
        d2 = (r2.json().get("data") or {})
        for key in ("review_id", "description", "context", "summary", "trigger",
                    "own_need", "partner_need", "suggested_expression", "event_time",
                    "outcome", "status", "content", "structured_output"):
            check("详情含 %s" % key, key in d2, str(sorted(d2)))
        check("description 回传（「重新复盘一次」恢复输入的前提）",
              d2.get("description") == "第一次：因为回消息慢吵架", str(d2.get("description")))
        check("六项逐项落库：摘要", d2.get("summary") == "第一次摘要", str(d2.get("summary")))
        check("六项逐项落库：触发点", d2.get("trigger") == "等太久", str(d2.get("trigger")))
        check("六项逐项落库：我方需求", d2.get("own_need") == "被回应", str(d2.get("own_need")))
        check("六项逐项落库：对方需求", d2.get("partner_need") == "空间", str(d2.get("partner_need")))
        check("六项逐项落库：建议表达",
              d2.get("suggested_expression") == "我想十分钟内收到一句在忙",
              str(d2.get("suggested_expression")))
        check("六项逐项落库：发生时间非空", bool(d2.get("event_time")), str(d2.get("event_time")))
    finally:
        _drop_client(app, g_db, g_user)


# --------------------------------------------------------------------- #
# 2. prepare 走追加表 + ai_generation 不再互相覆盖
# --------------------------------------------------------------------- #
def t_prepare_uses_history(db):
    print("\n[2] prepare_relationship_review → 留档行 + 不覆盖的 generation")
    from app.services import ai_service

    before_reviews = db.query(AiRelationshipReview).count()
    before_gens = db.query(AiGeneration).count()

    prepared = ai_service.prepare_relationship_review(
        db, A_ID, 9, "第三次：因为家务分工冷战了两天", context="最近加班多"
    )
    db.commit()
    review_id = prepared.get("review_id")
    check("prepared 带 review_id", isinstance(review_id, int) and review_id > 0, str(review_id))
    check("on_saved 回调已挂上", callable(prepared.get("on_saved")))
    check("留档表 +1 行", db.query(AiRelationshipReview).count() == before_reviews + 1)
    check(
        "ai_generation 的 target 指向本条留档",
        prepared.get("target_type") == "review" and prepared.get("target_id") == review_id,
        "%s %s" % (prepared.get("target_type"), prepared.get("target_id")),
    )
    check("ai_generation +1 行", db.query(AiGeneration).count() == before_gens + 1)

    # 再开一次：留档与 generation 各再 +1（此前 target_type='none' 时 generation 会覆盖）
    prepared2 = ai_service.prepare_relationship_review(db, A_ID, 9, "第四次：因为手机看成瘾")
    db.commit()
    check("第二次 prepare 留档 +1", db.query(AiRelationshipReview).count() == before_reviews + 2)
    check("第二次 prepare generation 也 +1（不覆盖）",
          db.query(AiGeneration).count() == before_gens + 2,
          str(db.query(AiGeneration).count()))
    check("两次 review_id 不同", prepared2.get("review_id") != review_id)


# --------------------------------------------------------------------- #
# 3. on_saved 回填六项 + 排定回访
# --------------------------------------------------------------------- #
def t_on_saved_backfills(db):
    print("\n[3] 流式结束回填（on_saved）与稍后回访任务")
    from app.services import relationship_review_service as svc

    row = relationship_review_repo.create_review(
        db, user_id=A_ID, relation_id=9, description="第五次：因为见家长的事吵了一架"
    )
    db.commit()
    check("新留档初始无回访", row.recall_at is None, str(row.recall_at))

    # 引擎落库后调 on_saved：模拟正常完成
    from app.services.ai_service import _review_mirror

    _review_mirror(row.id, db.get_bind())(
        status="done",
        content="正文内容",
        structured={
            "summary": "摘要",
            "trigger": "触发点",
            "own_need": "我要的",
            "partner_need": "TA 要的",
            "misunderstanding": "误解处",
            "escalation_phrases": ["你从来不在乎我"],
            "deescalation_phrases": ["我们停一下"],
            "next_time_scripts": ["要不我们先各自冷静十分钟", "第二句"],
        },
        risk_level="normal",
    )
    db.expire_all()
    got = db.query(AiRelationshipReview).filter(AiRelationshipReview.id == row.id).first()
    check("回填 status=done", got.status == "done", str(got.status))
    check("回填六项：摘要", got.summary == "摘要", str(got.summary))
    check("建议表达取 next_time_scripts 首条",
          got.suggested_expression == "要不我们先各自冷静十分钟", str(got.suggested_expression))
    check("结构化全量保留（降温话术可回读）",
          (got.structured_output or {}).get("escalation_phrases") == ["你从来不在乎我"],
          str(got.structured_output))
    check("发生时间不为空（缺省回落创建时间）", got.event_time is not None, str(got.event_time))
    check("完成后排定回访", got.recall_at is not None, str(got.recall_at))

    # 未到点 → 首页不出卡
    check("未到点不选为回访任务", relationship_review_repo.pick_due_recall(db, A_ID) is None)

    # 到点 → 首页出卡
    relationship_review_repo.set_recall_at(db, row.id, datetime.now() - timedelta(minutes=1))
    db.commit()
    due = relationship_review_repo.pick_due_recall(db, A_ID)
    check("到点后选为回访任务", due is not None and due.id == row.id, str(due))

    from app.services import home_service

    cards = home_service.get_home_data(db, A_ID).get("task_cards") or []
    recall_cards = [c for c in cards if c.get("type") == "review_recall"]
    check("首页出复盘回访卡", len(recall_cards) == 1, str([c.get("type") for c in cards]))
    if recall_cards:
        check("回访卡 id = review_id", recall_cards[0].get("id") == row.id, str(recall_cards[0]))
        check("回访卡 title", recall_cards[0].get("title") == "上次复盘后来怎么样了？",
              str(recall_cards[0].get("title")))

    # 回填结果 → 卡消失
    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.post(
            "/api/v1/couple/ai/review/%d/outcome" % row.id,
            json={"outcome": "当天晚上就说开了，她其实也在等我说"},
        )
        body = r.json()
        check("POST outcome → code 0", body.get("code") == 0, str(body)[:200])
        check("响应回传结果", (body.get("data") or {}).get("outcome") == "当天晚上就说开了，她其实也在等我说",
              str(body.get("data")))
    finally:
        _drop_client(app, g_db, g_user)

    check("回填后不再是待回访", relationship_review_repo.pick_due_recall(db, A_ID) is None)
    cards2 = home_service.get_home_data(db, A_ID).get("task_cards") or []
    check("回访卡消失", not [c for c in cards2 if c.get("type") == "review_recall"],
          str([c.get("type") for c in cards2]))


# --------------------------------------------------------------------- #
# 4. 失败/中断的复盘不排回访
# --------------------------------------------------------------------- #
def t_failed_review_no_recall(db):
    print("\n[4] 失败/中断的复盘不产生回访任务")
    from app.services import relationship_review_service as svc

    row = relationship_review_repo.create_review(
        db, user_id=A_ID, relation_id=9, description="第六次：模型挂了"
    )
    db.commit()
    svc.schedule_recall(db, row.id)
    db.commit()
    db.expire_all()
    got = db.query(AiRelationshipReview).filter(AiRelationshipReview.id == row.id).first()
    check("streaming 状态不排回访", got.recall_at is None, str(got.recall_at))

    # 失败但有半成品正文 → 仍不排（没有可回访的结论）
    relationship_review_repo.save_review_result(
        db, review_id=row.id, status="failed", content="半截话", structured_output=None,
        risk_level=None,
    )
    db.commit()
    svc.schedule_recall(db, row.id)
    db.commit()
    db.expire_all()
    got = db.query(AiRelationshipReview).filter(AiRelationshipReview.id == row.id).first()
    check("failed 状态不排回访", got.recall_at is None, str(got.recall_at))


# --------------------------------------------------------------------- #
# 5. 越权：他人复盘一律 70001
# --------------------------------------------------------------------- #
def t_ownership(db):
    print("\n[5] 越权保护：他人复盘当作不存在")
    other = _make_review(db, description="B 的复盘", summary="B 的摘要", user_id=B_ID)

    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.get("/api/v1/couple/ai/review/%d" % other.id)
        check("读他人复盘 → 非 0（70001）", r.json().get("code") == 70001, str(r.json())[:200])

        r2 = client.post(
            "/api/v1/couple/ai/review/%d/outcome" % other.id,
            json={"outcome": "越权写入尝试"},
        )
        check("写他人复盘结果 → 70001", r2.json().get("code") == 70001, str(r2.json())[:200])
    finally:
        _drop_client(app, g_db, g_user)

    db.expire_all()
    got = db.query(AiRelationshipReview).filter(AiRelationshipReview.id == other.id).first()
    check("越权写入未落库", got.outcome is None, str(got.outcome))

    # 我的历史里不含他人复盘
    mine = relationship_review_service_list(db)
    check("我的历史不含他人复盘", all(i["review_id"] != other.id for i in mine["items"]),
          str([i["review_id"] for i in mine["items"]]))


def relationship_review_service_list(db):
    from app.services import relationship_review_service as svc

    return svc.list_history(db, A_ID)


# --------------------------------------------------------------------- #
# 6. 不存在 / 缺参
# --------------------------------------------------------------------- #
def t_errors(db):
    print("\n[6] 错误路径")
    client, app, g_db, g_user = _fresh_client(db, A_ID)
    try:
        r = client.get("/api/v1/couple/ai/review/999999")
        check("不存在的复盘 → 70001", r.json().get("code") == 70001, str(r.json())[:200])

        r2 = client.post(
            "/api/v1/couple/ai/review/1/outcome", json={"outcome": ""}
        )
        check("空结果 → 10002（校验）", r2.json().get("code") == 10002, str(r2.json())[:200])

        # history 与 {review_id} 的路径优先级：history 不能被当成 int 解析
        r3 = client.get("/api/v1/couple/ai/review/history")
        check("history 路由未被 {review_id} 吞掉",
              r3.json().get("code") == 0, str(r3.json())[:200])
    finally:
        _drop_client(app, g_db, g_user)


def main() -> int:
    print("=" * 72)
    print("整改契约 §8.7：关系复盘历史 / 六项留档 / 回访任务")
    print("=" * 72)

    db = Session()
    try:
        _seed(db)
        t_append_not_overwrite(db)
        t_prepare_uses_history(db)
        t_on_saved_backfills(db)
        t_failed_review_no_recall(db)
        t_ownership(db)
        t_errors(db)
    finally:
        db.close()

    print("\n" + "=" * 72)
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
