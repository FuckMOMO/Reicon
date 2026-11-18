#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包发布用的 KernelSU 模块 zip（v1.1.0：新增 reload 入口 + action.sh 待办标记）。"""
import io
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "module_new")
OUT = os.path.join(HERE, "release", "Reicon_module_v1.1.0.zip")

PROP = """id=DarkIconAuto
name=Reicon
version=1.1.0
versionCode=110
author=Carrot
description=ColorOS17 dark and light icon completion module
"""

ORDER = ["module.prop", "apply.sh", "post-fs-data.sh", "action.sh", "service.sh", "xposed.conf"]


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for name in ORDER:
            if name == "module.prop":
                data = PROP.encode("utf-8")
            elif name == "xposed.conf":
                data = b""
            else:
                with io.open(os.path.join(SRC, name), "rb") as f:
                    data = f.read()
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 15, 0, 0, 0))
            # 脚本 0755，prop/conf 0644（KSU 刷入后按此落权限）
            info.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
            print("%-16s %5d bytes" % (name, len(data)))
    print("zip: %s (%d bytes)" % (OUT, os.path.getsize(OUT)))


if __name__ == "__main__":
    main()
