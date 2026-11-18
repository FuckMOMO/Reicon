package com.darkicongen.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.darkicongen.app.gen.ForegroundProcessor
import com.darkicongen.app.gen.IconCache
import com.darkicongen.app.gen.IconExtractor
import com.darkicongen.app.gen.LightIconProcessor
import com.darkicongen.app.gen.LightModePrefs
import com.darkicongen.app.mod.ModuleWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 弹窗界面文案（暗色）：告诉用户该准备什么样的图。
 * 对应处理链 ensureTransparent → normalizeCustom：强制透明底 → 裁透明边 → 长边 62% → 240 居中。
 */
private const val UPLOAD_DOC_DARK = """上传暗色图标 · 要求：
1. 正方形，建议 ≥512×512（各档尺寸自动缩放）
2. 首选已抠好的透明底 PNG；JPG 或白底/纯色底图会自动去背景，渐变或复杂背景可能抠不干净
3. logo 居中，占画面约 62%~80%（App 会把长边归一到 62%，超出会被桌面蒙版裁切）
4. 边缘干净：无白边/灰雾/半透明残留（换底工具选「透明背景导出」）
5. 配色适配暗底：深色 logo 需提亮或加浅色描边，否则黑底上不可见"""

/**
 * 弹窗界面文案（浅色）：对应 normalizeDay 的归一方式 —— 整图等比缩放进 240 满版居中，
 * 不裁边、不抠图，所以必须上传「完整满版、自带底色」的日间图标，留白会被原样写进 day.png。
 */
private const val UPLOAD_DOC_DAY = """上传自定义浅色图标 · 要求：
1. 完整「日间图标」原图：含底色/渐变底，画面铺满（不要抠图剪影、不要自行留白）
2. 正方形，建议 ≥512×512（会等比缩到 240 满版，原图太小会发虚）
3. PNG / JPG 均可（浅色图不需要透明通道）
4. App 只做等比缩放居中，不裁边、不抠图 —— 你上传什么就写入什么
5. 会覆盖全部浅色件（recfg/art_off/day/mat 及各档），不影响暗色图标"""

/**
 * 剪贴板提示词（暗色）：不是上面界面文案的副本，而是交给 AI 修图/生成工具的任务描述，
 * 逐条对应本项目的硬规格（透明底、居中占比、暗底可见性、禁止重绘），粘过去即可产出可用素材。
 */
private const val PROMPT_DARK = """把这张应用图标改造成安卓桌面「暗色模式」前景素材：
1. 输出 PNG 并保留透明通道，背景完全删除，不要白底/黑底/任何纯色底
2. 主体 logo 居中，占画面约 62%~80%，四边留空（超出部分会被桌面蒙版裁切）
3. 边缘干净：无白边、灰雾、半透明残留、描边锯齿
4. 配色适配深色桌面：原本深色的部分提亮，或加浅色描边，确保在 #1A1A1A 底上清晰可辨
5. 保留品牌识别度：不要重绘 logo、不要改造型、不要加文字、不要加投影
6. 输出正方形 1024×1024"""

/** 剪贴板提示词（浅色）：要求产出与官方一致的完整日间原图，满版不透明、不裁角。 */
private const val PROMPT_DAY = """输出这张应用的安卓桌面「浅色/日间」图标完整原图：
1. 画面铺满整个正方形，保留原本的底色/渐变底，不要抠图、不要透明背景、不要留白
2. 与官方原图标保持一致：不要重绘、不要改配色、不要加文字或水印
3. 四边四角完整，不要自行裁切或压圆角（桌面蒙版由系统负责）
4. 输出正方形 1024×1024，PNG 或 JPG 均可"""

/** 上传要求弹窗的入口目标：决定界面文案、提示词与文件选择器类型 */
private enum class UploadTarget { DAY, DARK }

