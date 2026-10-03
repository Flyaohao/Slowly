"""AI 记忆沉淀逻辑测试（不需要 API Key、不需要数据库）

## 为什么需要这个测试

`memory_service.create_memory()` 此前**全仓零调用**，`AiMemory` 表永远是空的，
于是「AI 记忆」页永远白屏、`get_memory_context()` 永远返回 ""。
补上写入端之后，真正需要验证的不是"能不能写进去"（那是一次 insert 而已），
而是**闸门有没有起作用**：

- 模型说不值得记 → 必须不写，否则记忆页会被噪音淹没，
  并且噪音会经 `get_memory_context()` 进入后续每一轮的 prompt；
- 模型给出类别外的值 → 必须收口，否则前端筛选/展示会失配；
- 完全相同的记忆 → 必须去重，否则同一件事每轮都写一条；
- 模型调用失败 → 必须静默降级，记忆是锦上添花，不能连累对话主链路。

这些分支用真实数据库很难稳定复现（还得有 Key），所以这里用**桩对象**直接
驱动 `distill_and_save()`：一个假的 db、一个假的 llm。

## 运行

    cd backend && python tests/test_memory_distill.py
"""
import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# 注意（2026-10-03）：桩必须拦在 `user_ai_config_service.build_chat_client`。
# 模块级的 `memory_service.distill_llm` 在蒸馏路径上**不会被读**——
# `distill_and_save(db, ..., llm_client=None)` 会现解析 uaicfg 客户端，
# 然后调 `llm_client.invoke_structured(...)`（memory_service.py:635）。
# 打旧位置会让请求真发上游 → 403 Free quota exhausted。


def _stub_client_factory(fake):
    """把 `fake` 接成蒸馏链路的 LLM 客户端，返回原函数供还原。"""
    from app.services import user_ai_config_service as uaicfg

    original = uaicfg.build_chat_client
    uaicfg.build_chat_client = lambda *a, **k: fake
    return original


def _restore_client_factory(original):
    from app.services import user_ai_config_service as uaicfg

    uaicfg.build_chat_client = original


from app.schemas.ai_output import MemoryDistillOutput  # noqa: E402
from app.services import memory_service  # noqa: E402
from app.services.llm_client import LlmError  # noqa: E402

failures = []
cases = []


class FakeDb:
    """只实现 distill_and_save 真正用到的那几个方法。"""

    def __init__(self, existing=None):
        self.existing = existing
        self.added = []
        self.commits = 0
        self.rollbacks = 0

    # 去重查询链：db.query(...).filter(...).first()
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
            obj.created_at = datetime(2026, 9, 14, 12, 0, 0)

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1


class FakeLlm:
    """返回预设结果，或被设为抛错。"""

    api_key = "test-key"

    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error
        self.calls = []

    def invoke_structured(self, messages, scene="unknown", **kwargs):
        self.calls.append(scene)
        if self.error:
            raise self.error
        return self.result


def run_case(name, *, db, llm, user_input="我其实更希望他直接说，不要让我猜"):
    # 记忆沉淀走的是专用轻量客户端（memory_service.distill_llm），
    # 不是主链路单例（llm），桩要打在同一个对象上。
    original = _stub_client_factory(llm)
    try:
        result = memory_service.distill_and_save(
            db, user_id=1, relation_id=2, scene_key="private_advisor",
            user_input=user_input, assistant_text="AI 的回复内容",
        )
    finally:
        _restore_client_factory(original)
    cases.append(name)
    return result


