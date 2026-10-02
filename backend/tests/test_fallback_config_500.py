# -*- coding: utf-8 -*-
"""兜底替身`_FallbackAiConfig` 落库 500 回归（v5.0 内测兜底配套）

## 这条测试挡的是什么

内测期加了「全局兜底」：用户没配自己的 AI 服务时，`resolve()` 不再直接
抛 `AiConfigMissingError`，而是返回一个**字段对齐的只读替身**
`_FallbackAiConfig`，让 `build_chat_client` / `build_embeddings` 两条
只读路径原样复用。

但 `add_key`（`POST /api/v1/users/me/ai-config/keys`）是**写库**路径：
它把 `resolve()` 的返回值当 ORM 行用，取 `config.id` 去填
`user_ai_key.config_id`（`NOT NULL` 外键）。走兜底时替身没有 `.id`
→ `AttributeError` → 端点 500。

复现前提（**缺一不可**，这也是它当初没被测出来的原因）：

1. 用户**没有**自己的 `user_ai_config` 行 —— 有行的用户走 BYOK 分支，
   `resolve()` 返回真ORM 对象，`.id` 正常，测一百遍都是绿的（**假绿**）；
2. `AI_FALLBACK_ENABLED=1` **且**兜底 key 非空 —— 否则 `resolve()` 直接
   抛 `AiConfigMissingError` → 30010，根本走不到 `config.id`。

所以本测试显式把 `app.core.config` 的兜底三件套打开，并**确保库里没有
该用户的配置行**。

## 为什么不给替身补个假 `id`

`user_ai_key.config_id` 是 `ForeignKey("user_ai_config.id") NOT NULL`。
补 `id=0`：MySQL 上撞外键报错（还是 500，只是换了张脸），SQLite 默认不开
外键时**直接写进一条 config_id=0 的孤儿 key 行** —— 更糟，因为用户之后
配好配置、设置页会列出这把来源不明的 key。补 `id=None` 同理。
所以正确解法是**写库路径不许碰替身**（`_require_persisted`）。

## 覆盖

1. 无自有配置 + 兜底开启 → `POST .../ai-config/keys` 返回 30011，**不是 5xx**，
   且库里**没有**落任何 `user_ai_key` 行（不能只测状态码：孤儿行是更隐蔽的坏数据）
2. 同样前置条件下 `_require_persisted` 直接抛 `AiConfigInvalidError`
3. 兜底关闭 + 无配置 → 30010（原本就正确，本测试锁住不被改坏）
4. 已有自有配置的用户 → 正常 200，key 落到**真实** config_id 下（防过度拦截）
5. 替身本身结构自检：刻意没有 `.id`，`is_fallback=True`

全程不碰真实 DB、不发真实 LLM 请求：SQLite 临时库 + `_chat_probe` 打桩。

## 运行

    cd backend && PYTHONUTF8=1 python tests/test_fallback_config_500.py
"""
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from sqlalchemy import BigInteger, Integer, create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

import app.models  # noqa: F401,E402  确保全部模型注册到 Base.metadata
from app.core.database import Base  # noqa: E402
from app.models.user import User  # noqa: E402
from app.models.user_ai_config import UserAiConfig, UserAiKey  # noqa: E402

# SQLite 只对 INTEGER PRIMARY KEY 自增，BIGINT 主键不自增（MySQL 无此问题）
for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()

KEYS_URL = "/api/v1/users/me/ai-config/keys"
AI_CONFIG_URL = "/api/v1/users/me/ai-config"

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def body_code(resp):
    """业务码提取：项目口径为业务错误 HTTP 200 + body 顶层 {code,message,data}。"""
    try:
        body = resp.json()
    except Exception:
        return None
    if isinstance(body, dict):
        if "code" in body:
            return body.get("code")
        detail = body.get("detail")
        if isinstance(detail, dict):
            return detail.get("code")
    return None


def make_db():
    """每个用例一套干净的内存库（StaticPool 保证同一连接）。"""
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


def seed_user(db, email):
    u = User(email=email, password_hash="x")
    db.add(u)
    db.commit()
    db.refresh(u)
    return u


def make_client(db, uid):
    from fastapi.testclient import TestClient
    from app.core.database import get_db
    from app.core.dependencies import get_current_user
    from app.main import app

    app.dependency_overrides[get_db] = lambda: db
    app.dependency_overrides[get_current_user] = lambda: type("U", (), {"id": uid})()
    return TestClient(app, raise_server_exceptions=False), app, get_db, get_current_user


def drop_overrides(app, get_db, get_current_user):
    app.dependency_overrides.pop(get_db, None)
    app.dependency_overrides.pop(get_current_user, None)


def enable_fallback():
    """打开兜底三件套，返回还原函数。"""
    from app.core import config as app_config

    saved = (
        app_config.AI_FALLBACK_ENABLED,
        app_config.AI_FALLBACK_API_KEY,
        app_config.AI_FALLBACK_BASE_URL,
        app_config.AI_FALLBACK_MODEL,
    )
    app_config.AI_FALLBACK_ENABLED = True
    app_config.AI_FALLBACK_API_KEY = "sk-fallback-global"
    app_config.AI_FALLBACK_BASE_URL = "https://fallback.example/v1"
    app_config.AI_FALLBACK_MODEL = "fallback-model"

    def restore():
        (app_config.AI_FALLBACK_ENABLED, app_config.AI_FALLBACK_API_KEY,
         app_config.AI_FALLBACK_BASE_URL, app_config.AI_FALLBACK_MODEL) = saved

    return restore


