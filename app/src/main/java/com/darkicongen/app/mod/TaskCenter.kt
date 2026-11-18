package com.darkicongen.app.mod

import android.content.Context
import com.darkicongen.app.gen.IconCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局任务中心。
 *
 * 之前所有耗时操作都挂在 `rememberCoroutineScope()` 上 —— 那个作用域绑定当前页面的组合生命周期，
 * 底部切一个页签、页面被移除组合，协程就被取消，任务直接断在半路（用户实测：切走就停）。
 * 这里改成应用级作用域 + StateFlow 状态：切页回来还能看到进度，任务继续跑完。
 */
object TaskCenter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 各任务独立状态（互不干扰：之前共用 exporting，点停用会让导出那里转圈）
    val applying = MutableStateFlow(false)
    val exporting = MutableStateFlow(false)
    val exportProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val importingData = MutableStateFlow(false)
    val importProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val importingSister = MutableStateFlow(false)
    val sisterProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val resetting = MutableStateFlow(false)
    val wiping = MutableStateFlow(false)
    val reloading = MutableStateFlow(false)

    /** 一次性提示消息（UI 用 LaunchedEffect 收走并 showSnackbar） */
    val message = MutableStateFlow<String?>(null)

    /**
     * 环境状态缓存（null = 还没检测过）。
     *
     * 首页之前每次切回来都从 null 开始，卡片先按"缺失"渲染成红色，检测完再变绿 ——
     * 用户看到的就是红色闪一下。缓存住之后切页回来直接是上一次的结果，不再闪。
     */
    val rootOk = MutableStateFlow<Boolean?>(null)
    val lsposedActive = MutableStateFlow<Boolean?>(null)
    val moduleFlashed = MutableStateFlow<Boolean?>(null)
    val pkgCount = MutableStateFlow(-1)

    /** 检测环境（已经在检测就不重复发起） */
    fun refreshStatus(ctx: Context) {
        if (rootOk.value != null && lsposedActive.value != null &&
            moduleFlashed.value != null && pkgCount.value >= 0) return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    // 先看 root：没 root 时后面两项必然是 false，不能据此说"模块/LSPosed 没装"
                    val rooted = com.topjohnwu.superuser.Shell.getShell().isRoot
                    rootOk.value = rooted
                    if (!rooted) {
                        null
                    } else {
                        Triple(
                            ModuleWriter.isLsposedActive(),
                            ModuleWriter.isModuleFlashed(),
                            ModuleWriter.listPackages().size,
                        )
                    }
                }.getOrNull()
            }
            if (r != null) {
                lsposedActive.value = r.first
                moduleFlashed.value = r.second
                pkgCount.value = r.third
            }
        }
    }

    /** 适配数量变化后刷新（生成/删除/导入后调用） */
    fun refreshPkgCount() {
        scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { ModuleWriter.listPackages().size }.getOrDefault(pkgCount.value) }
            pkgCount.value = n
        }
    }

    fun toast(msg: String) {
        message.value = msg
    }

    fun consumeMessage() {
        message.value = null
    }

    /** 立即执行（强制收敛） */
    fun applyNow(ctx: Context, onApplied: (ModuleWriter.ApplyResult?) -> Unit = {}) {
        if (applying.value) return
        applying.value = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ModuleWriter.applyNow(ctx) }.getOrNull()
            }
            applying.value = false
            onApplied(r)
            toast(
                when {
                    r == null || !r.ok -> "执行失败，请检查 root 权限"
                    else -> "已执行：新增 ${short(r.added)} · 移除 ${short(r.removed)} · 缓存 ${short(r.cache)}"
                }
            )
        }
    }

    /** 全量重载 */
    fun reloadAll(ctx: Context, fromScratch: Boolean) {
        if (reloading.value) return
        reloading.value = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ModuleWriter.reloadAll(ctx, fromScratch) }.getOrNull()
            }
            reloading.value = false
            toast(
                when {
                    r == null -> "重载失败，请检查 root 权限"
                    !r.ok -> "已回填素材，但脚本执行未完成：到管理器按播放键即可"
                    else -> "已重载 ${r.total} 个（回填 ${r.restored} / 重生成 ${r.regenerated}" +
                        " / 原样保留 ${r.kept}" + (if (r.failed > 0) " / 失败 ${r.failed}" else "") + "）"
                }
            )
        }
    }

    /** 导出数据到 Download */
    fun export(ctx: Context) {
        if (exporting.value) return
        exporting.value = true
        exportProgress.value = null
        scope.launch {
            val path = ModuleWriter.defaultExportPath()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ModuleWriter.exportIconPack(ctx, path) { d, t -> exportProgress.value = d to t }
                }.getOrDefault(false)
            }
            exporting.value = false
            exportProgress.value = null
            toast(
                if (ok) "已导出到 Download：\n${path.substringAfterLast('/')}"
                else "导出失败：还没有适配任何应用，或 root 权限被拒"
            )
        }
    }

    /** 导入 Reicon 数据包 */
    fun importData(ctx: Context, uri: android.net.Uri) {
        if (importingData.value) return
        importingData.value = true
        importProgress.value = null
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    ModuleWriter.importData(ctx, uri) { d, t -> importProgress.value = d to t }
                }.getOrDefault(0)
            }
            importingData.value = false
            importProgress.value = null
            toast(
                when {
                    n > 0 -> "已导入 $n 个应用的数据，点「立即执行」生效"
                    n == 0 -> "导入失败：文件为空或 root 权限被拒"
                    else -> "这不是有效的 Reicon 数据包"
                }
            )
        }
    }

    /** 导入 @天伞桜 模块（只取暗色件） */
    fun importSister(ctx: Context, uri: android.net.Uri, installedOnly: Boolean) {
        if (importingSister.value) return
        importingSister.value = true
        sisterProgress.value = null
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    ModuleWriter.importSisterModule(ctx, uri, installedOnly) { d, t ->
                        sisterProgress.value = d to t
                    }
                }.getOrDefault(0)
            }
            importingSister.value = false
            sisterProgress.value = null
            toast(
                when {
                    n > 0 -> "已导入 $n 个应用的暗色图标" +
                        (if (ModuleWriter.lastSkippedOfficial > 0) "（跳过 ${ModuleWriter.lastSkippedOfficial} 个官方已自带暗色的）" else "") +
                        "，点「立即执行」生效"
                    n == 0 -> if (installedOnly) "没有可导入的：官方都已自带暗色，或本机没装" else "导入失败，请检查 root 权限"
                    else -> "没在这个包里找到暗色图标素材"
                }
            )
        }
    }

    /** 停用 / 启用模块 */
    fun toggleModule(ctx: Context, currentlyDisabled: Boolean, onDone: (Boolean) -> Unit) {
        if (resetting.value) return
        resetting.value = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (currentlyDisabled) runCatching { ModuleWriter.reactivate() }.getOrDefault(false)
                else runCatching { ModuleWriter.deactivateAndRestore(ctx) }.getOrDefault(false)
            }
            resetting.value = false
            onDone(ok)
            toast(if (ok) "已完成" else "操作失败，请检查 root 权限")
        }
    }

    /** 清空全部图标数据 */
    fun wipeAll(ctx: Context) {
        if (wiping.value) return
        wiping.value = true
        scope.launch {
            withContext(Dispatchers.IO) {
                IconCache.clearAll(ctx)
                runCatching { ModuleWriter.wipeAll() }
                runCatching { ModuleWriter.applyNow(ctx) }
            }
            wiping.value = false
            toast("已清除全部图标数据，图标已恢复原厂")
        }
    }

    private fun short(s: String?): String =
        if (s.isNullOrBlank()) "无" else if (s.length > 24) s.take(24) + "…" else s
}
