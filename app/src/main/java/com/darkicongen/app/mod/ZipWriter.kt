package com.darkicongen.app.mod

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuRandomAccessFile
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 最小 ZIP 写入器：显式写入 Unix 权限位（external file attributes 高 16 位）。
 * KSU 刷入包硬性要求：脚本 0755 可执行位、条目名正斜杠、module.prop 在根部。
 * Windows 系压缩工具产出的 zip 权限位/分隔符不可靠，故 App 侧自产。
 */
object ZipWriter {

    // Unix 模式常量（Kotlin 无八进制字面量）
    private const val MODE_DIR = 0x41ED     // 040755
    private const val MODE_FILE_X = 0x81ED  // 100755
    private const val MODE_FILE = 0x81A4    // 100644

    private class CEntry(
        val name: ByteArray,
        val crc: Long,
        val compSize: Long,
        val size: Long,
        val offset: Long,
        val attrs: Int,
        val method: Int,
    )

    /**
     * 把模块目录打包成标准刷入包。
     * @param moduleDir 模块绝对路径（设备端）
     * @param dirs 相对目录列表（不含 "."）
     * @param files 相对文件列表
     * @param outPath 设备端输出路径（如 /sdcard/DarkIconAuto.zip）
     * @param cacheDir App 缓存目录（中转临时 zip）
     * @param alias 条目名 → 实际设备路径（用于打包与模块目录不同的文件，如图标包的 module.prop）
     * @param onProgress 逐文件进度回调（已处理数, 总数）—— 大包（几百个包的素材）要好几分钟，
     *        没有进度用户会以为点坏了，所以每处理 8 个文件回报一次
     */
    fun buildFromModule(
        moduleDir: String,
        dirs: List<String>,
        files: List<String>,
        outPath: String,
        cacheDir: File,
        alias: Map<String, String> = emptyMap(),
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Boolean = runCatching {
        val tmp = File(cacheDir, "module_export.zip")
        tmp.outputStream().use { fos ->
            val entries = mutableListOf<CEntry>()
            var offset = 0L

            // 目录条目（按路径深度排序，父目录在前）
            for (d in dirs.sortedBy { it.count { c -> c == '/' } }) {
                val name = "$d/"
                val nb = name.toByteArray(Charsets.UTF_8)
                writeLfh(fos, nb, 0, 0, 0, 0)
                entries += CEntry(nb, 0, 0, 0, offset, MODE_DIR shl 16, 0)
                offset += 30 + nb.size
            }

            val total = files.size
            var done = 0
            onProgress?.invoke(0, total)
            for (rel in files.sorted()) {
                val src = alias[rel] ?: "$moduleDir/$rel"
                val data = SuRandomAccessFile.open(src, "r").use { raf ->
                    ByteArray(raf.length().toInt()).also { raf.readFully(it) }
                }
                val crc = CRC32().apply { update(data) }.value
                val deflated = deflate(data)
                val useDeflate = deflated.size < data.size
                val (method, payload) = if (useDeflate) 8 to deflated else 0 to data
                val nb = rel.toByteArray(Charsets.UTF_8)
                writeLfh(fos, nb, crc, payload.size.toLong(), data.size.toLong(), method)
                fos.write(payload)
                val attrs = if (rel.endsWith(".sh")) MODE_FILE_X shl 16 else MODE_FILE shl 16
                entries += CEntry(nb, crc, payload.size.toLong(), data.size.toLong(), offset, attrs, method)
                offset += 30 + nb.size + payload.size
                done++
                if (done == total || done % 8 == 0) onProgress?.invoke(done, total)
            }

            // central directory
            val cdStart = offset
            var cdSize = 0L
            for (e in entries) {
                fos.write(le32(0x02014b50))
                fos.write(le16(0x031E))          // version made by: unix + zip 2.0
                fos.write(le16(20))              // version needed
                fos.write(le16(0))               // flags
                fos.write(le16(e.method))
                fos.write(le16(0)); fos.write(le16(0x21)) // time, date (1980-01-01)
                fos.write(le32(e.crc))
                fos.write(le32(e.compSize))
                fos.write(le32(e.size))
                fos.write(le16(e.name.size))
                fos.write(le16(0)); fos.write(le16(0))    // extra, comment
                fos.write(le16(0)); fos.write(le16(0))    // disk, internal attrs
                fos.write(le32(e.attrs.toLong() and 0xFFFFFFFFL))
                fos.write(le32(e.offset))
                fos.write(e.name)
                cdSize += 46 + e.name.size
            }

            // EOCD
            fos.write(le32(0x06054b50))
            fos.write(le16(0)); fos.write(le16(0))
            fos.write(le16(entries.size)); fos.write(le16(entries.size))
            fos.write(le32(cdSize))
            fos.write(le32(cdStart))
            fos.write(le16(0))
        }
        // 推到目标路径（/sdcard 可能需要 root 复制）
        Shell.cmd("cat ${tmp.absolutePath} > $outPath", "rm -f ${tmp.absolutePath}").exec()
        true
    }.getOrDefault(false)

    private fun writeLfh(
        out: OutputStream,
        name: ByteArray,
        crc: Long,
        compSize: Long,
        size: Long,
        method: Int,
    ) {
        out.write(le32(0x04034b50))
        out.write(le16(20))
        out.write(le16(0))
        out.write(le16(method))
        out.write(le16(0)); out.write(le16(0x21))
        out.write(le32(crc))
        out.write(le32(compSize))
        out.write(le32(size))
        out.write(le16(name.size))
        out.write(le16(0))
        out.write(name)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED, true)
        d.setInput(data)
        d.finish()
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream(data.size)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n > 0) out.write(buf, 0, n) else break
        }
        d.end()
        return out.toByteArray()
    }

    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )
}
