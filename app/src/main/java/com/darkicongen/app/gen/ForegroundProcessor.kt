package com.darkicongen.app.gen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.util.ArrayDeque

/**
 * 前景处理：按酷安帖子（居然是啊松）的两种暗色图标方案生成素材：
 * - CUTOUT（普通抠图）：原色 logo 透明底，适合彩色 logo 白/黑底图标（抖音类）
 * - RECOLOR（转色抠图）：白 logo 替换为品牌色透明底，适合白 logo 彩色底图标（微信/支付宝类）
 * 输出统一 240×240（官方规格），前景裁透明边后按长边 62% 居中。
 */
object ForegroundProcessor {

    enum class Style { CUTOUT, RECOLOR }

    /** 获取透明底前景。adaptiveFg 非空直接缩放返回；否则对 full 做 flood fill。 */
    fun extractForeground(adaptiveFg: Bitmap?, full: Bitmap): Bitmap {
        if (adaptiveFg != null) return adaptiveFg.copy(Bitmap.Config.ARGB_8888, true)
        return stripBackground(full)
    }

    /**
     * 上传的暗色素材强制透明底。
     * 用户经常直接传 JPG，或传白底/纯色底的不透明 PNG —— 暗色前景必须带透明通道：
     * rec_night 是叠在 recbg 上渲染的，不透明像素会整块盖住背景色，桌面暗色下就是一个白/彩方块。
     * 策略：已带透明像素即认为用户自己抠好了，原样返回（不再动它，避免 flood fill 误删主体）；
     * 完全不带透明通道才走 stripBackground，从边框 flood fill 去底色。
     * 局限：复杂/渐变背景可能抠不干净，这种情况仍建议用户自己用抠图工具导出透明 PNG。
     */
    fun ensureTransparent(src: Bitmap): Bitmap {
        if (hasTransparency(src)) {
            return if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        }
        return stripBackground(src)
    }

    /** 是否已带透明通道：超过 1% 的像素半透明即算用户已自行抠底。JPG 解码后全不透明，会落到 false。 */
    private fun hasTransparency(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return false
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var n = 0
        for (p in px) if (Color.alpha(p) < 250) n++
        return n * 100 > px.size
    }

    /** 自动推荐方案：前景以白/浅色为主 → 转色；否则普通抠图 */
    fun suggestStyle(foreground: Bitmap): Style =
        if (whiteRatio(foreground) > 0.6f) Style.RECOLOR else Style.CUTOUT

    /** 前景中近白像素占不透明像素的比例 */
    fun whiteRatio(bmp: Bitmap): Float {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var opaque = 0; var white = 0
        for (p in px) {
            if (Color.alpha(p) < 40) continue
            opaque++
            if (isNearWhite(p)) white++
        }
        return if (opaque == 0) 0f else white.toFloat() / opaque
    }

    private fun isNearWhite(p: Int): Boolean {
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        return max > 200 && max - min < 60
    }

    /**
     * 暗色前景的字形占比。
     *
     * 桌面渲染 `rec_night` 时按 adaptive 前景层规则（108/72）**再放大 1.5 倍裁切**，
     * 官方 rec_night 的字形实测占画布 74%×61%，放大后正好接近满版。
     * 这里用 0.74 与官方对齐；之前用 0.62（照搬亮色整图的比例）会让暗色图标比官方小一圈。
     */
    private const val FG_RATIO = 0.75f

    /**
     * 把前景层压到纯色底上，得到一张完整图标（用于预览"官方暗色图标"长什么样）。
     * 官方暗色图标就是 recbg（纯色底）+ rec_night（字形层）合成的，这里照同样方式拼一张。
     */
    /**
     * 原样叠加：把 base（暗色底）和 fg（字形层）按各自原比例缩放到 size 再叠在一起。
     * 不做裁边、不做归一化 —— 官方暗色图标就是这么渲染的，预览必须和桌面一致。
     */
    fun composeExact(base: Bitmap?, foreground: Bitmap?, size: Int = 240): Bitmap? {
        if (base == null && foreground == null) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        base?.let { c.drawBitmap(Bitmap.createScaledBitmap(it, size, size, true), 0f, 0f, null) }
        foreground?.let { c.drawBitmap(Bitmap.createScaledBitmap(it, size, size, true), 0f, 0f, null) }
        return out
    }

