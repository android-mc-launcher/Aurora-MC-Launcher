package com.yuanbao.mcjlauncher.core

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile

/**
 * 高 Android 版本机型 + 低 Java 版本的**透明兼容层**。
 *
 * 完全内部决策：没有任何设置项、UI 开关或选项，只按
 * `Build.VERSION.SDK_INT`、真实页大小、`javaMajor` 与运行时 ELF 的 PT_LOAD 对齐做加固。
 *
 * 处理四件在高版本 Android 上跑 JRE8（老 Minecraft）最容易踩的坑：
 * 1. Java 9+ 专属 JVM 参数（version.json / 用户自定义）会让 JRE8 直接
 *    `Unrecognized option` 退出 —— 按 javaMajor 自动过滤并补 JRE8 兼容参数；
 * 2. Android 10 (API 29) 起 W^X：app 私有目录里**可写**的可执行文件会被 execve 拒绝；
 * 3. Android 15 (API 35) 起 16KB 页设备要求 ELF 的 PT_LOAD 按页对齐 ——
 *    实测内置 `jre8/bin/java` 是 65536B 对齐，天然兼容 16KB 页，不做破坏性 JRE 升级；
 * 4. 低 Java 的 hsperfdata / 文件名编码在高 Android 上受限 —— 关 PerfData、固定 UTF-8。
 */
object AndroidCompat {
    /** Android 14：开始收紧动态代码加载与 JIT 相关行为。 */
    const val HIGH_ANDROID_API = 34

    /** Android 15：16KB 页设备成为正式要求。 */
    const val SIXTEEN_KB_PAGE_API = 35

    /** Android 10：禁止从可写文件 exec（W^X）。 */
    const val WX_API = 29

    /** version.json 的 javaVersion.majorVersion ≤ 8 视为低 Java（老 MC / JRE8）。 */
    const val LEGACY_JAVA_MAJOR = 8

    val sdkInt: Int get() = Build.VERSION.SDK_INT

    val highAndroid: Boolean get() = sdkInt >= HIGH_ANDROID_API

    val wxRequired: Boolean get() = sdkInt >= WX_API

    fun isLegacyJava(javaMajor: Int): Boolean = javaMajor <= LEGACY_JAVA_MAJOR

