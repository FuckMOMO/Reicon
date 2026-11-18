package com.darkicongen.app.gen

import android.content.Context

/**
 * 比例与选择的统一偏好。
 *
 * - `rangeMin` / `rangeMax`：设置页那个"范围条"的两个端点，app 详情页的两个滑块只能在这个区间里拖；
 * - `darkRatio` / `lightRatio`：暗色、亮色各自的比例（互相独立，都记忆）；
 * - `darkPick` / `lightPick`：上次在详情页选的方案（原图标 / 普通抠图 / 转色、原图标 / 原色 / 主题色）。
 *
 * 比例现在对**所有**应用生效（不再只对官方适配的生效）。
 */
object RatioPrefs {

    private const val SP = "darkicon_ratio"
    private const val K_MIN = "range_min"
    private const val K_MAX = "range_max"
    private const val K_DARK = "dark_ratio"
    private const val K_LIGHT = "light_ratio"
    private const val K_DARK_PICK = "dark_pick"
    private const val K_LIGHT_PICK = "light_pick"
    private const val K_MODE = "ratio_mode"
    private const val K_DEFAULT_DARK = "ratio_default_dark"
    private const val K_DEFAULT_LIGHT = "ratio_default_light"
    private const val K_LAST_DARK = "ratio_last_dark"
    private const val K_LAST_LIGHT = "ratio_last_light"

    /** 范围条的绝对边界：字形占画布的比例，超过 100% 会溢出被裁 */
    const val ABS_MIN = 0.30f
    const val ABS_MAX = 1.00f

    /** 默认区间 */
    const val DEFAULT_MIN = 0.60f
    const val DEFAULT_MAX = 0.85f

    /** 暗色 / 亮色的默认比例（与官方素材实测值对齐） */
    val DEFAULT_DARK = 0.77f
    val DEFAULT_LIGHT = 0.77f

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ── 范围 ──
    fun rangeMin(ctx: Context): Float =
        sp(ctx).getFloat(K_MIN, DEFAULT_MIN).coerceIn(ABS_MIN, ABS_MAX)

    fun rangeMax(ctx: Context): Float {
        val v = sp(ctx).getFloat(K_MAX, DEFAULT_MAX).coerceIn(ABS_MIN, ABS_MAX)
        return if (v <= rangeMin(ctx)) (rangeMin(ctx) + 0.05f).coerceAtMost(ABS_MAX) else v
    }

    fun writeRange(ctx: Context, min: Float, max: Float) {
        val lo = min.coerceIn(ABS_MIN, ABS_MAX)
        val hi = max.coerceIn(ABS_MIN, ABS_MAX)
        if (hi - lo < 0.05f) return
        sp(ctx).edit().putFloat(K_MIN, lo).putFloat(K_MAX, hi).apply()
    }

    // ── 比例 ──
    fun darkRatio(ctx: Context): Float =
        sp(ctx).getFloat(K_DARK, DEFAULT_DARK).coerceIn(rangeMin(ctx), rangeMax(ctx))

    fun lightRatio(ctx: Context): Float =
        sp(ctx).getFloat(K_LIGHT, DEFAULT_LIGHT).coerceIn(rangeMin(ctx), rangeMax(ctx))

    fun writeDarkRatio(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_DARK, v.coerceIn(rangeMin(ctx), rangeMax(ctx))).apply()
    }

    fun writeLightRatio(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_LIGHT, v.coerceIn(rangeMin(ctx), rangeMax(ctx))).apply()
    }

    // ── 上次选择（记忆） ──
    /** 暗色："off" 原图标 | "cutout" 普通抠图 | "recolor" 转色 */
    fun darkPick(ctx: Context): String? = sp(ctx).getString(K_DARK_PICK, null)

    /** 亮色："off" 原图标 | "original" 原色 | "brand" 主题色 */
    fun lightPick(ctx: Context): String? = sp(ctx).getString(K_LIGHT_PICK, null)

    fun writeDarkPick(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_DARK_PICK, v).apply()
    }

    fun writeLightPick(ctx: Context, v: String) {
        sp(ctx).edit().putString(K_LIGHT_PICK, v).apply()
    }

    // ── 比例模式：默认比例 / 记忆上次 ──
    const val MODE_DEFAULT = "default"
    const val MODE_MEMORY = "memory"

    fun mode(ctx: Context): String =
        sp(ctx).getString(K_MODE, MODE_DEFAULT) ?: MODE_DEFAULT

    fun writeMode(ctx: Context, m: String) {
        sp(ctx).edit().putString(K_MODE, m).apply()
    }

    /**
     * 暗色 / 亮色的比例**完全分开**：
     * 各自有自己的"默认值"和"记忆值"；记忆同样是全局一份，不按应用分开。
     */
    fun defaultDark(ctx: Context): Float =
        sp(ctx).getFloat(K_DEFAULT_DARK, DEFAULT_DARK).coerceIn(ABS_MIN, ABS_MAX)

    fun defaultLight(ctx: Context): Float =
        sp(ctx).getFloat(K_DEFAULT_LIGHT, DEFAULT_LIGHT).coerceIn(ABS_MIN, ABS_MAX)

    fun writeDefaultDark(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_DEFAULT_DARK, v.coerceIn(ABS_MIN, ABS_MAX)).apply()
    }

    fun writeDefaultLight(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_DEFAULT_LIGHT, v.coerceIn(ABS_MIN, ABS_MAX)).apply()
    }

    fun lastDark(ctx: Context): Float =
        sp(ctx).getFloat(K_LAST_DARK, defaultDark(ctx)).coerceIn(ABS_MIN, ABS_MAX)

    fun lastLight(ctx: Context): Float =
        sp(ctx).getFloat(K_LAST_LIGHT, defaultLight(ctx)).coerceIn(ABS_MIN, ABS_MAX)

    fun writeLastDark(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_LAST_DARK, v.coerceIn(ABS_MIN, ABS_MAX)).apply()
    }

    fun writeLastLight(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat(K_LAST_LIGHT, v.coerceIn(ABS_MIN, ABS_MAX)).apply()
    }

    /** 当前暗色该用哪个比例 */
    fun currentDark(ctx: Context): Float =
        if (mode(ctx) == MODE_MEMORY) lastDark(ctx) else defaultDark(ctx)

    /** 当前亮色该用哪个比例 */
    fun currentLight(ctx: Context): Float =
        if (mode(ctx) == MODE_MEMORY) lastLight(ctx) else defaultLight(ctx)

    fun label(ratio: Float): String = "${(ratio * 100).toInt()}%"
}
