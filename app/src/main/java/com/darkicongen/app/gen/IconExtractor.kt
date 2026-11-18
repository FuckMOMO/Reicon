package com.darkicongen.app.gen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.topjohnwu.superuser.Shell

/** 从系统提取应用图标的前景/背景/原始 bitmap */
object IconExtractor {

    /** 合成基底边长，输出时统一重采样到各档目标尺寸 */
    private const val BASE = 432

    /** 桌面渲染 adaptive-icon 时对两层的放大倍率：108dp 画布只显示中心 72dp 安全区 */
    private const val ADAPTIVE_LAYER_SCALE = 108f / 72f

    /** 单层渲染分辨率 = BASE*1.5，保证放大裁切后仍有 BASE 的有效像素 */
    private val LAYER_PX = (BASE * ADAPTIVE_LAYER_SCALE).toInt()

    /** ColorOS 原厂图标素材目录（逐包） */
    private const val UXICONS_DIR = "/data/oplus/uxicons"
    private const val MODULE_ID = "DarkIconAuto"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    /**
     * 原厂「完整图标」（满版不透明）候选，按画质/用途优先级排列。
     *
     * 实测（com.taobao.taobao 的素材目录）：`mat / recfg / nsd / day` 四个文件的**不透明率都是 100%**，
     * 画的是「橙色购物袋 + 淡色淘字」这种整图——它们就是 OPPO 重绘后的日间整图，不是抠好的字形层。
     * 所以这几个只能当「整图」用（取色 / 原图标基准），绝不能当字形，否则亮色会变成一只购物袋。
     */
    /**
     * 亮色「整图」候选：白底那张（淘宝实测 mat.png 是白底橙字）。
     * **nsd.png 不在这里** —— 它是暗色底（淘宝实测满版 #0F0F0F），当亮色用会显示成黑底图标。
     */
    private val OFFICIAL_FULL_NAMES = arrayOf("mat.png", "recfg.png", "day.png", "art_off.png")

    /**
     * 暗色「底」只用 recbg（官方品牌色纯色层）。
     * nsd 虽然也是满版图，但它是"深灰底+图案"的整图，用户明确不要这种，也不再叠字形（会重影）。
     */
    private val OFFICIAL_DARK_BASE_NAMES = arrayOf("recbg.png")

    /**
     * 原厂「字形层」只有一个真身：`rec_night.png`（暗色用，透明底，画的才是应用真 logo，
     * 淘宝实测不透明率 13%、是橙色「淘」字）。亮色管线也用它当字形，
     * 这样暗色/亮色形状完全一致，不会出现「暗色是淘字、亮色变购物袋」的错位。
     */
    private val OFFICIAL_GLYPH_NAMES = arrayOf("rec_night.png")

    data class Result(
        val foreground: Bitmap?,   // 前景/字形层（可能为 null）
        val background: Bitmap?,   // 背景层（可能为 null）
        val full: Bitmap,          // 合成完整图标（一定非空）
        val fromOfficial: Boolean = false, // true = 取自 ColorOS 原厂重绘素材
        /** 原厂亮色字形层（mat / recfg，透明底）：有它就不用再抠图，形状/占比就是桌面在用的那一套 */
        val lightForeground: Bitmap? = null,
        /** 原厂单色剪影（monochrome，透明底纯黑）：有它就不用自己膨胀描边 */
        val monochrome: Bitmap? = null,
        /** 原厂暗色底（nsd，满版深色）：拼"官方暗色图标"预览用 */
        val darkBase: Bitmap? = null,

    )

    fun extract(context: Context, packageName: String): Result {
        // 优先用原厂重绘素材：有官方适配的应用，抠图对象换成官方图，形状/配色才是桌面真正在用的那套
        officialIcon(context, packageName)?.let { return it }
        val pm = context.packageManager
        val drawable: Drawable = pm.getApplicationIcon(packageName)
        return when (drawable) {
            is AdaptiveIconDrawable -> {
                val fg = toBitmap(drawable.foreground)
                val bg = toBitmap(drawable.background)
                Result(fg, bg, composite(bg, fg))
            }
            is BitmapDrawable -> {
                val b = drawable.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                Result(null, null, b)
            }
            else -> {
                val b = toBitmap(drawable)
                Result(null, null, b)
            }
        }
    }