/**
 * 图标预览页（v2 版式）：
 *
 * ```
 * 暗色图标   [普通抠图] [转色]                     ← 深色底卡片，点选即预览
 * 亮色图标   [原图标] [白底品牌色] [白底原色]        ← 白底卡片，点选即预览
 *            [上传暗色素材]  [上传浅色素材]
 * ```
 * 底部常驻「生成并写入模块」，不再把参数说明铺在页面上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    row: AppRow,
    onBack: () -> Unit,
    onGenerated: (Boolean) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }

    // 预览数据异步加载（图标提取+方案处理是重活，不能阻塞主线程，否则点按卡顿）
    var loaded by remember(row.pkg) { mutableStateOf<PreviewData?>(null) }
    LaunchedEffect(row.pkg) {
        loaded = withContext(Dispatchers.IO) {
            runCatching {
                val ext = IconExtractor.extract(ctx, row.pkg)
                val fg = ForegroundProcessor.extractForeground(ext.foreground, ext.full)
                // 亮色字形：原厂自带抠好的透明底字形（mat/recfg）就直接用，否则退回暗色前景
                val lightGlyph = ext.lightForeground ?: fg
                // 品牌主色取自完整图标（中心加权），转色方案用它
                val brand = IconExtractor.dominantColor(ext.full)
                // 亮色强调色：优先取背景层（原厂 recbg 就是品牌色本身）
                val accent = IconExtractor.accentColor(ext.background, ext.full)
                val darkRatio = com.darkicongen.app.gen.RatioPrefs.currentDark(ctx)
                val lightRatio = com.darkicongen.app.gen.RatioPrefs.currentDark(ctx)
                // 有官方适配时：「原图标」= 官方那套（暗色用 recbg+rec_night 合成，亮色用官方整图），
                // 而不是应用自己的图标 —— 否则用户会以为官方图标被我们改过
                /*
                 * 「原图标」用的就是官方字形层（rec_night：淘宝是橙色「淘」字）：
                 * 暗色 = 近黑底 + 字形，亮色 = 白底 + 字形。
                 * 不碰 mat（拟物购物袋）、nsd（深灰整图）、recbg（品牌色底）—— 都不是用户要的那套。
                 */
                val officialDark = if (ext.fromOfficial && ext.foreground != null) {
                    val bg = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888)
                    bg.eraseColor(0xFF0F0F0F.toInt())
                    com.darkicongen.app.gen.ForegroundProcessor.composeExact(bg, ext.foreground, 240)
                } else null
                val officialLight = if (ext.fromOfficial && ext.foreground != null) {
                    val bg = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888)
                    bg.eraseColor(android.graphics.Color.WHITE)
                    com.darkicongen.app.gen.ForegroundProcessor.composeExact(bg, ext.foreground, 240)
                } else null
                PreviewData(
                    cutout = ForegroundProcessor.applyStyle(fg, ForegroundProcessor.Style.CUTOUT, 240, brand, darkRatio),
                    recolor = ForegroundProcessor.applyStyle(fg, ForegroundProcessor.Style.RECOLOR, 240, brand, darkRatio),
                    // 单色剪影：原厂 monochrome 直接用（不自己膨胀，边缘更准）
                    mono = ext.monochrome ?: ForegroundProcessor.toMonochrome(fg, 240, darkRatio),
                    bg = brand,
                    suggested = ForegroundProcessor.suggestStyle(fg),
                    ext = ext,
                    lightBrand = LightIconProcessor.build(
                        lightGlyph, accent, LightIconProcessor.Mode.BRAND, 240, lightRatio,
                    ),
                    lightOriginal = LightIconProcessor.build(
                        lightGlyph, accent, LightIconProcessor.Mode.ORIGINAL, 240, lightRatio,
                    ),
                    suggestedLight = LightIconProcessor.suggestMode(ext.background, ext.full),
                    // 「原图标」：官方适配就用官方素材，没有官方适配才退回应用自身图标
                    original = officialLight
                        ?: IconExtractor.appIcon(ctx, row.pkg)?.let { Bitmap.createScaledBitmap(it, 240, 240, true) }
                        ?: Bitmap.createScaledBitmap(ext.full, 240, 240, true),
                    originalDark = officialDark,
                    lightGlyph = lightGlyph,
                    accentColor = accent,
                )
            }.getOrNull()
        }
    }

    /*
     * 选择记忆：
     * - 有官方适配的应用，暗色默认「原图标」（官方素材本来就是设计师出的，比自动抠图好）；
     * - 没有官方适配的，沿用上一次的选择（RatioPrefs 记忆），第一次进来用自动推荐。
     */
    var darkPick by remember(row.pkg) {
        mutableStateOf<String?>(com.darkicongen.app.gen.RatioPrefs.darkPick(ctx))
    }
    // 暗色 / 亮色比例分开记忆（记忆本身是全局一份，不按应用区分）
    var darkRatioValue by remember {
        mutableStateOf(com.darkicongen.app.gen.RatioPrefs.currentDark(ctx))
    }
    var lightRatioValue by remember {
        mutableStateOf(com.darkicongen.app.gen.RatioPrefs.currentLight(ctx))
    }
    var lightPick by remember(row.pkg) {
        mutableStateOf(
            com.darkicongen.app.gen.RatioPrefs.lightPick(ctx)
                ?: if (com.darkicongen.app.gen.LightModePrefs.read(ctx) == "auto") "brand"
                   else com.darkicongen.app.gen.LightModePrefs.read(ctx)
        )
    }
    // 上传要求弹窗目标（null = 不显示）
    var uploadDoc by remember { mutableStateOf<UploadTarget?>(null) }
    // 暂存的自定义素材：只在「生成并写入模块」时落盘
    var customFg by remember { mutableStateOf<Bitmap?>(null) }
    var customMono by remember { mutableStateOf<Bitmap?>(null) }
    var customDay by remember { mutableStateOf<Bitmap?>(null) }

    // 暗色素材选择器：任意图片格式（JPG 也可），强制透明底后按长边 62% 归一居中
    val pickImage = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                // 剪影也在 IO 线程一并算好，预览与生成结果才一致（膨胀是重活，不能放组合期）
                val result = withContext(Dispatchers.IO) {
                    val bmp = runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()
                    bmp?.let {
                        runCatching {
                            val fg = ForegroundProcessor.normalizeCustom(ForegroundProcessor.ensureTransparent(it))
                            fg to ForegroundProcessor.toMonochrome(fg)
                        }.getOrNull()
                    }
                }
                busy = false
                if (result == null) {
                    snackbar.showSnackbar("无法读取图片")
                } else {
                    customFg = result.first
                    customMono = result.second
                }
            }
        }
    }
    // 浅色(日间)图标选择器：任意图片，归一到 240 全图标（非抠图，保留完整画面）
    val pickDayImage = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()
                }
                if (bmp == null) snackbar.showSnackbar("无法读取图片")
                else customDay = normalizeDay(bmp)
            }
        }
    }

    // 可预测返回：先关上传要求弹窗，无则回到列表而非退出应用
    BackHandler {
        if (uploadDoc != null) uploadDoc = null else onBack()
    }

    /** 导出原始素材（原图/前景/背景），写完即可在选图界面直接选取 */
    fun exportAssets() {
        val d = loaded ?: return
        scope.launch {
            busy = true
            val files = withContext(Dispatchers.IO) {
                runCatching { exportOriginalAssets(ctx, row.pkg, d.ext) }.getOrNull()
            }
            busy = false
            snackbar.showSnackbar(
                if (!files.isNullOrEmpty()) "已导出 ${files.size} 张到 相册/DarkIconGen"
                else "导出失败",
                duration = SnackbarDuration.Long,
            )
        }
    }

    /** 生成并写入模块（纯声明式：只写素材，运行时收敛归「立即执行」/开机脚本） */
    fun generate() {
        val d = loaded ?: return
        val darkKey = darkKeyOf(d, darkPick)
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // 上传的暗色素材优先于方案卡片；有上传就必然要写暗色件
                    val custom = customFg
                    val darkOn = custom != null || darkKey != "none"
                    val styleFinal = when (darkKey) {
                        "cutout" -> ForegroundProcessor.Style.CUTOUT
                        "recolor" -> ForegroundProcessor.Style.RECOLOR
                        else -> d.suggested
                    }
                    val fgFinal = custom ?: when (styleFinal) {
                        ForegroundProcessor.Style.CUTOUT -> d.cutout
                        ForegroundProcessor.Style.RECOLOR -> d.recolor
                    }
                    val monoFinal = customMono ?: d.mono
                    // 亮色件：用户上传的浅色图优先；否则按本页选中的亮色方案现场生成
                    val dayFinal = customDay ?: when (effectiveLight(d, lightPick)) {
                        LightIconProcessor.Mode.OFF -> null
                        LightIconProcessor.Mode.BRAND -> d.lightBrand
                        LightIconProcessor.Mode.ORIGINAL -> d.lightOriginal
                    }
                    // 原图标原料入永久缓存（撤销/重生成原料）
                    IconCache.storeOriginal(ctx, row.pkg, d.ext.full, d.ext.foreground, d.ext.background)
                    ModuleWriter.writePackageIcons(
                        ctx, row.pkg, fgFinal, monoFinal, d.bg,
                        if (custom != null) "CUSTOM" else styleFinal.name, dayFinal,
                        darkEnabled = darkOn,
                    )
                }.onFailure { err ->
                    // 写失败的原因写进运行日志，别再一律甩锅给 root
                    runCatching {
                        ModuleWriter.appendAudit(
                            "generate ${row.pkg} FAILED: ${err.javaClass.simpleName}: ${err.message}"
                        )
                    }
                }.isSuccess
            }
            busy = false
            snackbar.showSnackbar(
                if (ok) "素材已写入，点「立即执行」后生效" else "生成失败，请检查 root 权限",
                duration = SnackbarDuration.Long,
            )
            onGenerated(ok)
        }
    }

    val data = loaded

    // 数据加载完后校正默认选择：有官方适配且没有历史记忆时，暗色默认「原图标」
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        if (com.darkicongen.app.gen.RatioPrefs.darkPick(ctx) == null && d.ext.fromOfficial) {
            darkPick = "none"
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { exportAssets() }, enabled = data != null && !busy) {
                        Text("导出素材")
                    }
                },
            )
        },
        bottomBar = {
            if (data != null) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(50.dp),
                        enabled = !busy,
                        onClick = { generate() },
                    ) { Text(if (busy) "处理中…" else "生成并写入模块") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (data == null) {
            Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val darkKey = darkKeyOf(data, darkPick)
        // 实时预览：比例一变就重新合成这几张图
        val fgNow = data.ext.foreground ?: data.original
        val cutoutNow = remember(data, darkRatioValue) {
            ForegroundProcessor.applyStyle(fgNow, ForegroundProcessor.Style.CUTOUT, 240, data.bg, darkRatioValue)
        }
        val recolorNow = remember(data, darkRatioValue) {
            ForegroundProcessor.applyStyle(fgNow, ForegroundProcessor.Style.RECOLOR, 240, data.bg, darkRatioValue)
        }
        val lightBrandNow = remember(data, lightRatioValue) {
            data.lightGlyph?.let {
                LightIconProcessor.build(it, data.accentColor, LightIconProcessor.Mode.BRAND, 240, lightRatioValue)
            } ?: data.lightBrand
        }
        val lightOriginalNow = remember(data, lightRatioValue) {
            data.lightGlyph?.let {
                LightIconProcessor.build(it, data.accentColor, LightIconProcessor.Mode.ORIGINAL, 240, lightRatioValue)
            } ?: data.lightOriginal
        }
        val lightStyle = effectiveLight(data, lightPick)

        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 原厂重绘素材命中提示：有 ColorOS 官方适配的应用（淘宝这类）抠图基于官方图，值得让用户知道
            if (data.ext.fromOfficial) {
                AssistChip(
                    onClick = {},
                    label = { Text("已用 ColorOS 原厂图标") },
                )
            }

            SectionTitle("暗色")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionCard(
                    label = "原图标",
                    bmp = data.originalDark ?: data.original,
                    selected = darkKey == "none",
                    darkBackdrop = true,
                    modifier = Modifier.weight(1f),
                ) {
                    darkPick = "none"
                    com.darkicongen.app.gen.RatioPrefs.writeDarkPick(ctx, "none")
                }
                OptionCard(
                    label = "普通抠图",
                    bmp = cutoutNow,
                    selected = darkKey == "cutout",
                    darkBackdrop = true,
                    modifier = Modifier.weight(1f),
                    
                ) {
                    darkPick = "cutout"
                    com.darkicongen.app.gen.RatioPrefs.writeDarkPick(ctx, "cutout")
                }
                OptionCard(
                    label = "转色",
                    bmp = recolorNow,
                    selected = darkKey == "recolor",
                    darkBackdrop = true,
                    modifier = Modifier.weight(1f),
                    
                ) {
                    darkPick = "recolor"
                    com.darkicongen.app.gen.RatioPrefs.writeDarkPick(ctx, "recolor")
                }
            }


            // 暗色比例：范围来自设置页的区间，值记忆
            InlineRatioSlider(
                title = "暗色比例",
                ratio = darkRatioValue,
                onRatio = {
                    darkRatioValue = it
                    com.darkicongen.app.gen.RatioPrefs.writeLastDark(ctx, it)
                },
            )
            Spacer(Modifier.height(6.dp))
            SectionTitle("亮色")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OptionCard(
                    label = "原图标",
                    bmp = data.original,
                    selected = lightStyle == LightIconProcessor.Mode.OFF,
                    darkBackdrop = false,
                    modifier = Modifier.weight(1f),
                ) {
                    lightPick = "off"
                    LightModePrefs.write(ctx, "off")
                    com.darkicongen.app.gen.RatioPrefs.writeLightPick(ctx, "off")
                }
                OptionCard(
                    label = "原色",
                    bmp = lightOriginalNow,
                    selected = lightStyle == LightIconProcessor.Mode.ORIGINAL,
                    darkBackdrop = false,
                    modifier = Modifier.weight(1f),
                ) {
                    lightPick = "original"
                    LightModePrefs.write(ctx, "original")
                    com.darkicongen.app.gen.RatioPrefs.writeLightPick(ctx, "original")
                }
                OptionCard(
                    label = "主题色",
                    bmp = lightBrandNow,
                    selected = lightStyle == LightIconProcessor.Mode.BRAND,
                    darkBackdrop = false,
                    modifier = Modifier.weight(1f),
                ) {
                    lightPick = "brand"
                    LightModePrefs.write(ctx, "brand")
                    com.darkicongen.app.gen.RatioPrefs.writeLightPick(ctx, "brand")
                }
            }


            // 亮色比例
            InlineRatioSlider(
                title = "亮色比例",
                ratio = lightRatioValue,
                onRatio = {
                    lightRatioValue = it
                    com.darkicongen.app.gen.RatioPrefs.writeLastLight(ctx, it)
                },
            )

            Spacer(Modifier.height(10.dp))
            MaterialPickRow(
                thumb = customFg,
                label = "暗色素材",
                busy = busy,
                onPick = { uploadDoc = UploadTarget.DARK },
                onClear = { customFg = null; customMono = null },
            )
            MaterialPickRow(
                thumb = customDay,
                label = "浅色素材",
                busy = busy,
                onPick = { uploadDoc = UploadTarget.DAY },
                onClear = { customDay = null },
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    // 上传要求弹窗：浅色/暗色各有独立的界面要求文案与生成提示词 → 确认后拉起对应文件选择器
    val docTarget = uploadDoc
    if (docTarget != null) {
        val isDay = docTarget == UploadTarget.DAY
        val doc = if (isDay) UPLOAD_DOC_DAY else UPLOAD_DOC_DARK
        val prompt = if (isDay) PROMPT_DAY else PROMPT_DARK
        AlertDialog(
            onDismissRequest = { uploadDoc = null },
            title = { Text(if (isDay) "上传要求 · 浅色图标" else "上传要求 · 暗色图标") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(doc)
                    TextButton(onClick = {
                        copyToClipboard(ctx, if (isDay) "浅色图标提示词" else "暗色图标提示词", prompt)
                        scope.launch {
                            snackbar.showSnackbar(if (isDay) "已复制浅色图标提示词" else "已复制暗色图标提示词")
                        }
                    }) { Text(if (isDay) "复制浅色提示词" else "复制暗色提示词") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uploadDoc = null
                    if (isDay) pickDayImage.launch("image/*") else pickImage.launch("image/*")
                }) { Text("我已了解，选择图片") }
            },
            dismissButton = {
                TextButton(onClick = { uploadDoc = null }) { Text("取消") }
            },
        )
    }
}

