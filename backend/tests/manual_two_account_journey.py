# -*- coding: utf-8 -*-
"""B4.3 双账号人工旅程（真服务 + 真 worker + 真 LLM）。

## 为什么必须是「双账号」而不是单进程自演

调解的状态机、隐私边界、幂等约束全都建立在「两个人各自持有自己的 token、
各自只能看到自己那一侧的改写」之上。单账号自演会绕过全部权限判定
（`_get_session` 的 50002、`my_rewrite` 的按人取侧），等于没验。

## 走什么

账号 A（发起方）与账号 B（参与方）各自注册、绑定情侣关系，然后按 §8.5 走：

    邀请 → 接受 → 双方独立输入 → 各看自己的改写（**交叉核对不得泄露对方**）
    → 双方确认 → 总结 → 历史回看 → 高风险阻断 → end/continue 状态机

每一步都断言服务端**真实返回**的形状，而不是「请求没报错」。

## 关键断言（隐私红线）

- A 提交后 A 看到 `rewriting`，**不得**立刻看到双方确认页；
- `GET mediation/{id}` 对 A 返回的是 `my_rewrite`（A 自己那一侧），
  且**不得**含 B 的原始 messages / B 的改写；
- 未公开阶段 `rewrites` 数组也**只含我自己那一侧**（第二个出口）；
- 只有**双方都确认**后才产生总结。

## 用词约束（踩过的坑）

输入文本必须避开 `safety_service` 的安全词库：`submit_input` 在写库**之前**
先跑 `check_input_safety_detail`，命中即 HTTP 200 返回 `{"blocked": ...}`、
**一条消息都不落库**。于是「双方都提交过」永远不成立，会话停在 `inputting`、
任务表 0 行，日志里只表现为轮询超时——极易被误判成「LLM 慢 / worker 没跑」。

已知会命中的常见词：`吵架`（heated_conflict.strong）、`动手`（abuse_risk.strong）、
`不许`/`控制`（manipulation_risk.strong）。本脚本用词均经过实际校验。
`accepted_input()` 就是为此加的——它把「被拦截」与「真的提交了」分开。

## 可重跑

脚本会先 `reset_couple_state(relation_id)` 清掉这对固定账号的历史调解。
不清的话上一趟 `completed` 的场次仍占活跃槽位，`start` 幂等返回旧场次，
旧场次的 user 消息还会让新场次「一提交就凑满双方」——残留数据会被读成真缺陷。

## 运行

    cd backend && PYTHONUTF8=1 PYTHONIOENCODING=utf-8 \
        python tests/manual_two_account_journey.py

需要：后端在 127.0.0.1:8000 运行、`ai-task-worker` 在跑、`AI_API_KEY` 有效。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:8000/api/v1"
# 调解端点挂在 couple 模式前缀下（`api/v1/couple/mediation.py`，prefix=/ai/mediation，
# 再由 couple 子路由拼上 /couple）——不是 /ai/mediation。
MED = "/couple/ai/mediation"

FAILURES = []
CHECKS = 0


def check(name, cond, detail=""):
    global CHECKS
    CHECKS += 1
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)
    return bool(cond)


def call(method, path, token=None, body=None, timeout=60):
    """返回 (http_status, payload)。业务错误统一 HTTP 200 + envelope。"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer %s" % token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:  # noqa: BLE001
            return e.code, None
    except Exception as exc:  # noqa: BLE001
        return -1, {"_exc": repr(exc)}


def env(payload):
    """取 envelope 的 data 部分。"""
    if not payload:
        return None
    return payload.get("data")


def err_code(payload):
    """取业务错误码。

    `main.py` 的异常处理器把 `HTTPException(detail={"code": ...})` **归一化到
    envelope 顶层**，所以响应体是 `{"code": 50003, "message": ..., "data": None}`
    ——`code` 不在 `detail` 下面。这里两种形状都认，避免写死一种而假红。
    """
    if not isinstance(payload, dict):
        return None
    if isinstance(payload.get("code"), int):
        return payload["code"]
    detail = payload.get("detail")
    if isinstance(detail, dict) and isinstance(detail.get("code"), int):
        return detail["code"]
    return None


