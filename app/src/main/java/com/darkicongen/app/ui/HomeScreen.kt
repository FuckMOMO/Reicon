package com.darkicongen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkicongen.app.mod.ModuleWriter
import com.darkicongen.app.mod.TaskCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：状态卡（工作中/缺失组件）+ 立即执行 + 全量重载 + 运行日志。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 缓存住环境状态：null 表示还没检测过（此时不渲染红/绿，避免闪一下）
    val rootOk by TaskCenter.rootOk.collectAsState()
    val lsposedActive by TaskCenter.lsposedActive.collectAsState()
    val moduleFlashed by TaskCenter.moduleFlashed.collectAsState()
    var appVersion by remember { mutableStateOf("?") }
    var moduleVersion by remember { mutableStateOf("?") }
    val pkgCountRaw by TaskCenter.pkgCount.collectAsState()
    val pkgCount = if (pkgCountRaw < 0) 0 else pkgCountRaw
    var iconVersion by remember { mutableStateOf(-1) }
    var showLog by remember { mutableStateOf(false) }

    // 全量重载（强制刷新）
    var showReloadConfirm by remember { mutableStateOf(false) }
    // 任务走应用级 TaskCenter：底部切页不会中止，进度也留得住
    val reloading by TaskCenter.reloading.collectAsState()
    var reloadFromScratch by remember { mutableStateOf(false) }
    // 立即执行（强制收敛，等价 KSU 播放键）
    val applying by TaskCenter.applying.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        TaskCenter.message.collect { msg ->
            if (msg != null) {
                snackbar.showSnackbar(msg, duration = SnackbarDuration.Long)
                TaskCenter.consumeMessage()
            }
        }
    }

    LaunchedEffect(Unit) {
        appVersion = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        TaskCenter.refreshStatus(ctx)
    }

    if (showLog) {
        LogScreen(onBack = { showLog = false })
        return
    }

    val checked = lsposedActive != null && moduleFlashed != null
    val ready = (lsposedActive == true) && (moduleFlashed == true)
    val missing = buildList {
        if (lsposedActive == false) add("LSPosed 框架")
        if (moduleFlashed == false) add("Reicon 模块")
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).statusBarsPadding(),
            userScrollEnabled = false,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            // 大标题（仿 KernelSU，紧贴下面的状态卡）
            item {
                Text(
                    "Reicon",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
                )
            }

            // 主状态卡
            item {
                val infoColor = if (ready) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer
                // 渐变底色：比纯色块更有层次，数字放大做主视觉
                val bg = if (ready) {
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        )
                    )
                } else {
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.errorContainer,
                        )
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Box(
                        Modifier
                            .background(bg, RoundedCornerShape(28.dp))
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(26.dp),
                                    shape = CircleShape,
                                    color = if (ready) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (ready) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                            null,
                                            tint = if (ready) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onError,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                Text(
                                    when {
                rootOk == false -> "未获得 root 权限"
                ready -> "工作中"
                !checked -> "检测中…"
                else -> "缺失 ${missing.joinToString(" 与 ")}"
            },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = infoColor,
                                )
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "已适配",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = infoColor.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(bottom = 8.dp, end = 6.dp),
                                )
                                Text(
                                    "$pkgCount",
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = infoColor,
                                )
                                Text(
                                    "个应用",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = infoColor.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(bottom = 8.dp, start = 6.dp),
                                )
                            }
                            Text(
                                when {
                                    ready && pkgCount > 0 -> "改完图标记得点「立即执行」刷新生效"
                                    ready -> "还没有适配任何应用，去「应用」里挑一个试试"
                                    lsposedActive == false && moduleFlashed == false -> "安装 LSPosed，刷入模块后重启"
                                    lsposedActive == false -> "安装并启用 LSPosed"
                                    else -> "刷入模块包后重启"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = infoColor.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // 立即执行：强制收敛（强刷缓存 + 重建桌面）
            item {
                ActionCard(
                    icon = Icons.Filled.PlayArrow,
                    title = "立即执行",
                    subtitle = if (applying) "正在应用更改…" else "强制重挂并重建桌面图标",
                    enabled = !applying,
                    trailing = if (applying) {
                        { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else null,
                    onClick = {
                        TaskCenter.applyNow(ctx) {
                            TaskCenter.refreshPkgCount()
                            iconVersion = runCatching { ModuleWriter.readIconVersion() }.getOrDefault(iconVersion)
                        }
                    },
                )
            }

            if (applying) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
            }

            // 全量重载
            item {
                ActionCard(
                    icon = Icons.Filled.RestartAlt,
                    title = "全量重载全部图标",
                    subtitle = if (reloading) "正在重载…" else "素材回填 · 强制重挂 · 清桌面缓存",
                    enabled = !reloading,
                    trailing = if (reloading) {
                        { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else null,
                    onClick = { showReloadConfirm = true },
                )
            }

            if (reloading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
            }

            // 日志入口
            item {
                ActionCard(
                    icon = Icons.Filled.Subject,
                    title = "运行日志",
                    subtitle = "执行历史",
                    onClick = { showLog = true },
                )
            }
        }
    }

    if (showReloadConfirm) {
        AlertDialog(
            onDismissRequest = { showReloadConfirm = false },
            title = { Text("全量重载 $pkgCount 个图标") },
            text = {
                Column {
                    Text(
                        "会做三件事：\n" +
                            "1. 按 App 内缓存把素材重新写回模块（被删掉的素材直接补回来；缓存也没有的才重新生成，" +
                            "素材完好的一律原样保留）\n" +
                            "2. 删掉桌面图标缓存（app_icons.db）\n" +
                            "3. 强制拆挂重挂并重启桌面\n\n" +
                            "解决的就是「已经装过了所以脚本认为没变化」——不用手动删文件，也不用重刷模块。"
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { reloadFromScratch = !reloadFromScratch },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = reloadFromScratch, onCheckedChange = { reloadFromScratch = it })
                        Text(
                            "从头重新生成（忽略缓存，套用当前亮色设置）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !reloading,
                    onClick = {
                        showReloadConfirm = false
                        TaskCenter.reloadAll(ctx, reloadFromScratch)
                    },
                ) { Text(if (reloading) "重载中…" else "开始重载") }
            },
            dismissButton = {
                TextButton(onClick = { showReloadConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 动作卡：彩色圆形图标 + 功能名（首页与设置页共用，可挂尾部进度指示） */
@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    iconContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(modifier = Modifier.size(36.dp), shape = CircleShape, color = iconContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconTint)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
}

/** run.log 会话解析结果 */
private data class LogSession(val header: String, val lines: List<String>)

private fun parseRunLog(raw: List<String>): List<LogSession> {
    val sessions = mutableListOf<LogSession>()
    var cur: MutableList<String>? = null
    var header = ""
    for (line in raw) {
        val t = line.trim()
        if (t.isEmpty()) continue
        if (t.startsWith("===")) {
            cur?.let { if (it.isNotEmpty()) sessions.add(LogSession(header, it)) }
            header = t
            cur = mutableListOf()
        } else {
            if (cur == null) cur = mutableListOf()
            cur!!.add(t)
        }
    }
    cur?.let { if (it.isNotEmpty()) sessions.add(LogSession(header, it)) }
    return sessions.reversed()
}

/** 日志页：结构化渲染（会话分块、FAIL 标红、汇总加粗）+ 复制全文 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var raw by remember { mutableStateOf<List<String>?>(null) }
    var snackbarHost by remember { mutableStateOf(SnackbarHostState()) }

    fun reload() {
        scope.launch {
            raw = withContext(Dispatchers.IO) { runCatching { ModuleWriter.readRunLog() }.getOrDefault(emptyList()) }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val text = (raw ?: emptyList()).joinToString("\n")
                            if (text.isBlank()) {
                                snackbarHost.showSnackbar("日志为空")
                            } else {
                                clipboard.setText(AnnotatedString(text))
                                snackbarHost.showSnackbar("已复制全文")
                            }
                        }
                    }) { Icon(Icons.Filled.ContentCopy, "复制全文") }
                    // 删除：清空 run.log（原来这里的"刷新"按钮已去掉）
                    IconButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { ModuleWriter.clearRunLog() }.getOrDefault(false) }
                            reload()
                            snackbarHost.showSnackbar(if (ok) "日志已清空" else "清空失败，请检查 root 权限")
                        }
                    }) { Icon(Icons.Filled.Delete, "清空日志") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        val sessions = raw?.let { parseRunLog(it) }
        when {
            raw == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            raw!!.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("暂无执行记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sessions!!) { s ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (s.header.isNotEmpty()) {
                                Text(
                                    s.header,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            s.lines.forEach { line ->
                                val isFail = line.contains("FAIL", ignoreCase = true)
                                val isSummary = line.startsWith("新增") || line.startsWith("移除") || line.startsWith("缓存")
                                Text(
                                    line,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSummary) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isFail -> MaterialTheme.colorScheme.error
                                        isSummary -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
