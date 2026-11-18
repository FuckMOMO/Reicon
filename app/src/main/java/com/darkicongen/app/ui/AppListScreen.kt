package com.darkicongen.app.ui

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darkicongen.app.gen.ForegroundProcessor
import com.darkicongen.app.gen.IconCache
import com.darkicongen.app.gen.IconExtractor
import com.darkicongen.app.gen.LightModePrefs
import com.darkicongen.app.mod.ModuleWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppRow(
    val pkg: String,
    val label: String,
    val icon: Bitmap?,
    val sister: Boolean = false, // 伞姨模块已适配
)

/** 应用清单跨页缓存：切页签/预览页返回不重新扫描安装包（消除重复加载卡顿） */
private var cachedRows: List<AppRow>? = null

/**
 * 应用列表页（勾选驱动）：
 * - 硬排除系统应用；搜索保留
 * - 勾选 = 意向标记（待生成）；已启用包取消勾选 = 标记取消（移除收敛由 KSU 执行）
 * - 双分组：已启用 (N) 优先，其次未启用
 * - 浮动批量操作条：生成所选 (N) + 全选/清空，二次确认只弹包清单
 * - 批量生成方案解析：有末次缓存 → 重做；否则自动推荐
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("digen", android.content.Context.MODE_PRIVATE) }

    var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmTargets by remember { mutableStateOf<List<AppRow>?>(null) }
    var previewPkg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun savePending(set: Set<String>) = prefs.edit().putStringSet("pending", set).apply()

    LaunchedEffect(Unit) {
        busy = true
        withContext(Dispatchers.IO) {
            // 首次全量扫描，之后直接命中缓存（切页签/预览页返回零延迟）
            rows = cachedRows ?: loadApps(ctx).also { cachedRows = it }
            enabled = runCatching { ModuleWriter.listPackages().toSet() }.getOrDefault(emptySet())
            // 意向持久化：读出后剔除已启用的（重进时避免重复意向）
            val stored = prefs.getStringSet("pending", emptySet()) ?: emptySet()
            pending = stored - enabled
        }
        busy = false
    }

    /** 勾选 = 加入意向；取消勾选 = 移出意向（已启用的走移除标记） */
    fun toggle(row: AppRow) {
        when {
            row.pkg in enabled -> {
                // 已启用 → 标记取消：删静态数据，运行时收敛交给 KSU 执行
                enabled = enabled - row.pkg
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { ModuleWriter.removePackage(row.pkg) }.isSuccess
                    }
                    snackbar.showSnackbar(
                        if (ok) "已取消 ${row.label}，执行后恢复"
                        else "取消失败",
                        duration = SnackbarDuration.Long,
                    )
                }
            }
            row.pkg in pending -> pending = (pending - row.pkg).also { savePending(it) }
            else -> pending = (pending + row.pkg).also { savePending(it) }
        }
    }

    val filtered = rows.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    val enabledRows = filtered.filter { it.pkg in enabled }
    val disabledRows = filtered.filter { it.pkg !in enabled }

    if (previewPkg != null) {
        val target = rows.firstOrNull { it.pkg == previewPkg }
        if (target != null) {
            PreviewScreen(
                row = target,
                onBack = { previewPkg = null },
                onGenerated = { ok ->
                    if (ok) {
                        enabled = enabled + target.pkg
                        pending = (pending - target.pkg).also { savePending(it) }
                    }
                    cachedRows = null // 预览页可能触发过变更，下次进列表重新扫描一次（仅一次）
                    previewPkg = null
                },
            )
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("应用") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // 浮动批量操作条：有待生成意向时浮出
            if (pending.isNotEmpty()) {
                BottomAppBar {
                    TextButton(onClick = {
                        pending = (pending + filtered.filter { it.pkg !in enabled }.map { it.pkg }).also { savePending(it) }
                    }) { Text("全选") }
                    TextButton(onClick = {
                        pending = emptySet<String>().also { savePending(it) }
                    }) { Text("清空") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = !busy,
                        onClick = {
                            confirmTargets = rows.filter { it.pkg in pending }
                        },
                    ) { Text(if (busy) "生成中…" else "生成所选 (${pending.size})") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (enabledRows.isNotEmpty()) {
                    item { GroupHeader("已启用 (${enabledRows.size})") }
                    items(enabledRows, key = { "e_" + it.pkg }) { row ->
                        AppRowItem(
                            row = row,
                            checked = true,
                            awaiting = false,
                            onToggle = { toggle(row) },
                            onOpen = { previewPkg = row.pkg },
                        )
                    }
                }
                item { GroupHeader("未启用") }
                items(disabledRows, key = { "d_" + it.pkg }) { row ->
                    AppRowItem(
                        row = row,
                        checked = row.pkg in pending,
                        awaiting = row.pkg in pending,
                        onToggle = { toggle(row) },
                        onOpen = { previewPkg = row.pkg },
                    )
                }
            }
        }
    }

    // 批量生成二次确认：只弹包清单（不标注方案来源）
    confirmTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirmTargets = null },
            title = { Text("生成 ${targets.size} 个暗色图标") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(targets) { t ->
                        Text(
                            t.label,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmTargets = null
                        scope.launch {
                            busy = true
                            val results = withContext(Dispatchers.IO) {
                                targets.map { t ->
                                    t.pkg to generateFor(ctx, t)
                                }
                            }
                            val okCount = results.count { it.second }
                            enabled = enabled + results.filter { it.second }.map { it.first }.toSet()
                            pending = (pending - targets.map { it.pkg }.toSet()).also { savePending(it) }
                            busy = false
                            snackbar.showSnackbar(
                                if (okCount > 0) "已生成 $okCount/${targets.size} 个，在管理器执行模块后生效"
                                else "生成失败",
                                duration = SnackbarDuration.Long,
                            )
                        }
                    },
                ) { Text("生成") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTargets = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AppRowItem(
    row: AppRow,
    checked: Boolean,
    awaiting: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.label) },
        supportingContent = {
            when {
                awaiting -> Text("待生成", color = MaterialTheme.colorScheme.tertiary)
                row.sister -> Text("已由其他模块适配", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(row.pkg, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = {
            row.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(44.dp)) }
        },
        trailingContent = {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        },
        modifier = Modifier.clickable { onOpen() },
    )
}

/** 仅第三方应用（硬排除系统应用），按名称排序；图标用系统 loadIcon 轻取（144px，避免高清提取拖慢列表） */
private fun loadApps(ctx: android.content.Context): List<AppRow> {
    val pm = ctx.packageManager
    val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    val resolve = pm.queryIntentActivities(launcher, 0)
    val sister = runCatching { ModuleWriter.sisterPackages().toSet() }.getOrDefault(emptySet())
    return resolve.asSequence()
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != ctx.packageName }
        /*
         * 不再硬排除系统应用。
         * 之前这里把 FLAG_SYSTEM 的包全过滤掉，而导入清单（.packages）里是含系统 App 的，
         * 结果首页「已适配 N」和列表里打勾的数量对不上（26 vs 17）。
         * 而且 Chrome、Google 全家桶在 ColorOS 上就是预装/系统应用，它们并没有官方暗色适配，
         * 过滤掉等于没法给它们适配。
         */
        .map { ai ->
            val label = ai.loadLabel(pm).toString()
            val icon = runCatching { ai.loadIcon(pm)?.let { drawableToSmallBitmap(it) } }.getOrNull()
            AppRow(ai.packageName, label, icon, ai.packageName in sister)
        }
        .sortedBy { it.label }
        .toList()
}

/** 列表图标专用轻量位图化（144px，远低于预览页的 432 提取成本） */
private fun drawableToSmallBitmap(d: android.graphics.drawable.Drawable): Bitmap {
    val size = 144
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, size, size)
    d.draw(android.graphics.Canvas(bmp))
    return bmp
}

