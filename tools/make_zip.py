# -*- coding: utf-8 -*-
"""把 CodeBeacon 工程打包成 zip（供 OpenClaw 上传 GitHub）。"""

import os
import sys
import zipfile

SRC = r"D:/AI/WorkBuddy/2026-09-30-09-59-58/codebeacon"
OUT = r"D:/AI/WorkBuddy/2026-09-30-09-59-58/codebeacon-upload.zip"

# 根目录名 —— 解压后 OpenClaw 会看到它
ROOT = "codebeacon"

SKIP_DIRS = {"__pycache__", ".gradle", "build", ".dart_tool", ".idea"}
SKIP_EXT = {".pyc", ".jks", ".keystore"}


def main():
    if not os.path.isdir(SRC):
        print("源目录不存在: %s" % SRC)
        return 1

    total = 0
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for root, dirs, files in os.walk(SRC):
            # 原地修改 dirs 以跳过
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

            for fn in files:
                ext = os.path.splitext(fn)[1].lower()
                if ext in SKIP_EXT:
                    continue
                # 签名与本机配置绝不入包
                if fn in ("key.properties", "local.properties"):
                    continue

                fp = os.path.join(root, fn)
                rel = os.path.relpath(fp, SRC)
                arc = os.path.join(ROOT, rel).replace(os.sep, "/")

                try:
                    z.write(fp, arc)
                    total += 1
                    if total % 40 == 0:
                        print("  已加入 %d 个文件 ..." % total)
                except Exception as e:
                    print("  [跳过] %s -> %s" % (rel, e))

    size = os.path.getsize(OUT)
    print("\n完成：%d 个文件" % total)
    print("输出：%s" % OUT)
    print("体积：%.1f KB" % (size / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())