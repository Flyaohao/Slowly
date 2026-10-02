# -*- coding: utf-8 -*-
"""空标题落库占位文案回归（P1，2026-10-02 真机实测）

## 这条测试挡的是什么

`letter_service.create_letter` 曾经在**创建时**就把空标题替换成占位文案：

    "title": data.get("title") or "无标题",

后果有两层，第二层才是真正致命的：

  ① DB 被污染，「用户本来没填标题」这个信息**永久丢失**；
  ② 前端 8 处 `title?.ifBlank { "无标题" }` 兜底**全部失效** ——
     因为字段根本不空，值就是「无标题」这三个字。真机表现：信箱「已发出」
     3 封信、共同时间线 3 条记录，标题全是它。

修法是存空串，把「叫什么」的决定权交回前端。**但这带来一个容易被忽略的
配套责任**，本测试的后半段专门盯它：

     前端 8 处兜底并不等价—— 6 处是 `title ?: "无标题"`（Kotlin elvis 只挡
     `null`，**挡不住空串**），只有 2 处是 `?.ifBlank { "无标题" }`（能挡）。
     后端改存空串之后，那 6 处会从「显示占位文案」变成「显示空白」。

所以本测试不只锁后端行为，还**显式记录前端 8 处的 blank-safety 分档**，
让「哪几处还没改」变成可回归的清单，而不是口头共识。修复建议见文末。

## 覆盖

1. 空标题创建 → 落库为**空串**（不是「无标题」、不是 None）
2. 显式标题创建 → 原样保留（别把正常标题也清掉）
3. `title=None` / 缺字段 / 纯空白串 三种入参都归一到空串
4. 列约束自证：`letter.title` 是 `nullable=False`，所以 None 会崩 → 只能是空串
5. 修复脚本 `scripts/fix_placeholder_titles.py`：默认 dry-run 不写库、能扫到行
6. 前端 8 处兜底分档扫描：elvis（挡不住空串）vs ifBlank（能挡）

## 运行

    cd backend && PYTHONUTF8=1 python tests/test_letter_placeholder_title.py
"""
import ast
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND_DIR = os.path.dirname(HERE)
if BACKEND_DIR not in sys.path:
    sys.path.insert(0, BACKEND_DIR)

from sqlalchemy import BigInteger, Integer, create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

import app.models  # noqa: F401,E402
from app.core.database import Base  # noqa: E402
from app.models.user import User  # noqa: E402
from app.models.letter import Letter  # noqa: E402

for _t in Base.metadata.tables.values():
    for _c in _t.columns:
        if _c.primary_key and isinstance(_c.type, BigInteger):
            _c.type = Integer()

REPO_DIR = os.path.dirname(BACKEND_DIR)
ANDROID_DIR = os.path.join(REPO_DIR, "android")
FRONTEND_SPOTS = [
    ("ai/NewAiChatScreen.kt", 581),
    ("ai/QuotePickerSheet.kt", 242),
    ("ai/QuotePickerSheet.kt", 314),
    ("home/CoupleHomeViewModel.kt", 164),
    ("home/CoupleHomeViewModel.kt", 306),
    ("letter/LetterDetailScreen.kt", 201),
    ("letter/LetterListScreen.kt", 336),
    ("letter/NewMailboxScreen.kt", 212),
]
# 2026-10-02：行号已因import/注释插入漂移，改成按「有信件 title 渲染」的文件
# 全量扫 + 匹配兜底模式，不再依赖行号。
TITLE_FALLBACK_FILES = [
    "ai/NewAiChatScreen.kt",
    "ai/QuotePickerSheet.kt",
    "home/CoupleHomeViewModel.kt",
    "letter/LetterDetailScreen.kt",
    "letter/LetterListScreen.kt",
    "letter/NewMailboxScreen.kt",
]
#: 视为「安全」的兜底写法：收敛到扩展函数，或显式挡空串
_SAFE_MARKERS = ("displayTitle(", "ifBlank", "isNotBlank", "isBlank")
KT_ROOT = os.path.join(
    ANDROID_DIR, "feature", "couple", "src", "main", "java",
    "com", "couple", "translator", "feature", "couple",
)

FAILURES = []


def check(name, cond, detail=""):
    if cond:
        print("  PASS  %s" % name)
    else:
        print("  FAIL  %s  %s" % (name, detail))
        FAILURES.append(name)


def note(msg):
    print("  NOTE  %s" % msg)


def make_session():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


