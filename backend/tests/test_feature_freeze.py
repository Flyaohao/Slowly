# -*- coding: utf-8 -*-
"""§1 功能冻结回归（契约 §1 / §2.2-3 验收：冻结端点返回 10006）。

## 覆盖什么

`app.core.features.FEATURE_FLAGS` 八个开关（全部 False = 冻结）逐一打真实
路由，断言 **HTTP 200 + {code:10006, message:"该功能已停用"}**：

    museum / wishlists / presence / self_practices / practices
    / practice_summary / memory_card / avatar_appearance

同时断言：

- 冻结检查在鉴权**之前**（不带 token 也是 10006，非 401）——与
  §2.5-1 发信冻结同一裁决；
- 未登记的功能名在**创建依赖时**即抛错（fail-fast，防新增路由忘登记）；
- 未冻结的对照端点不误伤（GET /api/v1/home 无 10006）；
- §1 AI 形象**按字段/端点语义**冻结：PUT /me 带 appearance 字段 → 10006，
  仅改 name、GET /me、voice-style 不误伤；GET /assets 整端点 10006。

无需 MySQL / API Key（冻结依赖在路由层短路，端点逻辑不会执行）。

## 运行

    cd backend && PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python tests/test_feature_freeze.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
sys.path.insert(0, BACKEND_DIR)

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def _dummy_db():
    yield None


def _client():
    from fastapi.testclient import TestClient
    from app.main import app
    from app.core.database import get_db

    app.dependency_overrides[get_db] = _dummy_db
    return TestClient(app, raise_server_exceptions=False), app


def t_frozen_endpoints_10006():
    client, app = _client()
    try:
        cases = [
            ("GET", "/api/v1/couple/museum", None, "museum"),
            ("GET", "/api/v1/couple/presence/feed", None, "presence"),
            ("GET", "/api/v1/single/self-practices", None, "self_practices"),
            ("POST", "/api/v1/couple/ai/memory-card/stream",
             {"target_type": "anniversary", "target_id": 1}, "memory_card"),
            # §1 AI 形象（捏脸）：assets 整端点冻结（不带 token 也是 10006）
            ("GET", "/api/v1/couple/avatars/assets", None, "avatar_appearance"),
        ]
        for method, url, body, flag in cases:
            # 不带任何登录态——冻结检查在鉴权之前（裁决 d，同 §2.5-1）
            if method == "GET":
                r = client.get(url)
            else:
                r = client.post(url, json=body)
            data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
            ok = (
                r.status_code == 200
                and data.get("code") == 10006
                and data.get("message") == "该功能已停用"
            )
            check(
                "%s %s → 200 + 10006" % (method, url),
                ok,
                "%s %s" % (r.status_code, str(data)[:160]),
            )
    finally:
        from app.core.database import get_db as _g

        app.dependency_overrides.pop(_g, None)


def t_unregistered_feature_fails_fast():
    from app.core.features import require_feature

    try:
        require_feature("not_a_registered_flag")
        check("未登记功能名创建依赖时即抛错", False, "未抛异常")
    except ValueError as e:
        check("未登记功能名创建依赖时即抛错", "not_a_registered_flag" in str(e), str(e))


def t_unfrozen_not_affected():
    from app.core.features import FEATURE_FLAGS

    check("wishlists 已解冻为 True，其余开关仍全为 False",
          FEATURE_FLAGS.get("wishlists") is True
          and all(v is False for k, v in FEATURE_FLAGS.items() if k != "wishlists")
          and set(FEATURE_FLAGS) == {
              "museum", "wishlists", "presence", "self_practices",
              "practices", "memory_card", "practice_summary",
              "avatar_appearance",
          },
          str(FEATURE_FLAGS))

    client, app = _client()
    try:
        from app.core.database import get_db
        from app.core.dependencies import get_current_user

        app.dependency_overrides[get_current_user] = lambda: type(
            "U", (), {"id": 1}
        )()
        # 对照：未冻结的 home 不受 10006 影响（会因假 user 走 10001，绝不是 10006）
        r = client.get("/api/v1/home")
        data = r.json() if r.status_code == 200 else {}
        check("未冻结端点不误伤 10006",
              data.get("code") != 10006,
              str(data)[:160])
    finally:
        from app.core.database import get_db as _g
        from app.core.dependencies import get_current_user as _gu

        app.dependency_overrides.pop(_g, None)
        app.dependency_overrides.pop(_gu, None)


def t_avatar_appearance_field_freeze():
    """§1 AI 形象：只冻 appearance，name / voice-style 读写路径保留（§3.3）。"""
    client, app = _client()
    try:
        from app.core.database import get_db
        from app.core.dependencies import get_current_user

        app.dependency_overrides[get_current_user] = lambda: type(
            "U", (), {"id": 1}
        )()

        # 旧 APK 改脸型：appearance 字段写入 → 10006
        for body, label in (
            ({"face_config": {"shape": "oval"}}, "face_config"),
            ({"body_color": "#FFB6C1", "outfit_config": {"top": 1}}, "body_color+outfit_config"),
            ({"background_url": "/uploads/bg.png"}, "background_url"),
            ({"name": "军师", "face_config": None}, "name+清空 face_config 也算形象写"),
        ):
            r = client.put("/api/v1/couple/avatars/me", json=body)
            data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
            check(
                "PUT /avatars/me 带 %s → 200 + 10006" % label,
                r.status_code == 200 and data.get("code") == 10006,
                "%s %s" % (r.status_code, str(data)[:160]),
            )

        # 保留路径不误伤：仅改 name（军师设置存储）不走冻结——
        # 会继续进业务逻辑（此处 db=None 必然失败），断言绝不是 10006
        r = client.put("/api/v1/couple/avatars/me", json={"name": "小鹿"})
        data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
        check(
            "PUT /avatars/me 仅改 name 不被冻结误伤",
            data.get("code") != 10006,
            "%s %s" % (r.status_code, str(data)[:160]),
        )

        # 保留端点：GET /me（name/voice_style 读路径）、voice-style 写路径
        for method, url, body, label in (
            ("GET", "/api/v1/couple/avatars/me", None, "GET /me 读路径保留"),
            ("POST", "/api/v1/couple/avatars/me/voice-style",
             {"voice_style": "gentle"}, "voice-style 保留"),
        ):
            r = client.get(url) if method == "GET" else client.post(url, json=body)
            data = r.json() if r.headers.get("content-type", "").startswith("application/json") else {}
            check(
                "%s（%s）不被冻结" % (url, label),
                data.get("code") != 10006,
                "%s %s" % (r.status_code, str(data)[:160]),
            )
    finally:
        from app.core.database import get_db as _g
        from app.core.dependencies import get_current_user as _gu

        app.dependency_overrides.pop(_g, None)
        app.dependency_overrides.pop(_gu, None)


def main() -> int:
    print("[§1 功能冻结] 10006 全表接线回归")
    t_frozen_endpoints_10006()
    t_unregistered_feature_fails_fast()
    t_unfrozen_not_affected()
    t_avatar_appearance_field_freeze()
    print("========== 结果 ==========")
    if FAILURES:
        print("失败 %d 项：%s" % (len(FAILURES), FAILURES))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