/** 把「全局偏好 / 本页手选」折算成实际生效的亮色模式 */
private fun effectiveLight(data: PreviewData, pick: String): LightIconProcessor.Mode = when (pick.lowercase()) {
    "off" -> LightIconProcessor.Mode.OFF
    "brand" -> LightIconProcessor.Mode.BRAND
    "original" -> LightIconProcessor.Mode.ORIGINAL
    else -> data.suggestedLight
}

/** 暗色方案键：未手选时按自动推荐折算；"none" = 暗色保持原图标 */
private fun darkKeyOf(data: PreviewData, pick: String?): String =
    pick ?: if (data.suggested == ForegroundProcessor.Style.RECOLOR) "recolor" else "cutout"

/** 区块标题：只做分组，不写解释文案 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 选项卡片：卡面即预览（暗色方案用 #1A1A1A 深底，亮色方案用纯白底），选中描边加粗。
 * 尺寸由 weight + aspectRatio 均分，三列也不会溢出。
 *
 * 整列（图 + 下面的名字）都是点击区：只让图片可点的话，用户点文字名字会毫无反应，
 * 看上去就是"选项切不了"。
 */
@Composable
private fun OptionCard(
    label: String,
    bmp: Bitmap,
    selected: Boolean,
    darkBackdrop: Boolean,
    modifier: Modifier = Modifier,
    /** 预览放大倍率：暗色前景在桌面上会被放大 1.5 倍裁切，预览按同规则画才不会显得比亮色小 */
    zoom: Float = 1f,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (darkBackdrop) Color(0xFF1A1A1A) else Color.White)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    // 暗色卡片本身是近黑色底：选中的高亮必须用亮色描边，否则黑框压在黑底上根本看不出来
                    color = when {
                        selected && darkBackdrop -> MaterialTheme.colorScheme.primary
                        selected -> MaterialTheme.colorScheme.primary
                        darkBackdrop -> Color(0xFF3A3A3A)
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(16.dp),
                ),
        ) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 自定义素材行：缩略图（已上传时）+ 上传/更换 + 清除 */