    /**
     * 读 ColorOS 原厂素材（/data/oplus/uxicons/<pkg>/）。
     *
     * 命中条件：目录存在且至少有 `rec_night.png` 或某个整图件。
     * - 字形层：`rec_night.png`（透明底，官方重绘的字形）
     * - 背景层：`recbg.png`（官方品牌色纯色底，正好拿来当亮色的主题色来源）
     * - 整图：mat > day > nsd > recfg > art_off；都没有就用 recbg + rec_night 叠加合成
     *
     * 注意：本模块已经逐包挂载该包时，这个目录读到的就是**我们自己的输出**，
     * 拿它当"原厂素材"会自我循环，所以此分支直接放弃（此时素材早已生成过，不需要重新抠图）。
     */
    private fun officialIcon(context: Context, pkg: String): Result? {
        /*
         * 素材来源要分两种情况，否则「已接管」的包永远吃不到原厂件：
         *   - 未接管：/data/oplus/uxicons/<pkg> 就是系统原厂目录，直接读；
         *   - 已接管：那个路径已被本模块 bind 挂载，读到的是我们自己的输出。此时改读模块目录里的
         *     同名目录——首次生成时官方件被整目录透传保存了下来，只是被我们生成的暗色件覆盖了一部分。
         *     我们生成的浅色件是 240 满版不透明图，会被 looksLikeGlyph() 判成"整图"自动排除，
         *     所以这里选出来的仍然是真正的原厂字形层。
         */
        val mounted = isSelfMounted(pkg)
        /*
         * 素材目录优先级：
         *   1. 模块里的官方备份（`.official_backup/<pkg>/uxicons`）—— 唯一没被我们写脏过的一份；
         *   2. 未接管时 `/data/oplus/uxicons/<pkg>`（系统原厂原件）；
         *   3. 已接管又没有备份时，只能读模块目录（可能是我们上次的输出，至少形状对）。
         */
        val backupDir = "$MODULE_DIR/.official_backup/$pkg/uxicons"
        val dir = when {
            Shell.cmd("[ -d '$backupDir' ] && echo YES").exec().out.any { it.contains("YES") } -> backupDir
            !mounted -> "$UXICONS_DIR/$pkg"
            else -> "$MODULE_DIR/data/oplus/uxicons/$pkg"
        }
        val names = Shell.cmd("ls '$dir' 2>/dev/null").exec().out.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        fun pick(vararg candidates: String): Bitmap? = candidates.firstNotNullOfOrNull { n ->
            if (n in names) load(context, "$dir/$n") else null
        }
        // 原厂自带的各种层：能直接用的就绝不再算一遍
        val fg = pick(*OFFICIAL_GLYPH_NAMES)?.takeIf { looksLikeGlyph(it) }   // 字形（rec_night，透明底）
        val bg = pick("recbg.png")                                           // 背景（品牌色纯色）
        val mono = pick("monochrome.png")                                    // 单色剪影
        val darkBase = pick(*OFFICIAL_DARK_BASE_NAMES)                        // 暗色底（nsd）

        /*
         * 「完整图标」：优先真正满版的 day/nsd/mat/recfg/art_off；
         * 都没有就用 recbg + 字形叠加；再不行只好单独用字形（取色还能用 bg）。
         */
        val overlayFull = if (bg != null && fg != null) overlay(bg, fg) else null
        // 名单里的就是亮色整图，不再用 looksLikeGlyph 二次过滤
        // （拼多多 mat.png 只有 59% 不透明，会被误判成字形层，导致回退成 recbg+字形 的红底图）
        val full = pick(*OFFICIAL_FULL_NAMES)
            ?: overlayFull
            ?: overlay(null, fg)
        if (fg == null && bg == null && mono == null) return null
        return Result(
            foreground = fg,
            background = bg,
            full = full,
            fromOfficial = true,
            // 亮色字形直接复用暗色字形：同一张 logo，形状必然一致
            lightForeground = fg,
            monochrome = mono,
            darkBase = darkBase,
        )
    }

