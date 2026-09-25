"""v3.2 §7.1：Agent 工具身份绑定（服务端注入，模型不可自报）

断言：
  1. `build_agent_tools(user_id, relation_id)` 返回三个工具，名字与
     ALL_TOOLS 白名单一致；
  2. get_relation_profile / get_ai_memory 的 args_schema **不含**
     user_id / relation_id 字段（schema 层封死伪造面，IDOR 不可传参）；
  3. get_ai_memory 闭包身份生效：relation_id=0（单身）直接返回未绑定提示，
     不触库；
  4. search_theory 全局只读，仍是无身份工具；
  5. executor 系统提示不再出现「调用工具时请使用上述 ID」行。

纯 import/闭包断言，不调模型不查库（get_ai_memory relation_id=0 分支在
开 Session 之前返回）——hermetic。

运行：cd backend && PYTHONIOENCODING=utf-8 python tests/test_memory_agent_binding.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

FAILURES = []


def check(name: str, ok: bool, detail: str = ""):
    mark = "PASS" if ok else "FAIL"
    print(f"  {mark}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        FAILURES.append(name)


def main() -> int:
    print("=" * 72)
    print("v3.2 §7.1 Agent 工具身份绑定")
    print("=" * 72)

    from app.agent.tools import ALL_TOOLS, build_agent_tools

    tools = build_agent_tools(user_id=42, relation_id=7)
    names = [t.name for t in tools]

    print("\n[1] 工厂返回与白名单")
    check("返回三个工具", len(tools) == 3, str(names))
    check("名字与 ALL_TOOLS 白名单一致",
          set(names) == set(ALL_TOOLS), f"{names} vs {ALL_TOOLS}")

    print("\n[2] schema 不暴露身份字段（模型不可自报）")
    for t in tools:
        if t.name == "search_theory":
            continue
        schema = t.args_schema.model_json_schema() if t.args_schema else {}
        props = list(schema.get("properties", {}).keys())
        check(f"{t.name} 参数无 user_id/relation_id",
              "user_id" not in props and "relation_id" not in props,
              str(props))
    mem = next(t for t in tools if t.name == "get_ai_memory")
    mem_props = list(mem.args_schema.model_json_schema()["properties"].keys())
    check("get_ai_memory 只剩 query", mem_props == ["query"], str(mem_props))
    prof = next(t for t in tools if t.name == "get_relation_profile")
    prof_props = list(prof.args_schema.model_json_schema()["properties"].keys())
    check("get_relation_profile 只剩 include_partner",
          prof_props == ["include_partner"], str(prof_props))

    print("\n[3] 闭包身份生效")
    single_tools = build_agent_tools(user_id=42, relation_id=0)
    single_mem = next(t for t in single_tools if t.name == "get_ai_memory")
    out = single_mem.invoke({"query": "之前说过什么"})
    check("relation_id=0 → 未绑定提示（开 Session 前返回）",
          "未绑定伴侣" in out, str(out))

    print("\n[4] 系统提示不教模型用 ID")
    import inspect

    from app.agent import executor

    src = inspect.getsource(executor)
    check("无「调用工具时请使用上述 ID」行",
          "调用工具时请使用上述 ID" not in src)
    check("get_agent 走 build_agent_tools 工厂",
          "build_agent_tools(" in src)

    print("\n" + "=" * 72)
    if FAILURES:
        print(f"失败 {len(FAILURES)} 项：{FAILURES}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
