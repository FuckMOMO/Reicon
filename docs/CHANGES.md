# DarkIcon Auto 1.3.0 — 亮色模式 + 全量重载

基于 [zjy2968466837/DarkIcon-Auto](https://github.com/zjy2968466837/DarkIcon-Auto) v1.2.1 源码（`release/DarkIconGen_v1.2.1.apk` + `DarkIconAuto_module_v1.0.2.zip`）改造。

本次只加两个功能，不重构既有暗色管线：

1. **亮色（白底）图标**：把图标主体抠出来压成品牌色，铺纯白底 —— 微信那种「绿底白字形」变成「白底绿字形」，和系统里原生白底 App 摆一起不再突兀。
2. **全量重载**：绕开「已经装过所以脚本认为没变化」的差量捷径。素材被删、挂载丢了、清单被清空，一键从 App 缓存回填 + 强制拆挂重挂 + 强制清桌面缓存 + 重启桌面。

版本：`versionCode 7 / versionName 1.3.0`，模块 `versionCode 110 / version 1.1.0`。

---

## 一、这个项目原来的工作方式（改造前）

- **App 侧只写素材**：`ModuleWriter.writePackageIcons()` 把某个包的 PNG 写到模块目录两条路径
  - 主题侧（桌面主读取路径）：`/data/adb/modules/DarkIconAuto/my_product/media/theme/uxicons/hdpi/<pkg>/`
  - 逐包侧（其他场景）：`/data/adb/modules/DarkIconAuto/data/oplus/uxicons/<pkg>/`
  - 同时登记 `.packages` / `.managed` 台账、`drawableMapping.xml`、`icon_version.xml +1`、App 内双缓存
- **模块脚本负责收敛**：`apply.sh` 的 `run_apply()` 做「主题整目录 bind + 逐包 bind + 白名单注入 + 版本变化才清 `app_icons.db`」，入口三个（`post-fs-data.sh` 开机 / `action.sh` KSU 播放键 / `service.sh` 兜底）
- **素材命名**（ColorOS 16 morph icon 约定，实测官方微信素材可印证）：
  - 暗色件：`rec_night*`（字形，透明底）+ `recbg*`（背景，纯色）+ `monochrome*`（单色剪影）
  - 浅色件：`day/mat/nsd/recfg/art_off` + `_2x2/_1x2/_2x1` 变体，**整图满版**
  - 原来浅色件**只在用户手动上传浅色图时**才写（`customDay != null`），否则一件不写、桌面浅色回退到应用原图标

---

## 二、功能 1：亮色（白底）图标

### 2.1 新增：`gen/LightIconProcessor.kt`

与暗色管线对称的亮色管线：

```
抠出字形（复用 ForegroundProcessor.extractForeground）
  → 裁透明边 → 长边 62% 居中（与暗色前景同一套几何规则）
  → BRAND：SRC_IN 整体压成品牌色 / ORIGINAL：保留原配色
  → 先铺 #FFFFFFFF 白底，再画字形
  → 输出 240×240 **满版不透明** 的日间图标
```

为什么必须满版不透明：桌面的浅色渲染不做抠图合成，写进去什么就画什么；
透明底会在白底 App 中间漏出壁纸，和原生白底图标放一起最扎眼。

**踩到的坑（已修）**：`ORIGINAL`（白底原色）遇到「彩底白字形」的图标（微信/B站这类，前景本身就是一整块近白），
白底上原样画 = 白底白图，整张图标变一块空白。实测 Bilby 时整块变白就是这个原因。
处理：`isMostlyWhite()` 判定近白像素占比 > 55% 时，`ORIGINAL` 自动降级成品牌色，
预览页同时给出提示文案（`wouldFallbackToBrand()`）。

### 2.2 新增：`gen/LightModePrefs.kt`

全局偏好（`SharedPreferences: digen / light_mode`）：`auto | off | brand | original`
- 批量生成（应用列表勾选生成）没有逐包选项，统一读这里；
- 预览页选中某个方案时把选择写回，作为「下次默认」；
- `auto` = 按原图标底色推荐：**浅底 → 白底原色**（更接近官方观感），**彩底/深底 → 白底品牌色**。

### 2.3 新增：`IconExtractor.accentColor()`

白底上要当字形颜色的那一支必须是**有彩度**的颜色，而原来的 `dominantColor()` 走中心加权：
微信这种「彩底 + 中心白字形」的图标会被算回近白色（= 白底白字，等于什么都没画）。

新逻辑：
1. 优先从**背景层**取色（自适应图标的 bg 层通常就是品牌色本身：微信绿、支付宝蓝）；
2. 背景层不可用/无彩度时，从完整图里挑饱和度最高的主色（跳过近白/近黑/灰阶，中心权重 ×2）；
3. 兜底色也太亮（luma > 0.90）时给 `#1F1F1F`。

### 2.4 UI 接入

- **预览页**（`PreviewScreen.kt`）：新增「亮色模式（白底）」区块，三张白底卡片即点即预览
  `关闭（原图标） / 白底品牌色 / 白底原色`，并显示自动推荐值与降级提示；
  「生成并写入模块」时：**用户上传的浅色图 > 本页选中的亮色方案 > 不写浅色件**。
- **设置页**（`SettingsScreen.kt`）：新增「亮色模式（白底）」全局默认（自动推荐 / 白底品牌色 / 白底原色 / 关闭）。
- **应用列表批量生成**（`AppListScreen.kt`）：`generateFor()` 增加 `LightModePrefs.dayIcon()`，按全局偏好出浅色件。

写入链路完全复用原有 `customDay` 通道（`writePackageIcons(..., customDay = 亮色图)`），
所以模块侧**不需要为新文件格式做任何改动**：`LIGHT_GENERATED_NAMES` 名单、`mergePreserveOfficial()` 覆盖策略、
`purgeStaleLightFiles()` 清理策略全部原样生效。

---

## 三、功能 2：全量重载

### 3.1 App 侧：`ModuleWriter.reloadAll(context, forceRegenerate = false)`

逐包判定顺序（顺序很重要 — 不能一上来就重新生成，会覆盖用户手调过的素材）：

| 情况 | 动作 |
|---|---|
| App 内 `icon_cache/<pkg>/generated/` 有缓存 | **原样回填**两条路径（"文件被删了"最准的恢复方式） |
| 没缓存但素材核心件齐（`rec_night/recbg/monochrome`） | 一个字节都不动，只登记台账，靠强制重挂生效 |
| 素材缺件 / 目录整个没了 | 从已安装应用重新提取 + 重新生成（暗色自动推荐、亮色按全局偏好） |
| 应用已卸载且什么都没有 | 跳过，不污染台账 |
| 勾选「从头重新生成」 | 无视缓存，全部按当前设置重新生成一遍（改了亮色模式后一次性套到所有应用） |

包清单来源：`.packages` **∪** `data/oplus/uxicons/` 下的目录。
后者是我们自己的素材目录 —— 清单被误清空时靠它把包"捡回来"，否则空清单会让整个功能空转
（这次实测的那台设备正好就是 `.packages` 空 + 素材在，属于这种状态）。

之后：`icon_version +1` → 写 `.force_reload` 标记 → 直接以 root 执行
`MODDIR=...; . apply.sh; run_apply reload`。App 侧执行不了（没给 root / 被打断）时标记留着，
KSU 播放键读到标记会自动转成 `reload`，同一件事接着做完。

### 3.2 模块侧：`apply.sh` 新增 `reload` 触发器

`run_apply boot|manual|service` 之外新增 `reload`，内部 `FORCE=1`：

- **主题挂载**：先 `umount` 再重挂（不再"就绪"就直接跳过）
- **逐包挂载**：已挂的一律 `umount` 后重挂（挂载指向已删 inode 时只有重挂能救）
- **白名单**：缺的补注入（注入仍在清缓存之前，铁律不变）
- **桌面缓存**：`FORCE` 时不看版本号，**无条件**清 `app_icons.db` —— "装过了所以没变化"正是要解决的问题
- **收尾**：`am force-stop com.android.launcher` 无条件执行
- 顺带把「素材已移除但挂载点还在」的历史残留挂载全部卸载清理

### 3.3 `action.sh`

```sh
if [ -f $MODDIR/.force_reload ]; then
  rm -f $MODDIR/.force_reload
  run_apply reload
else
  run_apply manual
fi
```

### 3.4 UI 接入

首页新增「全量重载全部图标」卡片 → 二次确认弹窗（写明三件事 + 「从头重新生成」勾选项）
→ 执行完用 snackbar 汇报：`已重载 N 个（回填 x / 重生成 y / 原样保留 z / 失败 w），桌面已重启`；
脚本执行失败时提示「到管理器按播放键即可（已留待办标记）」。

`regenerateMountScript()` 在每次生成素材、每次全量重载时都会重写模块脚本，
所以**老模块（v1.0.2）不需要重刷**，装上 1.3.0 的 App 跑一次就会把 `apply.sh` 换成带 `reload` 的版本。

---

## 四、真机验证（OnePlus 15T / PLZ110，Android 17 / ColorOS，KernelSU 3.3.1 LKM）

用 `applicationIdSuffix = ".dev"` 单独装了一个测试包（不动用户已装的正式版，正式版是作者私钥签名装不上同包名），
KernelSU 里给它授权 root 后实测：

| 验证点 | 结果 |
|---|---|
| 首页状态卡 + 新卡片渲染 | 正常（工作中 / 组件齐备 / 模块 1.0.0 / 图标版本 34） |
| 预览页亮色区块 | 正常：关闭=原图标、白底品牌色=白底紫兔、白底原色自动降级并给出提示 |
| 生成写入 | `dev.bilby` 两条路径各 20 个文件（含 `day/mat/nsd/recfg/art_off` 全套浅色件 + 暗色件） |
| 浅色件像素校验 | `day.png` 240×240 **alpha 全 255（100% 不透明）**、四角 `#FFFFFF`、中心为品牌色 `#505090` |
| 暗色件未被破坏 | `rec_night.png` 透明底 13.6% 不透明、`recbg.png` 纯品牌色、`monochrome.png` 黑色剪影 |
| 桌面渲染 | 最近任务里该应用的图标已变成「白色圆角方块 + 紫色字形」 |
| 全量重载 | `全量重载：主题挂载已卸载，准备重新挂载` → `全量重载，已卸载旧挂载` → `挂载成功` → `白名单已注入` → `全量重载：已清桌面图标缓存（版本 36 不变也清）` → `已重启桌面` |
| 残留清理 | 顺带卸掉 16 个"素材已删但挂载还在"的历史挂载点（此前一直留在设备上） |
| snackbar 汇报 | `已重载 1 个（回填 1 / 重生成 0 / 原样保留 0），桌面已重启` |

测试用的 `dev.bilby` 已按 App 自己的 `removePackage` 逻辑摘干净（双路径素材 + `.packages` + `drawableMapping.xml` + `.managed`），
并跑了一次 `reload` 收敛，系统深浅色模式已复原，测试包已卸载。

---

## 五、改动文件清单

| 文件 | 改动 |
|---|---|
| `gen/LightIconProcessor.kt` | **新增**。白底亮色图标生成（含近白字形降级保护） |
| `gen/LightModePrefs.kt` | **新增**。亮色模式全局偏好 + `dayIcon()` |
| `gen/IconExtractor.kt` | 新增 `accentColor()` / `vividDominant()` / `luma()`（有彩度强调色） |
| `mod/ModuleWriter.kt` | 新增 `reloadAll()` / `restorePackage()` / `regeneratePackage()` / `rebuildPackage()`；`apply.sh` 模板加 `reload` 分支；`action.sh` 加 `.force_reload` 标记；`mergePreserveOfficial()` 入参由 `customDay: Bitmap?` 改成 `includeLight: Boolean` |
| `ui/PreviewScreen.kt` | 亮色区块（三张白底卡片 + 推荐值 + 降级提示）、生成链路接入亮色 |
| `ui/HomeScreen.kt` | 「全量重载全部图标」卡片 + 二次确认弹窗（含「从头重新生成」勾选） |
| `ui/SettingsScreen.kt` | 亮色模式全局默认选择器 |
| `ui/AppListScreen.kt` | 批量生成接入亮色偏好 |
| `app/build.gradle.kts` | `versionCode 7 / versionName 1.3.0`；debug 加 `applicationIdSuffix=".dev"`（便于与本机正式版共存调试） |

模块包 `DarkIconAuto_module_v1.1.0.zip` 里的 `apply.sh / action.sh` 由 `gen_scripts.py`
**直接从 `ModuleWriter.kt` 抽取**（去 `trimIndent` → 还原 `${'$'}` → 代入 Kotlin 常量），
与 App 运行时写出的脚本逐字一致，不会出现"包里一份、App 写的另一份"的漂移；`bash -n` 语法检查通过。

---

## 六、安装与使用

1. **换 App**：`DarkIconGen_v1.3.0.apk`。注意签名与作者原版不同，
   直接覆盖安装会失败 → 需先卸载旧版再装（卸载会丢 App 内的 `icon_cache`，模块里的素材不受影响；
   想保留缓存可以先用旧版把要保留的包重新生成一次，或接受"全量重载 → 从头重新生成"）。
2. **模块**：`DarkIconAuto_module_v1.1.0.zip` 只给全新刷入用。
   **已经在用 v1.0.2 的设备不用重刷** —— 1.3.0 的 App 在生成素材/全量重载时会自动把脚本换成带 `reload` 的版本。
3. **亮色图标**：
   - 单个包：应用页点进预览 → 「亮色模式（白底）」选方案 → 生成并写入模块 → KSU 播放键
   - 批量：设置页选好全局默认 → 应用页勾选多个 → 生成所选
   - 已有图标想套用新设置：首页「全量重载」→ 勾「从头重新生成」
4. **素材被删/图标不刷新**：首页「全量重载全部图标」→ 开始重载。

---

## 七、已知边界

- 亮色件是**满版不透明**图，桌面浅色下该包不再显示官方日间素材（这正是功能目的）；
  想反悔就在预览页把亮色改成「原图标」并重新生成。
- `原色` 会把字形里的近白像素换成主题色（否则白底白图不可见）；纯白字形的图标两种亮色方案结果相同。
- 复杂的非自适应图标（老式位图）走 `stripBackground` 抠图，毛边/渐变底可能抠不干净 —— 这是原项目既有局限，
  可继续用「上传自定义素材」兜。
- 全量重载会重启一次桌面（`am force-stop com.android.launcher`），主页图标会重排动画一闪。

---

## 八、第二轮调整（同一版本号 v1.3.0，重新出包）

### 8.1 App 图标换成兔子照片
- `res/drawable-nodpi/ic_launcher_{foreground,background}.png`（864×864）：
  前景 = 照片缩到画布 66.7%（即 108dp 画布中心 72dp 安全区）居中，四周透明；
  背景 = 同图高斯模糊 + 提亮，只用于圆角外的边缘过渡。渲染后照片正好铺满圆角图标，不会出现"照片贴卡片"。
- 删掉原来的星星矢量 `ic_launcher_{background,foreground,monochrome}.xml` 与 `drawable-night/`；
  adaptive-icon 里去掉 `<monochrome>`（照片图标做单色剪影没有意义）。

### 8.2 预览页重排（3 + 3）
```
暗色   [原图标] [普通抠图] [转色]
亮色   [原图标] [主题色]   [原色]
       [上传暗色素材] [上传浅色素材]
```
- 两排各三张、等宽（`weight(1f) + aspectRatio(1f)`），不再溢出；
- 页面上的说明小字全部删除（推荐值、降级提示等）；
- 「生成并写入模块」固定在底部；「导出素材」移到顶栏右上；
- **修复：整张卡（图 + 下面名字）都是点击区** —— 之前只有图片能点，点名字毫无反应，
  表现就是"选项切换不了"（真机上就是这个原因，不是状态没提升）。
- 暗色新增「原图标」= 本次不写暗色件（`darkEnabled=false`），并把历史残留的
  `rec_night*/recbg*/monochrome*` 清掉，桌面回落到原图标/官方件。

### 8.3 直接复用原厂抠好的图
原厂目录 `/data/oplus/uxicons/<pkg>/` 里本来就有做好的分层素材，之前只被当成"透传保留"，
现在**生成时直接拿它们当输入**（`IconExtractor.officialIcon()`）：

| 原厂文件 | 用途 |
|---|---|
| `rec_night.png` | 暗色字形（透明底，已抠好），直接当暗色前景，不再 flood fill |
| `mat.png` / `recfg.png` | 亮色字形（透明底），直接当亮色管线的字形 |
| `monochrome.png` | 单色剪影，直接写盘，不再自己膨胀描边 |
| `recbg.png` | 主题色来源（官方品牌色），比中心加权取色更准 |
| `day/nsd/art_off` | 满版整图，用作"原图标/取色"基准 |

- 判定「是字形层还是整图」用不透明像素占比（<85% 才算字形），避免把满版图当字形导致整块方图。
- 已接管（本模块已挂载该包）时，`/data/oplus/uxicons/<pkg>` 读到的就是自己的输出 →
  改从模块目录里读**首次透传保存下来的原厂件**，用同样的字形判定筛掉我们生成的满版浅色件。
- 命中时预览页显示「已用 ColorOS 原厂图标」chip。
- 「原图标」卡片固定用应用自身图标（`IconExtractor.appIcon`），不受原厂目录被覆盖影响。

### 8.4 首页「立即执行」
- 新增卡片，点一下直接跑模块 `run_apply manual`（= KSU 播放键），不用再进管理器；
- 执行中卡片转圈，完成后 snackbar 汇报 `新增 / 移除 / 缓存`（`kept` 归一成"无需清除"）。

### 8.5 设置页
- 「导出图标包」：卡片转圈 + 副标题 `正在打包 12/3800 个文件…` + 进度条（`ZipWriter` 新增逐文件进度回调）；
  点下去先弹"正在打包素材"的即时提示，不再像点坏了。
- 亮色选项改成 **两行两列**（`weight(1f)` + `maxLines=1`），窄屏不再被挤掉最后一个；
- 选项值提升为 `remember` 状态，点击立刻生效（原来只写 SharedPreferences、界面不重组）。
- 文案：`白底品牌色 → 主题色`、`白底原色 → 原色`。

### 8.6 亮色「原色」的算法修正
原来整片保留原配色，遇到"彩底白字形"会白底白图、和「主题色」完全撞图。现在：
`原色` = 保留原配色，**只把近白像素换成主题色**（`recolorNearWhite`）。纯白字形的结果与主题色一致（物理上无解），
多色字形（花瓣类）则能保住配色差异。

---

## 九、第三轮修复（同版本号，重新出包）

### 9.1 亮色变"购物袋"的根因：拿错了原厂层
实测 `com.taobao.taobao` 的原厂素材（逐张像素校验）：

| 文件 | 不透明率 | 实际内容 |
|---|---|---|
| `mat / recfg / nsd / day` | **100%** | OPPO 重绘的**满版整图**（橙色购物袋 + 淡淘字） |
| `rec_night` | 13% | 真正的字形（橙色「淘」字，透明底） |
| `recbg` | 100% | 纯色背景 |
| `cat` | 96% | 原应用图标 |

旧逻辑把 `mat/recfg` 当"亮色字形层"→ 白底上出现一只购物袋。现在：
**字形只认 `rec_night`（暗色/亮色共用同一张，形状必然一致）**，`mat/recfg/nsd/day` 降级为"满版整图"用途（取色/预览基准）。

### 9.2 原厂素材备份（用户要求的"改之前先备份"）
- 新增 `$MODULE_DIR/.official_backup/<pkg>/theme/`：**首次**为某包生成前，先把官方原件整目录备份。
- 备份源是**系统分区底层**（不是模块目录，那里可能已经被我们写过）：
  用 `nsenter -t 1 -m -- umount -l /my_product/media/theme` 临时摘掉整目录挂载，
  露出真正的 `theme/uxicons/hdpi/<pkg>`，拷完立刻重新 bind 回去（带 `finally`，异常也会挂回）。
- 之后 `readOfficialUxicons()` 与 `IconExtractor.officialIcon()` 都**优先读备份**——这是唯一没被污染过的一份。

### 9.3 取消勾选 = 还原官方图标（拼多多事件的根治）
- `removePackage()` 不再 `rm -rf` 包目录：有备份就把备份原样拷回去（目录保留，避免 bind 挂载悬空），
  没有备份才删。实测取消淘宝后：`mat.png` 从 16357 字节（我们的白底图）变回 6547 字节（官方原件），目录 20 个官方件。
- 新增设置页「**修复丢失的官方图标**」：全量比对系统分区与模块目录，
  系统有、模块没有的包整包拷回（拼多多正是这种），并把已取消勾选但仍是我们的图标的包整包覆盖回官方。
  实测一次修回 4 个包，拼多多恢复 10 个官方文件。

### 9.4 「清除数据」真正生效
- `wipeAll()` 改为对「清单 ∪ 素材目录」的并集逐包走 `removePackage()`（有备份先还原），再清台账；
- 清完**自动跑一次收敛**（`applyNow`），挂载/白名单/桌面缓存一起回到原样，不再"点了没反应"。

### 9.5 「立即执行」改为强制模式
- 之前用差量 `run_apply manual`：素材换了但 `icon_version` 没变时会判定"无需收敛"，点了没效果。
- 现在 `applyNow()` 直接走 `run_apply reload`：强制拆挂重挂 + 无条件清桌面缓存 + 重启桌面。
- 脚本里的桌面重启也加固了：`am force-stop` → `sleep 1` → `am start -H` 显式拉起桌面
  （只 force-stop 时 ColorOS 会立刻拉回但不重建图标）。
- 实测：点击后桌面进程 pid `20262 → 22253`，`app_icons.db` 被删并按新素材重建（6.7MB → 5.1MB）。

### 9.6 顺带
- 应用列表的自动重生成（`regeneratePackage`）与批量生成也统一走官方备份源，不再从模块目录里的旧输出取层。

### 9.7 「执行了但桌面没变化」的真凶：白名单条目被云控冲掉 + 子串误判

实测链条（不改代码，只读设备状态）：

1. 从桌面缓存 `app_icons.db` 直接导出淘宝的三张图 → 亮色那张还是**官方橙底白字**，说明桌面压根没读我们的素材；
2. `grep '<package_name>com.taobao.taobao<morph>' morph_icons_whitelist.xml` → **没有该条目**；
3. 手动注入这一条 → 清空 `app_icons.db` → 重启桌面 → 缓存里的淘宝亮色立刻变成**白底橙「淘」字**（我们的「主题色」方案）；
4. 再删掉条目、点 App 的「立即执行」→ 条目被自动补回、桌面重建、图标再次正确。

两个 bug：
- `apply.sh` 用 `grep -q "$p"` 做**子串**匹配判断"白名单已在列"——
  文件里只要出现 `com.taobao.taobao` 这个前缀（闲鱼/淘宝直播等条目），就误判为已注入，**永远不注入**，
  而且日志还打印"白名单已在列"。改成精确匹配 `<package_name>$p<morph>` + 写后校验，失败明确报 FAIL。
- 白名单会被系统云控**周期性整文件重写**，条目丢了之后：桌面不认 morph → 退回应用原图标，
  于是"素材写对了、缓存也清了、桌面也重启了，就是不生效"。

修复：
- `apply.sh` / `service.sh` 全部改成精确匹配 + 写后校验；
- `service.sh` 的 10 分钟守护窗口里，**每 30 秒**校验一次白名单，缺了就补注入并重建桌面；
- App 侧新增 `ensureWhitelist()`，「立即执行」「全量重载」在跑脚本**之前**先自检补注入（双保险）。

### 9.8 桌面"重绘"这一步才是最后一道坎

加了微信之后仍不生效，继续挖：把微信的亮色件换成纯绿、清缓存重启桌面 → 桌面立刻变绿；
再用 App 的「立即执行」跑同一步 → 桌面不变。两者差别在**桌面是否真的重绘**：
`am force-stop` 后 ColorOS 会马上把桌面前台拉起，但如果当时屏幕熄着/前台不是桌面，
图标只是"进程重启"而没有重新绘制，`app_icons.db` 也会被原样写回旧数据。

脚本改成三步：
```
input keyevent KEYCODE_WAKEUP      # 先亮屏，保证桌面会真正绘制
am force-stop com.android.launcher
sleep 2
am start -H                       # 显式拉起桌面
sleep 3
rm -f app_icons.db*               # 桌面起来后再清一次，逼它下次绘制重新取图
```
实测：加微信 → 全量重载 → 桌面微信图标从「绿底白气泡」变成「白底绿气泡」（我们的「主题色」方案）。

### 9.9 取消勾选/清除数据后"变不回来"：桌面把成品图缓存在 launcher.db

`app_icons.db` 只是"图标源"缓存；桌面最终画到屏幕上那张图，还存在
`/data/user_de/0/com.android.launcher/databases/launcher.db → singledesktopitems.icon` 里。
只清 `app_icons.db` 时，素材已经还原成官方件、挂载也卸了，桌面仍拿旧 blob 渲染 ——
表现就是"取消勾选、清数据都没用，图标还是我们那张"。

修复 `clearDesktopItemIcons()`：停桌面 → 把 launcher.db 拷到 App 私有目录 → SQLite 里
`UPDATE singledesktopitems SET icon=NULL, modified=modified+1`（只清成品图，**布局不动**）→ 写回 → 拉起桌面。
「立即执行」「清除数据」都会调用它（实测一次清 85 个条目）。

踩到的两个坑（都写在代码注释里）：
- App 进程的 mount namespace 里看不到 `/data/user_de/0/...`，`cp` 直接 "No such file or directory"；
  必须 `nsenter -t 1 -m -- sh <脚本>` 进 init 命名空间操作。
- 拷出来的副本属主是 root，App 打不开可写；脚本里要 `chmod 666`。

### 9.10 亮色图标比暗色小一圈：1.5 倍渲染差

桌面渲染 `rec_night` 时按 adaptive 前景层规则（108/72）**放大 1.5 倍再裁切**，
而 `day/mat` 是整图 1:1 缩放。两边都用 62% 字形时，亮色看起来就小一圈。
实测官方微信 `mat` 的字形占比是 **87%×71%**，所以把亮色管线的 `RATIO` 从 0.62 提到 **0.87**，
现在生成出来的亮色件字形占比正好 87%×71%，与官方一致，视觉与暗色对齐。

### 9.11 取消勾选时把逐包挂载源一并删掉
`removePackage()` 的逐包侧（`data/oplus/uxicons/<pkg>`）不再"还原官方件"，直接删目录 ——
目录没了 `apply.sh` 才会把 `/data/oplus/uxicons/<pkg>` 上的 bind 挂载卸掉；主题侧仍按备份还原官方件（整挂缺不得）。

### 9.12 暗色也偏小：前景层同样按官方比例重标定
上一版只把亮色调到 87%，暗色还是老的 62% —— 而桌面渲染 `rec_night` 会再放大 1.5 倍，
官方 `rec_night` 实测字形占 74%×61%，所以我们的暗色比官方小一圈。

现在两边都对齐官方实测值：
| 件 | 类型 | 字形占比 | 桌面渲染 | 视觉 |
|---|---|---|---|---|
| `rec_night` | 前景层 | **73%×60%** | ×1.5 裁切 | ≈ 满版 |
| `day/mat` | 整图 | **87%×71%** | 1:1 | 87% |

`ForegroundProcessor` 里前景层的比例统一提到 `FG_RATIO = 0.74`（`applyStyle` 与 `toMonochrome` 共用；
用户自上传素材的 `normalizeCustom` 仍走 62%，那是给手修图留的余量）。

预览页同步：暗色卡片按桌面规则**放大 1.5 倍裁切显示**（`zoom = 1.5f`），
所以现在预览里暗色和亮两个图标看起来一样大，不会再误导。

### 9.13 亮色"永远是原版图"的最终根因：另一个图标模块抢占了主题挂载

把微信的 `day/mat/nsd/recfg` 分别染成品红/青/蓝/黄再刷新 —— 桌面变成**黄色**，
说明桌面亮色读的是 `recfg.png`，链路本身没问题。但恢复正常素材后仍是原版，于是查挂载：

```
/my_product/media/theme  ← 源代码是 /data/adb/modules/DarIconComplete/my_product/media/theme
```

本机还装着「欧加暗色图标补全模块 v17.0.1.201（DarIconComplete）」，它把同一个挂载点抢走了。
更麻烦的是两边素材目录的**文件是同一个 inode（硬链接）**，它把自己的官方件写回，我们的生成结果当场被覆盖。
旧脚本只判断"这个挂载点有没有东西挂着"，看到别人挂过就认为"就绪"直接跳过 —— 暗色走逐包挂载所以看着正常，
亮色（走主题目录）永远是原版图。

修复：
1. `apply.sh` 挂载前先把自己和别人的层一起 `umount -l`（最多 6 层），再挂我们的，保证刷新后我们一定在最上面；
2. `service.sh` 守护每轮检查**最上层是不是我们**，被盖住就再叠一层；
3. 素材额外镜像一份到 `DarIconComplete` 的主题目录（`SISTER_MODULE_DIR`），两者共存时谁在上层都能读到我们的图。

实测结论：**两个图标模块不能同时生效**。禁用伞姨模块（`touch …/DarIconComplete/disable` 后重启）之后，
微信亮色立刻变成白底绿气泡（[截图](shots/107_verify.png)），暗色 73%、亮色 87% 都与官方一致。
想恢复伞姨，删掉那个 `disable` 文件重启即可。

### 9.14 尺寸最终定标：亮色 = 暗色 = 73%（附官方实测数据）

先量官方原件（从 `.official_backup` 里取首次生成前的原厂件）：

| 包 | `mat.png`（亮色整图） | `rec_night.png`（暗色前景） |
|---|---|---|
| 微信 | 画布 150，字形 **130×105**（87%） | 画布 180，字形 **134×109**（74%） |
| 淘宝 | 画布 180，字形 **152×144**（84%） | 画布 180，字形 **140×109**（78%） |
| 拼多多 | 画布 150，字形 **108×126**（72%） | — |

→ 官方本来就是**亮暗字形绝对像素几乎相等**（差 3%/8%）。

再在桌面上量我们自己的成品（同一裁剪区，绿色气泡实际像素）：

| 版本 | 暗色 | 亮色 |
|---|---|---|
| 暗 73% / 亮 87% | 142×109 | **170×120**（大 20%） |
| 暗 73% / 亮 73% | 142×109 | **143×109** ✅ |

两路渲染的缩放系数实测都是 0.81（175px→142、209px→170），不存在两套倍率 ——
所以只要亮色跟暗色用同一个 73%，屏幕上就一模一样大。`LightIconProcessor.RATIO` 定为 **0.73**，
预览页也不再对任何一方做 zoom（两边同为 73%，预览与桌面一致）。

顺带补上最后一层缓存：桌面还有 `/data/user_de/0/com.android.launcher/cache/`，
只清 `app_icons.db` + launcher.db 的 item blob 时，亮色仍会从这里读到旧图（实测差 27px）。
`clearDesktopItemIcons()` 现在把这一层也一起清掉，`reloadAll()` 也会调用它。

### 9.15 三个问题：停用不复原 / 图标每十几秒闪一次

**① 停用模块后图标不复原**
模块被 KernelSU 禁用后，`post-fs-data.sh`/`service.sh` 都不会执行 —— 挂载没了（桌面读官方素材），
但桌面自己的三层缓存里还是我们上次写的成品图，所以"模块关了图标没变"。
修复：
- 设置页新增「停用模块并复原图标」：卸掉全部挂载 → 素材还原官方 → 清残留白名单条目 →
  清三层桌面缓存 → 写 `disable` 标记。**点一下立刻复原，不用重启**。
- `BootReceiver`：开机若检测到模块被禁用，自动清一次缓存（ColorOS 会拦开机广播，所以还有兜底）；
- `MainActivity` 启动时再兜底一次（10 分钟内不重复）。
实测：停用后挂载 0 层、微信回到官方绿底白气泡（[截图](shots/122_final_official.png)）。

**② 图标每隔十几秒闪一次（真 bug）**
`service.sh` 的守护窗口每 30 秒跑一轮，用 `mountinfo | tail -1` 判断"最上层是不是我们" ——
这个判据不可靠，几乎每轮都误报"被别人盖住"，于是每轮重挂 + `am force-stop launcher` + 重建缓存；
再加上每轮都去补白名单（补完又被系统云控改写），形成无限循环。日志实测每 30 秒一轮：
```
[service] 白名单被云控冲掉，已补注入 …
[service] 已按补注后的白名单重建桌面
[service] 主题挂载被别人盖住，已叠回最上层
```
修复：
- 判据换成**标记文件** `$THEME_DST/.darkicon_owner`（模块 theme 目录里放的空文件，
  只有我们那层在最上面时才看得到），比解析 mountinfo 层级顺序可靠；
- 守护改成每 60 秒检查一次、**最多重挂 3 次**，重挂成功才重建一次桌面缓存，绝不每轮重启桌面；
- 白名单不再在守护里循环补（改由「立即执行」和生成时处理）。
`service.sh` 从 3621 字节瘦到 1710 字节。

### 9.16 与 hybrid_mount 冲突导致手机重启（严重，已修）

现象：更新模块后手机连续重启（dropbox 里 16:51 / 17:11 / 17:14 / 17:27 / 17:28 五条 `SYSTEM_BOOT`），
另有一份 `system_server_watchdog@…`：

```
Subject: Blocked in handler on PackageManager (PackageManager) for 610s
"PackageManager" prio=5 tid=73 Native
  at android.os.ServiceManager.waitForServiceNative(Native method)
  at android.os.ArtModuleServiceManager$ServiceRegisterer.waitForService(ArtModuleServiceManager.java:59)
  at com.android.server.art.GlobalInjector$DefaultGlobalInjector.getArtd(GlobalInjector.java:100)
```

即 system_server 的 PackageManager 卡在等 `artd` 服务，610 秒后被 watchdog 杀掉重启（当时 `pidof artd` 确实为空）。

根因是**模块目录布局和 hybrid_mount 撞车**：
`/data/adb/modules/hybrid_mount/config.toml` 里 `default_mode = "overlay"`，
`runtime.json → partition_layout.roots` 列了 `my_product`、`system`、`product`…
也就是说 hybrid_mount 会扫描每个模块目录下的 `my_product/…` 并 **overlay 到 `/my_product/…`**。
而我们的模块恰好把素材放在 `my_product/media/theme`、`data/oplus/uxicons`，
post-fs-data 里又对同一路径 `mount --bind` —— 同一个挂载点上 overlay + bind 叠加，
PM 扫描 `/my_product` 时就死在上面。

修复：
1. 素材根目录从 `my_product/media/theme`、`data/oplus/uxicons` 改为 **`payload/theme`、`payload/uxicons`**
   （中性名字，hybrid_mount 不认识，不会插手）；老设备启动时 `migrateLegacyPayload()` 自动搬家并删掉旧目录；
2. `apply.sh` 的"卸层重挂"从**无脑 `umount -l` 六次**改成**只卸载源路径属于本模块的层**
   —— 乱卸会连带拆掉 hybrid_mount 做的 overlay 和其他模块的挂载，这是导致系统异常的另一半原因；
3. 刷入包模板里那份 20 轮 × 30 秒的旧守护也一并换成"4 次定点检查"。

验证：迁移后挂载源变成 `/adb/modules/DarkIconAuto/payload/theme`，`/my_product/media/theme` 只剩 **1 层**，
`artd` 正常运行，dropbox 不再新增 `SYSTEM_BOOT`，桌面图标与白底效果照旧。










