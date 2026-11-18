package com.darkicongen.app.xposed

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method

/**
 * LSPosed 即时预览钩子：注入 ColorOS 桌面（com.android.launcher）进程。
 *
 * 桌面暗色图标内部机制为 MorphIcon（扫描 OplusLauncher.apk 确认），
 * 加载链路集中在 com.oplus.uxicon.ui.morphicon 包（MorphIconLoader /
 * LoaderFactory 家族 / BitmapCache）。本钩子采用运行时自适应策略：
 * 在候选类中查找「参数含包名字符串、返回 Bitmap/Drawable」的方法，
 * 命中受管包名时替换为模块目录内的暗色成品图，不写死私有方法签名。
 *
 * 图标文件由伴生 App 写入 /data/adb/modules/DarkIconAuto/data/oplus/uxicons/<包名>/，
 * 并已 chcon 为 uxicons_data_file（桌面进程可读）。
 * 受管包名清单：/data/adb/modules/DarkIconAuto/xposed.conf（一行一个）。
 */
class IconHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "DarkIconGen"
        private const val LAUNCHER_PKG = "com.android.launcher"
        private const val MODULE_BASE = "/data/adb/modules/DarkIconAuto"
        private const val ICONS_BASE = "$MODULE_BASE/data/oplus/uxicons"
        private const val CONF_PATH = "$MODULE_BASE/xposed.conf"

        /** 候选 hook 类（MorphIcon 加载链路，按优先级排列；后两项为日志实锤的加载器） */
        private val CANDIDATE_CLASSES = arrayOf(
            "com.oplus.uxicon.ui.morphicon.MorphIconLoader",
            "com.oplus.uxicon.ui.morphicon.LoaderFactory",
            "com.oplus.uxicon.ui.morphicon.BuildInThemeIconLoaderFactory",
            "com.oplus.uxicon.ui.morphicon.EditedIconLoaderFactory",
            "com.oplus.uxicon.ui.morphicon.IconPackLoaderFactory",
            "com.oplus.uxicon.ui.morphicon.MonoChromeIconLoaderFactory",
            "com.oplus.uxicon.ui.morphicon.MorphIconUtils",
            "com.oplus.uxicon.helper.UxIconLoaderUtil",
            "com.oplus.uxicon.helper.IconResLoader",
            "com.android.launcher3.morphicon.MorphIconLoader",
        )

        /** 成品图候选文件（按优先级，存在即用） */
        private val ICON_FILES = arrayOf(
            "mat.png", "rec_night.png", "recfg.png", "monochrome.png",
        )

        @Volatile
        private var managed: Set<String> = emptySet()

        /** 已解码图标缓存，避免桌面每帧重读磁盘 */
        private val bmpCache = HashMap<String, Bitmap>()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != LAUNCHER_PKG) return
        managed = loadManagedPackages()
        if (managed.isEmpty()) return
        XposedBridge.log("$TAG: loaded, managed=${managed.size}")
        var hooked = 0
        for (cls in CANDIDATE_CLASSES) {
            hooked += hookClass(lpparam.classLoader, cls)
        }
        XposedBridge.log("$TAG: hooked methods=$hooked")
    }

    private fun loadManagedPackages(): Set<String> = runCatching {
        File(CONF_PATH).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    /**
     * 对单个候选类安装钩子：遍历声明方法，凡是「返回 Bitmap/Drawable 且参数含
     * 至少一个 String（包名）」的公开方法都挂上替换逻辑。返回安装数量。
     */
    private fun hookClass(loader: ClassLoader, className: String): Int {
        val clazz = XposedHelpers.findClassIfExists(className, loader) ?: return 0
        var n = 0
        for (m in runCatching { clazz.declaredMethods }.getOrDefault(emptyArray())) {
            if (!isIconReturnMethod(m)) continue
            runCatching {
                XposedBridge.hookMethod(m, ReplaceCallback(className, m.name))
                n++
            }
        }
        return n
    }

    /** 判定：返回值是图标类型（或其容器），且参数含字符串（包名） */
    private fun isIconReturnMethod(m: Method): Boolean {
        val rt = m.returnType
        val returnsIcon = Drawable::class.java.isAssignableFrom(rt) ||
            Bitmap::class.java == rt
        if (!returnsIcon) return false
        return m.parameterTypes.any { it == String::class.java }
    }

    private inner class ReplaceCallback(
        private val clsName: String,
        private val methodName: String,
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val pkg = findPackageArg(param.args) ?: return
            if (pkg !in managed) return
            val bmp = bitmapFor(pkg) ?: return
            val rt = (param.method as? Method)?.returnType ?: return
            param.result = if (rt == Bitmap::class.java) bmp else BitmapDrawable(Resources.getSystem(), bmp)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            // 方法返回 null（before 未命中）时，受管包补上我们的图标
            val pkg = findPackageArg(param.args) ?: return
            if (pkg !in managed) return
            if (param.result == null) {
                val bmp = bitmapFor(pkg) ?: return
                val rt = (param.method as? Method)?.returnType ?: return
                param.result = if (rt == Bitmap::class.java) bmp else BitmapDrawable(Resources.getSystem(), bmp)
            }
        }
    }

    /** 从方法参数中找受管包名（可能同时有多个 String 参数） */
    private fun findPackageArg(args: Array<Any?>?): String? {
        if (args == null) return null
        for (a in args) {
            if (a is String && a in managed) return a
        }
        return null
    }

    /** 读取模块目录内的暗色成品图（带缓存） */
    private fun bitmapFor(pkg: String): Bitmap? {
        bmpCache[pkg]?.let { return it }
        val dir = File(ICONS_BASE, pkg)
        for (name in ICON_FILES) {
            val f = File(dir, name)
            if (!f.exists() || f.length() == 0L) continue
            val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            if (bmp != null) {
                synchronized(bmpCache) { bmpCache[pkg] = bmp }
                return bmp
            }
        }
        return null
    }
}
