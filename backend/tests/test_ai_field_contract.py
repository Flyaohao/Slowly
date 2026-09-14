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

本脚本把这种漂移变成可复现的失败：扫描各服务函数里读取的字段名，
断言它们都是该函数所用 scene 对应的输出模型的字段。

## 覆盖范围

自动发现并校验以下文件中所有"调用 `_call_llm(prompt, "<scene>")` 后
读取 `ai_response.get("<key>")`"的函数：
  - app/services/letter_ai_service.py
  - app/services/mediation_service.py
  - app/services/ai_service.py

## 运行

    cd backend && python tests/test_ai_field_contract.py
"""
import ast
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.schemas.ai_output import SCENE_OUTPUT_MODELS  # noqa: E402

#: 降级兜底回复会额外补上的字段，不属于任何输出模型
ALLOWED_EXTRA_KEYS = {"raw_text", "scene_key"}

TARGET_FILES = [
    "app/services/letter_ai_service.py",
    "app/services/mediation_service.py",
    "app/services/ai_service.py",
]

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

failures = []
checked = []


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


def check_file(rel_path):
    path = os.path.join(BASE_DIR, rel_path)
    tree = ast.parse(io.open(path, encoding="utf-8").read(), filename=rel_path)

    for node in tree.body:
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue

        scenes = _scene_keys_in(node)
        # 同一个函数里读取多个来源时无法唯一归因，跳过（人工确认）
        if len(scenes) != 1:
            continue

        scene = scenes[0]
        if scene not in SCENE_OUTPUT_MODELS:
            failures.append(
                "%s::%s 使用 scene '%s'，但它未登记在 SCENE_OUTPUT_MODELS，"
                "会静默回退到 TranslateOutput" % (rel_path, node.name, scene)
            )
            continue

        model = SCENE_OUTPUT_MODELS[scene]
        fields = set(model.model_fields.keys())
        read_keys = set(_read_keys_in(node, "ai_response"))
        illegal = read_keys - fields - ALLOWED_EXTRA_KEYS

        checked.append((rel_path, node.name, scene, model.__name__, sorted(read_keys)))

        if illegal:
            failures.append(
                "%s::%s 读取字段 %s，但 scene '%s' 对应的 %s 只有 %s"
                % (
                    rel_path,
                    node.name,
                    sorted(illegal),
                    scene,
                    model.__name__,
                    sorted(fields),
                )
            )


def main():
    print("=" * 72)
    print("AI 输出字段契约检查（prompt 声明 vs Pydantic 模型）")
    print("=" * 72)

    for rel in TARGET_FILES:
        check_file(rel)

    for rel, func, scene, model, keys in checked:
        print("\n%s::%s" % (rel, func))
        print("  scene=%s  模型=%s" % (scene, model))
        print("  读取字段=%s" % (keys if keys else "（无，仅原样透传）"))

    print("\n" + "=" * 72)
    print("共检查 %d 个函数" % len(checked))

    if failures:
        print("\n发现 %d 处字段契约错配：" % len(failures))
        for f in failures:
            print("  [FAIL] " + f)
        print("=" * 72)
        sys.exit(1)

    print("字段契约全部一致 —— 所有读取的字段都是对应模型的真实字段。")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())