    fun composeIcon(foreground: Bitmap, background: Int, size: Int = 240): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(background)
        val fg = Bitmap.createScaledBitmap(foreground, size, size, true)
        c.drawBitmap(fg, 0f, 0f, null)
        return out
    }

    /** 按方案生成 240 前景：CUTOUT/RECOLOR 均输出透明底。 */
    fun applyStyle(
        foreground: Bitmap,
        style: Style,
        size: Int = 240,
        brandColor: Int = Color.WHITE,
        ratio: Float = FG_RATIO,
    ): Bitmap {
        val content = trimTransparent(foreground)
        val scaled = fitContent(content, size, ratio)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, (size - scaled.width) / 2f, (size - scaled.height) / 2f, null)
        if (style == Style.RECOLOR) recolorWhite(out, brandColor)
        return out
    }

    /** 转色：近白像素替换为品牌色（保留 alpha，对应官方微信/支付宝暗色适配思路） */
    private fun recolorWhite(bmp: Bitmap, brandColor: Int) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val a = Color.alpha(brandColor); val r = Color.red(brandColor)
        val g = Color.green(brandColor); val b = Color.blue(brandColor)
        for (i in px.indices) {
            if (Color.alpha(px[i]) < 40) continue
            if (isNearWhite(px[i])) {
                px[i] = Color.argb(Color.alpha(px[i]) * a / 255, r, g, b)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** 双阶段 flood fill 去背景（老式图标） */
    private fun stripBackground(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)

        // 阶段1: 从边缘 flood fill 去背景（含透明）
        fun avgCorner(): Int {
            val cs = listOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
            var r = 0; var g = 0; var b = 0; var a = 0
            for (c in cs) { r += Color.red(c); g += Color.green(c); b += Color.blue(c); a += Color.alpha(c) }
            return Color.argb(a / 4, r / 4, g / 4, b / 4)
        }
        val bg = avgCorner()

        val visited = BooleanArray(w * h)
        val q = ArrayDeque<Int>()
        for (x in 0 until w) { q.add(x); q.add((h - 1) * w + x) }
        for (y in 0 until h) { q.add(y * w); q.add(y * w + w - 1) }

        fun isBgColor(p: Int): Boolean {
            if (Color.alpha(p) < 10) return true
            val dr = Math.abs(Color.red(p) - Color.red(bg))
            val dg = Math.abs(Color.green(p) - Color.green(bg))
            val db = Math.abs(Color.blue(p) - Color.blue(bg))
            return dr + dg + db <= 126
        }

        while (q.isNotEmpty()) {
            val i = q.poll()!!
            if (i < 0 || i >= px.size || visited[i]) continue
            visited[i] = true
            if (!isBgColor(px[i])) continue
            px[i] = Color.TRANSPARENT
            val x = i % w; val y = i / w
            if (x > 0) q.add(i - 1)
            if (x < w - 1) q.add(i + 1)
            if (y > 0) q.add(i - w)
            if (y < h - 1) q.add(i + w)
        }

        // 阶段2: 从非透明边缘 flood fill 去主体形状色
        val edgeColors = HashMap<Int, Int>()
        for (x in 0 until w) {
            for (y in intArrayOf(0, h - 1)) {
                val p = px[y * w + x]
                if (Color.alpha(p) > 10) edgeColors[quantize(p)] = (edgeColors[quantize(p)] ?: 0) + 1
            }
        }
        for (y in 0 until h) {
            for (x in intArrayOf(0, w - 1)) {
                val p = px[y * w + x]
                if (Color.alpha(p) > 10) edgeColors[quantize(p)] = (edgeColors[quantize(p)] ?: 0) + 1
            }
        }
        if (edgeColors.isNotEmpty()) {
            val shape = edgeColors.maxByOrNull { it.value }!!.key
            val visited2 = BooleanArray(w * h)
            val q2 = ArrayDeque<Int>()
            for (x in 0 until w) {
                for (y in intArrayOf(0, h - 1)) { val i = y * w + x; if (Color.alpha(px[i]) > 10) q2.add(i) }
            }
            for (y in 0 until h) {
                for (x in intArrayOf(0, w - 1)) { val i = y * w + x; if (Color.alpha(px[i]) > 10) q2.add(i) }
            }
            fun isShape(p: Int): Boolean {
                if (Color.alpha(p) < 10) return true
                return quantize(p) == shape
            }
            while (q2.isNotEmpty()) {
                val i = q2.poll()!!
                if (i < 0 || i >= px.size || visited2[i]) continue
                visited2[i] = true
                if (!isShape(px[i])) continue
                px[i] = Color.TRANSPARENT
                val x = i % w; val y = i / w
                if (x > 0) q2.add(i - 1)
                if (x < w - 1) q2.add(i + 1)
                if (y > 0) q2.add(i - w)
                if (y < h - 1) q2.add(i + w)
            }
        }
        // A+B 去毛边：传统位图抠底的抗锯齿残留处理（自适应前景不走此分支）
        defringe(px, w, h, bg)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 传统位图去毛边（A+B，仅 stripBackground 分支调用）：
     * B 侵蚀：紧邻透明区的超低 alpha 边缘像素直接移除（砍掉抗锯齿残留）；
     * A 去污染：半透明边缘像素按背景色反混合 c_fg=(c-(1-a)*bg)/a，消除白/灰边。
     */
    private fun defringe(px: IntArray, w: Int, h: Int, bg: Int) {
        val bgR = Color.red(bg); val bgG = Color.green(bg); val bgB = Color.blue(bg)
        // B: 标记紧邻透明区的弱边缘像素（alpha 1..59）
        val weak = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = Color.alpha(px[i])
            if (a in 1..59) {
                val nearTransparent =
                    (x > 0 && Color.alpha(px[i - 1]) < 10) ||
                    (x < w - 1 && Color.alpha(px[i + 1]) < 10) ||
                    (y > 0 && Color.alpha(px[i - w]) < 10) ||
                    (y < h - 1 && Color.alpha(px[i + w]) < 10)
                if (nearTransparent) weak[i] = true
            }
        }
        for (i in weak.indices) if (weak[i]) px[i] = Color.TRANSPARENT
        // A: 半透明边缘反混合去污染（20..239）
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = Color.alpha(px[i])
            if (a < 20 || a >= 240) continue
            val p = px[i]
            val fr = ((Color.red(p) * 255 - bgR * (255 - a)) / a).coerceIn(0, 255)
            val fg = ((Color.green(p) * 255 - bgG * (255 - a)) / a).coerceIn(0, 255)
            val fb = ((Color.blue(p) * 255 - bgB * (255 - a)) / a).coerceIn(0, 255)
            px[i] = Color.argb(a, fr, fg, fb)
        }
    }

    /** 自定义上传图归一化：裁透明边 → 长边 62% → 240 居中（容错用户构图偏差） */
    fun normalizeCustom(src: Bitmap, size: Int = 240): Bitmap {
        val content = trimTransparent(src)
        val scaled = fitContent(content, size, 0.62f)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, (size - scaled.width) / 2f, (size - scaled.height) / 2f, null)
        return out
    }

    private fun quantize(p: Int): Int =
        (Color.red(p) / 16 shl 16) or (Color.green(p) / 16 shl 8) or (Color.blue(p) / 16)

    /** monochrome: 黑色 + 膨胀填实（同样先裁边+居中，比例与暗色前景一致） */
    fun toMonochrome(foreground: Bitmap, size: Int = 240, ratio: Float = FG_RATIO): Bitmap {
        val content = trimTransparent(foreground)
        val scaled = fitContent(content, size, ratio)
        var filled = dilate(scaled, iterations = 2)
        // 保护：膨胀后占比过高（糊满）则回退为不膨胀
        if (opaqueRatio(filled) > 0.5f) {
            filled = scaled
        }
        // 二次保护：原图占比就过高（本身是满版图形），直接返回原形
        if (opaqueRatio(filled) > 0.85f) {
            filled = scaled
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(filled, (size - filled.width) / 2f, (size - filled.height) / 2f, null)
        tint(out, Color.BLACK)
        return out
    }

    /** 裁剪透明边：返回内容实际包围盒（自适应前景带大量透明区，不裁则缩放/定位全错） */
    private fun trimTransparent(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
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

    /** 内容适配：按长边缩到 canvas*ratio（保比例），高清重采样 */
    private fun fitContent(content: Bitmap, canvas: Int, ratio: Float): Bitmap {
        val target = (canvas * ratio).toInt()
        val scale = target.toFloat() / maxOf(content.width, content.height)
        val tw = (content.width * scale).toInt().coerceAtLeast(1)
        val th = (content.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(content, tw, th, true)
    }

    private fun opaqueRatio(bmp: Bitmap): Float {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var n = 0
        for (p in px) if (android.graphics.Color.alpha(p) > 40) n++
        return n.toFloat() / px.size
    }

    /** 变体尺寸（2x2/1x2/2x1 用不同画布） */
    fun forCanvas(src: Bitmap, w: Int, h: Int, logoH: Int): Bitmap {
        val scale = logoH.toFloat() / src.height
        val lw = (src.width * scale).toInt().coerceAtLeast(1)
        val logo = Bitmap.createScaledBitmap(src, lw, logoH, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(logo, ((w - lw) / 2).toFloat(), ((h - logoH) / 2).toFloat(), null)
        return out
    }

    private fun tint(bmp: Bitmap, color: Int) {
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
    }

    /** 简易膨胀：alpha > 阈值 的像素向 4 邻域扩散 */
    private fun dilate(src: Bitmap, iterations: Int): Bitmap {
        var cur = src
        repeat(iterations) {
            val w = cur.width; val h = cur.height
            val px = IntArray(w * h)
            cur.getPixels(px, 0, w, 0, 0, w, h)
            val out = px.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (Color.alpha(px[i]) > 40) continue
                // 任一邻居不透明则此像素变为不透明（保留原颜色）
                val n = mutableListOf<Int>()
                if (x > 0) n.add(px[i - 1])
                if (x < w - 1) n.add(px[i + 1])
                if (y > 0) n.add(px[i - w])
                if (y < h - 1) n.add(px[i + w])
                if (n.any { Color.alpha(it) > 40 }) out[i] = n.first { Color.alpha(it) > 40 }
            }
            cur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cur.setPixels(out, 0, w, 0, 0, w, h)
        }
        return cur
    }
}
