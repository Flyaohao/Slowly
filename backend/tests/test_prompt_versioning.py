"""Prompt 版本化 / A/B 分流测试（SQLite 内存库，不需要 MySQL / API Key）

## 覆盖什么

`resolve_system_prompt()` 是 prompt 的唯一解析入口，它必须同时满足两件互相
拉扯的事：**能从库里换模板**（版本化 / A/B 灰度），且**库出问题时绝不影响主链路**。

1. **回退契约**：db 为 None、库里无模板、库查询抛异常、模板内容被写坏，
   四种情况都必须静默回退到内置 `SYSTEM_PROMPTS`；
2. **花括号转义**：入库按存储约定写成 `{{user_profile}}`，取出必须还原成
   `{user_profile}`——不还原的话 `.format()` 拿到的是字面量，画像静默丢失；
3. **A/B 稳定分流**：同一 `user_id` 反复解析必须命中同一版本（横跳会污染体验
   且让实验数据不可比），不同 `user_id` 能覆盖到不同版本；
4. **单版本语义**：只有一个 active 版本时取它，与旧 `get_active_prompt_template` 一致；
5. **两条出口同源**：`build_prompt`（结构化）与 `build_stream_prompt`（流式）
   接受同一份 `system_template`，不会出现版本分叉。

## 运行

    cd backend && python tests/test_prompt_versioning.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

from sqlalchemy import BigInteger, Integer, create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app.core.database import Base  # noqa: E402
import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata

# SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()

from app.models.ai import AiPromptTemplate, AiScene  # noqa: E402
from app.repositories import ai_repo  # noqa: E402
from app.services.prompt_builder import (  # noqa: E402
    SYSTEM_PROMPTS,
    build_prompt,
    build_stream_prompt,
    resolve_system_prompt,
)

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
Base.metadata.create_all(engine)
Session = sessionmaker(bind=engine)

FAILURES = []


def case(name: str, fn):
    try:
        fn()
    except AssertionError as exc:
        FAILURES.append("%s -> %s" % (name, exc))
        print("  FAIL  %s" % name)
    except Exception as exc:  # noqa: BLE001
        FAILURES.append("%s -> %r" % (name, exc))
        print("  ERROR %s -> %r" % (name, exc))
    else:
        print("  PASS  %s" % name)


SCENE = "cold_war"
SCENE_FALLBACK = SYSTEM_PROMPTS[SCENE]


def _fresh_db():
    """清空模板表并确保场景存在（FK 约束）。"""
    db = Session()
    db.query(AiPromptTemplate).delete()
    if not db.query(AiScene).filter(AiScene.scene_key == SCENE).first():
        db.add(AiScene(scene_key=SCENE, name="冷战调解", description="test"))
    db.commit()
    return db


def _add_template(db, content: str, version: int, status: str = "active"):
    db.add(AiPromptTemplate(
        scene_key=SCENE, template_content=content, version=version, status=status
    ))
    db.commit()


# --------------------------------------------------------------------- #
# 1. 回退契约
# --------------------------------------------------------------------- #
def t_fallback_when_db_none():
    assert resolve_system_prompt(SCENE, db=None) == SCENE_FALLBACK


def t_fallback_when_no_template():
    db = _fresh_db()
    try:
        got = resolve_system_prompt(SCENE, db=db, user_id=1)
        assert got == SCENE_FALLBACK, "库里无模板时应回退内置模板"
        # 未注册场景也一样回退到默认场景，不能抛异常
        assert resolve_system_prompt("not_a_scene", db=db) == SYSTEM_PROMPTS["private_advisor"]
    finally:
        db.close()


def t_fallback_when_query_raises():
    original = ai_repo.get_active_prompt_template

    def _boom(*a, **kw):
        raise RuntimeError("db down")

    ai_repo.get_active_prompt_template = _boom
    try:
        assert resolve_system_prompt(SCENE, db=object(), user_id=1) == SCENE_FALLBACK
    finally:
        ai_repo.get_active_prompt_template = original


def t_fallback_when_template_broken():
    """模板被写坏（不含 {user_profile}）时必须回退，而不是带着空画像硬跑。"""
    db = _fresh_db()
    try:
        _add_template(db, "你是一位调解师，没有任何占位符。", version=1)
        assert resolve_system_prompt(SCENE, db=db, user_id=1) == SCENE_FALLBACK
    finally:
        db.close()


def t_fallback_when_blank():
    db = _fresh_db()
    try:
        _add_template(db, "   \n  ", version=1)
        assert resolve_system_prompt(SCENE, db=db, user_id=1) == SCENE_FALLBACK
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 2. 花括号转义还原
# --------------------------------------------------------------------- #
def t_unescape_braces():
    db = _fresh_db()
    try:
        raw = "人设A\n画像：{user_profile}\n伴侣：{partner_profile}\n冲突：{conflict_pattern}\n历史：{history}"
        stored = raw.replace("{", "{{").replace("}", "}}")   # 与 seed 脚本同一约定
        _add_template(db, stored, version=1)

        got = resolve_system_prompt(SCENE, db=db, user_id=1)
        assert got == raw, "取出的模板必须把 {{ }} 还原成 { }"
        # 还原不到位时 format 会失败或留下双花括号
        rendered = got.format(
            user_profile="U", partner_profile="P", conflict_pattern="C", history="H"
        )
        assert "{{" not in rendered and "U" in rendered
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 3. A/B 稳定分流
# --------------------------------------------------------------------- #
def t_single_version_takes_it():
    db = _fresh_db()
    try:
        _add_template(db, "唯一版本 {user_profile}", version=1)
        assert resolve_system_prompt(SCENE, db=db, user_id=7) == "唯一版本 {user_profile}"
    finally:
        db.close()


def t_ab_stable_and_spread():
    db = _fresh_db()
    try:
        _add_template(db, "版本一 {user_profile}", version=1)
        _add_template(db, "版本二 {user_profile}", version=2)

        first = resolve_system_prompt(SCENE, db=db, user_id=4)
        # 同一 user 反复解析必须稳定
        for _ in range(5):
            assert resolve_system_prompt(SCENE, db=db, user_id=4) == first, "同一 user 命中版本发生横跳"

        seen = {resolve_system_prompt(SCENE, db=db, user_id=u) for u in range(8)}
        assert seen == {"版本一 {user_profile}", "版本二 {user_profile}"}, "不同 user 未覆盖到两个版本"
    finally:
        db.close()


def t_inactive_version_ignored():
    db = _fresh_db()
    try:
        _add_template(db, "已停用 {user_profile}", version=9, status="archived")
        _add_template(db, "生效中 {user_profile}", version=1)
        assert resolve_system_prompt(SCENE, db=db, user_id=3) == "生效中 {user_profile}"
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 4. repo 层向后兼容
# --------------------------------------------------------------------- #
def t_repo_backward_compatible():
    db = _fresh_db()
    try:
        _add_template(db, "v1 {user_profile}", version=1)
        _add_template(db, "v2 {user_profile}", version=2)
        # 不传 user_id：保持旧语义 = 取最高版本
        tpl = ai_repo.get_active_prompt_template(db, SCENE)
        assert tpl is not None and tpl.version == 2, "不传 user_id 应取最高版本"
        assert ai_repo.get_active_prompt_template(db, "no_such_scene") is None
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 5. 两条出口同源
# --------------------------------------------------------------------- #
def t_two_outlets_share_template():
    db = _fresh_db()
    try:
        _add_template(db, "共用 {user_profile} / {partner_profile} / {conflict_pattern} / {history}",
                      version=1)
        tmpl = resolve_system_prompt(SCENE, db=db, user_id=1)
        args = dict(
            scene_key=SCENE, user_profile="UP", partner_profile="PP",
            conflict_pattern="CP", user_input="IN", history="HS",
            rag_context="RAG", memory_context="MEM",
        )
        structured = build_prompt(**args, system_template=tmpl)
        streamed = build_stream_prompt(**args, system_template=tmpl)

        assert "UP" in structured and "IN" in structured and "RAG" in structured
        assert "UP" in streamed and "IN" in streamed
        assert "请以 JSON 格式回复" not in streamed, "流式版必须截掉结构化字段说明"
        # 不传 system_template 时行为与老代码一致
        assert build_prompt(**args) == build_prompt(**args, system_template=None)
    finally:
        db.close()


# --------------------------------------------------------------------- #
# 6. 源码级：ai_service 主链路确实接上了
# --------------------------------------------------------------------- #
def t_wired_into_main_chain():
    src = open(
        os.path.join(BACKEND_DIR, "app", "services", "ai_service.py"), encoding="utf-8"
    ).read()
    assert "resolve_system_prompt(scene_key" in src, "主链路未调用 resolve_system_prompt"
    assert src.count("system_template=system_template") >= 2, \
        "结构化与流式两条出口未共用同一份 system_template"


def main():
    print("=" * 72)
    print("Prompt 版本化 / A/B 分流")
    print("=" * 72)
    cases = [
        ("db=None 回退内置模板", t_fallback_when_db_none),
        ("库中无模板回退", t_fallback_when_no_template),
        ("库查询异常回退", t_fallback_when_query_raises),
        ("模板缺占位符回退", t_fallback_when_template_broken),
        ("空模板回退", t_fallback_when_blank),
        ("花括号转义还原", t_unescape_braces),
        ("单版本取该版本", t_single_version_takes_it),
        ("A/B 稳定分流", t_ab_stable_and_spread),
        ("非 active 版本被忽略", t_inactive_version_ignored),
        ("repo 不传 user_id 向后兼容", t_repo_backward_compatible),
        ("结构化/流式两条出口同源", t_two_outlets_share_template),
        ("主链路确实接线", t_wired_into_main_chain),
    ]
    for name, fn in cases:
        case(name, fn)

    print()
    print("=" * 72)
    if FAILURES:
        print("失败 %d 项：" % len(FAILURES))
        for f in FAILURES:
            print("  -", f)
        sys.exit(1)
    print("共 %d 项检查全部通过 —— 版本化与回退契约均成立。" % len(cases))
    print("=" * 72)


if __name__ == "__main__":
    main()
