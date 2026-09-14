"""AI 输出字段契约测试（静态分析，不需要 API Key、不需要数据库）

## 为什么需要这个测试

Prompt 里用自然语言描述的 JSON 字段名（如"包含以下字段：summary / key_concerns"），
与 `app/schemas/ai_output.py` 里 Pydantic 模型的实际字段名，是**两处独立维护**的。
注意结构化输出走的是 Function Calling 承载 Schema，模型最终返回的字段
**完全由 Pydantic 模型决定**，prompt 里的描述只是给模型看的提示，并不约束输出。

两者一旦漂移，服务层的 `ai_response.get("xxx")` 就会静默取不到值：
接口照常返回 200、日志也没有报错，只是内容全空——或者像
`rewrite_expression` 那样退回到硬编码的占位文案，看起来"有结果"实则与用户输入无关。
这类缺陷从运行日志里几乎看不出来，只能靠静态比对发现。

本脚本把这种漂移变成可复现的失败，分两侧校验：

- **读取侧（A）**：服务函数里 `ai_response.get("<key>")` 读的字段，
  必须属于该函数所用 scene 的输出模型。查的是"代码要什么，模型有没有"。
- **声明侧（B）**：prompt 文本里用 `- 字段名: 说明` 声明的字段，
  必须属于该场景的输出模型。查的是"prompt 告诉模型什么，模型能不能给"。
  2026-09-14 修的正是这一类：`ai_service.rewrite_expression` 的 prompt 写着
  `versions`，而模型产出的是 `rewrites`，于是永远命中不到。

两侧都覆盖才能算闭环——只做 A 的话，prompt 在误导模型也发现不了。

## 覆盖范围

A. 自动发现并校验以下文件中所有"调用 `_call_llm(prompt, "<scene>")` 后
   读取 `ai_response.get("<key>")`"的函数：
     - app/services/letter_ai_service.py
     - app/services/mediation_service.py
     - app/services/ai_service.py

B. 逐场景扫描 prompt 文本里的字段声明：
     - app/services/prompt_builder.py 的 `SYSTEM_PROMPTS`（键即 scene_key）
       与其余模块级 prompt 常量（如 `MEMORY_DISTILL_PROMPT`）
     - app/services/letter_ai_service.py 的 `LETTER_*_PROMPT` 常量（显式映射 scene）
     - app/services/ai_service.py 中函数体内拼装的 prompt（scene 取自 `_call_llm`）

## 运行

    cd backend && python tests/test_ai_field_contract.py
"""
import ast
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.schemas.ai_output import TEXT_ONLY_SCENES, get_output_model  # noqa: E402
from app.services.prompt_builder import SYSTEM_PROMPTS  # noqa: E402

#: 降级兜底回复会额外补上的字段，不属于任何输出模型
ALLOWED_EXTRA_KEYS = {"raw_text", "scene_key"}

#: 读取侧要扫描的服务文件
TARGET_FILES = [
    "app/services/letter_ai_service.py",
    "app/services/mediation_service.py",
    "app/services/ai_service.py",
]

#: 声明侧：模块级 prompt 常量 → scene
MODULE_PROMPT_CONSTANTS = {
    "app/services/letter_ai_service.py": {
        "LETTER_UNDERSTAND_PROMPT": "letter_analysis",
        "LETTER_REWRITE_PROMPT": "letter_rewrite",
        "LETTER_REPLY_PROMPT": "letter_reply",
    },
    "app/services/prompt_builder.py": {
        # 内部辅助场景（不属于用户可选场景），由记忆沉淀链路调用
        "MEMORY_DISTILL_PROMPT": "memory_distill",
    },
}

#: prompt 里字段声明的写法：行首 `- field_name: 说明` / `- field_name：说明`
FIELD_LINE_RE = re.compile(r"^[ \t]*-[ \t]*([A-Za-z_][A-Za-z0-9_]*)[ \t]*[:：]", re.M)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

failures = []
warnings = []
checked_reads = []
checked_prompts = []


# --------------------------------------------------------------------------
# 工具
# --------------------------------------------------------------------------

def _parse(rel_path):
    path = os.path.join(BASE_DIR, rel_path)
    src = io.open(path, encoding="utf-8").read()
    return ast.parse(src, filename=rel_path)


def _scene_keys_in(func_node):
    """函数体内所有 _call_llm(...) 的第二个参数（scene key）"""
    found = []
    for node in ast.walk(func_node):
        if not isinstance(node, ast.Call):
            continue
        name = getattr(node.func, "id", None) or getattr(node.func, "attr", None)
        if name != "_call_llm" or len(node.args) < 2:
            continue
        arg = node.args[1]
        if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
            found.append(arg.value)
    return found


def _read_keys_in(func_node, var_name):
    """函数体内 var_name.get("key") 读取的所有 key"""
    keys = []
    for node in ast.walk(func_node):
        if not isinstance(node, ast.Call):
            continue
        if getattr(node.func, "attr", None) != "get":
            continue
        base = node.func.value
        if not (isinstance(base, ast.Name) and base.id == var_name):
            continue
        if node.args and isinstance(node.args[0], ast.Constant):
            keys.append(node.args[0].value)
    return keys


