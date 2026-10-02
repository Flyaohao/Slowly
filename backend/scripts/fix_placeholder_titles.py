"""
一次性数据修复：把落库的占位文案「无标题」还原成空串。

背景（P1，2026-10-02 真机实测发现）：
  `app/services/letter_service.py:109` 在**创建信件时**就把空标题替换成
  占位文案落库——原先是 `"title": data.get("title") or "无标题"`。
  后果有两层：
    ①数据库被污染，「用户本来没填标题」这个信息永久丢失；
    ②前端 8 处 `title?.ifBlank { "无标题" }` 兜底**全部失效**——
      因为字段根本不空，值就是「无标题」这三个字。
  真机表现：信箱「已发出」3 封信、共同时间线 3 条记录标题全是「无标题」。

修法：
  1. `letter_service.py:109` 已改成 `or ""`（代码侧，本脚本不改代码）；
  2. 本脚本把历史上已落库的 `'无标题'` 还原成空串（数据侧）。

安全设计：
  - **默认 dry-run**，只 SELECT 打印受影响行数，**不改任何数据**；
  - 真正执行必须显式传 `--apply`；
  - 不删数据，只把标题字段归零；
  - 幂等：已处理过的行 `title` 是空串，不会被再次选中。

线上库是否有删除/改数接口 —— **本脚本不会自动执行，需人工确认后手动跑**。
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import SessionLocal
from app.models.letter import Letter

#: 被当作占位文案写进 DB 的历史值
PLACEHOLDER = "无标题"


def scan(db):
    """返回所有 title 仍是占位文案的行。"""
    return (
        db.query(Letter)
        .filter(Letter.title == PLACEHOLDER)
        .order_by(Letter.id)
        .all()
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="修复信件标题占位文案「无标题」")
    parser.add_argument(
        "--apply",
        action="store_true",
        help="真正写库（默认只 dry-run 打印，不改数据）",
    )
    args = parser.parse_args()

    db = SessionLocal()
    try:
        rows = scan(db)
        print("扫描结果：title == %r 的信件 %d 条" % (PLACEHOLDER, len(rows)))
        for row in rows:
            preview = (row.content or "")[:24].replace("\n", " ")
            print("  id=%-6s sender=%-6s content=%s..." % (row.id, row.sender_id, preview))

        if not rows:
            print("无需修复，退出。")
            return 0

        if not args.apply:
            print("")
            print("这是 dry-run，未改动任何数据。确认无误后加 --apply 执行。")
            return 0

        for row in rows:
            row.title = ""
        db.commit()
        print("")
        print("已修复 %d 条。" % len(rows))
        return 0
    except Exception as exc:  # noqa: BLE001 - 脚本入口，异常要打印完整上下文
        db.rollback()
        print("失败：%s: %s" % (type(exc).__name__, exc), file=sys.stderr)
        return 1
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())