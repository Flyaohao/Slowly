"""扫描 app/ 包中会导致运行时 NameError 的未定义全局名。

用法：cd backend && python scripts/audit_undefined_globals.py
退出码：0 = 干净；1 = 发现未定义全局名（输出 模块 :: 函数() -> 名字）
"""
import builtins
import dis
import importlib
import os
import pkgutil
import sys
import types

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import app  # noqa: E402


def main() -> int:
    findings = []
    import_failures = []
    for info in pkgutil.walk_packages(app.__path__, "app."):
        try:
            module = importlib.import_module(info.name)
        except Exception as exc:  # 导入失败不算本脚本的职责
            import_failures.append((info.name, type(exc).__name__))
            continue
        module_globals = vars(module)
        for name, obj in list(module_globals.items()):
            # 只看本模块自定义的函数（排除第三方装饰器注入的）
            if not isinstance(obj, types.FunctionType):
                continue
            if obj.__globals__ is not module_globals:
                continue
            codes = [obj.__code__] + [
                c for c in obj.__code__.co_consts if isinstance(c, types.CodeType)
            ]
            for code in codes:
                for ins in dis.get_instructions(code):
                    if ins.opname not in ("LOAD_GLOBAL", "LOAD_NAME"):
                        continue
                    target = ins.argval
                    if not isinstance(target, str):
                        continue
                    if target in module_globals or hasattr(builtins, target):
                        continue
                    findings.append((info.name, code.co_name, target))

    print("=== 未定义的全局名（会导致运行时 NameError）===")
    for mod, func, target in sorted(set(findings)):
        print(f"  {mod} :: {func}() -> {target}")
    if not findings:
        print("  （无）")
    print(f"count = {len(set(findings))}")
    if import_failures:
        print(f"导入失败的模块（未纳入扫描）：{import_failures}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