# --------------------------------------------------------------------------- #
def t_repro_5xx_is_gone():
    """用例 1（主用例）：无自有配置 + 兜底开启 → 30011，不是 5xx，且不落库。"""
    print("\n[1] 无自有配置 + 兜底开启 → POST .../ai-config/keys")
    from app.services import user_ai_config_service as uaicfg

    engine, Session = make_db()
    db = Session()
    uid = seed_user(db, "nofallback@x.com").id
    restore = enable_fallback()
    probed = []
    orig_probe = uaicfg._chat_probe
    uaicfg._chat_probe = lambda *a, **k: probed.append(a)  # 不发真实 LLM
    client, app, gdb, gcu = make_client(db, uid)
    try:
        # 前置自证：这条路径上resolve 确实返回替身（否则本用例是假绿）
        cfg, keys = uaicfg.resolve(db, uid)
        is_fallback = isinstance(cfg, uaicfg._FallbackAiConfig)
        check("前置：resolve 返回兜底替身", is_fallback, type(cfg).__name__)
        check("前置：库里确实没有该用户的配置行",
              uaicfg.get_config(db, uid) is None)

        resp = client.post(KEYS_URL, json={"key": "sk-user-brand-new", "label": "新机"})
        code = body_code(resp)
        print("     HTTP %s code=%s body=%s" % (
            resp.status_code, code, resp.json()))

        check("不是 5xx", resp.status_code < 500, "HTTP %s" % resp.status_code)
        check("业务码 30011（引导先保存配置）", code == 30011, code)
        check("不是 30010（别把已在设置页的用户弹回设置页）", code != 30010, code)

        # 关键：不能只看状态码——孤儿 key 行是更隐蔽的坏数据
        orphan = db.query(UserAiKey).filter(UserAiKey.user_id == uid).all()
        check("未落任何 user_ai_key 行（无孤儿数据）", orphan == [],
              [o.id for o in orphan])
        check("未落任何 user_ai_config 行", db.query(UserAiConfig).count() == 0)
        check("探测未被触发（拦在 LLM 调用之前）", probed == [], probed)
    finally:
        uaicfg._chat_probe = orig_probe
        restore()
        drop_overrides(app, gdb, gcu)
        db.close()
        engine.dispose()


def t_require_persisted_raises():
    """用例 2：`_require_persisted` 对替身直接抛 AiConfigInvalidError。"""
    print("\n[2] _require_persisted 守门")
    from app.services import user_ai_config_service as uaicfg

    restore = enable_fallback()
    try:
        fake = uaicfg._fallback_config()
        check("兜底开启时 _fallback_config() 非 None", fake is not None)
        try:
            uaicfg._require_persisted(fake, 1)
            check("替身被拦下", False, "居然放行了")
        except uaicfg.AiConfigInvalidError as exc:
            check("替身被拦下（AiConfigInvalidError）", True)
            check("文案指向「先保存配置」",
                  "保存配置" in exc.message, exc.message)
        except Exception as exc:
            check("替身被拦下（AiConfigInvalidError）", False,
                  "%s: %s" % (type(exc).__name__, exc))

        # 真实 ORM 行必须放行（防过度拦截）
        engine, Session = make_db()
        db = Session()
        u = seed_user(db, "realcfg@x.com")
        real = UserAiConfig(
            user_id=u.id, provider_type="openai",
            base_url="https://real.example/v1", model_name="real-model",
        )
        db.add(real)
        db.commit()
        db.refresh(real)
        got = uaicfg._require_persisted(real, u.id)
        check("真实 ORM 行放行", got is real)
        db.close()
        engine.dispose()
    finally:
        restore()


def t_fallback_off_still_30010():
    """用例 3：兜底关闭 + 无配置 → 30010（原本正确，锁住不被改坏）。"""
    print("\n[3] 兜底关闭 + 无配置 → 30010")
    from app.core import config as app_config
    from app.services import user_ai_config_service as uaicfg

    engine, Session = make_db()
    db = Session()
    uid = seed_user(db, "nofb_off@x.com").id
    saved = app_config.AI_FALLBACK_ENABLED
    app_config.AI_FALLBACK_ENABLED = False
    orig_probe = uaicfg._chat_probe
    uaicfg._chat_probe = lambda *a, **k: None
    client, app, gdb, gcu = make_client(db, uid)
    try:
        resp = client.post(KEYS_URL, json={"key": "sk-x"})
        code = body_code(resp)
        print("     HTTP %s code=%s" % (resp.status_code, code))
        check("业务码 30010", code == 30010, code)
        check("不是 5xx", resp.status_code < 500, resp.status_code)
    finally:
        uaicfg._chat_probe = orig_probe
        app_config.AI_FALLBACK_ENABLED = saved
        drop_overrides(app, gdb, gcu)
        db.close()
        engine.dispose()


