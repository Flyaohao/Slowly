# -*- coding: utf-8 -*-
"""军师观察卡（V1 聚合版）验收（2026-09-28）。

覆盖《军师主动观察》设计文档 §七（API 契约）与 §十（V1：无新 LLM 调用，
观察由既有数据拼装；已读走服务端 ack）：

- 未绑定 → 30005 信封；
- 冷启动（无素材）→ content=None；
- 已结算调解书 → 正文/引用/签名 + has_new 生命周期（ack → 有新 → ack）；
- 无调解但有画像摘要 → 用画像摘要兜底；调解优先于画像；
- 坏 settlement JSON / 正文超长截断 / ack 参数校验。

DB 链路用独立 SQLite 文件库（同 test_mediation_room.py 的做法；不测
迁移本身——迁移由 verify_migrations.py / 部署流程覆盖）。
运行：`python tests/test_observation.py`（backend 目录下）。
"""
import json
import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sqlalchemy import Integer, create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata
from app.core.database import Base  # noqa: E402
from app.models.couple_relation import CoupleRelation  # noqa: E402
from app.models.mediation_room import MediationRoom  # noqa: E402
from app.models.profile import CoupleProfile  # noqa: E402
from app.models.user import User  # noqa: E402
from app.services import observation_service as svc  # noqa: E402

_engine = create_engine(
    "sqlite:///./_observation_test.sqlite3",
    connect_args={"check_same_thread": False},
)
# SQLite 只对 INTEGER PRIMARY KEY 自增（同 hermetic_harness 的做法）
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, Integer().__class__) and str(_c.type) == "BIGINT":
            _c.type = Integer()
if os.path.exists("./_observation_test.sqlite3"):
    os.remove("./_observation_test.sqlite3")
Base.metadata.create_all(_engine)
TestSession = sessionmaker(bind=_engine, expire_on_commit=False)

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


def make_settled_room(db, relation, creator_id, name="周末安排之争",
                      result="reconciled", settlement=None, settled_at=None):
    room = MediationRoom(
        relation_id=relation.id,
        creator_user_id=creator_id,
        name=name,
        event_time="2026-09-27 晚上",
        cause_text="想各自安排，没提前说",
        process_text="临时改了计划，一方觉得被打乱",
        current_text="两天没好好说话",
        style_key="gentle_empathy",
        status="settled",
        result=result,
        settlement=json.dumps(settlement, ensure_ascii=False) if settlement else None,
        settled_at=settled_at or datetime(2026, 9, 27, 21, 4, 0),
    )
    db.add(room)
    db.commit()
    return room


