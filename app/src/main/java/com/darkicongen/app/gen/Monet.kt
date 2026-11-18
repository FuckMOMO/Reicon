package com.darkicongen.app.gen

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color as AColor
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 莫奈取色（Material You 风格）：从壁纸主色出发，生成整套配色。
 *
 * 为什么自己算而不是只用 `dynamicLightColorScheme`：ColorOS 上它有时返回近中性灰
 * （壁纸明明是绿色，界面却一片惨白），所以这里直接读壁纸主色自己调和，
 * 系统能给出动态色时优先用系统的，给不出或用户关掉开关时用这套。
 */
object Monet {

    private const val SP = "darkicon_theme"
    private const val KEY = "monet"

    /** 默认开启 */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** 系统动态取色可用（Android 12+） */
    fun systemDynamicAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** 壁纸主色（读不到返回 null） */
    fun wallpaperSeed(ctx: Context): Int? = runCatching {
        val wm = WallpaperManager.getInstance(ctx)
        val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?: wm.getWallpaperColors(WallpaperManager.FLAG_LOCK)
            ?: return@runCatching null
        colors.primaryColor.toArgb()
    }.getOrNull()

    private fun lum(c: Int): Double {
        val r = AColor.red(c) / 255.0
        val g = AColor.green(c) / 255.0
        val b = AColor.blue(c) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** 往白/黑方向混合，t∈[0,1] */
    private fun mix(c: Int, target: Int, t: Float): Int {
        val r = (AColor.red(c) * (1 - t) + AColor.red(target) * t).toInt().coerceIn(0, 255)
        val g = (AColor.green(c) * (1 - t) + AColor.green(target) * t).toInt().coerceIn(0, 255)
        val b = (AColor.blue(c) * (1 - t) + AColor.blue(target) * t).toInt().coerceIn(0, 255)
        return AColor.rgb(r, g, b)
    }

    private fun lighten(c: Int, t: Float) = mix(c, 0xFFFFFF, t)
    private fun darken(c: Int, t: Float) = mix(c, 0x000000, t)

    /** 由种子色生成整套配色；拿不到种子色时返回 null（调用方回退默认配色） */
    fun schemeFromSeed(seed: Int, dark: Boolean): ColorScheme? {
        if (seed == 0) return null
        // 提高饱和度，避免壁纸取色过于灰
        val base = AColor.HSVToColor(
            floatArrayOf(
                run {
                    val hsv = FloatArray(3)
                    AColor.colorToHSV(seed, hsv)
                    hsv[0]
                },
                0.55f.coerceAtLeast(
                    FloatArray(3).also { AColor.colorToHSV(seed, it) }[1]
                ),
                0.85f,
            )
        )
        val surface = if (dark) darken(base, 0.86f) else lighten(base, 0.94f)
        val surfaceContainer = if (dark) darken(base, 0.80f) else lighten(base, 0.88f)
        val onSurface = if (dark) lighten(base, 0.88f) else darken(base, 0.80f)
        val primary = if (dark) lighten(base, 0.35f) else darken(base, 0.12f)
        val onPrimary = if (lum(primary) > 0.5) darken(primary, 0.85f) else 0xFFFFFFFF.toInt()
        val primaryContainer = if (dark) darken(base, 0.55f) else lighten(base, 0.70f)
        val onPrimaryContainer = if (dark) lighten(base, 0.75f) else darken(base, 0.72f)
        val secondaryContainer = if (dark) darken(base, 0.62f) else lighten(base, 0.78f)
        val onSecondaryContainer = if (dark) lighten(base, 0.80f) else darken(base, 0.74f)
        val tertiaryContainer = if (dark) darken(base, 0.68f) else lighten(base, 0.82f)
        val outline = if (dark) darken(base, 0.55f) else darken(base, 0.45f)
        val error = if (dark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
        val onError = if (dark) 0xFF690005.toInt() else 0xFFFFFFFF.toInt()
        val errorContainer = if (dark) 0xFF93000A.toInt() else 0xFFFFDAD6.toInt()
        val onErrorContainer = if (dark) 0xFFFFDAD6.toInt() else 0xFF410002.toInt()

        return if (dark) {
            darkColorScheme(
                primary = Color(primary),
                onPrimary = Color(onPrimary),
                primaryContainer = Color(primaryContainer),
                onPrimaryContainer = Color(onPrimaryContainer),
                secondaryContainer = Color(secondaryContainer),
                onSecondaryContainer = Color(onSecondaryContainer),
                tertiaryContainer = Color(tertiaryContainer),
                background = Color(surface),
                onBackground = Color(onSurface),
                surface = Color(surface),
                onSurface = Color(onSurface),
                surfaceVariant = Color(surfaceContainer),
                onSurfaceVariant = Color(onSurface),
                surfaceContainer = Color(surfaceContainer),
                outline = Color(outline),
                outlineVariant = Color(lighten(surfaceContainer, 0.12f)),
                error = Color(error),
                onError = Color(onError),
                errorContainer = Color(errorContainer),
                onErrorContainer = Color(onErrorContainer),
            )
        } else {
            lightColorScheme(
                primary = Color(primary),
                onPrimary = Color(onPrimary),
                primaryContainer = Color(primaryContainer),
                onPrimaryContainer = Color(onPrimaryContainer),
                secondaryContainer = Color(secondaryContainer),
                onSecondaryContainer = Color(onSecondaryContainer),
                tertiaryContainer = Color(tertiaryContainer),
                background = Color(surface),
                onBackground = Color(onSurface),
                surface = Color(surface),
                onSurface = Color(onSurface),
                surfaceVariant = Color(surfaceContainer),
                onSurfaceVariant = Color(darken(base, 0.45f)),
                surfaceContainer = Color(surfaceContainer),
                outline = Color(outline),
                outlineVariant = Color(darken(surfaceContainer, 0.10f)),
                error = Color(error),
                onError = Color(onError),
                errorContainer = Color(errorContainer),
                onErrorContainer = Color(onErrorContainer),
            )
        }
    }
}