    /**
     * 应用自身的图标（桌面没被接管时就是这个）：
     * 「原图标」选项要展示的永远是它，而不是原厂素材目录里可能已被我们覆盖的图。
     */
    fun appIcon(context: Context, pkg: String): Bitmap? = runCatching {
        when (val d = context.packageManager.getApplicationIcon(pkg)) {
            is AdaptiveIconDrawable -> composite(toBitmap(d.background), toBitmap(d.foreground))
            is BitmapDrawable -> d.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            else -> toBitmap(d)
        }
    }.getOrNull()

    /**
     * 判定一张原厂图是「抠好的字形层」还是「整图」：字形层透明底、不透明像素占比低；
     * 整图（mat/day 有的包直接放的是满版图）占比接近 100%，不能当字形用，否则白底上会出现一整块方图。
     */
    private fun looksLikeGlyph(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return false
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var opaque = 0
        for (p in px) if (Color.alpha(p) > 40) opaque++
        return opaque * 100 / px.size < 85
    }

    /** /data/oplus/uxicons/<pkg> 是否已被本模块逐包 bind 挂载（挂载后读到的即本模块自身输出） */
    private fun isSelfMounted(pkg: String): Boolean =
        Shell.cmd("grep -q '$MODULE_ID/data/oplus/uxicons/$pkg ' /proc/1/mountinfo && echo YES").exec()
            .out.any { it.contains("YES") }

    /** 官方件按 1:1 叠成完整图标（它们本就是"已渲染好的可见部分"，不能再按 1.5 倍裁切） */
    private fun overlay(bg: Bitmap?, fg: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(BASE, BASE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = RectF(0f, 0f, BASE.toFloat(), BASE.toFloat())
        bg?.let { c.drawBitmap(it, null, r, null) }
        fg?.let { c.drawBitmap(it, null, r, null) }
        return out
    }

    /** 经 root 把官方素材拷到缓存再解码（二进制不走 stdout，避免被 stdin/stdout 编码破坏） */
    private fun load(context: Context, path: String): Bitmap? {
        val tmp = context.cacheDir.resolve("official_${path.hashCode()}.png")
        val r = Shell.cmd(
            "[ -s '$path' ] && cp '$path' '${tmp.absolutePath}' && chmod 644 '${tmp.absolutePath}' && echo OK"
        ).exec()
        if (r.out.none { it.contains("OK") }) {
            runCatching { tmp.delete() }
            return null
        }
        val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(tmp.absolutePath) }.getOrNull()
        runCatching { tmp.delete() }
        return bmp?.let {
            if (it.width == BASE && it.height == BASE) it
            else Bitmap.createScaledBitmap(it, BASE, BASE, true)
        }
    }

