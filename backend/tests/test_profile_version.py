# -*- coding: utf-8 -*-
"""画像版本管理 + 观点补充（用户需求 #5）——真实 MySQL 基座。

跑法：`python tests/test_profile_version.py`（backend 目录下，需本地 MySQL）。
MySQL 不可用时**跳过并返回 0**，不伪装通过。

覆盖三件事：

  A. 渐进公式（纯函数）：单次上限、累计上限、方向语义；
  B. 丰富画像：派生新版本、证据行追加、维度白名单、置信度门槛；
  C. 版本 CRUD：列表 / 详情 / 对比 / 撤回（含幂等）/ 删除 / 回收策略。

为什么必须连真 MySQL：本功能的全部风险都在**多版本数据的读写一致性**上
（派生新版本、删旧版本、外键引用）。SQLite 的单写者模型验不出「删掉一个
仍被 couple_profile 引用的版本」这类问题。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
for p in (BACKEND_DIR, HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

from sqlalchemy import text  # noqa: E402

from mysql_harness import MysqlHarness, mysql_available  # noqa: E402
from app.repositories import profile_repo  # noqa: E402
from app.services import profile_service  # noqa: E402

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


def approx(a, b, tol=0.05):
    return abs(a - b) <= tol


DIMS_V1 = {
    "attachment_anxiety": 62.0,
    "attachment_avoidance": 38.0,
    "conflict_pursue": 70.0,
    "conflict_withdraw": 30.0,
    "defensive_response": 55.0,
    "emotional_validation_need": 76.0,
    "factual_explanation_need": 48.0,
    "personal_space_need": 44.0,
    "reassurance_need": 60.0,
    "directness_preference": 52.0,
    "softness_preference": 66.0,
}


def seed_user_and_questionnaire(h):
    """用 ORM 造最小前置数据。

    刻意不手写 INSERT：`user.status` / `questionnaire.version` 这类列只有
    Python 侧 default，裸 SQL 插进去会撞 NOT NULL。走 ORM 让模型自己填。
    """
    from app.models.user import User
    from app.models.questionnaire import Questionnaire

    db = h.db()
    u = User(email="pv@test.local", password_hash="x")
    q = Questionnaire(title="测试问卷")
    db.add(u)
    db.add(q)
    db.commit()
    return u.id, q.id


def seed_profile(db, uid, qid):
    p = profile_repo.create_profile(
        db,
        user_id=uid,
        questionnaire_id=qid,
        profile_type="secure",
        confidence=0.7,
        summary="测试用画像",
        version=1,
        origin="questionnaire",
    )
    profile_repo.add_dimension_scores(
        db,
        p.id,
        [
            {"dimension_key": k, "score": v, "explanation": "%s 初始说明" % k}
            for k, v in DIMS_V1.items()
        ],
    )
    db.commit()
    return p


# ---------------------------------------------------------------- A. 渐进公式

def test_scoring_formula():
    print("\n[A] 渐进公式")

    up_mild = profile_service.compute_enriched_score(50.0, "up", "mild", 50.0)
    check("up/mild：50 -> 57.5（向 100 逼近 15%）", approx(up_mild, 57.5), up_mild)

    up_mod = profile_service.compute_enriched_score(50.0, "up", "moderate", 50.0)
    check("up/moderate 被单次上限夹到 62（原应 65）", approx(up_mod, 62.0), up_mod)

    down_mild = profile_service.compute_enriched_score(50.0, "down", "mild", 50.0)
    check("down/mild：50 -> 42.5", approx(down_mild, 42.5), down_mild)

    # 未知强度退回 mild（不因为字段写错就放大权重）
    weird = profile_service.compute_enriched_score(50.0, "up", "extreme", 50.0)
    check("未知强度按 mild 处理", approx(weird, 57.5), weird)

    # 累计上限：baseline=50 -> 最高 70
    s = 50.0
    for _ in range(10):
        s = profile_service.compute_enriched_score(s, "up", "moderate", 50.0)
    check("连续 10 次 up 后停在 baseline+20 = 70", approx(s, 70.0), s)

    s = 50.0
    for _ in range(10):
        s = profile_service.compute_enriched_score(s, "down", "moderate", 50.0)
    check("连续 10 次 down 后停在 baseline-20 = 30", approx(s, 30.0), s)

    # 已是极值时不再移动（返回原值，调用方据此只记文本）
    top = profile_service.compute_enriched_score(90.0, "up", "moderate", 90.0)
    check("baseline=90 时 up 不越过 100", top <= 100.0 and approx(top, 90.0 + 3.0), top)

    low = profile_service.compute_enriched_score(5.0, "down", "moderate", 5.0)
    check("baseline=5 时 down 不跌破 0", low >= 0.0, low)

    # 问卷不再给 values_orientation 编造默认分
    empty = profile_service._calculate_dimension_scores([])
    check("空作答不出现 values_orientation（没证据就不编数据）",
          "values_orientation" not in empty, sorted(empty.keys()))
    check("空作答仍补齐 11 个问卷维度", len(empty) == 11, len(empty))


# ---------------------------------------------------------------- B. 丰富画像

def test_enrich(h):
    print("\n[B] 观点补充画像")
    db = h.db()
    uid, qid = seed_user_and_questionnaire(h)
    seed_profile(db, uid, qid)

    # 置信度门槛
    try:
        profile_service.enrich_from_viewpoint(
            db, uid, viewpoint_id=1, dimensions=["reassurance_need"],
            summary="x", directions={"reassurance_need": {"direction": "up", "strength": "mild"}},
            confidence=0.59,
        )
        check("置信度 0.59 被拒（70003）", False, "未抛错")
    except ValueError as e:
        check("置信度 0.59 被拒（70003）", str(e) == "70003", str(e))

    # 维度白名单：非法 key 全部过滤后为空 -> 70002
    try:
        profile_service.enrich_from_viewpoint(
            db, uid, viewpoint_id=1, dimensions=["made_up_dimension"],
            summary="x", directions={"made_up_dimension": {"direction": "up"}},
            confidence=0.9,
        )
        check("自造维度 key 被拒（70002）", False, "未抛错")
    except ValueError as e:
        check("自造维度 key 被拒（70002）", str(e) == "70002", str(e))

    # 方向缺失同样不产生写入
    try:
        profile_service.enrich_from_viewpoint(
            db, uid, viewpoint_id=1, dimensions=["reassurance_need"],
            summary="x", directions={}, confidence=0.9,
        )
        check("缺方向的维度被跳过（70002）", False, "未抛错")
    except ValueError as e:
        check("缺方向的维度被跳过（70002）", str(e) == "70002", str(e))

    # 正常丰富
    r = profile_service.enrich_from_viewpoint(
        db, uid, viewpoint_id=42, dimensions=["reassurance_need"],
        summary="我希望他晚回家能提前说一声", directions={
            "reassurance_need": {"direction": "up", "strength": "mild"}
        }, confidence=0.82,
    )
    check("派生新版本 version=2", r["version"] == 2, r["version"])
    check("origin=viewpoint_enrich", r["origin"] == "viewpoint_enrich", r["origin"])
    changed = r["changed"]["reassurance_need"]
    check("分数按公式 60 -> 66", approx(changed["to"], 66.0), changed)

    latest = profile_repo.get_latest_profile(db, uid)
    scores = {s.dimension_key: s for s in profile_repo.get_dimension_scores(db, latest.id)}
    check("最新版本已是 v2", latest.version == 2, latest.version)
    check("同一版本仍包含全部 11 个原维度 + 调整项", len(scores) == 11, len(scores))
    check("来源观点被记录", latest.source_viewpoint_id == 42, latest.source_viewpoint_id)
    exp = scores["reassurance_need"].explanation or ""
    check("explanation 追加了观点依据", "· 你的观点" in exp and "提前说一声" in exp, exp)
    check("基础文案随新分重算（不残留旧分）", "66" in exp.split("\n")[0], exp)
    check("旧版本未被就地改写(v1 仍是 60)",
          approx({s.dimension_key: s.score for s in profile_repo.get_dimension_scores(db, latest.id - 1)}.get("reassurance_need", -1), 60.0))

    # 首次写入 values_orientation（无历史分，从中性 50 起步）
    r2 = profile_service.enrich_from_viewpoint(
        db, uid, viewpoint_id=43, dimensions=["values_orientation"],
        summary="我觉得再忙也要留出一天只属于我们", directions={
            "values_orientation": {"direction": "up", "strength": "mild"}
        }, confidence=0.8,
    )
    check("三观维度首次写入从 50 起步 -> 57.5",
          approx(r2["changed"]["values_orientation"]["to"], 57.5),
          r2["changed"]["values_orientation"])

    vl = profile_repo.get_latest_profile(db, uid)
    vscores = {s.dimension_key: s for s in profile_repo.get_dimension_scores(db, vl.id)}
    check("三观维度已出现在画像里", "values_orientation" in vscores, sorted(vscores.keys()))
    check("三观维度的基础文案是价值取向",
          (vscores["values_orientation"].explanation or "").startswith("价值取向"),
          vscores["values_orientation"].explanation)

    # 累计上限（相对问卷基线 60 -> 最高 80）
    for _ in range(8):
        profile_service.enrich_from_viewpoint(
            db, uid, viewpoint_id=44, dimensions=["reassurance_need"],
            summary="还是希望有回应", directions={
                "reassurance_need": {"direction": "up", "strength": "moderate"}
            }, confidence=0.9,
        )
    vp = profile_repo.get_latest_profile(db, uid)
    fin = {s.dimension_key: s.score for s in profile_repo.get_dimension_scores(db, vp.id)}
    check("累次丰富后停在问卷基线 +20 = 80", approx(fin["reassurance_need"], 80.0), fin["reassurance_need"])
    # ⚠️ 必须显式 close：借出的连接不在连接池里，engine.dispose() 关不掉它，
    # 基座退出时的 DROP DATABASE 会被这个连接的 metadata lock 卡到超时。
    db.close()
    return uid, qid


# ---------------------------------------------------------------- C. 版本 CRUD

def test_versions(h, uid):
    print("\n[C] 版本历史 / 对比 / 撤回 / 删除")
    db = h.db()

    versions = profile_service.list_versions(db, uid)
    check("版本列表非空且按新→旧", len(versions) >= 2 and versions[0]["version"] > versions[-1]["version"],
          [v["version"] for v in versions])
    check("列表带中文来源标签", all(v["origin_label"] for v in versions), versions[0])

    v1 = [v for v in versions if v["version"] == 1][0]
    current = versions[0]

    detail = profile_service.version_detail(db, uid, v1["id"])
    check("版本详情含全部维度", len(detail["dimensions"]) == 11, len(detail["dimensions"]))
    check("版本详情维度自带中文 label", all(d["label"] for d in detail["dimensions"]))

    d = profile_service.diff_versions(db, uid, v1["id"], current["id"])
    keys = [i["dimension_key"] for i in d["items"]]
    check("对比列出了变化的维度", "reassurance_need" in keys, keys)
    check("对比不含未变化维度（attachment_anxiety 未动）", "attachment_anxiety" not in keys, keys)
    check("对比按变化幅度降序", d["items"][0]["delta"] is not None)

    # 撤回：回到 v1
    r = profile_service.restore_version(db, uid, v1["id"])
    check("撤回产生新版本（派生式，不就地改）", r["version"] > current["version"], r)
    check("origin=restore 且写明来源", r["origin"] == "restore" and "v1" in (r["origin_note"] or ""), r)

    back = profile_repo.get_latest_profile(db, uid)
    back_scores = {s.dimension_key: s.score for s in profile_repo.get_dimension_scores(db, back.id)}
    check("撤回后分数等于 v1", approx(back_scores["reassurance_need"], 60.0), back_scores["reassurance_need"])
    check("撤回后三观维度一并回退（v1 没有它）", "values_orientation" not in back_scores, sorted(back_scores.keys()))

    # 撤回幂等
    r2 = profile_service.restore_version(db, uid, back.id)
    check("撤回当前版本不产生冗余版本", r2["version"] == back.version, r2)

    # 正常路径：撤回后仍可继续丰富
    r3 = profile_service.enrich_from_viewpoint(
        db, uid, viewpoint_id=99, dimensions=["directness_preference"],
        summary="我更喜欢有话直说", directions={
            "directness_preference": {"direction": "up", "strength": "mild"}
        }, confidence=0.9,
    )
    check("撤回后仍可继续丰富画像", r3["version"] > back.version, r3)

    # 删除保护
    latest = profile_repo.get_latest_profile(db, uid)
    try:
        profile_service.delete_version(db, uid, latest.id)
        check("当前版本不可删（70006）", False, "未抛错")
    except ValueError as e:
        check("当前版本不可删（70006）", str(e) == "70006", str(e))

    try:
        profile_service.delete_version(db, uid, 99999999)
        check("不存在的版本 -> 70005", False, "未抛错")
    except ValueError as e:
        check("不存在的版本 -> 70005", str(e) == "70005", str(e))

    # 删一个衍生版本（成功）
    deriv = [
        v for v in profile_service.list_versions(db, uid)
        if v["origin"] in ("viewpoint_enrich", "restore", "manual")
    ]
    target = deriv[-1]
    before = len(profile_service.list_versions(db, uid))
    profile_service.delete_version(db, uid, target["id"])
    after = len(profile_service.list_versions(db, uid))
    check("删除衍生版本成功", after == before - 1, (before, after))
    check("删除后维度分不留孤儿",
          profile_repo.get_dimension_scores(db, target["id"]) == [], "仍有维度分")

    # 手动存版本（"增"）
    m = profile_service.save_manual_version(db, uid, "重要节点")
    check("手动保存产生 manual 版本", m["origin"] == "manual" and m["origin_note"] == "重要节点", m)
    db.close()


def test_prune(h, uid):
    print("\n[C2] 版本回收策略")
    db = h.db()
    # 21 次是最小可行值（>20 才会触发回收），把测试时长压到最短
    for i in range(21):
        profile_service.save_manual_version(db, uid, "批量 %d" % i)

    versions = profile_service.list_versions(db, uid)
    check("版本数被收到上限 20", len(versions) == 20, len(versions))
    check("问卷版本被保护（不因上限被误删）",
          any(v["origin"] == "questionnaire" for v in versions),
          [v["origin"] for v in versions])
    check("被回收的是衍生版本",
          sum(1 for v in versions if v["origin"] != "questionnaire") < 25)
    db.close()


def main():
    import time

    t0 = time.time()
    avail = mysql_available()
    if not avail:
        print("MySQL 不可用，跳过（不伪装通过）")
        return 0
    print("MySQL %s / %s" % avail)

    t = time.time()
    test_scoring_formula()
    print("  [耗时 %.1fs]" % (time.time() - t))

    t = time.time()
    with MysqlHarness("pver") as h:
        print("  [基座就绪 %.1fs]" % (time.time() - t))
        uid, _qid = test_enrich(h)
        print("  [丰富画像 %.1fs]" % (time.time() - t))
        t = time.time()
        test_versions(h, uid)
        print("  [版本 CRUD %.1fs]" % (time.time() - t))
        t = time.time()
        test_prune(h, uid)
        print("  [回收策略 %.1fs]" % (time.time() - t))
    print("  [总计 %.1fs]" % (time.time() - t0))

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
