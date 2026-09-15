# -*- coding: utf-8 -*-
"""AI 内容安全加固验证脚本（2026-09-15）。

覆盖四块：
1. 扩充词库加载与存量兼容（老词一个不能丢）
2. 命中详情 API（level + hits，供审计用）
3. 新增词的正例 / 常见口语的误报检查
4. 审计落库（safety_repo.log_event，SQLite 独立会话）
5. 限流配置开关与按用户限流键

运行：python tests/test_safety_hardening.py
"""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sqlalchemy import BigInteger, Integer, create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.database import Base
from app.services import safety_service, safety_words
from app.services.safety_service import (
    check_input_safety,
    check_output_safety,
    check_input_safety_detail,
    check_output_safety_detail,
    get_safety_response,
)

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}  {detail}")
        FAILURES.append(name)


def case_wordlib():
    print("\n[1] 词库加载与存量兼容")
    words = safety_service.SAFETY_KEYWORDS
    total = sum(len(v.get("strong", [])) + len(v.get("weak", [])) for v in words.values())
    check("四个风险等级齐全", set(words) == {
        "heated_conflict", "manipulation_risk", "abuse_risk", "self_harm_risk",
    }, str(list(words)))
    check(f"词库规模 >= 150（实际 {total}）", total >= 150)
    # 存量词一个不能丢
    old = [
        ("heated_conflict", "strong", "摔东西"), ("heated_conflict", "weak", "你总是"),
        ("manipulation_risk", "strong", "查手机"), ("manipulation_risk", "strong", "要挟"),
        ("abuse_risk", "strong", "家暴"), ("abuse_risk", "strong", "没收手机"),
        ("self_harm_risk", "strong", "不想活"), ("self_harm_risk", "strong", "轻生"),
    ]
    for level, kind, kw in old:
        check(f"存量词保留 {level}.{kind}.{kw}", kw in words[level][kind])
    check("内置词库模块与生效词库同源", words is safety_words.WORDS or words == safety_words.WORDS)


def case_detail():
    print("\n[2] 命中详情 API")
    level, hits = check_input_safety_detail("你去死吧，废物")
    check("混合强信号取最高等级 self_harm", level == "self_harm_risk", level)
    check("命中词列表非空且含「去死」", "去死" in hits, str(hits))

    level2, hits2 = check_input_safety_detail("你总是这样，你从来不考虑我")
    check("双弱信号共现 → heated_conflict", level2 == "heated_conflict", level2)
    check("弱信号命中词也被收集", len(hits2) >= 2, str(hits2))

    level3, _ = check_input_safety_detail("今天天气不错，一起去散步吧")
    check("正常语句 normal", level3 == "normal", level3)

    # 旧接口行为不变（老调用方兼容）
    check("check_input_safety 仍返回纯等级", check_input_safety("滚") == "heated_conflict")
    check("check_output_safety 对输出同样生效",
          check_output_safety("我建议你威胁他一下") == "manipulation_risk")
    check("安全话术非空", all(get_safety_response(k) for k in
          ["heated_conflict", "manipulation_risk", "abuse_risk", "self_harm_risk"]))


def case_new_words_and_false_positive():
    print("\n[3] 新增词正例 + 口语误报检查")
    # 新增词正例（每类抽 3 个）
    positives = [
        ("老死不相往来", "heated_conflict"),
        ("发到网上", "manipulation_risk"),
        ("翻你手机", "abuse_risk"),
        ("生无可恋", "self_harm_risk"),
        ("扇耳光", "abuse_risk"),
        ("阴阳怪气", "manipulation_risk"),
    ]
    for kw, expect in positives:
        level, _ = check_input_safety_detail(f"他就是{kw}的样子")
        check(f"新增词命中 {kw} → {expect}", level == expect, level)

    # 误报检查：这些句子不该被判为 abuse/self_harm（最高等级）
    # 注：「我感冒了正在吃药」是**已知存量误报**（吃药是存量强信号），
    #     按只增不改原则本轮不调整，留待词库精细化时处理。
    false_positive_guards = [
        "我们约定好一起去天台看日落",  # 天台语境歧义
        "同事捅了娄子大家一起扛",      # 「捅」单字已被移除
        "这是我最后一次警告你",        # 「最后一次」已被移除
        "周末吃了顿火锅，味道好极了",  # 纯正常
    ]
    for text in false_positive_guards:
        level, hits = check_input_safety_detail(text)
        check(f"误报守卫「{text[:14]}…」≠ abuse/self_harm",
              level not in ("abuse_risk", "self_harm_risk"), f"{level} {hits}")


def case_audit():
    print("\n[4] 审计落库（SQLite 独立会话）")
    from app.repositories import safety_repo
    from app.models.safety_event import SafetyEvent

    # SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）。
    # 建表前把 BigInteger 主键类型替换为 Integer（同 test_forgot_password 的修法）。
    for _t in Base.metadata.tables.values():
        for _c in _t.columns:
            if _c.primary_key and isinstance(_c.type, BigInteger):
                _c.type = Integer()

    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    TestSession = sessionmaker(bind=engine)

    # 劫持 repo 的 SessionLocal → 测试库（log_event 自带会话，必须替换其来源）
    safety_repo.SessionLocal = TestSession

    safety_repo.log_event(4, "chat", "input", "self_harm_risk", ["去死", "废物"])
    safety_repo.log_event(None, "mediation", "output", "heated_conflict", ["滚"])
    # 写失败不应抛出：故意传不可序列化对象之外的场景——正常路径之上再验证容错
    safety_repo.log_event("not-an-int", "chat", "input", "heated_conflict", ["x"])  # int() 会失败→吞掉

    db = TestSession()
    rows = db.query(SafetyEvent).order_by(SafetyEvent.id).all()
    check("成功落库 2 条（第 3 条 user_id 非法被吞）", len(rows) == 2, str(len(rows)))
    if len(rows) >= 2:
        check("字段正确 user_id=4", rows[0].user_id == 4)
        check("hit_keywords 为 JSON 数组", json.loads(rows[0].hit_keywords) == ["去死", "废物"])
        check("匿名事件 user_id 为空", rows[1].user_id is None)
        check("source 区分 input/output", rows[0].source == "input" and rows[1].source == "output")
    db.close()

    # get_recent_events 可用
    db = TestSession()
    check("get_recent_events 返回 2 条", len(safety_repo.get_recent_events(db)) == 2)
    db.close()


def case_rate_limit():
    print("\n[5] 限流配置与用户键")
    from app.core import config
    from app.core.limiter import get_request_key, limiter
    from app.security.jwt import create_access_token

    check("默认限流值为 20/hour", config.AI_RATE_LIMIT == "20/hour", config.AI_RATE_LIMIT)

    from app.api.v1.couple.ai import _ai_limit
    check("开启时返回真装饰器", _ai_limit() is not None and hasattr(_ai_limit(), "__call__"))

    token = create_access_token(7)

    class FakeClient:
        host = "127.0.0.1"

    class FakeReq:
        headers = {"Authorization": f"Bearer {token}"}
        client = FakeClient()
    check("带 token → 按用户键", get_request_key(FakeReq()) == "user:7", get_request_key(FakeReq()))

    class FakeReq2:
        headers = {}
        client = FakeClient()
    key2 = get_request_key(FakeReq2())
    check("无 token → IP 兜底键", isinstance(key2, str) and not key2.startswith("user:"), key2)


def main() -> int:
    case_wordlib()
    case_detail()
    case_new_words_and_false_positive()
    case_audit()
    case_rate_limit()
    print("\n========== 结果 ==========")
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
