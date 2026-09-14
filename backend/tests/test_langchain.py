"""LangChain Prompt 层验证

验证内容：
    1. ChatPromptTemplate 正常渲染、无残留占位符
    2. system / human 角色正确分离（旧版是拼成一条超长字符串）
    3. 画像变量、RAG 上下文、历史对话被注入到正确的位置
    4. 与旧版 build_prompt 的变量契约一致

运行：
    cd backend
    python tests/test_langchain.py
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.lc_prompt_builder import (  # noqa: E402
    CHAT_SCENES,
    build_messages,
    get_conversation_memory,
    history_to_messages,
)
from app.services.prompt_builder import build_prompt  # noqa: E402

USER_PROFILE = "依恋类型: 焦虑依恋型, 维度分数: [attachment_anxiety=82, conflict_pursue=79]"
PARTNER_PROFILE = "依恋类型: 疏离回避型, 维度分数: [attachment_avoidance=84, conflict_withdraw=81]"
PATTERN = "追逃模式（焦虑方追问 → 回避方撤退）"
RAG_CONTEXT = "## 沟通原则参考\n### 参考 1: Demand-Withdraw 追逃模式\n一方持续提出需求，另一方持续退缩……"
MEMORY_CONTEXT = "## 用户记忆\n- 伴侣不喜欢当面被追问"
HISTORY = "user: 他今天又没回我消息\nassistant: 我理解你的不安"
USER_INPUT = "我该怎么跟他开口？"

PLACEHOLDER_PATTERN = re.compile(r"\{[a-z_]+\}")


def main() -> int:
    print("=" * 72)
    print("LangChain Prompt 层验证")
    print("=" * 72)

    failures = []

    # ---- 1. 逐场景渲染 ----
    print("\n[1] 场景渲染检查")
    for scene in CHAT_SCENES:
        try:
            messages = build_messages(
                scene_key=scene,
                user_profile=USER_PROFILE,
                partner_profile=PARTNER_PROFILE,
                conflict_pattern=PATTERN,
                user_input=USER_INPUT,
                history=HISTORY,
                rag_context=RAG_CONTEXT,
                memory_context=MEMORY_CONTEXT,
            )
        except Exception as exc:
            print("  ❌ %-20s 渲染失败: %s" % (scene, exc))
            failures.append(scene)
            continue

        roles = [m["role"] for m in messages]
        leftovers = PLACEHOLDER_PATTERN.findall("".join(m["content"] for m in messages))
        status = "✅" if roles == ["system", "user"] and not leftovers else "❌"
        if status == "❌":
            failures.append(scene)
        print("  %s %-20s roles=%s 残留占位符=%s"
              % (status, scene, roles, leftovers or "无"))

    # ---- 2. 角色分离与内容归属 ----
    print("\n[2] 角色分离与内容归属")
    messages = build_messages(
        scene_key="partner_translate",
        user_profile=USER_PROFILE,
        partner_profile=PARTNER_PROFILE,
        conflict_pattern=PATTERN,
        user_input=USER_INPUT,
        history=HISTORY,
        rag_context=RAG_CONTEXT,
        memory_context=MEMORY_CONTEXT,
    )
    system_msg, user_msg = messages[0]["content"], messages[1]["content"]

    checks = [
        ("system 含用户画像", "焦虑依恋型" in system_msg),
        ("system 含伴侣画像", "疏离回避型" in system_msg),
        ("system 含冲突循环", "追逃模式" in system_msg),
        ("system 含历史对话", "他今天又没回我消息" in system_msg),
        ("human 含 RAG 上下文", "Demand-Withdraw" in user_msg),
        ("human 含用户记忆", "伴侣不喜欢当面被追问" in user_msg),
        ("human 含用户输入", USER_INPUT in user_msg),
        ("human 不含画像内容", "焦虑依恋型" not in user_msg),
    ]
    for label, ok in checks:
        print("  %s %s" % ("✅" if ok else "❌", label))
        if not ok:
            failures.append(label)

    # ---- 3. 与旧版 build_prompt 的契约一致性 ----
    print("\n[3] 与旧版 build_prompt 的契约对照")
    legacy = build_prompt(
        scene_key="partner_translate",
        user_profile=USER_PROFILE,
        partner_profile=PARTNER_PROFILE,
        conflict_pattern=PATTERN,
        user_input=USER_INPUT,
        history=HISTORY,
        rag_context=RAG_CONTEXT,
        memory_context=MEMORY_CONTEXT,
    )
    combined = system_msg + "\n" + user_msg
    print("  旧版 prompt 长度: %d 字符" % len(legacy))
    print("  LangChain 消息总长: %d 字符（system=%d / human=%d）"
          % (len(combined), len(system_msg), len(user_msg)))

    for label, text in (("旧版", legacy), ("LangChain 版", combined)):
        missing = [v for v in ("焦虑依恋型", "疏离回避型", "追逃模式", USER_INPUT)
                   if v not in text]
        print("  %s 变量完整性: %s" % (label, "✅ 全含" if not missing else "❌ 缺 %s" % missing))
        if missing:
            failures.append("%s 缺变量" % label)

    # ---- 4. 记忆工具 ----
    print("\n[4] LangChain 记忆与历史转换")
    memory = get_conversation_memory(k=20)
    print("  ✅ 记忆配置: 保留最近 %s 轮（%s）" % (memory["k"], type(memory["trimmer"]).__name__))

    msgs = history_to_messages([
        {"role": "user", "content": "你好"},
        {"role": "assistant", "content": "我在"},
    ])
    print("  ✅ 历史转换: %d 条 → %s" % (len(msgs), [m.type for m in msgs]))
    if len(msgs) != 2:
        failures.append("history_to_messages")

    print("\n" + "=" * 72)
    if failures:
        print("❌ 失败项: %s" % failures)
        return 1
    print("✅ LangChain Prompt 层全部检查通过")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
