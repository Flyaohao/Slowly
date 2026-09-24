"""
P0-3 验收：统一记忆事件入口（memory_events）。

源覆盖（v1.2 裁决后）：
  实际接线 5 源：letter / museum / anniversary / dual / questionnaire
  diary 缺席：DiaryEntry 无 relation_id、AiMemory.relation_id NOT NULL，
             且日记为单身专属——见任务单差异 1 裁决，连跳过分支都不写
  practice 缺席：真机路径复现判定 b（双方各自 start→各 submit 自己的 record，
             status 永远停在 both_completed，summarized 不可达）——
             见差异 2 裁决与 §0.5.4#2，本轮不接、不修状态机

断言：
  a) 5 源各构造一个 MemoryEvent，mock LLM / 桩 db，distill_event 不抛异常、
     source 正确、AI 源带 focus、结构化源走 save_structured_memory
  b) focus="" 的旧路径 user 消息与改动前逐字节一致（防回归）
  c) 超长 content 截断到 1200 字
  d) 重复 anniversary 事件 → 只落一条记忆（幂等，复用同一套去重）
  e) 超短 content / 空 content → 不调模型、不落库、不抛异常

运行：cd backend && python tests/test_memory_events.py
"""
import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services import memory_service  # noqa: E402
from app.services.memory_events import (  # noqa: E402
    AI_SOURCE_FOCUS,
    CONTENT_MAX_LEN,
    STRUCTURED_SOURCES,
    MemoryEvent,
    distill_event,
)

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


class FakeDb:
    """只实现 distill_and_save / save_structured_memory 用到的方法。"""

    def __init__(self, existing=None):
        self.existing = existing
        self.added = []
        self.commits = 0
        self.rollbacks = 0

    def query(self, model):
        return self

    def filter(self, *criteria):
        return self

    def first(self):
        return self.existing

    def add(self, obj):
        self.added.append(obj)

    def flush(self):
        obj = self.added[-1]
        if getattr(obj, "id", None) is None:
            obj.id = len(self.added)
        if getattr(obj, "created_at", None) is None:
            obj.created_at = datetime(2026, 9, 24, 12, 0, 0)

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1


class FakeLlm:
    api_key = "test-key"

    def __init__(self, should_remember=True, memory_text="他希望周末有一天不被打扰"):
        self.should_remember = should_remember
        self.memory_text = memory_text
        self.calls = []

    def invoke_structured(self, messages, scene="unknown", **kwargs):
        self.calls.append({"scene": scene, "messages": messages})
        from app.schemas.ai_output import MemoryDistillOutput

        return MemoryDistillOutput(
            should_remember=self.should_remember,
            memory_type="核心诉求",
            memory_text=self.memory_text if self.should_remember else "",
        )


class _R:
    """轻量 memory 结果替身。"""

    def __init__(self, **kw):
        self.__dict__.update(kw)


def _make_event(source: str, content: str = "这是一段足够长的测试内容用于通过最小长度闸门") -> MemoryEvent:
    return MemoryEvent(
        source=source,
        source_id=1,
        user_id=4,
        relation_id=1,
        content=content,
        occurred_at=datetime(2026, 9, 24, 12, 0, 0),
        extra={"context": f"{source} 测试事件"},
    )


def _with_llm(fake):
    original = memory_service.distill_llm
    memory_service.distill_llm = fake
    return original


def _restore_llm(original):
    memory_service.distill_llm = original


