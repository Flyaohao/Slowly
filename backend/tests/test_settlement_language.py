# -*- coding: utf-8 -*-
"""调解书输出语言约束回归（P1，2026-10-02 真机实测复现）

## 这条测试挡的是什么

真机上已结算房间的「共同约定」显示成英文：

    Change plans at least one day ahead, no last-minute overrides

而同一张卡片的 `summary_text` 是正常中文。根因是本模型既不知道要对哪个
字段负责、也没被告知要输出中文——原先 `SettlementOutput` 的每个
`description` 都是纯功能描述，system prompt 里也零语言约束，模型偶尔
就按英文作答。

## 为什么约束要写两遍（system + 每个字段 description）

`description=` 会随Function Calling Schema 一起进 prompt，也就是模型在
**逐字段生成时**会再看到一次约束。真实的失效模式是「模型整体用中文，
但某个列表项的措辞漂到英文」——这种漂移 system 层单点约束拦不住，
而逐字段约束恰好压在生成点上。

## 为什么 `result` 字段故意不加

它是 `reconciled` / `deferred` / `cold_war` 枚举值，**必须保持英文**。
给枚举值加「必须是简体中文」会让模型试图翻译枚举，直接破坏
`_VALID_RESULTS` 校验与前端 `RESULT_LABELS` 映射。

## 覆盖

1. system prompt **开头**就有语言约束（位置重要：system 开头比结尾更稳）
2. 6 个自然语言字段的 `description` 都带约束（且是「必须是简体中文」原话，
   不是「用中文」之类的弱表述——弱表述实测会被模型忽略）
3. `result` 字段**不带**中文约束（防上面说的枚举污染）
4. 6 个字段都在 Function Calling schema 里真的可见（不只是源码里有字面量：
   验证 `model_json_schema()` 里能 grep 到，防止字段被改名/挪走而约束失联）
5. 判定器兜底：`result` 的合法枚举仍是三个英文值
6. `room_advisor_service` **不需要**同样处理（纯文本、无结构化 schema
   可注入 → 没有逐字段注入点；本测试把这个判断固化为可回归的结论）

不发起真实 LLM 请求：只验 prompt 与 schema 的静态形状。
「模型是否真的听约束」属真机验证范畴，不在单测里假装能验。

## 运行

    cd backend && PYTHONUTF8=1 python tests/test_settlement_language.py
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

#: 必须是简体中文的自然语言字段（与 SettlementOutput 一一对应）
NL_FIELDS = [
    "summary_text",
    "agreements",
    "responsibility_a",
    "responsibility_b",
    "viewpoint_a",
    "viewpoint_b",
]
#: 必须保持英文的枚举字段
ENUM_FIELDS = ["result"]
#: 枚举的合法取值
VALID_RESULTS = ("reconciled", "deferred", "cold_war")

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def note(msg):
    print("  NOTE  %s" % msg)


def read(rel):
    return open(os.path.join(BACKEND_DIR, *rel.split("/")), encoding="utf-8").read()


def extract_invoke_body(src, func_name):
    """取函数体源码（按def 到下一个顶层 def 截断）。"""
    m = re.search(r"^def %s\(" % re.escape(func_name), src, re.M)
    if not m:
        return ""
    rest = src[m.start():]
    nxt = re.search(r"^def \w+\(", rest[len("def %s(" % func_name):], re.M)
    return rest[:len("def %s(" % func_name) + nxt.start()] if nxt else rest


# --------------------------------------------------------------------------- #
def t_system_prompt_head():
    print("\n[1] system prompt 开头的语言约束")
    src = read("app/services/room_settlement_service.py")
    body = extract_invoke_body(src, "_invoke_settlement")
    check("_invoke_settlement 找得到", bool(body))
    if not body:
        return

    m = re.search(r'system\s*=\s*\((.*?)\n    \)', body, re.S)
    check("system 是括号拼接形式（可静态取证）", bool(m))
    if not m:
        return
    lit = m.group(1)
    check("含「输出必须是简体中文」原话", "输出必须是简体中文" in lit,
          lit[:120])
    check("含「禁止使用英文句子或中英混杂」", "禁止使用英文句子或中英混杂" in lit)

    # 位置：约束必须是拼接串的**第一段**，也就是真正落在 system 最开头
    first_seg = lit.strip().split("\n", 1)[0]
    check("约束在 system prompt **开头**（第一段拼接）",
          "简体中文" in first_seg, first_seg[:120])
    idx_cn = lit.find("简体中文")
    idx_role = lit.find("你是情侣双人调解室")
    check("约束早于角色设定", 0 <= idx_cn < idx_role, (idx_cn, idx_role))

    # 不能有「英文」豁免：枚举说明段里的 reconciled/deferred/cold_war 是
    # 枚举值，必须留在 prompt 里（否则模型不知道该输出什么）
    for token in VALID_RESULTS:
        check("枚举 %s 仍在 prompt 里（枚举不能被翻译掉）" % token, token in lit)


def t_nl_field_descriptions():
    print("\n[2] 6 个自然语言字段的 description")
    from app.services.room_settlement_service import SettlementOutput

    schema = SettlementOutput.model_json_schema()
    props = schema.get("properties", {})
    check("schema 里有全部 7 个字段",
          set(props) == set(NL_FIELDS + ENUM_FIELDS), sorted(props))

    for f in NL_FIELDS:
        desc = props.get(f, {}).get("description", "")
        check("%s 的 description 含「必须是简体中文」" % f,
              "必须是简体中文" in desc, repr(desc)[:110])

    for f in ENUM_FIELDS:
        desc = props.get(f, {}).get("description", "")
        check("%s **不含**中文约束（枚举必须保持英文）" % f,
              "简体中文" not in desc, repr(desc)[:110])

    check("agreements 是数组（多条约定，漂英文风险最高）",
          props.get("agreements", {}).get("type") == "array",
          props.get("agreements", {}).get("type"))


def t_schema_reaches_model():
    print("\n[3] 约束真的进了 Function Calling schema（防约束失联）")
    from app.services.room_settlement_service import SettlementOutput

    # llm_client 走 model_json_schema() 生成 tool.parameters
    blob = json.dumps(SettlementOutput.model_json_schema(), ensure_ascii=False)
    for f in NL_FIELDS:
        check("schema JSON 里能看到 %s 的中文约束" % f,
              re.search(r'"%s".{0,400}?必须是简体中文' % f, blob, re.S) is not None)
    # 反向：result 不该在 schema 里带中文约束。
    # 匹配必须**限定在该字段的 properties 块内**（`[^}]*` 而非 `.{0,300}`）——
    # 后者会顺带吃进下一个字段的 description，把summary_text 的约束误判成
    # result 的（我第一版就踩了这个，拿到一个假 FAIL）。这类「断言本身错了
    # 而不是被测代码错了」的失败必须修断言，不许改被测代码去迎合。
    m = re.search(r'"result":\s*\{[^}]*\}', blob)
    check("schema JSON 里 result 不含中文约束",
          m is not None and "简体中文" not in m.group(0),
          m.group(0)[:150] if m else "(result 块未匹配到)")

    # 确认 llm_client 确实是从 model_json_schema 取的（否则上面的验证是空转）
    lc = read("app/services/llm_client.py")
    check("llm_client 用 model_json_schema() 生成 tool 参数",
          "model_json_schema()" in lc)


def t_no_detection_fallback():
    print("\n[4] 没加「检测英文后翻译」这类兜底（会引入新故障面）")
    src = read("app/services/room_settlement_service.py")
    for banned, why in [
        ("translate", "翻译兜底会二次调 LLM，失败即整份调解书失败"),
        ("检测到英文", "语言检测本身会误判（专有名词/型号/英文名）"),
        ("if not _is_chinese", "同上"),
    ]:
        check("未引入「%s」（%s）" % (banned, why), banned not in src)
    check("未动 RESULT_LABELS 等枚举映射",
          "_VALID_RESULTS = (RESULT_RECONCILED, RESULT_DEFERRED, RESULT_COLD_WAR)"
          in src)


def t_advisor_service_needs_no_change():
    print("\n[5] room_advisor_service 判定：不需要同样处理（固化为结论）")
    src = read("app/services/room_advisor_service.py")
    # 理由：它是纯文本对话，没有 output_model → 没有「逐字段注入」的位置。
    has_schema = ("invoke_structured" in src) or ("output_model" in src)
    check("advisor 不用结构化输出（故无逐字段注入点）", not has_schema)
    check("advisor 里没有 description= 可挂约束",
          "description=" not in src)
    # 那么它靠什么保证中文？全局层。确认全局层确实有约束存在：
    lc = read("app/services/llm_client.py")
    check("全局 _arm_tool_messages 已有中文约束（advisor 间接受益于此）",
          "内容使用简体中文" in lc)
    note("结论：advisor 是「纯文本 + 全局约束」，与 settlement 的")
    note("「结构化 + 逐字段约束」是两种不同形态，不该强行照抄。")
    note("若将来真机上 advisor 也出英文，正确的修法是给全局层加，")
    note("而不是给这个文件单独加一句 system 话。")


def main() -> int:
    print("=" * 72)
    print("调解书输出语言约束回归（settlement 英文输出）")
    print("=" * 72)
    t_system_prompt_head()
    t_nl_field_descriptions()
    t_schema_reaches_model()
    t_no_detection_fallback()
    t_advisor_service_needs_no_change()
    print("\n" + "=" * 72)
    if FAILURES:
        print("结果：FAIL %d 项 → %s" % (len(FAILURES), FAILURES))
        return 1
    print("结果：全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