    /** 真实页大小（Android 15 的 16KB 页设备返回 16384），读失败回退 4096。 */
    fun pageSize(): Long =
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }
            .getOrDefault(4096L)
            .coerceAtLeast(4096L)

    /** Java 9+ 才认识的参数前缀：JRE8 遇到即启动失败，低 Java 时自动剔除。 */
    private val JAVA9_ONLY_PREFIXES =
        listOf(
            "--add-opens",
            "--add-exports",
            "--add-modules",
            "--add-reads",
            "--module-path",
            "--patch-module",
            "--upgrade-module-path",
            "--enable-native-access",
            "--illegal-access",
            "--list-modules",
            "--describe-module",
            "--validate-modules",
            "-XX:+UseZGC",
            "-XX:+UseShenandoahGC",
            "-XX:+UseEpsilonGC",
            "-XX:MaxRAMPercentage",
            "-XX:InitialRAMPercentage",
            "-XX:MinRAMPercentage",
            "-XX:+EnableDynamicAgentLoading",
            "-XX:+UseContainerSupport",
            "-Djdk.module.",
            "-Djava.security.manager=allow",
        )

    private fun isJava9Only(arg: String): Boolean = JAVA9_ONLY_PREFIXES.any { arg.startsWith(it) }

    /**
     * 低 Java 时消毒 JVM 参数，返回「生效参数」与「被剔除条数」。
     * 高 Java 原样返回；不改动任何参数顺序语义，只做减法与必要补齐。
     */
    fun sanitizeJvmArgs(
        args: List<String>,
        javaMajor: Int,
    ): Pair<List<String>, Int> {
        if (!isLegacyJava(javaMajor)) return args to 0
        var removed = 0
        val kept =
            args
                .filter { arg ->
                    if (isJava9Only(arg)) {
                        removed++
                        false
                    } else {
                        true
                    }
                }.toMutableList()
        // JRE8 在高 Android 上写 /tmp/hsperfdata_root 常被 SELinux / scoped storage 拒绝。
        if (kept.none { it.startsWith("-XX:+UsePerfData") || it.startsWith("-XX:-UsePerfData") }) {
            kept += "-XX:-UsePerfData"
        }
        // Java 8 在 Android 上默认文件名编码不总是 UTF-8，老 MC 资源路径会乱码。
        if (kept.none { it.startsWith("-Dsun.jnu.encoding") }) {
            kept += "-Dsun.jnu.encoding=UTF-8"
        }
        if (kept.none { it.startsWith("-Dfile.encoding") }) {
            kept += "-Dfile.encoding=UTF-8"
        }
        return kept to removed
    }

    /** JAVA_TOOL_OPTIONS：低 Java 额外关 PerfData，高 Java 保持最小改动。 */
    fun toolOptions(javaMajor: Int): String =
        if (isLegacyJava(javaMajor)) {
            "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -XX:-UsePerfData"
        } else {
            "-Dfile.encoding=UTF-8"
        }

    /**
     * 读取 ELF 的最小 PT_LOAD `p_align`（字节）。非 ELF / 大端 / 读失败返回 -1。
     * 只读文件头与 program header table，不读整个 .so / 可执行文件。
     */
    fun loadAlignment(file: File): Long {
        if (!file.isFile) return -1L
        return runCatching {
            RandomAccessFile(file, "r").use { r ->
                val magic = ByteArray(6)
                r.read(magic)
                if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte() ||
                    magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()
                ) {
                    return@runCatching -1L
                }
                val is64 = magic[4] == 2.toByte()
                // Android ELF 一律 little-endian，遇到大端保守返回 -1。
                if (magic[5] != 1.toByte()) return@runCatching -1L
                val hdr = ByteArray(if (is64) 64 else 52)
                r.seek(0)
                r.readFully(hdr)

                fun u16(
                    buf: ByteArray,
                    o: Int,
                ): Int = (buf[o].toInt() and 0xff) or ((buf[o + 1].toInt() and 0xff) shl 8)

                fun u32(
                    buf: ByteArray,
                    o: Int,
                ): Long = (u16(buf, o).toLong()) or (u16(buf, o + 2).toLong() shl 16)

                fun u64(
                    buf: ByteArray,
                    o: Int,
                ): Long = u32(buf, o) or (u32(buf, o + 4) shl 32)

                val phoff = (if (is64) u64(hdr, 32) else u32(hdr, 28))
                val phentsize = (if (is64) u16(hdr, 54) else u16(hdr, 42))
                val phnum = (if (is64) u16(hdr, 56) else u16(hdr, 44))
                if (phoff <= 0L || phentsize < 32 || phnum <= 0 || phnum > 512) return@runCatching -1L
                val table = ByteArray(phentsize * phnum)
                r.seek(phoff)
                r.readFully(table)
                var minAlign = Long.MAX_VALUE
                for (i in 0 until phnum) {
                    val off = i * phentsize
                    val type = u32(table, off)
                    if (type != 1L) continue // PT_LOAD
                    val alignOff = off + if (is64) 48 else 28
                    val align = if (is64) u64(table, alignOff) else u32(table, alignOff)
                    if (align > 0L) minAlign = minOf(minAlign, align)
                }
                if (minAlign == Long.MAX_VALUE) -1L else minAlign
            }
        }.getOrDefault(-1L)
    }

    /** JRE 目录里找一个真实存在的关键 ELF，返回其 p_align。 */
    fun jreAlignment(javaHome: String): Long {
        val candidates =
            listOf(
                File(javaHome, "bin/java"),
                File(javaHome, "lib/server/libjvm.so"),
                File(javaHome, "lib/aarch64/server/libjvm.so"),
                File(javaHome, "lib/arm/server/libjvm.so"),
                File(javaHome, "lib/amd64/server/libjvm.so"),
                File(javaHome, "lib/i386/server/libjvm.so"),
                File(javaHome, "lib/jli/libjli.so"),
            )
        return candidates.firstNotNullOfOrNull { f -> loadAlignment(f).takeIf { it > 0L } } ?: -1L
    }

    /** 该 JRE 能否在当前页大小的设备上被 linker 加载。 */
    fun jreAligned(javaHome: String): Boolean {
        val align = jreAlignment(javaHome)
        if (align <= 0L) return true // 读不到就交给 linker，不误报拦截
        return align % pageSize() == 0L
    }

    /** 写进引擎进程环境的诊断标记（不是设置项，不参与 UI）。 */
    fun envFor(
        javaMajor: Int,
        javaHome: String,
        sanitizedCount: Int,
    ): Map<String, String> {
        val page = pageSize()
        val align = jreAlignment(javaHome)
        return linkedMapOf(
            "MCJL_ANDROID_API" to sdkInt.toString(),
            "MCJL_PAGE_SIZE" to page.toString(),
            "MCJL_16K_PAGE" to (page >= 16384L).toString(),
            "MCJL_HIGH_ANDROID" to highAndroid.toString(),
            "MCJL_LEGACY_JAVA" to isLegacyJava(javaMajor).toString(),
            "MCJL_JAVA_MAJOR" to javaMajor.toString(),
            "MCJL_JRE_ALIGN" to align.toString(),
            "MCJL_JRE_ALIGNED" to jreAligned(javaHome).toString(),
            "MCJL_JVM_SANITIZED" to sanitizedCount.toString(),
            "MCJL_WX_TIGHTENED" to wxRequired.toString(),
        )
    }
}
