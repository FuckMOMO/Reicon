package com.darkicongen.app.gen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * 亮色（日间）图标生成 —— 与暗色管线对称的另一半。
 *
 * 暗色管线：抠出前景 → 透明底 → 叠 recbg，交给桌面做暗色合成。
 * 亮色管线：抠出前景 → 整体压成品牌色（或保留原色）→ 铺白底 → 输出**满版不透明**的完整日间图标。
 *
 * 为什么必须是满版不透明：桌面浅色读取的是 recfg/day/mat/nsd/art_off 这类「整图」素材
 * （见 ModuleWriter.LIGHT_GENERATED_NAMES 的实测注释），它不做抠图合成，图片里有什么就画什么。
 * 所以这里直接输出白底 + 居中字形的 240 满版图，走 writePackageIcons(customDay = ...) 通道，
 * 与用户手动上传的浅色图完全同构，模块侧无需任何改动即可生效。
 *
 * 效果对齐用户诉求：微信原图标是「绿底白字形」，白底模式下想要的是「白底 + 绿字形」，
 * 于是把字形当掩膜压成品牌绿（Mode.BRAND）；字号、占比、居中规则与暗色前景保持一致（长边 62%）。
 */
object LightIconProcessor {

    /** 亮色方案：关 / 白底品牌色 / 白底原色 */
    enum class Mode { OFF, BRAND, ORIGINAL }

    /** 白底模式下的纯白画布色 */
    const val WHITE_BG: Int = 0xFFFFFFFF.toInt()

    /**
     * 字形长边占画布比例的**默认值**。
     *
     * 依据（都在真机上量过）：
     * - 官方素材：微信 mat 字形 130×105 / rec_night 134×109，淘宝 mat 152×144 / rec_night 140×109
     *   → 官方本来就是"亮暗字形绝对像素几乎相等"；
     * - 桌面渲染：暗色前景与亮色整图的缩放系数实测同为 0.81，
     *   所以亮色只要跟暗色用同一个比例，屏幕上就一模一样大。
     *
     * 用户可以在设置里调（亮色图标比例），实际值由 [com.darkicongen.app.gen.LightRatioPrefs] 提供；
     * 只对"基于 ColorOS 官方素材生成的图标"生效 —— 直接拿 App 自身图标做前景时仍用固定比例。
     */
    const val RATIO = 0.75f

    /** 用 App 自身图标（没有官方素材）时的固定比例：不跟随用户设置 */
    private const val RATIO_FALLBACK = 0.62f

    /** 品牌色太浅（白字白底）时的兜底字形色 */
    private const val FALLBACK_GLYPH = 0xFF1F1F1F.toInt()

    /** 解析持久化字符串 → 模式（未知值按自动处理） */
    fun parseMode(raw: String?): Mode = when (raw?.lowercase()) {
        "off" -> Mode.OFF
        "original" -> Mode.ORIGINAL
        "brand" -> Mode.BRAND
        else -> Mode.BRAND
    }

    fun modeKey(mode: Mode): String = when (mode) {
        Mode.OFF -> "off"
        Mode.BRAND -> "brand"
        Mode.ORIGINAL -> "original"
    }