@Composable
private fun MaterialPickRow(
    thumb: Bitmap?,
    label: String,
    busy: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (thumb != null) {
            Image(
                thumb.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // 垫底色：暗色素材是透明 PNG，不垫底在深色主题下会看不见
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            )
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f), enabled = !busy) {
            Text(if (thumb == null) "上传$label" else "更换$label")
        }
        if (thumb != null) {
            TextButton(onClick = onClear, enabled = !busy) { Text("清除") }
        }
    }
}

/** 导出目录：相册（Pictures）下的专属文件夹。只有落在媒体目录才会被 MediaProvider 收录，选图界面才看得到。 */
private const val EXPORT_DIR = "/sdcard/Pictures/DarkIconGen"

/**
 * 导出原始素材为图片，扁平放在 [EXPORT_DIR]，文件名以包名描述：`<包名>_原图/_前景/_背景.png`，
 * 用户在「上传暗色素材 / 上传浅色素材」的选图界面里能直接认出来。
 *
 * 经 root 写入以绕过应用存储限制；写完 chmod 644 让 MediaProvider 可读，再触发媒体扫描建索引
 * （否则文件虽在相册目录里，选图界面要等下次全盘扫描才显示）。
 * 返回经 ls 确认已落盘的文件绝对路径——cp 失败时不会谎报成功。
 */
