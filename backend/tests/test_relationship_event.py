# -*- coding: utf-8 -*-
"""关系事件「写入门槛」验收（2026-09-27）。

本功能的产品约束是**事件必须带原因与作用方向才能落库**，所以测试聚焦这条
门槛：schema 与 service 两层都要拦住，且方向大小写要能收敛。

不连数据库：DB 链路（迁移、ORM、API 增删改查）由 `test_endpoint_smoke.py`
与真机走查覆盖。运行：`python tests/test_relationship_event.py`（backend 目录下）。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pydantic import ValidationError  # noqa: E402

from app.schemas.relationship_event_schema import (  # noqa: E402
    REASON_MIN_LEN,
    RelationshipEventCreate,
    RelationshipEventUpdate,
)
from app.services.relationship_event_service import validate_gate  # noqa: E402

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


def _create(**over):
    base = {
        "title": "因为回消息慢吵了一架",
        "event_time": "2026-09-20T21:30:00",
        "description": "我连着追问了三次，他直接不回了。",
        "reason": "暴露出我们在压力下会进入追问—退缩循环，是消极作用",
        "polarity": "negative",
    }
    base.update(over)
    return RelationshipEventCreate(**base)


def t_service_gate():
    print("[service] validate_gate")
    def _expect_gate_error(reason, polarity, name):
        raised, why = False, ""
        try:
            validate_gate(reason, polarity)
        except ValueError as e:
            raised, why = True, str(e)
        check(name, raised and why == "20001", "raised=%s why=%s" % (raised, why))

    _expect_gate_error("", "positive", "空原因被拒（20001）")
    _expect_gate_error("太短", "positive", "原因过短被拒")
    _expect_gate_error(" " * (REASON_MIN_LEN + 4), "positive", "纯空格原因被拒")
    _expect_gate_error("这是一条足够长的原因描述", "随便写", "非法作用方向被拒")
    _expect_gate_error("这是一条足够长的原因描述", None, "缺失作用方向被拒")

    try:
        text, direction = validate_gate("  这是一条足够长的原因描述  ", " POSITIVE ")
        check("合法输入通过并归一化", text == "这是一条足够长的原因描述" and direction == "positive",
              "%r/%r" % (text, direction))
    except ValueError as e:
        check("合法输入通过并归一化", False, str(e))


def t_schema_gate():
    print("[schema] RelationshipEventCreate")
    item = _create()
    check("合法事件构造成功", item.polarity == "negative", item.polarity)

    try:
        _create(polarity="POSITIVE")
        check("大写作用方向归一化为小写", True)
    except ValidationError as e:
        check("大写作用方向归一化为小写", False, str(e))

    for name, over in (
        ("空原因", {"reason": ""}),
        ("过短原因", {"reason": "无"}),
        ("纯空格原因", {"reason": " " * 20}),
        ("非法方向", {"polarity": "mixed"}),
        ("空标题", {"title": "   "}),
    ):
        try:
            _create(**over)
            check("schema 拒绝：%s" % name, False, "未抛 ValidationError")
        except ValidationError:
            check("schema 拒绝：%s" % name, True)

    print("[schema] RelationshipEventUpdate")
    try:
        RelationshipEventUpdate(reason="太短")
        check("update 拒绝过短原因", False, "未抛 ValidationError")
    except ValidationError:
        check("update 拒绝过短原因", True)

    try:
        RelationshipEventUpdate(reason=None, title=None)
        check("update 允许全空（部分更新）", True)
    except ValidationError as e:
        check("update 允许全空（部分更新）", False, str(e))


def main() -> int:
    print("关系事件写入门槛验收")
    t_service_gate()
    t_schema_gate()
    print("========== 结果 ==========")
    print("断言数：%d" % CHECKS)
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
