#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 repo_src(原始) → work_src(改后) 的统一 diff，输出到 changes.diff 并打印改动文件清单。"""
import os
import difflib
import io

HERE = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(HERE, "repo_src", "DarkIcon-Auto-main")
B = os.path.join(HERE, "work_src")
OUT = os.path.join(HERE, "changes.diff")

SKIP_DIRS = {".git", "build", ".gradle", ".idea", ".kotlin"}
SKIP_FILES = {"local.properties", "gradle-daemon-jvm.properties", "changes.diff"}
# 构建期产物/本机环境文件不进 diff
SKIP_EXT = {".apk", ".jar", ".zip"}


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn in SKIP_FILES or os.path.splitext(fn)[1] in SKIP_EXT:
                continue
            p = os.path.join(dirpath, fn)
            yield os.path.relpath(p, root).replace("\\", "/"), p


def read(p):
    try:
        with io.open(p, encoding="utf-8") as f:
            return f.read().splitlines(keepends=True)
    except UnicodeDecodeError:
        return None


def main():
    fa = dict(walk(A))
    fb = dict(walk(B))
    out = []
    changed = []
    for rel in sorted(set(fa) | set(fb)):
        ta = read(fa[rel]) if rel in fa else None
        tb = read(fb[rel]) if rel in fb else None
        if ta == tb:
            continue
        changed.append(rel)
        out.extend(
            difflib.unified_diff(
                ta or [], tb or [],
                fromfile="a/" + rel, tofile="b/" + rel, n=4,
            )
        )
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(out)
    print("changed files (%d):" % len(changed))
    for c in changed:
        print("  " + c)
    print("diff: %s (%d lines)" % (OUT, len(out)))


if __name__ == "__main__":
    main()
