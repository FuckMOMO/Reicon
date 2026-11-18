package com.darkicongen.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.darkicongen.app.gen.IconCache
import com.darkicongen.app.mod.ModuleWriter
import com.darkicongen.app.mod.TaskCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * 所有耗时任务都走 [TaskCenter]（应用级作用域）：底部切页不会中断，切回来还能看到进度。
 * 每个操作有各自的忙碌状态，互不串台。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val exporting by TaskCenter.exporting.collectAsState()
    val expP by TaskCenter.exportProgress.collectAsState()
    val importingData by TaskCenter.importingData.collectAsState()
    val impP by TaskCenter.importProgress.collectAsState()
    val importingSister by TaskCenter.importingSister.collectAsState()
    val sisP by TaskCenter.sisterProgress.collectAsState()
    val resetting by TaskCenter.resetting.collectAsState()
    val wiping by TaskCenter.wiping.collectAsState()

    var disabled by remember { mutableStateOf(false) }
    var showClearCache by remember { mutableStateOf(false) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(com.darkicongen.app.gen.RatioPrefs.mode(ctx)) }
    var defaultDark by remember { mutableStateOf(com.darkicongen.app.gen.RatioPrefs.defaultDark(ctx)) }
    var defaultLight by remember { mutableStateOf(com.darkicongen.app.gen.RatioPrefs.defaultLight(ctx)) }
    // 正在编辑哪一个："" / "dark" / "light"
    var editingDefault by remember { mutableStateOf("") }
    var showSisterScope by remember { mutableStateOf(false) }
    var pendingSisterUri by remember { mutableStateOf<Uri?>(null) }
    var ratioRange by remember {
        mutableStateOf(
            com.darkicongen.app.gen.RatioPrefs.rangeMin(ctx)..
                com.darkicongen.app.gen.RatioPrefs.rangeMax(ctx),
        )
    }

    val busy = exporting || importingData || importingSister || resetting || wiping

    LaunchedEffect(Unit) {
        disabled = withContext(Dispatchers.IO) { runCatching { ModuleWriter.isModuleDisabled() }.getOrDefault(false) }
    }
    // 全局任务消息（即使切页也能收到）
    LaunchedEffect(Unit) {
        TaskCenter.message.collect { msg ->
            if (msg != null) {
                snackbar.showSnackbar(msg, duration = SnackbarDuration.Long)
                TaskCenter.consumeMessage()
            }
        }
    }

    val importDataPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) TaskCenter.importData(ctx, uri)
    }
    val importSisterPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            pendingSisterUri = uri
            showSisterScope = true
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel("操作") }
            item {
                ActionCard(
                    icon = Icons.Filled.Delete,
                    title = "清除缓存",
                    enabled = !busy,
                    onClick = { showClearCache = true },
                )
            }

            item {
                ActionCard(
                    icon = Icons.Filled.Download,
                    title = "导出数据",
                    enabled = !busy,
                    subtitle = if (exporting) expP?.let { (d, t) -> "正在打包 $d/$t 个文件…" } ?: "正在打包已适配的图标…" else null,
                    trailing = if (exporting) {
                        { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else null,
                    onClick = { TaskCenter.export(ctx) },
                )
            }
            if (exporting) item { ProgressRow(expP?.first, expP?.second, expP == null) }

            item {
                ActionCard(
                    icon = Icons.Filled.Upload,
                    title = "导入数据",
                    enabled = !busy,
                    subtitle = if (importingData) "正在导入…" else null,
                    trailing = if (importingData) {
                        { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else null,
                    onClick = { importDataPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                )
            }
            if (importingData) item { ProgressRow(impP?.first, impP?.second, impP == null) }

            item {
                ActionCard(
                    icon = Icons.Filled.Archive,
                    title = "导入 @天伞桜 模块数据",
                    enabled = !busy,
                    subtitle = if (importingSister) "正在导入…" else null,
                    trailing = if (importingSister) {
                        { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else null,
                    onClick = { importSisterPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                )
            }
            if (importingSister) item { ProgressRow(sisP?.first, sisP?.second, sisP == null) }

            // 比例范围：两个端点决定详情页里暗色/亮色滑块的可用区间
            item { SectionLabel("比例模式") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == com.darkicongen.app.gen.RatioPrefs.MODE_DEFAULT,
                                onClick = {
                                    mode = com.darkicongen.app.gen.RatioPrefs.MODE_DEFAULT
                                    com.darkicongen.app.gen.RatioPrefs.writeMode(ctx, mode)
                                },
                            )
                            Text(
                                "默认比例",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "暗 ${com.darkicongen.app.gen.RatioPrefs.label(defaultDark)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { editingDefault = "dark" }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Text(
                                "亮 ${com.darkicongen.app.gen.RatioPrefs.label(defaultLight)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { editingDefault = "light" }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == com.darkicongen.app.gen.RatioPrefs.MODE_MEMORY,
                                onClick = {
                                    mode = com.darkicongen.app.gen.RatioPrefs.MODE_MEMORY
                                    com.darkicongen.app.gen.RatioPrefs.writeMode(ctx, mode)
                                },
                            )
                            Text("记忆选中比例", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            item { SectionLabel("比例范围") }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("字形占图标的比例区间", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${(ratioRange.start * 100).toInt()}% – ${(ratioRange.endInclusive * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { showRangeDialog = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        RangeSlider(
                            value = ratioRange,
                            onValueChange = { ratioRange = it },
                            onValueChangeFinished = {
                                com.darkicongen.app.gen.RatioPrefs.writeRange(
                                    ctx, ratioRange.start, ratioRange.endInclusive,
                                )
                            },
                            valueRange = com.darkicongen.app.gen.RatioPrefs.ABS_MIN..
                                com.darkicongen.app.gen.RatioPrefs.ABS_MAX,
                        )
                    }
                }
            }

            item { SectionLabel("重置") }

            // 清除所有图标数据（在"停用模块"上面）
            item {
                ActionCard(
                    icon = Icons.Filled.DeleteForever,
                    title = "清除数据",
                    subtitle = if (wiping) "正在清理…" else null,
                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                    iconContainer = MaterialTheme.colorScheme.errorContainer,
                    enabled = !busy,
                    trailing = if (wiping) {
                        { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else null,
                    onClick = { TaskCenter.wipeAll(ctx) },
                )
            }
            if (wiping) item { ProgressRow(null, null, true) }

            item {
                ActionCard(
                    icon = Icons.Filled.PowerSettingsNew,
                    title = if (disabled) "启用模块" else "清除数据并停用模块",
                    subtitle = if (resetting) "正在处理…" else null,
                    iconTint = if (disabled) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                    iconContainer = if (disabled) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    enabled = !busy,
                    trailing = if (resetting) {
                        { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    } else null,
                    onClick = {
                        TaskCenter.toggleModule(ctx, disabled) {
                            scope.launch {
                                disabled = withContext(Dispatchers.IO) { ModuleWriter.isModuleDisabled() }
                            }
                        }
                    },
                )
            }
            if (resetting) item { ProgressRow(null, null, true) }
        }
    }

    if (showSisterScope) {
        AlertDialog(
            onDismissRequest = { showSisterScope = false; pendingSisterUri = null },
            title = { Text("导入范围") },
            text = { Text("这个模块里有几百个应用，很多你手机里并没有安装。要全部导入，还是只导入本机已安装的？") },
            confirmButton = {
                TextButton(onClick = {
                    val u = pendingSisterUri
                    showSisterScope = false
                    pendingSisterUri = null
                    if (u != null) TaskCenter.importSister(ctx, u, installedOnly = true)
                }) { Text("只导入已安装") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val u = pendingSisterUri
                    showSisterScope = false
                    pendingSisterUri = null
                    if (u != null) TaskCenter.importSister(ctx, u, installedOnly = false)
                }) { Text("全部导入") }
            },
        )
    }

    // 手填默认比例（暗色 / 亮色各自一个）
    if (editingDefault.isNotEmpty()) {
        val isDark = editingDefault == "dark"
        val cur = if (isDark) defaultDark else defaultLight
        var text by remember(editingDefault) { mutableStateOf((cur * 100).toInt().toString()) }
        AlertDialog(
            onDismissRequest = { editingDefault = "" },
            title = { Text(if (isDark) "暗色默认比例" else "亮色默认比例") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("百分比") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { v ->
                        val r = (v / 100f).coerceIn(
                            com.darkicongen.app.gen.RatioPrefs.ABS_MIN,
                            com.darkicongen.app.gen.RatioPrefs.ABS_MAX,
                        )
                        if (isDark) {
                            defaultDark = r
                            com.darkicongen.app.gen.RatioPrefs.writeDefaultDark(ctx, r)
                        } else {
                            defaultLight = r
                            com.darkicongen.app.gen.RatioPrefs.writeDefaultLight(ctx, r)
                        }
                    }
                    editingDefault = ""
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingDefault = "" }) { Text("取消") } },
        )
    }

    // 手填比例区间
    if (showRangeDialog) {
        var loText by remember { mutableStateOf((ratioRange.start * 100).toInt().toString()) }
        var hiText by remember { mutableStateOf((ratioRange.endInclusive * 100).toInt().toString()) }
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { Text("比例范围") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = loText,
                        onValueChange = { loText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("最小 %") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = hiText,
                        onValueChange = { hiText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("最大 %") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lo = (loText.toIntOrNull() ?: 60) / 100f
                    val hi = (hiText.toIntOrNull() ?: 85) / 100f
                    com.darkicongen.app.gen.RatioPrefs.writeRange(ctx, lo, hi)
                    ratioRange = com.darkicongen.app.gen.RatioPrefs.rangeMin(ctx)..
                        com.darkicongen.app.gen.RatioPrefs.rangeMax(ctx)
                    showRangeDialog = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRangeDialog = false }) { Text("取消") } },
        )
    }

    if (showClearCache) {
        AlertDialog(
            onDismissRequest = { showClearCache = false },
            title = { Text("清除缓存") },
            text = { Text("仅删除 App 内的生成素材缓存，模块素材和桌面图标不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCache = false
                    scope.launch {
                        withContext(Dispatchers.IO) { IconCache.clearAllGenerated(ctx) }
                        snackbar.showSnackbar("已清除 App 内缓存")
                    }
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClearCache = false }) { Text("取消") } },
        )
    }
}

/** 进度行：有确定的 done/total 走确定进度，否则走不确定进度 */
@Composable
private fun ProgressRow(done: Int?, total: Int?, indeterminate: Boolean) {
    if (!indeterminate && done != null && total != null && total > 0) {
        LinearProgressIndicator(
            progress = { done.toFloat() / total },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}
