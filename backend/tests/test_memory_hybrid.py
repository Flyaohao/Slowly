"""
P-C2 §7 验收：混合召回（向量 + 关键词通道 + RRF 融合）。

覆盖（spec §7，三项一条不少）：
  ① 专有名词用例：query 含「第一次看海」，关键词通道能召回该条；
     向量通道结构性置空（不调模型红线）时它是唯一召回路径，
     关掉关键词后该行不可达——立论点的可测形式。
  ② RRF 融合顺序：双通道共识行排第一，两条「不同通道命中」的行都保留，
     同为单通道时 rank 高者在前（纯函数，不碰库不调模型）。
  ③ 可见性反向断言：对方 private 记忆绝不出现——关键词扫描 / 主入口 /
     近因降级三层都断言（最容易漏，必须有）。

红线遵守：
  - 不调模型：`_get_collection` 置 None → 向量通道走「通道一为空」分支，
    全程零 embedding / LLM 网络调用；
  - 如需临时行：直接 db.add（不走 create_memory，避免其异步向量化），
    finally 按 id 单行删除，打印「基线行数 == 收尾行数」。

运行：cd backend && python tests/test_memory_hybrid.py
"""
import os
import sys
from datetime import datetime, timedelta

# 测试隔离：任何链路触发的 distill 后台线程都不允许写库（污染基线行数）
os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []

# 专有名词 query（整段 6 字，落在 _extract_terms 的 2~12 字短语窗口内）
QUERY = "第一次看海"
# 目标行：含完整短语 + 若干 2-gram；老到近因降级够不着（200 天前）
TEXT_TARGET = "我们第一次看海是在青岛，海风很咸他一直牵着手"
# 陪跑行：新鲜、不含短语的任何 2-gram（近因降级的对照）
TEXT_FILLER_1 = "今天点了外卖，味道一般般晚上没出门"
TEXT_FILLER_2 = "周末大扫除把阳台收拾干净了"
# 泄漏饵：对方 private、含完整短语、且最新——若可见性破了它必然出现
TEXT_PARTNER_PRIVATE = "我们第一次看海那天下着小雨，伞只有一把"


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


# ---------------------------------------------------------------------- #
# ① 专有名词：关键词通道召回
# ---------------------------------------------------------------------- #
def case_keyword_recalls_proper_noun(db, uid, rid, ids):
    print("\n[①] 专有名词：关键词通道召回「第一次看海」（向量通道结构性置空）")
    import app.services.memory_retrieval as mr

    id_target, id_f1, id_f2 = ids["target"], ids["filler1"], ids["filler2"]

    # 通道级直断言：关键词通道给目标行打分过门槛，陪跑行零分
    kw = mr._keyword_scores(db, rid, uid, QUERY)
    check(
        "关键词通道给专有名词行打分 ≥ 门槛",
        kw.get(id_target, 0.0) >= mr.KEYWORD_MIN_SCORE,
        f"score={kw.get(id_target)}",
    )
    leaked = {k: v for k, v in kw.items() if k in (id_f1, id_f2)}
    check(
        "无短语陪跑行不被误打分",
        id_f1 not in kw and id_f2 not in kw,
        str(leaked),
    )

    # 主入口：向量通道为空（_get_collection 已置 None），关键词独立召回
    items = mr.retrieve_memory_items(db, uid, rid, query=QUERY, limit=5)
    result_ids = [x["id"] for x in items]
    check("专有名词老记忆被召回", id_target in result_ids, str(result_ids))
    target = next((x for x in items if x["id"] == id_target), None)
    check(
        "召回分 ≥ 归一化阈值",
        (target or {}).get("score") is not None
        and (target or {}).get("score") >= mr.SCORE_THRESHOLD,
        str(target),
    )
    check(
        "召回走通道而非近因降级（陪跑行不在）",
        id_f1 not in result_ids and id_f2 not in result_ids,
        str(result_ids),
    )

    # A/B：关掉关键词通道 → 老记忆不可达，证明是关键词在起作用
    orig_kw = mr._keyword_scores
    mr._keyword_scores = lambda *a, **k: {}
    try:
        items_ab = mr.retrieve_memory_items(db, uid, rid, query=QUERY, limit=2)
        ids_ab = [x["id"] for x in items_ab]
        check(
            "关键词关掉后专有名词行不可达（非近因凑巧）",
            id_target not in ids_ab,
            str(ids_ab),
        )
    finally:
        mr._keyword_scores = orig_kw