def main():
    print("=" * 72)
    print("AI 记忆沉淀逻辑检查（闸门 / 去重 / 降级）")
    print("=" * 72)

    # 1. 模型认为不值得记 → 不写库
    db = FakeDb()
    llm = FakeLlm(MemoryDistillOutput(should_remember=False, memory_text=""))
    result = run_case("should_remember=False 时不写库", db=db, llm=llm)
    if result is not None or db.added:
        failures.append("should_remember=False 时仍然写库了")
    print("\n[1] should_remember=False -> 写库 %d 条（期望 0）" % len(db.added))

    # 2. 正常沉淀
    db = FakeDb()
    llm = FakeLlm(MemoryDistillOutput(
        should_remember=True, memory_type="偏好", memory_text="更希望对方直接表达而非暗示"
    ))
    result = run_case("正常沉淀", db=db, llm=llm)
    if result is None or len(db.added) != 1:
        failures.append("正常路径未写入记忆")
    elif db.added[0].memory_type != "偏好" or db.added[0].visibility != "private":
        failures.append("写入内容的类别或默认可见性不符合预期")
    print("[2] 正常路径 -> 写库 %d 条，type=%s visibility=%s"
          % (len(db.added),
             db.added[0].memory_type if db.added else "-",
             db.added[0].visibility if db.added else "-"))

    # 3a. 类别越界：第一道防线是 Pydantic 的 Literal，应当直接拒绝
    try:
        MemoryDistillOutput(
            should_remember=True, memory_type="随便编的类别", memory_text="一句话记忆"
        )
        failures.append("Pydantic 未拒绝越界类别，Literal 约束形同虚设")
        schema_rejected = False
    except Exception:  # noqa: BLE001
        schema_rejected = True
    print("[3a] Pydantic 拦截越界类别 -> %s" % ("已拒绝" if schema_rejected else "未拒绝"))

    # 3b. 第二道防线：万一某个值绕过了 schema（换模型、桩数据等），
    #     落库前仍应收口到默认类别，不能让脏值进库。
    class _Raw:
        should_remember = True
        memory_type = "随便编的类别"
        memory_text = "一句话记忆"

    db = FakeDb()
    llm = FakeLlm(_Raw())
    run_case("绕过 schema 的越界类别收口", db=db, llm=llm)
    if not db.added or db.added[0].memory_type != memory_service._DEFAULT_MEMORY_TYPE:
        failures.append("绕过 schema 的越界类别未被收口到默认值")
    print("[3b] 绕过 schema 的越界值 -> 落库 type=%s（期望 %s）"
          % (db.added[0].memory_type if db.added else "-",
             memory_service._DEFAULT_MEMORY_TYPE))

    # 4. 完全重复 → 去重
    db = FakeDb(existing=object())
    llm = FakeLlm(MemoryDistillOutput(
        should_remember=True, memory_type="关系事实", memory_text="重复的一句话"
    ))
    result = run_case("重复记忆去重", db=db, llm=llm)
    if result is not None or db.added:
        failures.append("重复记忆未被去重")
    print("[4] 重复记忆 -> 写库 %d 条（期望 0）" % len(db.added))

    # 5. 模型失败 → 静默降级，不抛异常
    db = FakeDb()
    llm = FakeLlm(error=LlmError("模拟模型不可用"))
    try:
        result = run_case("模型失败静默降级", db=db, llm=llm)
        raised = False
    except Exception as exc:  # noqa: BLE001
        result, raised = None, True
        failures.append("模型失败时异常逃逸到了调用方: %s" % exc)
    if result is not None or db.added:
        failures.append("模型失败时不应写库")
    if not raised:
        print("[5] 模型失败 -> 未抛异常、写库 %d 条（期望 0）" % len(db.added))

    # 6. 输入过短 → 连模型都不调，省一次开销
    db = FakeDb()
    llm = FakeLlm(MemoryDistillOutput(should_remember=True, memory_text="x"))
    run_case("输入过短直接跳过", db=db, llm=llm, user_input="嗯")
    if llm.calls:
        failures.append("输入过短时仍调用了模型")
    print("[6] 输入过短 -> 模型调用 %d 次（期望 0）" % len(llm.calls))

    print("\n" + "=" * 72)
    print("共检查 %d 个用例" % len(cases))
    if failures:
        print("\n发现 %d 处不符合预期：" % len(failures))
        for f in failures:
            print("  [FAIL] " + f)
        print("=" * 72)
        sys.exit(1)
    print("记忆沉淀的闸门 / 去重 / 降级行为全部符合预期。")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())