    /**
     * 生成 240 满版亮色图标。
     *
     * @param glyph  已抠好的字形（透明底，通常来自 ForegroundProcessor.extractForeground）
     * @param accent 品牌强调色（IconExtractor.accentColor 的计算结果）
     * @param mode   BRAND = 整片压成品牌色；ORIGINAL = 保留原配色，只把「近白部分」换成品牌色
     * @param ratio  字形占画布比例；传 null 表示"这是拿 App 自身图标做的（没有官方素材）"，
     *               此时固定用 RATIO_FALLBACK —— 用户调的比例只对官方适配图标生效。
     *
     * 为什么 ORIGINAL 也要动白色：彩底白字形的图标（微信、B 站这类），前景就是一整块近白色，
     * 白底上原样画等于白底白图，整张图标变一块空白。所以白色像素一律换成品牌色，
     * 非白部分（彩色 logo 的花瓣、渐变）保持原样 —— 既不会白底白图，也不会像 BRAND 那样把多色压成单色。
     */
    fun build(
        glyph: Bitmap,
        accent: Int,
        mode: Mode = Mode.BRAND,
        size: Int = 240,
        ratio: Float? = null,
    ): Bitmap {
        val content = trimTransparent(glyph)
        val scaled = fitContent(content, size, ratio ?: RATIO_FALLBACK)
        val tint = safeGlyphColor(accent)
        val glyphFinal = when (mode) {
            Mode.BRAND -> tintCopy(scaled, tint)
            Mode.ORIGINAL -> recolorNearWhite(scaled, tint)
            Mode.OFF -> scaled
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // 白底先铺满：桌面浅色不做合成，缺底色就是一块透明（桌面漏出壁纸）
        c.drawColor(WHITE_BG)
        c.drawBitmap(
            glyphFinal,
            (size - glyphFinal.width) / 2f,
            (size - glyphFinal.height) / 2f,
            null,
        )
        return out
    }

    /** 把字形里的近白像素换成 [color]（保留 alpha），其余像素原样 */
    private fun recolorNearWhite(src: Bitmap, color: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width; val h = out.height
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val tr = Color.red(color); val tg = Color.green(color); val tb = Color.blue(color)
        for (i in px.indices) {
            val a = Color.alpha(px[i])
            if (a < 30) continue
            if (!isNearWhite(px[i])) continue
            px[i] = Color.argb(a, tr, tg, tb)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 近白判定：够亮且饱和度低（与 ForegroundProcessor.isNearWhite 同口径） */
    private fun isNearWhite(p: Int): Boolean {
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        return max > 200 && max - min < 60
    }

    /**
     * 字形色下限保护：品牌色被识别成近白（白底白字就看不见了）时退到深灰。
     * 只拦「过亮」，过暗的品牌色在白底上辨识度反而更好，保留原样。
     */
    fun safeGlyphColor(accent: Int): Int {
        val r = Color.red(accent); val g = Color.green(accent); val b = Color.blue(accent)
        val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luma > 0.86) FALLBACK_GLYPH else accent
    }

    /** SRC_IN 着色：保留字形 alpha，只换 RGB（不动原图，返回新位图） */
    private fun tintCopy(src: Bitmap, color: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        return out
    }

    /** 裁掉字形四周透明边：自适应前景自带大量留白，不裁则 62% 归一后整体偏小 */
    private fun trimTransparent(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        if (w <= 0 || h <= 0) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 10) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return src
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** 按长边缩到 canvas*ratio（保持比例） */
    private fun fitContent(content: Bitmap, canvas: Int, ratio: Float): Bitmap {
        val target = canvas * ratio
        val scale = target / maxOf(content.width, content.height)
        val tw = (content.width * scale).toInt().coerceAtLeast(1)
        val th = (content.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(content, tw, th, true)
    }

    /**
     * 亮色方案自动推荐：原图标本身就是浅底（白/浅色底）→ 保留原色更接近官方观感；
     * 原图标是深色/彩色底（微信、支付宝这类彩底白字形）→ 压成品牌色最和谐。
     */
    fun suggestMode(background: Bitmap?, full: Bitmap?): Mode {
        val bg = background ?: full ?: return Mode.BRAND
        return if (isLightBackdrop(bg)) Mode.ORIGINAL else Mode.BRAND
    }

    /** 采样四角+中心，判断原图标是否为浅底（白底类） */
    private fun isLightBackdrop(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return false
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val probes = intArrayOf(0, w - 1, (h - 1) * w, h * w - 1, (h / 2) * w + w / 2)
        var light = 0; var counted = 0
        for (i in probes) {
            val p = px[i.coerceIn(0, px.size - 1)]
            if (Color.alpha(p) < 40) { light++; counted++; continue }
            val luma = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0
            if (luma > 0.82) light++
            counted++
        }
        return counted > 0 && light * 100 / counted >= 60
    }
}
