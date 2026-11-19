# Reicon

**ColorOS 17 暗色 / 亮色图标补全模块** —— 生成器 App + KernelSU 模块的组合，让没有官方暗色适配的第三方应用融入深色模式，并给官方适配的应用补一套白底亮色图标。

> 基于 [DarkIcon-Auto](https://github.com/zjy2968466837/DarkIcon-Auto) 改造，致谢原作者。
> 并致谢：**居然是啊松**（酷安帖子的三种暗色图标制作思路）、**天伞桜**（模块挂载架构与素材导入方案）。

---
## 效果展示

<img width="1216" height="710" alt="IMG_20261118_174901" src="https://github.com/user-attachments/assets/62ebc8a4-3fee-49ed-a7e2-1ee88aef3664" />

## 它做什么

ColorOS 的暗色图标只对官方适配过的应用生效，大量第三方应用在深色模式下仍是亮底原色图标。Reicon 做两件事：

1. **第三方应用**：从应用图标提取素材 → 抠图 / 转色 / 单色三种方案 → 写入模块 → 桌面显示暗色图标
2. **官方已适配应用**：只补一套**白底亮色**图标（暗色保持官方原样）

## 特性

- **三种暗色方案**：普通抠图 / 转色 / 单色剪影（自动推荐）
- **亮色方案**：原图标 / 原色 / 主题色（白底 + 品牌色字形）
- **官方适配应用走文件级管线**：
  - `recfg.png` ← 复制官方 `rec_night.png`（真字形）
  - `recbg.png` ← 生成同尺寸纯白
  - 暗色件（`rec_night` / `mat` / `nsd` / `monochrome`）**完全不动**
- **比例可调**：暗色 / 亮色各自独立的默认值与记忆值，滑块实时预览
- **素材导入/导出**：支持导入同源模块素材（暗色层）、导出完整数据包便于备份迁移
- **差量执行**：`立即执行` 只处理新增/移除的包；`全量重载` 才重建全部

## 项目结构

```
.
├── app/                        # Android App（Kotlin + Compose）
│   └── src/main/java/com/darkicongen/app/
│       ├── gen/                # 图标生成管线（抠图、转色、亮色合成、比例）
│       ├── mod/                # 模块写入、脚本生成、任务中心
│       └── ui/                 # 首页 / 应用列表 / 详情预览 / 设置
├── tools/                      # 构建与打包脚本（Python）
├── docs/                       # 更新记录
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

## 构建

需要 JDK 17 与 Android SDK（compileSdk 见 `app/build.gradle.kts`）。

```bash
# 1) 配置 SDK 路径
cp local.properties.template local.properties
# 编辑 local.properties: sdk.dir=/path/to/Android/sdk

# 2) 编译 release APK（未签名）
./gradlew :app:assembleRelease

# 3) 签名（用你自己的 keystore，仓库里不放任何密钥）
$ANDROID_HOME/build-tools/<ver>/zipalign -p -f 4 app-release-unsigned.apk aligned.apk
$ANDROID_HOME/build-tools/<ver>/apksigner sign \
  --ks your.jks --ks-key-alias your_alias --out Reicon.apk aligned.apk
```

模块 zip 的打包脚本在 `tools/mk_module_zip.py`（生成 `module.prop` + 脚本 + 空 payload 骨架）。

## 模块运行时结构

```
/data/adb/modules/<模块 id>/
├── module.prop
├── post-fs-data.sh / service.sh / action.sh
├── apply.sh                    # 状态自检与收敛（boot / manual / service / reload）
├── payload/
│   ├── theme/uxicons/hdpi/<包名>/   # 主题侧素材（桌面渲染读这里）
│   └── uxicons/<包名>/              # 逐包侧素材（挂到 /data/oplus/uxicons/<包名>）
├── .packages                   # 当前纳入管理的包清单
└── .managed                    # 历史台账（白名单清理用）
```

要点：

- 桌面渲染读的是 **少一层** 的 `/my_product/media/theme/uxicons/hdpi/<包名>/`
- 挂载在 PID 1 的 mount namespace 里完成（`nsenter -t 1 -m`）
- 素材目录必须是 **root:root + 755（目录）/ 644（png）**，否则桌面读不到 —— 这是「导入成功但图标不生效」的常见原因

## 已知限制

- 仅针对 ColorOS / OPPO 系主题管线，其他 ROM 不适用
- 需要 KernelSU（或支持 hybrid_mount 的方案）+ LSPosed 用于白名单注入
- 官方素材结构随 ColorOS 版本变化，升级系统后可能需要重新核对文件名

## 许可证与致谢

本项目基于 [zjy2968466837/DarkIcon-Auto](https://github.com/zjy2968466837/DarkIcon-Auto) 改造。

**使用前请先到上游仓库确认其许可证（LICENSE 文件），并按该许可证的要求保留版权声明与相同方式共享。**
如果上游采用 GPL 系许可证，本项目同样需要以相同许可证发布，并在 README 中保留原作者署名。

## 免责声明

本项目仅修改图标素材与桌面缓存，不修改应用本身。刷入模块前建议先备份 `/data/adb/modules` 与桌面布局。因使用本模块导致的任何设备异常，由使用者自行承担。