def accepted_input(payload):
    """提交是否**真的落库**了。

    契约（`mediation_service.submit_input` 首段）：命中安全词库时返回
    HTTP 200 + `{"blocked": True, "risk_level": ..., "safety_response": ...}`，
    **一条消息都不写**。只看「st==200 且 data 非空」会把这个拦截当成提交成功，
    之后双方永远凑不齐「都提交过」，会话停在 inputting、任务表 0 行，
    而日志里只表现为轮询超时——被误读成「LLM 慢」。必须单独判这一项。
    """
    d = env(payload) or {}
    if d.get("blocked"):
        return False, "被安全词库拦截 risk=%s hits=%s" % (
            d.get("risk_level"), d.get("safety_response", "")[:40],
        )
    return True, ""


def register(tag):
    """固定邮箱：已存在就登录，不存在才注册。

    为什么不用随机邮箱：`/auth/register` 有 slowapi `3/minute` 限流，
    随机邮箱意味着**每次重跑都消耗配额**，调试几轮就被 10029 挡住。
    固定账号让重跑变成纯登录（登录限流 5/minute，也更省）。
    """
    email = "b43_%s@example.com" % tag
    password = "Passw0rd!"
    st, p = call("POST", "/auth/login", body={"email": email, "password": password})
    if st == 200 and env(p) and env(p).get("access_token"):
        uid = None
        st2, p2 = call("GET", "/users/me", token=env(p)["access_token"])
        if env(p2):
            # `/users/me` 的用户 id 字段名是 `user_id`（不是 `id`）——
            # 写成 `id` 会静默拿到 None，后面凡是拿它当「我自己那一侧」判据的
            # 断言都会假红/假绿（本脚本的 rewrites 归属核对就吃过这个）。
            uid = env(p2).get("user_id")
        return uid, email, env(p)["access_token"]

    st, p = call("POST", "/auth/register", body={"email": email, "password": password})
    if st != 200 or not env(p) or not env(p).get("user_id"):
        print("  注册失败 %s: %s %s" % (email, st, p))
        return None, None, None
    uid = env(p)["user_id"]
    st, p = call("POST", "/auth/login", body={"email": email, "password": password})
    tok = env(p).get("access_token") if env(p) else None
    return uid, email, tok


def bind(a_tok, b_tok):
    """已绑定就跳过（重跑友好：固定账号第二次跑时关系已存在）。"""
    st, p = call("GET", "/couples/me", token=a_tok)
    info = env(p)
    # 契约：`data.status` 在顶层（不是嵌在 info 里）。重跑时关系已存在 → 直接跳过。
    if info and info.get("status") == "active":
        return True, "已绑定"
    st, p = call("POST", "/couples/invite", token=a_tok)
    code = env(p).get("invite_code") if env(p) else None
    if not code:
        return False, "invite_code 缺失 %s" % p
    st, p = call("POST", "/couples/bind", token=b_tok, body={"invite_code": code})
    return st == 200 and env(p) is not None, p


def reset_couple_state(relation_id):
    """把这对固定账号的调解历史清干净，让本趟旅程从零开始。

    为什么必须清：旅程是**可重跑**的（固定邮箱就是为了不烧注册限流），但
    调解会话会累积——上一趟 `completed` 的那一场仍持有活跃槽位，
    `start_mediation` 会**幂等返回旧场次**，于是这一趟实际是在旧会话上
    继续跑：旧场次的 user 消息还在库里（`_commit_generation_result` 把
    「双方是否提交」按 distinct user 判定，旧消息会让新场次一提交就凑满），
    旧场次的 `safety_blocked` 也会让新场次看起来"高风险"。
    实测过一次：第二场 `start` 返回的还是上一场的 session_id，
    结果「正常」场次被判 `safety_blocked`——那是残留数据，不是真缺陷。

    只清这一对 relation 的调解会话与其消息/任务，不动其他数据。
    """
    # 直跑本脚本时 sys.path[0] 是 tests/ 而非 backend/，`import app` 会失败
    # （同 `ai_task_worker` 需要 PYTHONPATH 的原因）。这里自己补上 backend 根。
    _root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if _root not in sys.path:
        sys.path.insert(0, _root)

    from app.core.database import SessionLocal
    from app.models import AiChatSession, AiChatMessage, AiTask

    db = SessionLocal()
    try:
        sids = [
            r[0] for r in db.query(AiChatSession.id)
            .filter(AiChatSession.relation_id == relation_id,
                    AiChatSession.session_type == "mediation")
            .all()
        ]
        if sids:
            db.query(AiChatMessage).filter(AiChatMessage.session_id.in_(sids)).delete(
                synchronize_session=False)
            db.query(AiTask).filter(AiTask.session_id.in_(sids)).delete(
                synchronize_session=False)
            db.query(AiChatSession).filter(AiChatSession.id.in_(sids)).delete(
                synchronize_session=False)
            db.commit()
        return sids
    finally:
        db.close()