# ---------------------------------------------------------------------- #
# ② RRF 融合顺序（纯函数：transient 行，不入库）
# ---------------------------------------------------------------------- #
def case_rrf_fusion_order():
    print("\n[②] RRF 融合顺序：双通道共识 > 单通道；两通道命中都保留")
    from app.models.ai import AiMemory

    import app.services.memory_retrieval as mr

    ts = datetime.now()

    def mk(mid: int, text: str) -> AiMemory:
        return AiMemory(
            id=mid,
            user_id=0,
            relation_id=0,
            memory_type="偏好",
            memory_text=text,
            visibility="private",
            importance=1,
            occurred_at=ts,
            created_at=ts,
        )

    both = mk(710001, "他记得我们所有的纪念日")       # 双通道命中
    vec_only = mk(710002, "周末我们去爬了山")         # 只在向量通道（rank2）
    kw_only = mk(710003, "她喜欢在雨天散步")          # 只在关键词通道（rank1）

    # 时间/重要度/场景/回声四项对三行完全同值 → 差异只来自 rrf
    fused = mr._fuse_scores(
        [both, vec_only, kw_only],
        {both.id: 0.90, vec_only.id: 0.80},   # 通道一：both rank1, vec rank2
        {kw_only.id: 0.55, both.id: 0.45},    # 通道二：kw rank1, both rank2
        echo_source="",
        scene_key=None,
    )

    check(
        "融合保留全部候选（两通道命中都在）",
        set(fused) == {both.id, vec_only.id, kw_only.id},
        str(fused),
    )
    check(
        "双通道共识排第一（归一化 top=1.0）",
        fused.get(both.id) == 1.0,
        str(fused),
    )
    check(
        "两条不同通道命中的行都过阈值（融合不饿死单通道）",
        fused.get(vec_only.id, 0.0) >= mr.SCORE_THRESHOLD
        and fused.get(kw_only.id, 0.0) >= mr.SCORE_THRESHOLD,
        str(fused),
    )
    check(
        "同为单通道：kw rank1 > vec rank2",
        fused.get(kw_only.id, 0.0) > fused.get(vec_only.id, 0.0),
        str(fused),
    )


# ---------------------------------------------------------------------- #
# ③ 可见性反向断言
# ---------------------------------------------------------------------- #
def case_partner_private_never_leaks(db, uid_a, rid, ids):
    print("\n[③] 可见性反向断言：对方 private 绝不出现（三层）")
    import app.services.memory_retrieval as mr

    id_target, id_partner = ids["target"], ids["partner"]

    # P 行含完整短语、若可见必然高分——扫描层即拦截
    kw = mr._keyword_scores(db, rid, uid_a, QUERY)
    check(
        "关键词扫描不含对方 private（它含短语，可见必然打分）",
        id_partner not in kw,
        f"泄漏分数={kw.get(id_partner)}",
    )

    items = mr.retrieve_memory_items(db, uid_a, rid, query=QUERY, limit=5)
    result_ids = {x["id"] for x in items}
    check(
        "主入口召回含自己的专有名词行（非空对照）",
        id_target in result_ids,
        str(sorted(result_ids)),
    )
    check(
        "主入口绝不含对方 private",
        id_partner not in result_ids,
        str(sorted(result_ids)),
    )

    # P 是最新行：若降级可见性破了，limit=10 内它排第一
    fallback = mr._fallback_recent(db, uid_a, rid, limit=10)
    fb_ids = {x["id"] for x in fallback}
    check(
        "近因降级也不含对方 private",
        id_partner not in fb_ids,
        str(sorted(fb_ids)),
    )