# ---------------------------------------------------------------------- #
# a) 5 源冒烟
# ---------------------------------------------------------------------- #
def case_five_sources():
    print("\n[1] 实际接线 5 源冒烟（mock LLM / 桩 db）")
    for source in ("letter", "museum", "dual"):
        fake = FakeLlm()
        orig = _with_llm(fake)
        db = FakeDb()
        try:
            result = distill_event(_make_event(source), db=db)
            check(f"{source} 不抛异常", True)
            check(f"{source} 返回 list", isinstance(result, list), type(result).__name__)
            check(f"{source} 落库 1 条", len(result) == 1 and len(db.added) == 1,
                  f"result={result} added={len(db.added)}")
            check(f"{source} 带 focus 调 LLM", len(fake.calls) == 1)
            user_msg = fake.calls[0]["messages"][1]["content"]
            check(f"{source} user 消息含抽取重点", "抽取重点：" in user_msg, user_msg[:80])
            check(
                f"{source} focus 取自 AI_SOURCE_FOCUS",
                AI_SOURCE_FOCUS[source].split("——")[0][:6] in user_msg,
                user_msg,
            )
        except Exception as exc:
            check(f"{source} 不抛异常", False, repr(exc))
        finally:
            _restore_llm(orig)

    # 结构化源：不经 AI
    for source in ("anniversary", "questionnaire"):
        fake = FakeLlm()
        orig = _with_llm(fake)
        db = FakeDb()
        try:
            result = distill_event(_make_event(source, "在一起纪念日是 6 月 28 日"), db=db)
            check(f"{source} 不抛异常", True)
            check(f"{source} 落库 1 条", len(result) == 1 and len(db.added) == 1)
            check(f"{source} 不调 LLM", len(fake.calls) == 0, f"calls={len(fake.calls)}")
            check(f"{source} 在 STRUCTURED_SOURCES", source in STRUCTURED_SOURCES)
        except Exception as exc:
            check(f"{source} 不抛异常", False, repr(exc))
        finally:
            _restore_llm(orig)


def case_source_field_roundtrip():
    print("\n[2] source 字段原样保留")
    ev = _make_event("letter")
    fake = FakeLlm()
    orig = _with_llm(fake)
    try:
        distill_event(ev, db=FakeDb())
        check("event.source 未被改写", ev.source == "letter")
        # source 作为 scene_key 进入 user 消息的「场景：」行；
        # invoke_structured 的 scene 参数固定为 memory_distill（既有契约）
        user_msg = fake.calls[0]["messages"][1]["content"]
        check("user 消息场景行 == source", user_msg.startswith("场景：letter\n"), user_msg[:40])
        check("invoke scene 固定 memory_distill", fake.calls[0]["scene"] == "memory_distill")
    finally:
        _restore_llm(orig)


# ---------------------------------------------------------------------- #
# b) focus="" 逐字节防回归
# ---------------------------------------------------------------------- #
def case_focus_empty_byte_identical():
    print("\n[3] focus='' 旧路径逐字节一致")
    scene, user_in, ai_out = "private_advisor", "我其实更希望他直接说", "好的我理解"
    expected = "场景：%s\n\n用户说：%s\n\nAI 回复：%s" % (scene, user_in, ai_out)
    got = memory_service.build_distill_user_message(scene, user_in, ai_out)
    check("build_distill_user_message(focus='') == 旧硬编码", got == expected, repr(got))

    # 经 distill_and_save 真实路径再验一次
    fake = FakeLlm()
    orig = _with_llm(fake)
    try:
        memory_service.distill_and_save(
            FakeDb(), user_id=1, relation_id=2, scene_key=scene,
            user_input=user_in, assistant_text=ai_out,
        )
        user_msg = fake.calls[0]["messages"][1]["content"]
        check("distill_and_save 默认参数下 user 消息逐字节一致", user_msg == expected,
              repr(user_msg))
        sys_msg = fake.calls[0]["messages"][0]["content"]
        from app.services.prompt_builder import MEMORY_DISTILL_PROMPT
        check("system prompt 一字未动", sys_msg == MEMORY_DISTILL_PROMPT)
    finally:
        _restore_llm(orig)


# ---------------------------------------------------------------------- #
# c) 截断 1200
# ---------------------------------------------------------------------- #
def case_truncate_1200():
    print("\n[4] 超长 content 截断到 1200 字")
    long_text = "很" * 5000
    event = _make_event("letter", long_text)
    fake = FakeLlm()
    orig = _with_llm(fake)
    try:
        distill_event(event, db=FakeDb())
        check("LLM 被调用", len(fake.calls) == 1)
        user_msg = fake.calls[0]["messages"][1]["content"]
        # 内容段在「内容：\n」之后
        body = user_msg.split("内容：\n", 1)[-1]
        check(f"输入正文长度 == {CONTENT_MAX_LEN}", len(body) == CONTENT_MAX_LEN,
              f"len={len(body)}")
        check("原 event.content 未被就地改写", len(event.content) == 5000,
              f"len={len(event.content)}")
    finally:
        _restore_llm(orig)