def declared_fields(text):
    """从 prompt 文本里抽出 `- field:` 形式的字段声明"""
    return set(FIELD_LINE_RE.findall(text))


def _string_constants(node):
    """节点下所有字符串字面量（f-string 会按静态片段拆开，字段声明仍在其中）"""
    for n in ast.walk(node):
        if isinstance(n, ast.Constant) and isinstance(n.value, str):
            yield n.value


# --------------------------------------------------------------------------
# A. 读取侧：代码读的字段 ⊆ 模型字段
# --------------------------------------------------------------------------

def check_reads(rel_path):
    tree = _parse(rel_path)

    for node in tree.body:
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue

        scenes = _scene_keys_in(node)
        # 同一个函数里读取多个来源时无法唯一归因，跳过（人工确认）
        if len(scenes) != 1:
            continue

        scene = scenes[0]
        model = get_output_model(scene)
        fields = set(model.model_fields.keys())
        read_keys = set(_read_keys_in(node, "ai_response"))
        illegal = read_keys - fields - ALLOWED_EXTRA_KEYS

        checked_reads.append((rel_path, node.name, scene, model.__name__, sorted(read_keys)))

        if illegal:
            failures.append(
                "[读取] %s::%s 读取字段 %s，但 scene '%s' 对应的 %s 只有 %s"
                % (rel_path, node.name, sorted(illegal), scene, model.__name__, sorted(fields))
            )


# --------------------------------------------------------------------------
# B. 声明侧：prompt 声明的字段 ⊆ 模型字段
# --------------------------------------------------------------------------

def _check_declared(label, scene, text):
    if scene in TEXT_ONLY_SCENES:
        checked_prompts.append((label, scene, "（纯文本场景，跳过）", []))
        return

    model = get_output_model(scene)
    fields = set(model.model_fields.keys())
    declared = declared_fields(text)

    checked_prompts.append((label, scene, model.__name__, sorted(declared)))

    if not declared:
        warnings.append("%s 未声明任何字段（scene=%s）" % (label, scene))
        return

    illegal = declared - fields
    if illegal:
        failures.append(
            "[声明] %s 的 prompt 声明了字段 %s，但 scene '%s' 对应的 %s 只有 %s"
            % (label, sorted(illegal), scene, model.__name__, sorted(fields))
        )


def check_prompt_builder():
    """prompt_builder.SYSTEM_PROMPTS：键即 scene_key"""
    for scene, template in SYSTEM_PROMPTS.items():
        _check_declared("prompt_builder.SYSTEM_PROMPTS[%r]" % scene, scene, template)


def check_module_prompt_constants(rel_path):
    """模块级 prompt 常量，用显式映射定位 scene"""
    mapping = MODULE_PROMPT_CONSTANTS[rel_path]
    tree = _parse(rel_path)

    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if not isinstance(target, ast.Name) or target.id not in mapping:
                continue
            scene = mapping[target.id]
            text = "\n".join(_string_constants(node))
            _check_declared("%s.%s" % (rel_path, target.id), scene, text)


def check_function_prompts(rel_path):
    """函数体内拼装的 prompt，scene 取自该函数的 _call_llm 第二参数"""
    tree = _parse(rel_path)

    for node in tree.body:
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        scenes = _scene_keys_in(node)
        if len(scenes) != 1:
            continue
        text = "\n".join(_string_constants(node))
        _check_declared("%s::%s" % (rel_path, node.name), scenes[0], text)


# --------------------------------------------------------------------------

def main():
    print("=" * 72)
    print("AI 输出字段契约检查（prompt 声明 / 代码读取  vs  Pydantic 模型）")
    print("=" * 72)

    print("\n--- A. 读取侧（代码读的字段 ⊆ 模型字段）---")
    for rel in TARGET_FILES:
        check_reads(rel)
    for rel, func, scene, model, keys in checked_reads:
        print("\n%s::%s" % (rel, func))
        print("  scene=%s  模型=%s" % (scene, model))
        print("  读取字段=%s" % (keys if keys else "（无，仅原样透传）"))

    print("\n--- B. 声明侧（prompt 声明的字段 ⊆ 模型字段）---")
    check_prompt_builder()
    for rel in MODULE_PROMPT_CONSTANTS:
        check_module_prompt_constants(rel)
    check_function_prompts("app/services/ai_service.py")
    for label, scene, model, declared in checked_prompts:
        print("\n%s" % label)
        print("  scene=%s  模型=%s" % (scene, model))
        print("  声明字段=%s" % (declared if declared else "（未声明）"))

    print("\n" + "=" * 72)
    print("A. 共检查 %d 个函数；B. 共检查 %d 处 prompt"
          % (len(checked_reads), len(checked_prompts)))

    if warnings:
        print("\n提示（不判失败）：")
        for w in warnings:
            print("  [WARN] " + w)

    if failures:
        print("\n发现 %d 处字段契约错配：" % len(failures))
        for f in failures:
            print("  [FAIL] " + f)
        print("=" * 72)
        sys.exit(1)

    print("字段契约全部一致 —— 读取与声明的字段都是对应模型的真实字段。")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())
