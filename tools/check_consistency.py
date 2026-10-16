# -*- coding: utf-8 -*-
"""
混编工程一致性检查：验证 Kotlin 与 Dart 的 MethodChannel 契约是否匹配。

这是混编工程最容易出错的地方 —— Dart 调用的方法名在 Kotlin 里不存在，
或参数名不一致，运行时才抛 MissingPluginException。
本脚本能在编译前静态发现这类问题。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT_DIR = os.path.join(ROOT, "android/app/src/main/java/com/codebeacon/widget")
DART_DIR = os.path.join(ROOT, "lib")

CHANNEL = "com.codebeacon/bridge"


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def check_channel_name():
    """通道名必须完全一致，否则 MethodChannel 连不上"""
    print("=== 1. 通道名一致性 ===")
    kt = ""
    for dirpath, _, files in os.walk(KT_DIR):
        for fn in files:
            if fn.endswith(".kt"):
                kt += read(os.path.join(dirpath, fn))

    dart = ""
    for dirpath, _, files in os.walk(DART_DIR):
        for fn in files:
            if fn.endswith(".dart"):
                dart += read(os.path.join(dirpath, fn))

    kt_ok = CHANNEL in kt
    dart_ok = CHANNEL in dart
    print("  [%s] Kotlin 侧包含通道名 %s" % ("ok" if kt_ok else "FAIL", CHANNEL))
    print("  [%s] Dart 侧包含通道名 %s" % ("ok" if dart_ok else "FAIL", CHANNEL))
    return kt_ok and dart_ok


def check_methods():
    """Dart 调用的方法必须在 Kotlin 的 when 分支里存在"""
    print("\n=== 2. 方法覆盖检查 ===")

    # Dart 侧：invokeMethod('xxx')
    dart_calls = set()
    for dirpath, _, files in os.walk(DART_DIR):
        for fn in files:
            if not fn.endswith(".dart"):
                continue
            src = read(os.path.join(dirpath, fn))
            for m in re.finditer(r"invokeMethod(?:<[^>]*>)?\(\s*'([^']+)'", src):
                dart_calls.add(m.group(1))

    # Kotlin 侧：when 分支里的字符串
    kt_path = os.path.join(KT_DIR, "bridge", "AppBridge.kt")
    kt_methods = set()
    if os.path.exists(kt_path):
        src = read(kt_path)
        for m in re.finditer(r'^\s*"([a-zA-Z_]+)"\s*->', src, re.MULTILINE):
            kt_methods.add(m.group(1))
        # 也匹配多分支形式："a", "b" ->
        for m in re.finditer(r'^\s*((?:"[a-zA-Z_]+"\s*,\s*)+"[a-zA-Z_]+")\s*->',
                             src, re.MULTILINE):
            for s in re.findall(r'"([a-zA-Z_]+)"', m.group(1)):
                kt_methods.add(s)

    print("  Kotlin 实现 %d 个方法" % len(kt_methods))
    print("  Dart 调用 %d 个方法" % len(dart_calls))

    missing = sorted(dart_calls - kt_methods)
    extra = sorted(kt_methods - dart_calls)

    ok = True
    if missing:
        ok = False
        print("  [FAIL] Dart 调用了但 Kotlin 未实现：")
        for m in missing:
            print("         · " + m)
    else:
        print("  [ok]   Dart 调用的方法全部有 Kotlin 实现")

    if extra:
        print("  [note] Kotlin 有但 Dart 未调用（不影响运行）：")
        for m in extra:
            print("         · " + m)

    return ok


def check_args():
    """检查关键方法的参数名一致性"""
    print("\n=== 3. 关键方法参数检查 ===")
    kt_path = os.path.join(KT_DIR, "bridge", "AppBridge.kt")
    if not os.path.exists(kt_path):
        return False
    kt = read(kt_path)

    checks = [
        ("markPicked", "id"),
        ("unmarkPicked", "id"),
        ("deleteCode", "id"),
        ("copyCode", "code"),
        ("setSourceEnabled", "package"),
        ("openMemo", "uri"),
    ]
    ok = True
    for method, arg in checks:
        # Kotlin: call.argument<String>("xxx")
        kt_has = re.search(
            r'%s"[\s\S]{0,200}?argument<[^>]+>\("%s"' % (method, arg), kt)
        kt_arg_alt = re.search(
            r'argument<[^>]+>\("%s"\)[\s\S]{0,200}?%s' % (arg, method), kt)
        found = bool(kt_has or kt_arg_alt)

        print("  [%s] %s(%s)" % ("ok" if found else "FAIL", method, arg))
        if not found:
            ok = False
    return ok


def check_manifest():
    """Manifest 中的类必须存在"""
    print("\n=== 4. Manifest 声明与源文件对应 ===")
    mf_path = os.path.join(ROOT, "android/app/src/main/AndroidManifest.xml")
    if not os.path.exists(mf_path):
        print("  [FAIL] Manifest 不存在")
        return False

    mf = read(mf_path)
    ok = True

    # android:name="..." → 转成包路径
    names = re.findall(r'android:name="(\.[^"]+)"', mf)
    for n in names:
        # ".bridge.AppBridge" → "bridge/AppBridge.kt"
        # 注意：不能用包名拼接，因为清单里的类名是相对包名的短形式
        rel = n.lstrip(".").replace(".", os.sep) + ".kt"
        exists = os.path.exists(os.path.join(KT_DIR, rel))
        print("  [%s] %-32s → %s"
              % ("ok" if exists else "FAIL", n, rel.replace(os.sep, "/")))
        if not exists:
            ok = False

    return ok


def check_resources():
    """资源引用必须存在"""
    print("\n=== 5. 资源引用检查 ===")
    res_dir = os.path.join(ROOT, "android/app/src/main/res")

    # 收集已定义的资源
    defined = set()
    for dirpath, _, files in os.walk(res_dir):
        for fn in files:
            path = os.path.join(dirpath, fn)
            if fn.endswith(".xml") and "values" in dirpath:
                src = read(path)
                for m in re.finditer(r'name="([a-zA-Z_0-9]+)"', src):
                    defined.add(m.group(1))
            elif fn.endswith(".xml"):
                defined.add(os.path.splitext(fn)[0])
                defined.add("R.layout." + os.path.splitext(fn)[0])

    # Manifest 与 layout 中的引用
    refs = set()
    mf = read(os.path.join(res_dir, "..", "AndroidManifest.xml"))
    for m in re.finditer(r'@xml/([a-zA-Z_0-9]+)', mf):
        refs.add("xml/" + m.group(1))
    for m in re.finditer(r'@layout/([a-zA-Z_0-9]+)', mf):
        refs.add("layout/" + m.group(1))

    # layout 内部的 @dimen/@color/@string/@drawable
    for dirpath, _, files in os.walk(res_dir):
        if "layout" not in dirpath and "xml" not in dirpath:
            continue
        for fn in files:
            if not fn.endswith(".xml"):
                continue
            src = read(os.path.join(dirpath, fn))
            for m in re.finditer(r'@(drawable|string|color|dimen)/([a-zA-Z_0-9]+)', src):
                refs.add(m.group(1) + "/" + m.group(2))

    ok = True
    for r in sorted(refs):
        kind, name = r.split("/")
        # @xml/@layout 需检查文件
        if kind in ("xml", "layout"):
            exists = os.path.exists(os.path.join(res_dir, kind, name + ".xml"))
        else:
            # 值类资源只需名字在 defined 里
            exists = name in defined
        if not exists:
            print("  [FAIL] %s (未找到定义)" % r)
            ok = False

    if ok:
        print("  [ok]   %d 个资源引用全部有效" % len(refs))
    return ok


def check_plugin_registration():
    """AppBridge 必须被手动注册，否则 MethodChannel 不通"""
    print("\n=== 6. 插件注册检查 ===")
    act = os.path.join(KT_DIR, "bridge", "MainActivity.kt")
    if not os.path.exists(act):
        print("  [FAIL] MainActivity.kt 不存在")
        return False

    src = read(act)
    has_add = "add(AppBridge())" in src or "plugins.add" in src
    has_override = "configureFlutterEngine" in src

    print("  [%s] 重写 configureFlutterEngine" % ("ok" if has_override else "FAIL"))
    print("  [%s] 手动 add(AppBridge())" % ("ok" if has_add else "FAIL"))
    return has_add and has_override


def main():
    print("=" * 50)
    print("CodeBeacon 混编一致性检查")
    print("=" * 50)

    results = [
        check_channel_name(),
        check_methods(),
        check_args(),
        check_manifest(),
        check_resources(),
        check_plugin_registration(),
    ]

    print("\n" + "=" * 50)
    passed = sum(1 for r in results if r)
    print("结果：%d/%d 项通过" % (passed, len(results)))
    print("=" * 50)

    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())