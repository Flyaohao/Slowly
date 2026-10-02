"""统计路由层 Query/Path/Field 缺失 `description=` 的数量（只读，不改代码）。

派单要求：统计缺 description 的数量并**只报告**，不一次全补。

覆盖：
  - 路由函数签名里的 Query(...) / Path(...)
  - Pydantic schema 字段的 Field(...)
按文件分组，标出**完全没有任何 description** 的文件。
"""
import io
import os
import re
import sys

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BACKEND)

QUERY_RE = re.compile(r"\b(Query|Path)\s*\(")
FIELD_RE = re.compile(r"\bField\s*\(")
DESC_RE = re.compile(r"\bdescription\s*=")


def scan(paths, opener):
    rows = []
    for p in paths:
        with io.open(p, encoding="utf-8") as f:
            lines = f.readlines()
        for i, line in enumerate(lines, 1):
            if not opener.search(line):
                continue
            # 参数可能跨行，写到下一个非空行为止，拼起来判断有无 description
            chunk = line
            j = i
            while chunk.count("(") > chunk.count(")") and j < len(lines):
                j += 1
                chunk += lines[j - 1]
            rows.append((p, i, bool(DESC_RE.search(chunk))))
    return rows


api_files, schema_files = [], []
for root, _d, files in os.walk(os.path.join(BACKEND, "app")):
    if "__pycache__" in root:
        continue
    for fn in files:
        if not fn.endswith(".py"):
            continue
        full = os.path.join(root, fn)
        rel = os.path.relpath(full, BACKEND).replace("\\", "/")
        if rel.startswith("app/api/"):
            api_files.append(full)
        elif rel.startswith("app/schemas/"):
            schema_files.append(full)

qp = scan(sorted(api_files), QUERY_RE)
fl = scan(sorted(schema_files), FIELD_RE)

def report(title, rows):
    total = len(rows)
    missing = [r for r in rows if not r[2]]
    print("=" * 72)
    print("%s：共 %d 处，缺 description %d 处（%.0f%%）"
          % (title, total, len(missing), 100.0 * len(missing) / total if total else 0))
    print("=" * 72)
    by_file = {}
    for p, ln, _ in missing:
        by_file.setdefault(os.path.relpath(p, BACKEND).replace("\\", "/"), []).append(ln)
    for f in sorted(by_file, key=lambda x: -len(by_file[x])):
        print("  %-46s %2d 处  行 %s" % (f, len(by_file[f]), by_file[f][:12]))
    print()
    return total, len(missing)


t1, m1 = report("路由签名 Query()/Path()", qp)
t2, m2 = report("Pydantic Field()", fl)
print("合计：%d 处调用，缺 description %d 处" % (t1 + t2, m1 + m2))
print()
print("=== 完全没有 description 的文件（≥1 处调用且 0 处有 description）===")
for rows, label in ((qp, "Query/Path"), (fl, "Field")):
    for p, ln, has in rows:
        if not has:
            rel = os.path.relpath(p, BACKEND).replace("\\", "/")
            same = [r for r in rows if r[0] == p]
            if all(not r[2] for r in same):
                print("  [%s] %s （%d 处全缺）" % (label, rel, len(same)))
                break