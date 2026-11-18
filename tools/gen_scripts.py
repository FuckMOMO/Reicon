#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 ModuleWriter.kt 里的 shell 脚本模板「机械地」还原成真正的 .sh 文件。

为什么这样做：模块脚本的唯一真源是 Kotlin 里的 generateMountScript()/buildApplyScript()，
App 每次生成素材、每次全量重载都会按模板重写模块里的 apply.sh / action.sh / service.sh。
发布用的模块 zip 只是给「全新刷入」用的引导脚本，必须和模板逐字一致，
所以这里不再手抄，而是直接从源码抽取 → 去缩进 → 替换 ${'$'} 转义 → 代入 Kotlin 常量。
"""
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(HERE, "work_src", "app", "src", "main", "java", "com", "darkicongen", "app", "mod", "ModuleWriter.kt")
OUT = os.path.join(HERE, "module_new")

CONSTS = {
    # 长名优先，避免 MODULE_ID 抢在 MODULE_DIR 前面
    "SISTER_MODULE_DIR": "/data/adb/modules/DarIconComplete",
    "LAUNCHER_ICON_DB": "/data/user_de/0/com.android.launcher/databases/app_icons.db",
    "THEME_SRC": "/data/adb/modules/DarkIconAuto/my_product/media/theme",
    "THEME_HDPI": "/data/adb/modules/DarkIconAuto/my_product/media/theme/uxicons/hdpi",
    "PKGS_SRC": "/data/adb/modules/DarkIconAuto/data/oplus/uxicons",
    "MODULE_DIR": "/data/adb/modules/DarkIconAuto",
    "MODULE_ID": "DarkIconAuto",
    "THEME_DST": "/my_product/media/theme",
    "UXICONS_DST": "/data/oplus/uxicons",
    "LAUNCHER_PKG": "com.android.launcher",
    "LABEL_OFFICIAL": "u:object_r:uxicons_data_file:s0",
    "LABEL_FALLBACK": "u:object_r:system_file:s0",
    "LABEL_THEME": "u:object_r:system_file:s0",
}


def trim_indent(raw: str) -> str:
    """等价 Kotlin 的 trimIndent()：去首尾空行 + 去掉所有非空行的公共缩进。"""
    lines = raw.split("\n")
    if lines and lines[0].strip() == "":
        lines = lines[1:]
    while lines and lines[-1].strip() == "":
        lines = lines[:-1]
    indents = [len(l) - len(l.lstrip(" ")) for l in lines if l.strip()]
    cut = min(indents) if indents else 0
    return "\n".join(l[cut:] if l.strip() else "" for l in lines)


def unescape(body: str) -> str:
    """Kotlin 里 ${'$'} 是「一个字面量美元符」，还原成 $。"""
    body = body.replace("${'$'}", "$")
    # 再代入 Kotlin 常量（这些在原串里是 $CONST 形式的字符串插值）
    for name in sorted(CONSTS, key=len, reverse=True):
        body = re.sub(r"\$" + name + r"\b", lambda m, v=CONSTS[name]: v, body)
    return body


def blocks_of(src: str, start_marker: str, end_marker: str):
    start = src.index(start_marker)
    end = src.index(end_marker, start)
    seg = src[start:end]
    return [trim_indent(b) for b in re.findall(r'"""([\s\S]*?)"""', seg)]


def main():
    src = io.open(KT, encoding="utf-8").read()

    apply_blocks = blocks_of(src, "private fun buildApplyScript(): String {",
                             "/** 列出我们模块已适配的包名")
    if len(apply_blocks) != 6:
        raise SystemExit("apply.sh 模板块数量异常：%d（预期 head/stale/mount/whitelist/cache/tail）"
                         % len(apply_blocks))
    apply_sh = "\n".join(unescape(b) for b in apply_blocks) + "\n"

    mount_blocks = blocks_of(src, "fun regenerateMountScript(", "/** apply.sh 内核")
    # mount_blocks 顺序：pfs, action, svc
    if len(mount_blocks) != 3:
        raise SystemExit("入口脚本模板块数量异常：%d（预期 post-fs-data/action/service）" % len(mount_blocks))
    pfs_sh, action_sh, svc_sh = [unescape(b) for b in mount_blocks]

    os.makedirs(OUT, exist_ok=True)
    for name, text in (("apply.sh", apply_sh),
                       ("post-fs-data.sh", pfs_sh + "\n"),
                       ("action.sh", action_sh + "\n"),
                       ("service.sh", svc_sh + "\n")):
        path = os.path.join(OUT, name)
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote %-16s %5d bytes" % (name, len(text.encode("utf-8"))))


if __name__ == "__main__":
    main()