/** 单包自动管线（新包/缓存已清退化路径）：原色方案自动推荐，亮色按全局偏好，纯声明式写入 */
private fun generateFor(ctx: android.content.Context, row: AppRow): Boolean = runCatching {
    val ext = IconExtractor.extract(ctx, row.pkg)
    // 原图标原料入永久缓存（撤销/重生成原料）
    IconCache.storeOriginal(ctx, row.pkg, ext.full, ext.foreground, ext.background)
    val fg = ForegroundProcessor.extractForeground(ext.foreground, ext.full)
    val brand = IconExtractor.dominantColor(ext.full)
    // 酷安思路：按前景构成自动选方案（白/浅色为主 → 转色；否则普通抠图）
    val style = ForegroundProcessor.suggestStyle(fg)
    val styled = ForegroundProcessor.applyStyle(fg, style, 240, brand, com.darkicongen.app.gen.RatioPrefs.currentDark(ctx))
    // 原厂 monochrome 剪影优先，省掉自己膨胀描边
    val mono = ext.monochrome ?: ForegroundProcessor.toMonochrome(fg, 240, com.darkicongen.app.gen.RatioPrefs.currentDark(ctx))
    // 亮色件：按设置页的全局偏好（auto/off/brand/original），自动推荐走原图标底色判断
    val day = LightModePrefs.dayIcon(ctx, fg, ext)
    // 纯声明式：只写文件，运行时收敛归 KSU 播放键/开机脚本
    ModuleWriter.writePackageIcons(ctx, row.pkg, styled, mono, brand, style.name, day)
    true
}.getOrDefault(false)