    /**
     * 合成完整图标：按桌面渲染 adaptive-icon 的方式，把背景/前景两层各放大 108/72=1.5 倍居中绘制，
     * 超出画布的部分被裁掉——桌面只显示 108dp 画布中心的 72dp 安全区，1:1 绘制会让图标整体缩到 2/3。
     * 层按 base*1.5 渲染，保证裁切后仍有 base 的有效分辨率。
     *
     * 真机实证（net.xmind.doughnut）：其 ic_launcher_background 是 512 viewport 内的 "M58 58H454V454H58z"，
     * 只占 396/512=77.3% 的内缩方块。1:1 绘制得到的 recfg 就是 77.5% 硬边方块 + 四周透明，
     * 而桌面浅色是 recfg 叠 recbg（平涂品牌红）渲染的，于是正中一块渐变红方块、四周另一色平涂红，
     * 接缝实测正好落在 78%（168 渲染图上 x=19..149）。按 1.5 倍绘制后该方块变 116% 被裁满，
     * 浅色恢复满版渐变，logo 占比也从 0.425 回到 0.638。
     */
    private fun composite(bg: Bitmap?, fg: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(BASE, BASE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val half = BASE * ADAPTIVE_LAYER_SCALE / 2f
        val r = android.graphics.RectF(BASE / 2f - half, BASE / 2f - half, BASE / 2f + half, BASE / 2f + half)
        bg?.let { c.drawBitmap(it, null, r, null) }
        fg?.let { c.drawBitmap(it, null, r, null) }
        return out
    }

    private fun toBitmap(d: Drawable): Bitmap {
        val size = maxOf(d.intrinsicWidth, d.intrinsicHeight).takeIf { it > 0 }?.coerceAtLeast(LAYER_PX) ?: LAYER_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }

    /**
     * 提取品牌主色（用于 recbg）：取完整图标（含前景）的加权主色。
     * 官方语义（B站实证）：recbg = 品牌色而非深色；中心区域加权避免背景层滿版色淹没 logo 色。
     */
    fun dominantColor(bitmap: Bitmap?): Int {
        if (bitmap == null) return Color.parseColor("#1A1A1A")
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val counts = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                if (Color.alpha(p) < 128) continue
                // 中心 70% 区域权重 3，边缘 1：让 logo 主体颜色胜出（浅色背景图标取色的关键）
                val inCenter = x >= w * 15 / 100 && x < w * 85 / 100 && y >= h * 15 / 100 && y < h * 85 / 100
                val weight = if (inCenter) 3 else 1
                val key = (Color.red(p) / 32 shl 16) or (Color.green(p) / 32 shl 8) or (Color.blue(p) / 32)
                counts[key] = (counts[key] ?: 0) + weight
            }
        }
        val top = counts.maxByOrNull { it.value }?.key
            ?: return Color.parseColor("#1A1A1A")
        val r = (top shr 16 and 0xFF) * 32 + 16
        val g = (top shr 8 and 0xFF) * 32 + 16
        val b = (top and 0xFF) * 32 + 16
        return Color.rgb(r.coerceAtMost(255), g.coerceAtMost(255), b.coerceAtMost(255))
    }

    /**
     * 亮色管线专用「强调色」：白底上要当字形颜色的那一支，所以必须是**有彩度**的颜色。
     *
     * 与 dominantColor 的区别：dominantColor 走中心加权，微信这类「彩底 + 白色字形」的图标
     * 会因为中心是白字形而算回近白色；白底 + 白字形等于什么都看不见。
     * 这里优先从**背景层**取色（自适应图标的 bg 层通常就是品牌色本身，微信绿、支付宝蓝），
     * 背景层不可用/无彩度时才从完整图里挑饱和度最高的主色。
     */
    fun accentColor(background: Bitmap?, full: Bitmap?): Int {
        background?.let { bg -> vividDominant(bg)?.let { return it } }
        full?.let { f -> vividDominant(f)?.let { return it } }
        val fallback = dominantColor(full)
        // 兜底色也太亮（白底白字）时给深灰，保证字形可见
        return if (luma(fallback) > 0.90) Color.parseColor("#1F1F1F") else fallback
    }

    /**
     * 有彩度主色：跳过近白、近黑、灰阶像素，按「像素数 × (1 + 彩度) × 中心权重」投票。
     * 无可用色返回 null（纯白/纯黑/单色灰阶图标会走到这里）。
     */
    private fun vividDominant(bmp: Bitmap): Int? {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val counts = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                if (Color.alpha(p) < 100) continue
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val max = maxOf(r, g, b); val min = minOf(r, g, b)
                if (max < 48) continue                       // 近黑
                if (max - min < 24) continue                 // 灰阶（白/黑/灰都跳过）
                if (min > 210) continue                      // 极浅的近白彩色
                val sat = (max - min).toFloat() / max
                val inCenter = x >= w * 15 / 100 && x < w * 85 / 100 &&
                    y >= h * 15 / 100 && y < h * 85 / 100
                val weight = (1f + sat * 3f) * (if (inCenter) 2f else 1f)
                val key = (r / 32 shl 16) or (g / 32 shl 8) or (b / 32)
                counts[key] = (counts[key] ?: 0) + (weight * 100).toInt()
            }
        }
        val top = counts.maxByOrNull { it.value }?.key ?: return null
        val r = ((top shr 16 and 0xFF) * 32 + 16).coerceAtMost(255)
        val g = ((top shr 8 and 0xFF) * 32 + 16).coerceAtMost(255)
        val b = ((top and 0xFF) * 32 + 16).coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    /** 感知亮度（0..1） */
    private fun luma(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
}
