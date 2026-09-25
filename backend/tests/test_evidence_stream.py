"""
P0-5 验收（流式）：SSE 末尾有 event:evidence，且 thinking/delta 未被污染。

不启 uvicorn、不调真模型：直接驱动 stream_chat_events()，mock 掉
_stream_with_heartbeat 与 _persist_streamed_message。

断言：
  a) 事件序列含 meta → thinking* → delta* → evidence → done
  b) evidence 在 done 之前、delta 之后
  c) thinking 帧只出现在 evidence 之前且不被 evidence 顶掉
  d) evidence 数据与 prepared["evidence"] 逐字节一致（未再查库）
  e) 无 evidence 的 prepared（旧路径）→ 不 yield evidence 帧（向后兼容）

运行：cd backend && python tests/test_evidence_stream.py
"""
import os
import sys

# 测试隔离：防 persist 路径触发 distill 后台线程写库（本用例虽 mock persist，统一设上）
os.environ["COUPLE_DISABLE_MEMORY_DISTILL"] = "1"

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def _run_stream(prepared: dict, chunks=None, thinking=None):
    """驱动 stream_chat_events，返回事件名列表与 evidence 数据。"""
    import app.services.ai_service as ai_service
    from app.services.llm_client import llm

    chunks = chunks if chunks is not None else ["你好，", "我理解你。"]
    thinking = thinking if thinking is not None else ["先分析一下，", "再安抚。"]

    def _fake_hb(messages, scene_key, **kwargs):
        for t in thinking:
            yield (llm.KIND_THINKING, t)
        for c in chunks:
            yield (llm.KIND_CONTENT, c)

    def _fake_persist(**kwargs):
        # P-C3 §3.2 起契约为 (message_id, token_after)；返回裸 int 会在
        # stream_chat_events 的解包处 TypeError
        return (42, 0)

    orig_hb = ai_service._stream_with_heartbeat
    orig_persist = ai_service._persist_streamed_message
    ai_service._stream_with_heartbeat = _fake_hb
    ai_service._persist_streamed_message = _fake_persist
    try:
        events = list(ai_service.stream_chat_events(prepared))
    finally:
        ai_service._stream_with_heartbeat = orig_hb
        ai_service._persist_streamed_message = orig_persist
    names = [e.get("event") for e in events if "event" in e]
    ev_payload = next((e["data"] for e in events if e.get("event") == "evidence"), None)
    return names, ev_payload, events


def _base_prepared(with_evidence=True):
    evidence = {
        "scene_key": "private_advisor",
        "self_profile_card": "【画像】焦虑依恋型 · 置信度 0.77",
        "partner_profile_card": "",
        "relationship_pattern": "",
        "recalled_memories": [
            {"content": "希望周末有半天独处", "source": "核心诉求", "created_at": "2026-09-24"}
        ],
        "theory_chunks": [
            {"title": "依恋理论", "snippet": "焦虑型需要…", "score": 0.82}
        ],
        "avatar_name": "小翻译",
        "voice_style": "gentle",
        "voice_style_label": "温柔",
    }
    return {
        "blocked": False,
        "session_id": 1,
        "scene_key": "private_advisor",
        "scene_name": "日常",
        "stream_messages": [{"role": "user", "content": "x"}],
        "rag_hit": 1,
        "evidence": evidence if with_evidence else None,
        "user_id": 4,
        "relation_id": 1,
        "user_input": "他三天没回我消息",
    }


def case_sequence():
    print("\n[1] 事件序列 meta → thinking* → delta* → evidence → done")
    names, ev, _ = _run_stream(_base_prepared(True))
    check("以 meta 开头", names[0] == "meta", str(names))
    check("以 done 结尾", names[-1] == "done", str(names))
    check("含 thinking", "thinking" in names)
    check("含 delta", "delta" in names)
    check("含 evidence", "evidence" in names)
    check("evidence 在 done 之前",
          "evidence" in names and names.index("evidence") < names.index("done"),
          str(names))
    check("evidence 在最后一个 delta 之后",
          names.index("evidence") > max(i for i, n in enumerate(names) if n == "delta"),
          str(names))
    # thinking 不得出现在 evidence 之后
    if "evidence" in names:
        last_thinking = max((i for i, n in enumerate(names) if n == "thinking"), default=-1)
        check("thinking 全在 evidence 之前", last_thinking < names.index("evidence"),
              str(names))


def case_payload_identity():
    print("\n[2] evidence 载荷与 prepared 逐字节一致（不再查库）")
    prepared = _base_prepared(True)
    _, ev, _ = _run_stream(prepared)
    check("evidence 非空", isinstance(ev, dict) and bool(ev))
    check("与 prepared.evidence 完全一致", ev == prepared["evidence"])
    check("含画像块", ev.get("self_profile_card", "").startswith("【画像】"))
    check("含记忆块", len(ev.get("recalled_memories") or []) > 0)
    check("含理论块", len(ev.get("theory_chunks") or []) > 0)


def case_backward_compat():
    print("\n[3] 无 evidence 的 prepared（旧路径）→ 不发 evidence 帧")
    names, ev, _ = _run_stream(_base_prepared(False))
    check("无 evidence 帧", "evidence" not in names, str(names))
    check("仍有 done", names[-1] == "done")
    check("meta/delta 正常", names[0] == "meta" and "delta" in names)


def case_blocked_no_evidence_pollution():
    print("\n[4] blocked 路径不发 evidence（安全拦截无依据可言）")
    prepared = _base_prepared(True)
    prepared["blocked"] = True
    prepared["content"] = "触发安全护栏"
    prepared["risk_level"] = "heated_conflict"
    names, ev, _ = _run_stream(prepared, chunks=[], thinking=[])
    check("blocked：meta → delta → done", names == ["meta", "delta", "done"], str(names))
    check("blocked：无 evidence", "evidence" not in names)


def case_delta_not_polluted():
    print("\n[5] delta 正文未被 evidence 污染")
    prepared = _base_prepared(True)
    _, _, events = _run_stream(prepared, chunks=["第一段", "第二段"])
    deltas = [e["data"]["content"] for e in events if e.get("event") == "delta"]
    check("delta 仅正文两帧", deltas == ["第一段", "第二段"], str(deltas))
    joined = "".join(deltas)
    check("delta 不含 evidence 字段名", "self_profile_card" not in joined)
    done = next(e["data"] for e in events if e.get("event") == "done")
    check("done.content 是纯正文", done["content"] == "第一段第二段", done["content"])


def main() -> int:
    print("=" * 72)
    print("P0-5 依据可视化（SSE evidence 末帧）")
    print("=" * 72)
    case_sequence()
    case_payload_identity()
    case_backward_compat()
    case_blocked_no_evidence_pollution()
    case_delta_not_polluted()

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