# ---------------------------------------------------------------------- #
# d) anniversary 幂等
# ---------------------------------------------------------------------- #
def case_anniversary_idempotent():
    print("\n[5] 重复 anniversary 事件只落一条（复用去重）")
    text = "在一起纪念日是 6 月 28 日"
    db = FakeDb(existing=None)
    r1 = distill_event(_make_event("anniversary", text), db=db)
    check("首次落库 1 条", len(r1) == 1 and len(db.added) == 1)

    # 第二次：existing 指向已有同文记忆 → 去重命中
    db.existing = _R(id=1, memory_text=text)
    r2 = distill_event(_make_event("anniversary", text), db=db)
    check("重复事件返回空", r2 == [], repr(r2))
    check("未再 add", len(db.added) == 1, f"added={len(db.added)}")


# ---------------------------------------------------------------------- #
# e) 边界：空 / 过短 / LLM 失败
# ---------------------------------------------------------------------- #
def case_boundaries():
    print("\n[6] 边界：空内容 / LLM 失败不抛")
    fake = FakeLlm()
    orig = _with_llm(fake)
    try:
        r = distill_event(_make_event("letter", "   "), db=FakeDb())
        check("空白 content → [] 且不调 LLM", r == [] and len(fake.calls) == 0)

        r = distill_event(_make_event("letter", "短"), db=FakeDb())
        check("过短 content → [] 且不调 LLM（_MIN_INPUT_LEN）",
              r == [] and len(fake.calls) == 0)
    finally:
        _restore_llm(orig)

    # LLM 抛错 → 吞掉返回 []
    class _Boom:
        api_key = "k"

        def invoke_structured(self, *a, **k):
            raise RuntimeError("boom")

    orig = _with_llm(_Boom())
    try:
        r = distill_event(_make_event("museum"), db=FakeDb())
        check("LLM 异常 → [] 不抛", r == [])
    finally:
        _restore_llm(orig)

    # distill_event 顶层再兜一层：db 方法炸了也不抛
    class _BadDb(FakeDb):
        def query(self, model):
            raise RuntimeError("db boom")

    r = distill_event(_make_event("anniversary"), db=_BadDb())
    check("db 异常 → [] 不抛", r == [])


def case_wired_into_services():
    print("\n[7] 5 源服务层接线（源码级）")
    root = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "services",
    )
    expectations = [
        ("letter_service.py", ["distill_event_in_background", 'source="letter"'], 2),
        ("museum_service.py", ["distill_event_in_background", 'source="museum"'], 1),
        ("anniversary_service.py", ["distill_event_in_background", 'source="anniversary"'], 1),
        ("dual_perspective_service.py", ["distill_event_in_background", 'source="dual"'], 1),
        ("questionnaire_service.py", ["distill_event_in_background", 'source="questionnaire"'], 1),
    ]
    for fname, needles, min_count in expectations:
        with open(os.path.join(root, fname), encoding="utf-8") as f:
            src = f.read()
        for needle in needles:
            check(f"{fname} 含 {needle!r}", needle in src)
        check(
            f"{fname} distill 调用 >= {min_count}",
            src.count("distill_event_in_background") >= min_count,
            str(src.count("distill_event_in_background")),
        )
    # diary / practice 不接
    for forbidden, fname in (("diary", "repositories/single/diary_repo.py"),):
        pass  # diary 压根不 import memory_events，下面直接查全仓
    all_src = ""
    for fname in os.listdir(root):
        if fname.endswith(".py"):
            with open(os.path.join(root, fname), encoding="utf-8") as f:
                all_src += f.read()
    check("无 diary 接线", 'source="diary"' not in all_src)
    check("无 practice 接线", 'source="practice"' not in all_src)


def main() -> int:
    print("=" * 72)
    print("P0-3 统一记忆事件入口（5 源）")
    print("=" * 72)
    case_five_sources()
    case_source_field_roundtrip()
    case_focus_empty_byte_identical()
    case_truncate_1200()
    case_anniversary_idempotent()
    case_boundaries()
    case_wired_into_services()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
