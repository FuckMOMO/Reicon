package com.darkicongen.app.gen

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File

/**
 * App 内双缓存：filesDir/icon_cache/<pkg>/
 * - original/：原图标原料层（full/fg/bg 432px），永久保留，清除缓存不触碰
 * - generated/：末次生成的全套素材（重做时直接写回模块，跳过生成管线）
 * - meta.json：方案类型、品牌色、生成时间、版本号
 */
object IconCache {

    private fun root(context: Context): File = context.filesDir.resolve("icon_cache")

    private fun pkgDir(context: Context, pkg: String): File = root(context).resolve(pkg)

    /** 写入原图标原料（仅当尚未缓存时，永久保留） */
    fun storeOriginal(context: Context, pkg: String, full: Bitmap?, fg: Bitmap?, bg: Bitmap?) {
        val dir = pkgDir(context, pkg).resolve("original").apply { mkdirs() }
        fun save(name: String, b: Bitmap?) {
            if (b == null) return
            val f = dir.resolve(name)
            if (f.exists()) return
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        save("full.png", full)
        save("fg.png", fg)
        save("bg.png", bg)
    }

    /** 写入末次生成的全套素材 + 元数据 */
    fun storeGenerated(
        context: Context,
        pkg: String,
        files: List<Pair<String, ByteArray>>,
        style: String,
        brandColor: Int,
        version: Int,
    ) {
        val dir = pkgDir(context, pkg).resolve("generated").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        for ((name, bytes) in files) {
            dir.resolve(name).writeBytes(bytes)
        }
        val meta = JSONObject()
            .put("pkg", pkg)
            .put("style", style)
            .put("brandColor", String.format("#%08X", brandColor))
            .put("version", version)
            .put("time", System.currentTimeMillis())
        pkgDir(context, pkg).resolve("meta.json").writeText(meta.toString())
    }

    /** 读取末次生成素材（重做用）；无缓存返回 null */
    fun readGenerated(context: Context, pkg: String): List<Pair<String, ByteArray>>? {
        val dir = pkgDir(context, pkg).resolve("generated")
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f -> f.name.endsWith(".png") } ?: return null
        if (files.isEmpty()) return null
        return files.map { it.name to it.readBytes() }
    }

    /** 是否存在末次生成缓存（重做按钮可见性判断） */
    fun hasGenerated(context: Context, pkg: String): Boolean =
        !readGenerated(context, pkg).isNullOrEmpty()

    /** 读取原图标原料的完整图（撤销展示/重新生成原料） */
    fun readOriginalFull(context: Context, pkg: String): Bitmap? {
        val f = pkgDir(context, pkg).resolve("original/full.png")
        if (!f.exists()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** 清除缓存：只删 generated/ + meta.json，original/ 永不动 */
    fun clearGenerated(context: Context, pkg: String) {
        val dir = pkgDir(context, pkg)
        dir.resolve("generated").deleteRecursively()
        dir.resolve("meta.json").delete()
    }

    /** 清除所有包的生成物缓存（顶栏"清除缓存"入口） */
    fun clearAllGenerated(context: Context) {
        root(context).listFiles()?.forEach { pkg ->
            pkg.resolve("generated").deleteRecursively()
            pkg.resolve("meta.json").delete()
        }
    }

    /** 已缓存原图标的包列表（撤销/对比展示用） */
    fun cachedPackages(context: Context): List<String> =
        root(context).listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    /** 清除数据：删光全部缓存（含受保护的原图标原料，设置页“清除数据”专属） */
    fun clearAll(context: Context) {
        root(context).deleteRecursively()
    }
}