# ---------------------------------------------------------------------- #
# main：一次性夹具 + finally 单行清理 + 基线行数核对
# ---------------------------------------------------------------------- #
def main() -> int:
    print("=" * 72)
    print("P-C2 §7 混合召回：专有名词 / RRF 融合 / 可见性反向")
    print("=" * 72)

    from app.core.database import SessionLocal
    from app.models.ai import AiMemory
    from app.models.couple_relation import CoupleRelation
    import app.services.memory_retrieval as mr

    db = SessionLocal()
    created_ids = []
    baseline = None
    # 向量通道结构性置空（不调模型红线）：走「通道一为空」分支，
    # 关键词通道成为唯一召回路径——正是立论点的可测形式。
    orig_get_collection = mr._get_collection
    mr._get_collection = lambda: None
    try:
        baseline = db.query(AiMemory).count()
        rel = (
            db.query(CoupleRelation)
            .filter(CoupleRelation.status == "active")
            .first()
        )
        if rel is None:
            check("存在 active couple_relation 夹具", False, "本机无可用 relation")
            return finish()  # finally 仍会执行（无临时行可清）

        uid_a, uid_b, rid = rel.user_a_id, rel.user_b_id, rel.id
        now = datetime.now()
        stale = now - timedelta(days=200)

        # 四行直接 db.add：不走 create_memory（它会异步向量化 = 调模型），
        # 也不触发任何写入钩子 → chroma 零污染，无需向量清理。
        rows = [
            # target：专有名词老记忆（事件时间与入库时间都设老 → 近因够不着）
            dict(user_id=uid_a, visibility="couple", memory_type="事件",
                 memory_text=TEXT_TARGET, occurred_at=stale, created_at=stale),
            # 陪跑：新鲜、无短语
            dict(user_id=uid_a, visibility="couple", memory_type="偏好",
                 memory_text=TEXT_FILLER_1, occurred_at=now, created_at=now),
            dict(user_id=uid_a, visibility="couple", memory_type="偏好",
                 memory_text=TEXT_FILLER_2, occurred_at=now, created_at=now),
            # 泄漏饵：对方 private、含短语、最新
            dict(user_id=uid_b, visibility="private", memory_type="偏好",
                 memory_text=TEXT_PARTNER_PRIVATE, occurred_at=now, created_at=now),
        ]
        for spec in rows:
            m = AiMemory(relation_id=rid, **spec)
            db.add(m)
            db.flush()
            created_ids.append(m.id)
        db.commit()

        ids = {
            "target": created_ids[0],
            "filler1": created_ids[1],
            "filler2": created_ids[2],
            "partner": created_ids[3],
        }
        print(f"  造临时行 target={ids['target']} filler=[{ids['filler1']},"
              f"{ids['filler2']}] partner_private={ids['partner']}")

        case_keyword_recalls_proper_noun(db, uid_a, rid, ids)
        case_rrf_fusion_order()
        case_partner_private_never_leaks(db, uid_a, rid, ids)
    finally:
        # 单行删除（红线①：禁批量递归——按 id 逐条）
        for mid in created_ids:
            row = db.get(AiMemory, mid)
            if row:
                db.delete(row)
        db.commit()
        mr._get_collection = orig_get_collection
        if baseline is not None:
            after = db.query(AiMemory).count()
            print(f"  基线行数={baseline} 收尾行数={after} "
                  f"{'==' if baseline == after else '!= 污染!'}")
            check("基线行数 == 收尾行数", baseline == after,
                  f"{baseline} != {after}")
        db.close()

    # 收尾判断放在 finally **之后**——基线核对的 FAIL 必须计入 rc
    return finish()


def finish() -> int:
    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