def poll_until(session_id, token, wanted, timeout=240, interval=3):
    """轮询会话状态直到进入 wanted（集合）或超时。

    返回 `(最终状态, data)`——**data 已经是拆过 envelope 的 data**，
    调用方不要再 `env()` 一次（那样会得到 None，断言假红）。

    每轮打印一次当前状态——真实 LLM 生成是分钟级的，没有进度输出时
    「卡住」和「正在生成」在日志里长得一模一样。
    """
    deadline = time.time() + timeout
    last = None
    round_no = 0
    while time.time() < deadline:
        round_no += 1
        st, p = call("GET", MED + "/%d" % session_id, token=token)
        d = env(p)
        if d:
            last = d.get("mediation_status")
            print("      轮询 %d: status=%s failure=%s" % (round_no, last, d.get("failure")))
            if last in wanted:
                return last, d
        else:
            print("      轮询 %d: 无数据 st=%s p=%s" % (round_no, st, str(p)[:150]))
        time.sleep(interval)
    return last, None


def main():
    print("=" * 72)
    print("B4.3 双账号人工旅程（真服务 / 真 worker / 真 LLM）")
    print("=" * 72)

    # ---- 0. 两个账号 + 绑定 ----
    print("\n[0] 两个账号注册 + 绑定情侣关系")
    a_id, a_email, a_tok = register("a")
    b_id, b_email, b_tok = register("b")
    if not check("两个账号注册成功", a_tok and b_tok, "a=%s b=%s" % (a_tok, b_tok)):
        return 1
    ok, detail = bind(a_tok, b_tok)
    check("情侣关系绑定成功", ok, str(detail))
    if not ok:
        return 1
    st, p = call("GET", "/couples/me", token=a_tok)
    check("A 侧看到已绑定", env(p) is not None, str(p))

    # 清掉这对账号的历史调解，保证本趟从零开始（理由见 reset_couple_state）。
    rel_id = (env(p) or {}).get("relation_id") or (env(p) or {}).get("id")
    if rel_id:
        cleared = reset_couple_state(rel_id)
        print("      已清理历史调解会话：%s（relation=%s）" % (cleared, rel_id))

    # ---- 1. 发起邀请 ----
    print("\n[1] A 发起调解（start 幂等）")
    st, p = call("POST", MED + "/start", token=a_tok)
    d = env(p)
    check("start 返回会话", bool(d and d.get("session_id")), str(p))
    if not d or not d.get("session_id"):
        return 1
    sid = d["session_id"]
    print("      session_id=%s" % sid)

    st, p2 = call("POST", MED + "/start", token=a_tok)
    check("重复 start 返回同一场（幂等）", env(p2) and env(p2).get("session_id") == sid,
          "%s vs %s" % (env(p2), sid))

    # ---- 2. B 看到邀请并接受 ----
    print("\n[2] B 收到邀请并接受")
    st, p = call("GET", MED + "?role=invited", token=b_tok)
    items = (env(p) or {}).get("items") or []
    check("B 的待回应邀请里有这一场", any(i.get("session_id") == sid for i in items),
          str(items)[:200])

    st, p = call("POST", MED + "/%d/accept" % sid, token=b_tok)
    check("B 接受成功", st == 200 and env(p) is not None, "%s %s" % (st, p))

    st, p = call("GET", MED + "/%d" % sid, token=a_tok)
    check("接受后状态 = inputting", env(p) and env(p).get("mediation_status") == "inputting",
          str(env(p) and env(p).get("mediation_status")))

    # ---- 3. 双方独立输入 ----
    # 用词避开安全词库（见下方 [3.1]）：这里要验的是**状态机与隐私边界**，
    # 不是安全拦截——输入一旦命中词库就一条消息都不落库，后续全部走不通。
    print("\n[3] 双方独立输入（A 先提交，必须进等待态而非直接进确认页）")
    A_TEXT = "他每次争执就不说话，我觉得被忽视，很受伤。"
    B_TEXT = "她一争论就提以前的事，我不知道怎么回应，只能先躲开。"
    st, p = call("POST", MED + "/%d/input" % sid, token=a_tok, body={"content": A_TEXT})
    ok, why = accepted_input(p)
    check("A 提交成功（真的落库，未被安全词库拦截）", st == 200 and ok, "%s %s" % (st, why or p))
    st, p = call("GET", MED + "/%d" % sid, token=a_tok)
    stt = env(p) and env(p).get("mediation_status")
    check("A 提交后处于等待态（不得直接进确认）", stt in ("inputting", "rewriting"), str(stt))

    st, p = call("POST", MED + "/%d/input" % sid, token=b_tok, body={"content": B_TEXT})
    ok, why = accepted_input(p)
    check("B 提交成功（真的落库，未被安全词库拦截）", st == 200 and ok, "%s %s" % (st, why or p))

    # 双方都提交后服务端应已入队改写任务。这里立刻查一次任务表：
    # 若为 0 行，说明「双方提交」这一步没有真正推进状态机——后面无论轮询多久
    # 都不会有产出，必须在这一步就暴露，而不是等 poll 超时后误判成「LLM 慢」。
    st, p = call("GET", MED + "/%d" % sid, token=a_tok)
    stt = env(p) and env(p).get("mediation_status")
    check("双方提交后进入生成态（rewriting）", stt == "rewriting", str(stt))
    task_info = (env(p) or {}).get("task")
    check("服务端已下发任务信息（task 非空 = 确实入队）", bool(task_info), str(task_info))

    # ---- 4. 各看自己的改写（隐私红线） ----
    print("\n[4] 双方各自看到自己的改写（交叉核对不得泄露对方原文）")
    st, p = poll_until(sid, a_tok, {"confirming"})
    d = p  # poll_until 返回的已经是 data（已拆过 envelope），不能再 env() 一次
    check("双方提交后进入 confirming", bool(d), "最终状态 %s" % st)

    st, pa = call("GET", MED + "/%d" % sid, token=a_tok)
    st, pb = call("GET", MED + "/%d" % sid, token=b_tok)
    da, db = env(pa) or {}, env(pb) or {}

    a_mine = da.get("my_rewrite")
    b_mine = db.get("my_rewrite")
    check("A 拿到 my_rewrite", bool(a_mine), str(a_mine)[:120])
    check("B 拿到 my_rewrite", bool(b_mine), str(b_mine)[:120])
    check("双方拿到的改写不同（各是自己那一侧）", a_mine != b_mine,
          "A=%s B=%s" % (str(a_mine)[:60], str(b_mine)[:60]))

    # 原始输入不得互相返回。
    # 哨兵用**完整原句**而不是「被忽视」「提以前的事」这类 3-4 字短语：
    # 后者是自然中文，模型写对方改写时会自己再写一遍（实测 B 的 rewrite 里就
    # 出现了「你会觉得被忽视」），于是把「模型恰好用了同一个词」误报成泄露。
    # 要证的是**原话没有原样传过去**，整句才是与契约同义的哨兵。
    blob_a = json.dumps(da, ensure_ascii=False)
    blob_b = json.dumps(db, ensure_ascii=False)
    check("A 的响应里不含 B 的原始输入", B_TEXT not in blob_a, blob_a[:200])
    check("B 的响应里不含 A 的原始输入", A_TEXT not in blob_b, blob_b[:200])

    # `rewrites` 数组是同一份内容的**第二个出口**，必须单独查：
    # 它此前无条件把 inviter 那一侧放进数组，partner 在未公开的 `confirming`
    # 就能从 `rewrites[0].content` 读到对方改写——`my_rewrite` 分得很细，
    # 这个数组却漏了。只查 `my_rewrite`/原句会放它过去。
    def _leaked_rewrite(blob, own_role, my_uid):
        """未公开阶段，rewrites 里只允许出现**我自己**那一侧。"""
        if my_uid is None:
            # 没有自己的 id 就无法判定归属——静默返回 None 会让断言假绿
            # （"没找到泄露" 其实是 "没查"）。这里显式暴露。
            return {"_error": "my_uid 为空，无法判定归属"}
        try:
            obj = json.loads(blob)
        except Exception:  # noqa: BLE001
            return {"_error": "响应不是合法 JSON"}
        for r in (obj.get("rewrites") or []):
            if r.get("author_user_id") != my_uid:
                return r
        return None

    la = _leaked_rewrite(blob_a, "inviter", a_id)
    lb = _leaked_rewrite(blob_b, "partner", b_id)
    check("未公开时 A 的 rewrites 只含自己那一侧", la is None, str(la)[:160])
    check("未公开时 B 的 rewrites 只含自己那一侧", lb is None, str(lb)[:160])
    check("双方用户 id 已取到（归属判定的前提）", a_id is not None and b_id is not None,
          "a_id=%s b_id=%s" % (a_id, b_id))

    # ---- 5. 只有单方确认时不产生总结 ----
    print("\n[5] 单方确认不得产生总结（只有双方确认后才生成）")
    st, p = call("POST", MED + "/%d/confirm" % sid, token=a_tok, body={"confirmed": True})
    check("A 确认成功", st == 200 and env(p) is not None, "%s %s" % (st, p))
    st, p = call("GET", MED + "/%d" % sid, token=a_tok)
    stt = env(p) and env(p).get("mediation_status")
    check("A 单方确认后仍停在 confirming（不生成总结）", stt == "confirming", str(stt))

    st, p = call("POST", MED + "/%d/confirm" % sid, token=b_tok, body={"confirmed": True})
    check("B 确认成功", st == 200 and env(p) is not None, "%s %s" % (st, p))

    # ---- 6. 双方确认 → 总结 ----
    print("\n[6] 双方确认后生成总结")
    st, p = poll_until(sid, a_tok, {"completed", "summary_failed"}, timeout=300)
    d = p  # 同上：poll_until 返回的已是 data
    check("会话收口到 completed", st == "completed", str(st))
    if d:
        # 总结不在 GET 响应顶层——它作为 assistant 消息的 structured_output 下发
        # （`_message_payload` → `structured_output`）。顶层只有会话状态、改写、
        # 确认标记、任务可观察状态等；按顶层字段找 summary 会永远拿到 None。
        summaries = [
            m.get("structured_output") or {}
            for m in (d.get("messages") or [])
            if m.get("role") == "assistant"
            and (m.get("structured_output") or {}).get("common_points") is not None
        ]
        s = summaries[-1] if summaries else {}
        check("总结内容已生成（common_points / next_actions 非空）",
              bool(s.get("common_points")) and bool(s.get("next_actions")),
              "common=%s next=%s" % (s.get("common_points"), s.get("next_actions")))
        check("总结含 differences", bool(s.get("differences")), str(s.get("differences"))[:120])

    # ---- 7. 历史回看 ----
    print("\n[7] 历史回看（completed 可回看）")
    st, p = call("GET", MED + "?role=history", token=a_tok)
    items = (env(p) or {}).get("items") or []
    check("completed 出现在 A 的历史里", any(i.get("session_id") == sid for i in items),
          str(items)[:200])
    st, p = call("GET", MED + "/%d" % sid, token=a_tok)
    check("已完成的调解仍可 GET 回看", st == 200 and env(p) is not None, "%s" % st)

    # ---- 8. 高风险阻断 → safety_blocked ----
    #
    # 关键：这里**不能**写「动手 / 打我 / 不许我告诉别人」这类词。
    # `submit_input` 的词库前置闸是 strong 命中即拦（"动手"∈abuse_risk.strong、
    # "不许"∈manipulation_risk.strong），一旦命中就 HTTP 200 返回 blocked、
    # **一条消息都不落库**，永远走不到任务层，也就永远验不到 `safety_blocked`。
    # 要验的是**任务产出侧**的 `BLOCKING_RISK_LEVELS` 门控（P0-2/P0-4），
    # 所以用词必须干净、把高风险判定交给模型（这正是该门控要覆盖的路径）。
    print("\n[8] 高风险语境 → safety_blocked 独立终态（不产生调解产物）")
    st, p = call("POST", MED + "/start", token=a_tok)
    d = env(p) or {}
    sid2 = d.get("session_id")
    check("第二场 start 成功（completed 已释放槽位）", bool(sid2), str(d))
    if sid2:
        call("POST", MED + "/%d/accept" % sid2, token=b_tok)
        ok_a, why_a = accepted_input(call("POST", MED + "/%d/input" % sid2, token=a_tok,
                 body={"content": "我很害怕他，他一生气我就觉得他会伤害我，"
                                  "我不敢跟任何人讲我们之间的事。"})[1])
        ok_b, why_b = accepted_input(call("POST", MED + "/%d/input" % sid2, token=b_tok,
                 body={"content": "我知道我让她害怕了，我脾气上来就收不住，"
                                  "事后我也很后悔。"})[1])
        check("高风险场次双方输入也需真的落库", ok_a and ok_b, "%s / %s" % (why_a, why_b))
        stt, dd = poll_until(sid2, a_tok, {"safety_blocked", "confirming"}, timeout=300)
        if stt == "confirming":
            # 判定落在总结阶段，双方确认后应走安全阻断
            call("POST", MED + "/%d/confirm" % sid2, token=a_tok, body={"confirmed": True})
            call("POST", MED + "/%d/confirm" % sid2, token=b_tok, body={"confirmed": True})
            stt, dd = poll_until(sid2, a_tok, {"safety_blocked", "completed"}, timeout=300)
        check("高风险会话收口到 safety_blocked", stt == "safety_blocked", str(stt))
        if dd:
            # 安全阻断只落一条 assistant 消息（正文=安全资源，
            # structured_output={"risk_level", "safety_response"}），
            # **不落调解产物**。这两项都从该消息里读——顶层没有。
            asst = [
                m for m in (dd.get("messages") or []) if m.get("role") == "assistant"
            ]
            check("高风险下未产出调解改写/总结",
                  not any((m.get("structured_output") or {}).get("common_points")
                          or (m.get("structured_output") or {}).get("rewrite_a")
                          or (m.get("structured_output") or {}).get("rewrite_b")
                          for m in asst),
                  str([m.get("structured_output") for m in asst])[:200])
            risks = [m.get("risk_level") for m in asst if m.get("risk_level")]
            check("高风险消息带 risk_level", bool(risks), str(risks))
            check("风险等级属于阻断集合",
                  bool(risks) and risks[-1] in
                  ("manipulation_risk", "abuse_risk", "self_harm_risk", "unknown"),
                  str(risks))

        st, p = call("POST", MED + "/%d/next" % sid2, token=a_tok, body={"action": "continue"})
        code = err_code(p)
        check("safety_blocked 拒绝 continue（50003）", code == 50003, str(p))

        st, p = call("GET", MED + "?role=history", token=a_tok)
        items = (env(p) or {}).get("items") or []
        check("safety_blocked 不进 history 列表",
              not any(i.get("session_id") == sid2 for i in items), str(items)[:200])

        st, p = call("POST", MED + "/%d/next" % sid2, token=a_tok, body={"action": "pause"})
        code = err_code(p)
        check("pause 被明确拒绝（40001，不再假装成功）", code == 40001, str(p))

    # ---- 9. 状态机：completed 可 continue，重新占用槽位 ----
    print("\n[9] completed 可 continue 重开（重新占用活跃槽位）")
    st, p = call("POST", MED + "/%d/next" % sid, token=a_tok, body={"action": "continue"})
    check("completed 允许 continue", st == 200 and env(p) is not None, "%s %s" % (st, p))
    st, p = call("GET", MED + "/%d" % sid, token=a_tok)
    check("continue 后回到 inputting", env(p) and env(p).get("mediation_status") == "inputting",
          str(env(p) and env(p).get("mediation_status")))

    st, p = call("POST", MED + "/start", token=a_tok)
    check("槽位被占用时不得再开新场（幂等返回同一场或拒绝）",
          env(p) and env(p).get("session_id") == sid, str(p)[:200])

    print("\n" + "=" * 72)
    print("断言数：%d，失败：%d" % (CHECKS, len(FAILURES)))
    if FAILURES:
        print("失败项：")
        for f in FAILURES:
            print("  - %s" % f)
        print("=" * 72)
        return 1
    print("全部通过")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())