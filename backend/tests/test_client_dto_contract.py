"""客户端 DTO 契约校验（静态分析，不需要 API Key、不需要数据库）

## 为什么需要这个测试

2026-09-14 的两次 P0 事故都是同一类问题：**后端产出的字段，客户端接不住**。

- 冷战页：后端 `ColdWarOutput` 的 7 个字段，客户端 `AiDto.StructuredOutput`
  一个都没有 → 六个区块全部落空，页面显示 raw_text 兜底。
- AI 记忆页：写入端根本没接线 → 页面永远空白。

它们的共同点是**都发生在 Python 与 Kotlin 的边界上**，因此
`tests/test_ai_field_contract.py` 查不出来——那个测试只比对 Python 内部
（prompt 声明 ↔ Pydantic 模型 ↔ 服务层读取）。

这类错配的症状极为隐蔽：接口返回 200、日志没有任何异常、Kotlin 侧因为字段
可空也不会崩，只是页面静默变空。只能靠"把两边的字段名放在一起比"来发现。

## 检查项

- **A. 字段承接**：后端每个场景输出模型的每个字段，客户端必须有地方能反序列化它
  （`AiDto.StructuredOutput` 或信件/调解的专用 DTO）。查的是"后端给了，客户端接得住吗"。
- **B. 死字段**：客户端 `StructuredOutput` 里声明、但没有任何后端场景会产出的字段。
  这类字段会让维护者误以为某功能存在（`suggested_actions` 就是这种）。
- **C. 场景键合法**：`AiSceneCatalog` 里本地配置的场景键，必须都是后端真实存在的场景。
  此前客户端凭空造了 `reply` / `apologize`，选中后服务端静默回退到兜底模型。

## 不做的事

不校验必须用哪个字段渲染哪个 UI——那是产品决策，静态分析判不了。
本测试只保证**契约层面**不断裂。

运行：
    cd backend
    python tests/test_client_dto_contract.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Dict, List, Set, Tuple

BACKEND_DIR = Path(__file__).resolve().parents[1]
ANDROID_DIR = BACKEND_DIR.parent / "android"
sys.path.insert(0, str(BACKEND_DIR))

from app.schemas.ai_output import SCENE_OUTPUT_MODELS, TEXT_ONLY_SCENES  # noqa: E402
from app.services.prompt_builder import SYSTEM_PROMPTS  # noqa: E402
from app.services.scene_router import SCENE_CONFIGS  # noqa: E402

#: 客户端 DTO 所在目录（相对 android/）。新增 DTO 文件时补进来即可
CLIENT_MODEL_DIRS = [
    "core/src/main/java/com/couple/translator/core/data/model",
    "feature/couple/src/main/java/com/couple/translator/feature/couple/data/model",
]

#: 后端产出但**不需要**客户端承接的字段，附不承接的原因。
#: 加任何一项都必须写明理由，否则就是拿白名单掩盖真问题。
NO_CLIENT_HOME_OK: Dict[str, str] = {
    "scene": "服务端标注用途（TranslateOutput.scene），客户端不渲染",
    "should_remember": "内部场景 memory_distill 的闸门字段，不对客户端暴露",
    "memory_type": "同上",
    "memory_text": "同上",
}

#: 客户端声明但不由场景模型产出的字段（由别的链路写入），附来源。
CLIENT_EXTRA_OK: Dict[str, str] = {
    "thinking": "由 ai_service.stream_chat_events 写入 structured_output.thinking",
}

AI_DTO_REL = "core/src/main/java/com/couple/translator/core/data/model/AiDto.kt"
SCENE_CATALOG_REL = (
    "feature/couple/src/main/java/com/couple/translator/feature/couple/ai/AiSceneCatalog.kt"
)

JSON_NAME_RE = re.compile(r'@Json\(name\s*=\s*"([a-z0-9_]+)"\)')


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _json_names(text: str) -> Set[str]:
    return set(JSON_NAME_RE.findall(text))


def collect_client_field_providers() -> Tuple[Set[str], Dict[str, List[str]]]:
    """扫描客户端所有 DTO 文件，返回 (字段全集, 字段 → 提供它的文件名列表)。"""
    all_fields: Set[str] = set()
    providers: Dict[str, List[str]] = {}

    for rel in CLIENT_MODEL_DIRS:
        directory = ANDROID_DIR / rel
        if not directory.exists():
            continue
        for kt in sorted(directory.glob("*.kt")):
            for field in _json_names(_read(kt)):
                all_fields.add(field)
                providers.setdefault(field, []).append(kt.name)

    return all_fields, providers


def structured_output_fields() -> Set[str]:
    """只取 `AiDto.StructuredOutput` 这一个 data class 的字段。"""
    text = _read(ANDROID_DIR / AI_DTO_REL)
    match = re.search(r"data class StructuredOutput\((.*?)\n\s*\)", text, re.S)
    if not match:
        raise AssertionError("未能在 AiDto.kt 中定位 StructuredOutput 定义")
    return _json_names(match.group(1))


def backend_scene_fields() -> Set[str]:
    fields: Set[str] = set()
    for model in SCENE_OUTPUT_MODELS.values():
        fields |= set(model.model_fields)
    return fields


def catalog_entries() -> Dict[str, str]:
    """取 AiSceneCatalog 里 presentation 映射：场景键 → 该条目的源码片段。

    返回源码片段而不是只返回键名，是因为还要看 `selectableAsChatScene = false`
    这类标记。只扫 `presentation` 这一段：seed 列表是后端种子的镜像，
    真正需要盯住的是本地样式表里有没有写错键或标错可选性。
    """
    text = _read(ANDROID_DIR / SCENE_CATALOG_REL)
    match = re.search(
        r"private val presentation: Map<String, Presentation> = mapOf\((.*?)\n    \)",
        text,
        re.S,
    )
    if not match:
        raise AssertionError("未能在 AiSceneCatalog.kt 中定位 presentation 映射")

    body = match.group(1)
    entries: Dict[str, str] = {}
    for item in re.finditer(r'"([a-z0-9_]+)"\s+to\s+Presentation\(', body):
        start = item.end()
        # 截到本条目结束（下一个 "xxx" to Presentation( 之前）
        nxt = body.find(" to Presentation(", start)
        entries[item.group(1)] = body[start:nxt if nxt != -1 else len(body)]
    return entries


def main() -> int:
    failures: List[str] = []
    warnings: List[str] = []

    backend_fields = backend_scene_fields()
    client_fields, providers = collect_client_field_providers()
    structured = structured_output_fields()

    print("=" * 72)
    print("客户端 DTO 契约校验（后端产出 ↔ Kotlin DTO）")
    print("=" * 72)
    print(f"后端场景模型字段并集: {len(backend_fields)} 个"
          f"（{len(SCENE_OUTPUT_MODELS)} 个场景 + {len(TEXT_ONLY_SCENES)} 个纯文本场景）")
    print(f"客户端 DTO 字段并集  : {len(client_fields)} 个")
    print(f"AiDto.StructuredOutput: {len(structured)} 个字段")

    # ---- A. 字段承接 ----
    print()
    print("-" * 72)
    print("A. 后端产出的字段，客户端是否接得住")
    print("-" * 72)
    missing: List[str] = []
    for field in sorted(backend_fields):
        if field in client_fields:
            continue
        if field in NO_CLIENT_HOME_OK:
            print(f"  ⊘ {field:26} 无需承接 —— {NO_CLIENT_HOME_OK[field]}")
            continue
        missing.append(field)

    if missing:
        print(f"  ❌ {len(missing)} 个字段在客户端找不到承接方:")
        for field in missing:
            owners = [name for name, model in SCENE_OUTPUT_MODELS.items() if field in model.model_fields]
            print(f"       {field:26} 产出场景: {', '.join(owners)}")
        failures.append(f"A. {len(missing)} 个后端字段客户端无承接方: {', '.join(missing)}")
    else:
        print("  ✅ 后端所有字段都能在客户端找到承接方")

    print()
    print("  明细（字段 → 客户端提供方）:")
    for field in sorted(backend_fields - set(NO_CLIENT_HOME_OK)):
        print(f"    {field:26} ← {', '.join(providers.get(field, []))}")

    # ---- B. 死字段 ----
    print()
    print("-" * 72)
    print("B. 客户端声明、后端从不产出的字段")
    print("-" * 72)
    dead: List[str] = []
    for field in sorted(structured):
        if field in backend_fields or field in CLIENT_EXTRA_OK:
            continue
        dead.append(field)

    if dead:
        print(f"  ❌ {len(dead)} 个死字段（会被误认为功能存在）: {', '.join(dead)}")
        failures.append(f"B. AiDto.StructuredOutput 存在死字段: {', '.join(dead)}")
    else:
        print("  ✅ 没有死字段")
    for field, reason in sorted(CLIENT_EXTRA_OK.items()):
        print(f"  ⊘ {field:26} 非场景字段 —— {reason}")

    # ---- C. 场景键合法 ----
    print()
    print("-" * 72)
    print("C. 客户端本地场景配置的键是否都是后端真实场景")
    print("-" * 72)
    entries = catalog_entries()
    local_keys = set(entries)
    backend_keys = set(SCENE_CONFIGS)
    print(f"  后端 chat 场景 ({len(backend_keys)}): {', '.join(sorted(backend_keys))}")
    print(f"  客户端本地配置 ({len(local_keys)}): {', '.join(sorted(local_keys))}")

    bogus = sorted(local_keys - backend_keys)
    if bogus:
        print(f"  ❌ 客户端凭空造了场景: {', '.join(bogus)}")
        failures.append(f"C. 客户端存在后端不存在的场景键: {', '.join(bogus)}")
    else:
        print("  ✅ 本地配置的场景键全部合法")

    # 后端有、客户端没配样式：不是错误（会用后端 name 兜底），但值得提示
    unstyled = sorted(backend_keys - local_keys)
    if unstyled:
        print(f"  ⚠️  未配 UI 样式的场景（会用后端 name 兜底）: {', '.join(unstyled)}")
        warnings.append(f"C. 未配样式的场景: {', '.join(unstyled)}")

    # ---- D. 可选的聊天场景必须两端齐备 ----
    print()
    print("-" * 72)
    print("D. 客户端可选的聊天场景，后端是否 prompt / 输出模型齐备")
    print("-" * 72)
    not_chat = sorted(
        k for k, block in entries.items() if "selectableAsChatScene = false" in block
    )
    chat_selectable = sorted(local_keys - set(not_chat))
    print(f"  可作为聊天场景 ({len(chat_selectable)}): {', '.join(chat_selectable)}")
    print(f"  标记为不可聊天的   ({len(not_chat)}): {', '.join(not_chat) or '无'}")

    # `build_prompt` 对未知场景会静默回退到 private_advisor 的模板，
    # 于是"缺 prompt + 有输出模型"会变成"用私人军师的提示词、填信件改写的字段"，
    # 接口照常 200、内容却答非所问。这条检查就是拦住它。
    broken: List[str] = []
    for key in chat_selectable:
        has_prompt = key in SYSTEM_PROMPTS
        has_model = key in SCENE_OUTPUT_MODELS
        if not (has_prompt and has_model):
            flaws = []
            if not has_prompt:
                flaws.append("缺 SYSTEM_PROMPTS 模板（build_prompt 会静默回退成 private_advisor）")
            if not has_model:
                flaws.append("缺 SCENE_OUTPUT_MODELS 条目（会静默回退成 TranslateOutput）")
            broken.append(f"{key}: {'；'.join(flaws)}")

    if broken:
        for item in broken:
            print(f"  ❌ {item}")
        failures.append(f"D. {len(broken)} 个聊天场景两端不齐备")
    else:
        print("  ✅ 每个可选的聊天场景都有 prompt 与输出模型")

    # ---- 汇总 ----
    print()
    print("=" * 72)
    if failures:
        print(f"共 {len(failures)} 项失败：")
        for item in failures:
            print(f"  ❌ {item}")
        print("=" * 72)
        return 1

    print("全部通过 —— 后端产出与客户端 DTO 契约一致。")
    if warnings:
        for item in warnings:
            print(f"  ⚠️  {item}")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())