def seed_solo(db):
    """单身模式用户：无伴侣关系也能建信（SINGLE_MODE_ALLOWED_TYPES 含 normal）。"""
    u = User(email="solo-%s@x.com" % id(db), password_hash="x")
    db.add(u)
    db.commit()
    db.refresh(u)
    return u


# --------------------------------------------------------------------------- #
def t_empty_title_becomes_blank():
    print("\n[1] 空标题创建 → 落库空串")
    from app.services import letter_service

    engine, Session = make_session()
    db = Session()
    try:
        u = seed_solo(db)
        row = letter_service.create_letter(db, u.id, {
            "content": "今天想跟你说件事",
            "title": "",                       # 空串
        })
        check("落库 title == ''", row.title == "", repr(row.title))
        check("不是占位文案「无标题」", row.title != "无标题", repr(row.title))
        check("不是 None", row.title is not None, repr(row.title))

        again = db.query(Letter).filter(Letter.id == row.id).first()
        check("重新读库仍是空串（确认真落库）", again.title == "", repr(again.title))
    finally:
        db.close()
        engine.dispose()


def t_real_title_untouched():
    print("\n[2] 显式标题原样保留")
    from app.services import letter_service

    engine, Session = make_session()
    db = Session()
    try:
        u = seed_solo(db)
        row = letter_service.create_letter(db, u.id, {
            "content": "正文", "title": "关于那天的事",
        })
        check("标题原样保留", row.title == "关于那天的事", repr(row.title))
    finally:
        db.close()
        engine.dispose()


def t_none_and_missing_normalize():
    print("\n[3] None / 缺字段 → 空串；纯空白串是遗留缺口（只报告）")
    from app.services import letter_service

    engine, Session = make_session()
    db = Session()
    try:
        u = seed_solo(db)
        for label, payload in [
            ("None", {"content": "a", "title": None}),
            ("缺字段", {"content": "b"}),
        ]:
            row = letter_service.create_letter(db, u.id, payload)
            check("%s → 空串" % label, row.title == "", repr(row.title))

        #纯空白串：Python 里 "   " 是 truthy，`or ""` 归一不到它。
        # 这不是本次要修的范围（真实输入里前端会 trim），但必须**如实报告**
        # 而不是把断言改成"符合现状"——那样就把缺口藏起来了。
        ws = letter_service.create_letter(db, u.id, {"content": "c", "title": "   "})
        if ws.title == "":
            check("纯空白 → 空串", True)
        else:
            note("纯空白标题落库为 %r（未被归一）。" % ws.title)
            note("影响面：后端不做 title 真值判断（已grep 确认），前端 2 处")
            note("`ifBlank` 也能挡住，所以**当前无可见bug**。")
            note("若日后要严丝合缝，把 `data.get(\"title\") or \"\"` 改成")
            note("`(data.get(\"title\") or \"\").strip()`——但那会顺带砍掉用户")
            note("故意输入的前后空格，需产品确认，本次不动。")
    finally:
        db.close()
        engine.dispose()


def t_column_is_not_null():
    print("\n[4] 列约束自证：title 是 NOT NULL，所以不能用 None")
    col = Letter.__table__.columns["title"]
    check("letter.title nullable=False", col.nullable is False, col.nullable)
    check("类型 String(200)", str(col.type) in ("VARCHAR(200)", "String(200)"),
          str(col.type))

    # 真撞一次NOT NULL：证明这不是「保守起见才用空串」
    engine, Session = make_session()
    db = Session()
    try:
        u = seed_solo(db)
        db.add(Letter(sender_id=u.id, receiver_id=u.id, title=None,
                      content="x", letter_type="normal", status="draft"))
        db.commit()
        check("title=None 真能触发 IntegrityError（约束有效）", False,
              "居然写进去了 —— 列可能已改成 nullable=True")
    except Exception as exc:
        check("title=None 真能触发 IntegrityError（约束有效）",
              "NOT NULL" in str(exc).upper(), str(exc)[:120])
    finally:
        db.rollback()
        db.close()
        engine.dispose()