def t_existing_config_not_blocked():
    """用例 4：已有自有配置的用户正常加 key（防「一刀切」回归）。"""
    print("\n[4] 已有自有配置 → 正常 200，key 落到真实 config_id")
    from app.services import user_ai_config_service as uaicfg

    engine, Session = make_db()
    db = Session()
    u = seed_user(db, "hasconfig@x.com")
    cfg = UserAiConfig(
        user_id=u.id, provider_type="openai",
        base_url="https://real.example/v1", model_name="real-model",
    )
    db.add(cfg)
    db.commit()
    db.refresh(cfg)
    from app.core.crypto import encrypt_text
    db.add(UserAiKey(user_id=u.id, config_id=cfg.id,
                     key_enc=encrypt_text("sk-existing"), enabled=True))
    db.commit()

    restore = enable_fallback()
    orig_probe = uaicfg._chat_probe
    uaicfg._chat_probe = lambda *a, **k: None
    client, app, gdb, gcu = make_client(db, u.id)
    try:
        resp = client.post(KEYS_URL, json={"key": "sk-user-second", "label": "备用"})
        code = body_code(resp)
        body = resp.json()
        print("     HTTP %s body=%s" % (resp.status_code, body))
        check("HTTP 200", resp.status_code == 200, resp.status_code)
        check("业务码 0（成功）", code == 0, code)
        rows = db.query(UserAiKey).filter(UserAiKey.user_id == u.id).all()
        check("库里有2 把 key", len(rows) == 2, len(rows))
        check("新 key 挂在真实 config_id=%s 下" % cfg.id,
              all(r.config_id == cfg.id for r in rows),
              [r.config_id for r in rows])
        check("返回的 id 指向真实行",
              (body.get("data") or {}).get("id") in [r.id for r in rows],
              body.get("data"))
        check("明文 key 不外泄", "sk-user-second" not in str(body), body)
    finally:
        uaicfg._chat_probe = orig_probe
        restore()
        drop_overrides(app, gdb, gcu)
        db.close()
        engine.dispose()


def t_fallback_shape():
    """用例 5：替身结构自检—— 刻意没有 .id，标记齐全。"""
    print("\n[5] _FallbackAiConfig 结构自检")
    from app.services import user_ai_config_service as uaicfg

    restore = enable_fallback()
    try:
        fb = uaicfg._fallback_config()
        check("刻意没有 .id（写库路径必须自己拦）",
              not hasattr(fb, "id"), "竟有 id=%r" % getattr(fb, "id", None))
        check("is_fallback=True", getattr(fb, "is_fallback", False) is True)
        check("user_id=0（不指向任何真实用户）", fb.user_id == 0, fb.user_id)
        check("keys=[]（不参与 DB 轮换）", fb.keys == [], fb.keys)
        for attr in ("provider_type", "base_url", "model_name",
                     "embedding_model", "embedding_dim", "key_cursor",
                     "enable_rate_limit", "api_key"):
            check("只读路径字段齐全：%s" % attr, hasattr(fb, attr))
    finally:
        restore()


def t_source_no_late_binding():
    """用例 6：源码静态断言——写库路径不得直接吃resolve() 的返回值。"""
    print("\n[6] 源码静态断言")
    path = os.path.join(BACKEND_DIR, "app", "services", "user_ai_config_service.py")
    src = open(path, encoding="utf-8").read()
    add_key_body = src.split("def add_key(", 1)[1].split("\ndef ", 1)[0]
    check("add_key 调用了 _require_persisted",
          "_require_persisted" in add_key_body)
    check("add_key 先守门再取 config.id",
          add_key_body.index("_require_persisted") < add_key_body.index("config.id"))
    check("替身类注释说明了为何不给假 id",
          "刻意没有" in src.split("class _FallbackAiConfig", 1)[1][:2000])
    # 全量扫：还有没有别处直接吃 resolve() 结果当 ORM 行用
    import subprocess
    out = subprocess.run(
        ["git", "grep", "-n", "--", "= resolve(db, user_id)"],
        cwd=BACKEND_DIR, capture_output=True, text=True,
    ).stdout.strip()
    print("     resolve() 直接赋值点：\n     %s" % (out.replace("\n", "\n     ") or "(无)"))
    check("resolve() 的调用点数量已收敛到 4 处（build_chat_client / "
          "build_embeddings / add_key / 定义处）",
          len([l for l in out.splitlines() if l.strip()]) <= 4, out)


def main() -> int:
    print("=" * 72)
    print("兜底替身落库 500 回归（add_key + _FallbackAiConfig.id）")
    print("=" * 72)
    t_repro_5xx_is_gone()
    t_require_persisted_raises()
    t_fallback_off_still_30010()
    t_existing_config_not_blocked()
    t_fallback_shape()
    t_source_no_late_binding()
    print("\n" + "=" * 72)
    if FAILURES:
        print("结果：FAIL %d 项 → %s" % (len(FAILURES), FAILURES))
        return 1
    print("结果：全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