def main():
    db = TestSession()
    try:
        ua = User(email="obs_a@test.local", password_hash="x", has_couple=True)
        ub = User(email="obs_b@test.local", password_hash="x", has_couple=True)
        db.add_all([ua, ub])
        db.commit()

        # ---- 未绑定 → 30005 ----
        print("[无关系]")
        try:
            svc.get_observation(db, ua.id)
            check("未绑定抛 30005", False, "未抛错")
        except ValueError as e:
            check("未绑定抛 30005", str(e) == "30005", str(e))

        rel = CoupleRelation(user_a_id=ua.id, user_b_id=ub.id, status="active")
        db.add(rel)
        db.commit()

        # ---- 冷启动 ----
        print("[冷启动]")
        out = svc.get_observation(db, ua.id)
        check("无素材 content=None", out["content"] is None, out)
        check("冷启动 has_new=False", out["has_new"] is False)

        # ---- 画像摘要兜底 ----
        print("[画像摘要]")
        # SQLite 默认不强制外键：画像行直接用占位 profile id（service 只读 summary）
        cp = CoupleProfile(
            relation_id=rel.id,
            user_a_profile_id=9001,
            user_b_profile_id=9002,
            summary="你们在「计划被打乱」时最容易起冲突，但都愿意先低头。",
        )
        db.add(cp)
        db.commit()
        out = svc.get_observation(db, ua.id)
        check("无调解时用画像摘要", out["content"] is not None and "军师目前对你们关系的理解" in out["content"], out)
        check("摘要路径无引用", out["citation"] is None)
        check("首次 has_new=True", out["has_new"] is True and out["signature"])

        # ---- ack 生命周期 ----
        print("[ack 生命周期]")
        svc.ack_observation(db, ua.id, out["signature"])
        out2 = svc.get_observation(db, ua.id)
        check("ack 后 has_new=False", out2["has_new"] is False, out2)
        try:
            svc.ack_observation(db, ua.id, "")
            check("空签名被拒", False, "未抛错")
        except ValueError as e:
            check("空签名被拒", str(e) == "100001", str(e))

        # ---- 已结算调解书（最高价值素材）----
        print("[调解书拼装]")
        make_settled_room(
            db, rel, ua.id,
            settlement={
                "result": "reconciled",
                "summary_text": "你们不是在争周末，是在争「我的时间有没有被尊重」。",
                "agreements": ["改计划至少提前一天商量", "有情绪先说出来"],
                "responsibilities": {"a": "主动先开口", "b": "把不满说出来"},
            },
        )
        out3 = svc.get_observation(db, ua.id)
        check("正文含调解名与结果", out3["content"] and "「周末安排之争」" in out3["content"] and "和解" in out3["content"], out3)
        check("正文含约定条数", "定下了 2 条约定" in out3["content"], out3)
        check("正文含军师一句话", "被尊重" in out3["content"], out3)
        check("引用指向调解室", out3["citation"] and out3["citation"]["type"] == "mediation_room"
              and out3["citation"]["title"] == "周末安排之争", out3)
        check("调解落地即有新", out3["has_new"] is True)
        check("正文不超 120 字", len(out3["content"]) <= 120, len(out3["content"] or ""))
        check("observed_at=结算时间", out3["observed_at"] == "2026-09-27T21:04:00", out3["observed_at"])

        # 调解优先于画像摘要
        check("调解优先于画像摘要", "军师目前对你们关系的理解" not in (out3["content"] or ""))

        # ---- 坏 settlement JSON 不炸接口 ----
        print("[容错]")
        room = MediationRoom(
            relation_id=rel.id, creator_user_id=ua.id, name="关于游戏时间",
            event_time="9月20日", cause_text="c", process_text="p", current_text="t",
            style_key="rational_review", status="settled", result="cold_war",
            settlement="{not-json", settled_at=datetime(2026, 9, 28, 1, 0, 0),
        )
        db.add(room)
        db.commit()
        out4 = svc.get_observation(db, ua.id)
        check("坏 JSON 回退到头部句", out4["content"] and "关于游戏时间" in out4["content"] and "冷战" in out4["content"], out4)
        check("取最近结算的房间", out4["citation"]["title"] == "关于游戏时间")

        # ---- 超长截断 ----
        print("[截断]")
        make_settled_room(
            db, rel, ua.id, name="超长军师一句话的调解",
            result="deferred",
            settlement={"result": "deferred", "summary_text": "长" * 200, "agreements": []},
            settled_at=datetime(2026, 9, 28, 2, 0, 0),
        )
        out5 = svc.get_observation(db, ua.id)
        check("超长截断到 120 并带省略号",
              len(out5["content"]) == 120 and out5["content"].endswith("…"),
              (len(out5["content"]), out5["content"][-3:]))

        # 双方都能读（user_b 同一观察）
        out_b = svc.get_observation(db, ub.id)
        check("双方看到同一观察", out_b["content"] == out5["content"] and out_b["has_new"] is True)

        print()
        print("共 %d 项，失败 %d 项" % (CHECKS, len(FAILURES)))
        if FAILURES:
            sys.exit(1)
    finally:
        db.close()
        _engine.dispose()
        if os.path.exists("./_observation_test.sqlite3"):
            try:
                os.remove("./_observation_test.sqlite3")
            except OSError:
                pass


if __name__ == "__main__":
    main()