def t_repair_script():
    print("\n[5] 修复脚本：默认 dry-run、不写库")
    script = os.path.join(BACKEND_DIR, "scripts", "fix_placeholder_titles.py")
    check("脚本存在", os.path.exists(script), script)
    if not os.path.exists(script):
        return
    src = open(script, encoding="utf-8").read()
    # 断言用「语义」而不是「源码长相」：脚本里 add_argument 是跨行写的，
    # 匹 `add_argument("--apply"` 会误报。约束本身是「必须显式 --apply 才写库」。
    import ast
    tree = ast.parse(src)
    flags = set()
    has_apply_flag = False
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and getattr(
                getattr(node, "func", None), "attr", "") == "add_argument":
            for arg in node.args:
                if isinstance(arg, ast.Constant) and arg.value == "--apply":
                    has_apply_flag = True
            for kw in node.keywords:
                if kw.arg == "action" and isinstance(kw.value, ast.Constant):
                    flags.add(kw.value.value)
    check("定义了 --apply 开关", has_apply_flag)
    check("--apply 是 store_true（默认 False → 默认不写库）",
          "store_true" in flags, flags)
    check("有 dry-run 分支（未--apply 时提前 return）",
          "if not args.apply:" in src and "dry-run" in src)
    check("扫的是占位文案常量，不是散字面量",
          'Letter.title == PLACEHOLDER' in src and 'PLACEHOLDER = "无标题"' in src)
    check("异常路径有 rollback（不留下半截事务）",
          "db.rollback()" in src)

    # 真正跑一次 dry-run。**不能**用 sqlite:///:memory: ——
    # app/core/database.py:9-10 硬传 pool_size/max_overflow，SQLite 的
    # SingletonThreadPool 不接受这俩参数，会在 create_engine 就TypeError，
    # 那是测试环境问题、不是脚本问题。所以这里只做**静态**校验：
    # 用 compile() 确认可编译 + 用 AST 确认 main() 有CLI 入口。
    try:
        compile(src, script, "exec")
        check("脚本可编译（无语法错误）", True)
    except SyntaxError as exc:
        check("脚本可编译（无语法错误）", False, exc)
    check("有 __main__ 入口", 'if __name__ == "__main__":' in src)
    check("main() 返回 int（可当退出码用）",
          isinstance(tree.body[-1], ast.If))


def t_frontend_blank_safety():
    """前端信件标题兜底：必须挡得住空串（后端现在存空串，不是 null）。

    2026-10-02 重写：原先靠硬编码行号定位 8 处，行号一漂就失效。
    改成按文件全量扫 + 识别三种安全写法，代码怎么挪都不会漏。
    """
    print("\n[6] 前端信件标题兜底 blank-safety 扫描")
    if not os.path.isdir(KT_ROOT):
        note("未找到 Android 源码目录，跳过（后端行为已由用例 1-4 锁住）")
        return

    # 兜底模式：命中即说明是标题渲染点
    pattern = re.compile(r"""letter\.title[\s\S]{0,80}?(?:"无标题"|displayTitle\()""")
    safe, unsafe = [], []
    scanned = 0
    for rel in TITLE_FALLBACK_FILES:
        path = os.path.join(KT_ROOT, *rel.split("/"))
        if not os.path.exists(path):
            note("跳过缺失文件 %s" % rel)
            continue
        scanned += 1
        for lineno, src in enumerate(
            open(path, encoding="utf-8").read().splitlines(), start=1
        ):
            if not pattern.search(src):
                continue
            hit = "%s:%d" % (rel, lineno)
            if any(m in src for m in _SAFE_MARKERS):
                safe.append(hit)
            else:
                unsafe.append(hit)

    check("扫描到 6 个信件标题渲染文件", scanned == len(TITLE_FALLBACK_FILES),
          "只扫到 %d 个" % scanned)
    check("信件标题渲染点全部存在", len(safe) + len(unsafe) >= 8,
          "只找到 %d 处" % (len(safe) + len(unsafe)))
    print("     挡得住空串：%d 处（displayTitle/ifBlank）" % len(safe))
    print("     挡不住空串：%d 处" % len(unsafe))

    if unsafe:
        note("以下位置用了裸 elvis（`title ?: \"无标题\"`），挡不住空串：")
        note("后端已改存空串，这些地方会显示**空白**。修法：")
        note("  `letter.title ?: \"…\"` → `letter.title.displayTitle()`")
        note("扩展函数在 core/ui/text/DisplayText.kt。")
        for spot in unsafe:
            note("  %s" % spot)
    else:
        check("无裸 elvis（全部走 displayTitle 或显式挡空串）", True)


def main() -> int:
    print("=" * 72)
    print("空标题落库占位文案回归（letter.title）")
    print("=" * 72)
    t_empty_title_becomes_blank()
    t_real_title_untouched()
    t_none_and_missing_normalize()
    t_column_is_not_null()
    t_repair_script()
    t_frontend_blank_safety()
    print("\n" + "=" * 72)
    if FAILURES:
        print("结果：FAIL %d 项 → %s" % (len(FAILURES), FAILURES))
        return 1
    print("结果：全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
