package com.darkicongen.app.gen

import android.content.Context
import android.graphics.Bitmap

/**
 * 亮色模式的全局偏好（SharedPreferences: `digen` / `light_mode`）。
 *
 * 取值：`auto`（按原图标底色自动推荐）| `off` | `brand` | `original`
 * - 批量生成（应用列表勾选生成）没有逐包选项，统一读这里；
 * - 预览页选中某个方案时会把选择写回这里，作为「下次的默认」。
 */
object LightModePrefs {

    private const val PREFS = "digen"
    private const val KEY = "light_mode"

    const val AUTO = "auto"

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    fun write(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    fun label(raw: String): String = when (raw.lowercase()) {
        "off" -> "原图标"
        "brand" -> "主题色"
        "original" -> "原色"
        else -> "自动"
    }

    /** 偏好 → 实际模式（auto 走原图标底色判断：浅底保留原色，彩底/深底压品牌色） */
    fun resolve(context: Context, background: Bitmap?, full: Bitmap?): LightIconProcessor.Mode {
        val raw = read(context)
        if (raw.equals(AUTO, true)) return LightIconProcessor.suggestMode(background, full)
        return LightIconProcessor.parseMode(raw)
    }

    /**
     * 按全局偏好生成一张 240 亮色满版图；返回 null 表示"不写任何浅色件"（关闭 / 生成失败）。
     * 写入侧直接把它当 customDay 用，与用户手动上传的浅色图完全同一条链路。
     *
     * 字形优先用**原厂亮色字形层**（mat/recfg，已抠好的透明底）：
     * 有官方适配的应用直接拿它合成，比从整图 flood fill 抠图干净得多。
     */
    fun dayIcon(context: Context, glyph: Bitmap, ext: IconExtractor.Result, size: Int = 240): Bitmap? {
        val mode = resolve(context, ext.background, ext.full)
        if (mode == LightIconProcessor.Mode.OFF) return null
        val accent = IconExtractor.accentColor(ext.background, ext.full)
        val source = ext.lightForeground ?: glyph
        // 「亮色图标比例」现在对**所有**应用生效（不再区分有没有官方素材）
        val ratio = com.darkicongen.app.gen.RatioPrefs.currentLight(context)
        return runCatching { LightIconProcessor.build(source, accent, mode, size, ratio) }.getOrNull()
    }
}
