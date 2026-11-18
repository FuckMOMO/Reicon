#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包交付物：APK / 模块 zip / 源码 zip / diff / 截图证据 → work/darkicon/dist"""
import io
import os
import shutil
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "work_src")
ORIG_SETTINGS = os.path.join(HERE, "repo_src", "DarkIcon-Auto-main", "settings.gradle.kts")
DIST = os.path.join(HERE, "dist")

SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", ".idea", "outputs"}
SKIP_FILES = {"local.properties", "gradle-daemon-jvm.properties"}
SKIP_EXT = {".apk", ".idsig"}

BUILD_NOTES = """# 本机构建备忘

- JDK 17（`JAVA_HOME`）
- Gradle 9.4.1（wrapper 指向本地发行包；Android Studio 打开会用它）
- AGP 9.2.1 / Kotlin 2.3.0 / Compose BOM 2026.08.00 / libsu 6.0.0 / xposed api 82
- compileSdk 37（`platforms;android-37.0` + `build-tools;37.0.0`）

```bash
cp local.properties.template local.properties   # sdk.dir=...
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
zipalign -p -f 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks your.jks --out DarkIconGen_v1.4.0.apk aligned.apk
```

模块脚本的唯一真源是 `ModuleWriter.kt`：

```bash
python gen_scripts.py     # 从 Kotlin 抽取 → module_new/
python mk_module_zip.py   # 打模块 zip
```

本机两个网络坑（与代码无关）：jitpack / api.xposed.info 的 TLS 握手失败，已把 libsu 与 xposed api
落到本地 maven 目录（交付源码里已还原成上游 settings）；foojay 插件解析不到，构建时临时移除。
"""


def stage_source(dst):
    for dirpath, dirnames, filenames in os.walk(SRC):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        rel = os.path.relpath(dirpath, SRC)
        target_dir = dst if rel == "." else os.path.join(dst, rel)
        os.makedirs(target_dir, exist_ok=True)
        for fn in filenames:
            if fn in SKIP_FILES or os.path.splitext(fn)[1] in SKIP_EXT:
                continue
            shutil.copy2(os.path.join(dirpath, fn), os.path.join(target_dir, fn))
    # 交付源码用上游 settings（本机加了本地 maven 镜像，属于环境细节）
    shutil.copy2(ORIG_SETTINGS, os.path.join(dst, "settings.gradle.kts"))
    # wrapper 地址还原成官方
    wp = os.path.join(dst, "gradle", "wrapper", "gradle-wrapper.properties")
    with io.open(wp, encoding="utf-8") as f:
        txt = f.read()
    lines = []
    for line in txt.splitlines():
        if line.startswith("distributionUrl="):
            line = "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.4.1-bin.zip"
        lines.append(line)
    with io.open(wp, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    shutil.copy2(os.path.join(HERE, "CHANGES.md"), os.path.join(dst, "CHANGES.md"))
    with io.open(os.path.join(dst, "BUILD_NOTES.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(BUILD_NOTES)


def zip_dir(src, out, arcroot):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, dirnames, filenames in os.walk(src):
            dirnames.sort()
            for fn in sorted(filenames):
                p = os.path.join(dirpath, fn)
                rel = os.path.relpath(p, src).replace("\\", "/")
                z.write(p, arcroot + "/" + rel)


def main():
    os.makedirs(DIST, exist_ok=True)

    artifacts = (
        ("DarkIconGen_v1.3.0.apk", "Reicon_v1.3.0.apk"),
        ("Reicon_module_v1.1.0.zip", "Reicon_module_v1.1.0.zip"),
    )
    for src, dst in artifacts:
        shutil.copy2(os.path.join(HERE, "release", src), os.path.join(DIST, dst))
    shutil.copy2(os.path.join(HERE, "changes.diff"), os.path.join(DIST, "changes.diff"))

    shots = [
        ("80_tb_light.png", "preview-taobao-official.png"),
        ("cp_day.png", "generated-day-240.png"),
        ("82_home_desktop.png", "desktop-after-apply.png"),
        ("54_apply_done.png", "apply-result.png"),
        ("71_persist.png", "preview-official-detected.png"),
    ]
    shot_dir = os.path.join(DIST, "screens")
    shutil.rmtree(shot_dir, ignore_errors=True)
    os.makedirs(shot_dir, exist_ok=True)
    for src_name, dst_name in shots:
        p = os.path.join(HERE, "shots", src_name)
        if os.path.exists(p):
            shutil.copy2(p, os.path.join(shot_dir, dst_name))

    stage = os.path.join(HERE, ".dist_src", "Reicon-1.3.0-src")
    shutil.rmtree(os.path.join(HERE, ".dist_src"), ignore_errors=True)
    stage_source(stage)
    zip_dir(stage, os.path.join(DIST, "Reicon-1.3.0-src.zip"), "Reicon-1.3.0-src")
    shutil.rmtree(os.path.join(HERE, ".dist_src"), ignore_errors=True)

    for fn in sorted(os.listdir(DIST)):
        p = os.path.join(DIST, fn)
        if os.path.isfile(p):
            print("%-40s %10d bytes" % (fn, os.path.getsize(p)))


if __name__ == "__main__":
    main()