private fun exportOriginalAssets(ctx: android.content.Context, pkg: String, ext: IconExtractor.Result): List<String> {
    val tmp = ctx.cacheDir.resolve("export_$pkg").apply { mkdirs() }
    val names = mutableListOf<String>()
    fun save(suffix: String, b: Bitmap?) {
        if (b == null) return
        val n = "${pkg}_$suffix.png"
        runCatching { tmp.resolve(n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onSuccess { names.add(n) }
    }
    save("原图", ext.full)
    save("前景", ext.foreground?.let { ForegroundProcessor.normalizeCustom(it, 432) }
        ?: runCatching {
            // 传统图标无独立前景层：导出抠图（已去毛边）
            ForegroundProcessor.extractForeground(null, ext.full)
        }.getOrNull())
    save("背景", ext.background)
    if (names.isEmpty()) {
        tmp.deleteRecursively()
        return emptyList()
    }
    com.topjohnwu.superuser.Shell.cmd(
        "mkdir -p '$EXPORT_DIR'",
        "cp ${tmp.absolutePath}/*.png '$EXPORT_DIR/'",
        "chmod 644 '$EXPORT_DIR'/*.png",
    ).exec()
    tmp.deleteRecursively()
    val landed = com.topjohnwu.superuser.Shell.cmd("ls '$EXPORT_DIR'").exec().out.map { it.trim() }.toSet()
    val paths = names.filter { it in landed }.map { "$EXPORT_DIR/$it" }
    if (paths.isNotEmpty()) {
        android.media.MediaScannerConnection.scanFile(
            ctx, paths.toTypedArray(), Array(paths.size) { "image/png" }, null
        )
    }
    return paths
}

/**
 * 写系统剪贴板。Android 13+ 系统自带浮层提示，但仍额外由调用方弹 snackbar，
 * 保证 12 及以下也有可感知反馈。
 */
private fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

/** 浅色(日间)图标归一：等比缩放到 240×240 居中（完整图标，不抠图/不裁透明边，区别于暗色前景的 62% 归一） */
private fun normalizeDay(src: Bitmap, size: Int = 240): Bitmap {
    val scale = size.toFloat() / maxOf(src.width, src.height)
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(src, w, h, true)
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(scaled, (size - w) / 2f, (size - h) / 2f, null)
    return out
}

private data class PreviewData(
    val cutout: Bitmap,           // 普通抠图：原色透明底
    val recolor: Bitmap,          // 转色：白→品牌色透明底
    val mono: Bitmap,             // monochrome 剪影
    val bg: Int,                  // 品牌主色（recbg 用）
    val suggested: ForegroundProcessor.Style,
    val ext: IconExtractor.Result,
    val lightBrand: Bitmap,       // 白底 + 品牌色字形
    val lightOriginal: Bitmap,    // 白底 + 原色字形（近白部分自动替换成品牌色）
    val suggestedLight: LightIconProcessor.Mode,
    val original: Bitmap,         // 240 归一原图标（"原图标"项的预览）
    /** 官方暗色原图（有官方适配时才有，用于「原图标」暗色卡） */
    val originalDark: Bitmap? = null,
    /** 亮色字形与强调色：比例变化时要重新合成，所以一并存下来 */
    val lightGlyph: Bitmap? = null,
    val accentColor: Int = 0,
)

/** 详情页的比例条：只有滑块 + 右侧百分比（点了弹框手填），拖到哪预览就变到哪 */
@Composable
private fun InlineRatioSlider(
    title: String,
    ratio: Float,
    onRatio: (Float) -> Unit,
) {
    val ctx = LocalContext.current
    val lo = com.darkicongen.app.gen.RatioPrefs.rangeMin(ctx)
    val hi = com.darkicongen.app.gen.RatioPrefs.rangeMax(ctx)
    var editing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Slider(
            value = ratio.coerceIn(lo, hi),
            onValueChange = onRatio,
            valueRange = lo..hi,
            modifier = Modifier.weight(1f),
        )
        Text(
            com.darkicongen.app.gen.RatioPrefs.label(ratio),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { editing = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
    if (editing) {
        var text by remember { mutableStateOf((ratio * 100).toInt().toString()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
                        label = { Text("百分比（${(lo * 100).toInt()} ~ ${(hi * 100).toInt()}）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { onRatio((it / 100f).coerceIn(lo, hi)) }
                    editing = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
    }
}
