package com.yuanbao.mcjlauncher.core

import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 流式 ustar / GNU / PAX TAR 解压器。
 * 用于把 FCL 的 `universal.tar.xz` + `bin-<abi>.tar.xz` 合并解压成完整 JRE。
 * 支持 GNU longname('L')、PAX 扩展头('x')、目录、普通文件、符号链接；
 * 逐 512 字节块读取，不把 130MB 的 lib/modules 整体加载进内存。
 */
class TarExtractor(
    private val input: InputStream,
    private val dest: File,
) {
    fun extract(onEntry: (String) -> Unit = {}): Int {
        var count = 0
        var pendingLongName: String? = null
        while (true) {
            val header = ByteArray(BLOCK)
            if (!readFully(header, BLOCK)) break
            if (header.all { it == 0.toByte() }) break // 两个零块 = 归档结束

            val rawName = readString(header, 0, 100)
            val prefix = readString(header, 345, 155)
            val size = readSize(header, 124, 12)
            val type = header[156].toInt().toChar()
            val name = pendingLongName ?: (if (prefix.isEmpty()) rawName else "$prefix/$rawName")
            pendingLongName = null

            val padded = ((size + BLOCK - 1) / BLOCK) * BLOCK
            val target = resolve(name)

            when (type) {
                'L' -> { // GNU long name
                    val buf = ByteArray(size.toInt())
                    readFully(buf, size.toInt())
                    pendingLongName = String(buf, Charsets.UTF_8).trimEnd('\u0000', '\n')
                    skip(padded - size)
                }

                'x', 'g', 'X' -> {
                    skip(padded)
                }

                // PAX / GNU 扩展头，忽略
                '5' -> { // 目录
                    target.mkdirs()
                    skip(padded)
                    onEntry(name)
                    count++
                }

                '0', '\u0000', '7' -> { // 普通文件
                    writeFile(target, size)
                    skip(padded - size)
                    onEntry(name)
                    count++
                }

                '2' -> { // 符号链接
                    val linkBuf = readString(header, 157, 100)
                    if (linkBuf.isNotEmpty()) {
                        link(target, linkBuf)
                        onEntry(name)
                        count++
                    }
                    skip(padded)
                }

                else -> {
                    skip(padded)
                } // 其它类型（fifo/chr 等）跳过
            }
        }
        return count
    }

    private fun resolve(nameRaw: String): File {
        val name = nameRaw.removePrefix("./").trimEnd('\u0000')
        val canonicalDest = dest.canonicalPath
        val target = File(dest, name)
        val canonical = target.canonicalPath
        if (canonical != canonicalDest && !canonical.startsWith(canonicalDest + File.separator)) {
            throw IOException("Tar Slip 拦截: $name")
        }
        return target
    }

    private fun writeFile(
        target: File,
        size: Long,
    ) {
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) throw IOException("TAR 数据提前结束: ${target.name}")
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        if (target.name == "java" || target.extension == "so" || target.parentFile?.name == "bin") {
            target.setExecutable(true, false)
        }
    }

    private fun link(
        target: File,
        linkTarget: String,
    ) {
        runCatching {
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            Os.symlink(linkTarget, target.absolutePath)
        }.onFailure {
            // 部分 Android 版本禁止 symlink：降级为复制链接目标内容
            val src = File(target.parentFile, linkTarget)
            if (src.isFile) src.copyTo(target, overwrite = true)
        }
    }

    private fun readFully(
        buf: ByteArray,
        length: Int,
    ): Boolean {
        var offset = 0
        while (offset < length) {
            val n = input.read(buf, offset, length - offset)
            if (n < 0) return false
            if (n == 0) continue
            offset += n
        }
        return true
    }

    private fun skip(bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private fun readString(
        header: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        var end = offset + length
        while (end > offset && header[end - 1] == 0.toByte()) end--
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    /** 支持 octal 与 GNU base-256 两种 size 编码。 */
    private fun readSize(
        header: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in 1 until length) {
                value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            }
            return value
        }
        var value = 0L
        for (i in offset until offset + length) {
            val c = header[i].toInt().toChar()
            if (c !in '0'..'7') break
            value = value * 8 + (c - '0')
        }
        return value
    }

    private companion object {
        const val BLOCK = 512
    }
}
