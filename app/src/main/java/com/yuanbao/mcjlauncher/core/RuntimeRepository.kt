package com.yuanbao.mcjlauncher.core

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException

/**
 * 运行时释放引擎：把 assets/app_runtime 里的真实原版文件释放到 app 私有目录。
 *
 * - jre8 / jre17 是 HMCL-PE 的展开格式，直接递归复制；
 * - jre21 / jre25 是 FCL 官方分卷格式，运行时用 XZ for Java 把
 *   `universal.tar.xz` 与当前 ABI 的 `bin-<abi>.tar.xz` 合并解压成同构 JRE；
 * - JNA 按 assets 里的 `version` 选版本、按当前 ABI 选 `libjnidispatch.so`；
 * - LWJGL 按设置选 3.3.3 / 3.4.1；Caciocavallo 按 javaMajor 选目录；
 * - 已释放且标记文件匹配时跳过，避免每次启动重复拷贝 299MB。
 */
class RuntimeRepository(
    private val context: Context,
) {
    private val assets: AssetManager = context.assets
    private val root = File(context.filesDir, "runtime")

    val runtimeRoot: File get() = root

    fun abi(): String {
        val supported = Build.SUPPORTED_ABIS
        return when {
            supported.any { it.startsWith("arm64") } -> "arm64-v8a"
            supported.any { it.startsWith("x86_64") } -> "x86_64"
            supported.any { it.startsWith("armeabi") } -> "armeabi-v7a"
            supported.any { it == "x86" } -> "x86"
            else -> "arm64-v8a"
        }
    }

    private fun tarAbi(): String =
        when (abi()) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86" -> "x86"
            else -> "x86_64"
        }

    /**
     * 确保运行时就位并返回布局。
     * @param javaMode 用户设置（AUTO 时由 javaMajor 决定）
     * @param javaMajor version.json 的 javaVersion.majorVersion
     */
    fun prepare(
        javaMode: JavaMode,
        javaMajor: Int,
        lwjglVersion: String,
        verify: Boolean,
        onProgress: (String) -> Unit = {},
    ): RuntimeLayout {
        val jreDir = javaMode.directory() ?: JavaMode.forGame(javaMajor)
        val abi = abi()
        val tag = "$jreDir-$abi-$lwjglVersion-v3"
        val marker = File(root, ".prepared-$tag")

        if (!marker.exists() || !marker.readText().trim().equals(tag)) {
            onProgress("释放运行时：$jreDir / $abi / LWJGL $lwjglVersion")
            ensureJre(jreDir, onProgress)
            onProgress("释放 JNA / LWJGL / Caciocavallo")
            copyTree("app_runtime/jna", File(root, "jna"), onProgress)
            copyTree("app_runtime/lwjgl/$lwjglVersion", File(root, "lwjgl/$lwjglVersion"), onProgress)
            copyTree("app_runtime/caciocavallo", File(root, "caciocavallo"), onProgress)
            copyTree("app_runtime/caciocavallo17", File(root, "caciocavallo17"), onProgress)
            root.mkdirs()
            marker.writeText(tag)
        } else {
            onProgress("运行时已就位（$tag），跳过重复释放")
        }

        val javaHome = File(root, "java/$jreDir")
        val javaBin = File(javaHome, "bin/java")
        if (!javaBin.exists()) {
            throw IOException("JRE 释放不完整，缺少 bin/java：$javaBin")
        }

        val jnaVersion = readAssetText("app_runtime/jna/version").trim().ifBlank { "5.19.1" }
        val jnaRoot = File(root, "jna/$jnaVersion")
        val lwjglRoot = File(root, "lwjgl/$lwjglVersion")
        val cacDir = if (javaMajor >= 17) "caciocavallo17" else "caciocavallo"

        return RuntimeLayout(
            javaHome = javaHome.absolutePath,
            javaBin = javaBin.absolutePath,
            jliCandidates =
                listOf(
                    File(javaHome, "lib/libjli.so"),
                    File(javaHome, "lib/${archJli()}/jli/libjli.so"),
                    File(javaHome, "lib/server/libjli.so"),
                    File(javaHome, "lib/jli/libjli.so"),
                ).map { it.absolutePath },
            nativeLibDir =
                listOf(
                    File(javaHome, "lib"),
                    File(javaHome, "lib/server"),
                    File(javaHome, "lib/${archJli()}"),
                    File(javaHome, "lib/${archJli()}/jli"),
                    File(lwjglRoot, "natives"),
                    File(jnaRoot, "natives/$abi"),
                    File(lwjglRoot, "renderer/gl4es"),
                ).joinToString(File.pathSeparator) { it.absolutePath },
            jnaJar = File(jnaRoot, "jna-$jnaVersion.jar").absolutePath,
            jnaNativeDir = File(jnaRoot, "natives/$abi").absolutePath,
            lwjglJars =
                (lwjglRoot.listFiles().orEmpty())
                    .filter { it.isFile && it.extension == "jar" }
                    .map { it.absolutePath }
                    .sorted(),
            lwjglNativeDir = File(lwjglRoot, "natives").absolutePath,
            rendererDir = File(lwjglRoot, "renderer/gl4es").absolutePath,
            caciocavalloJars =
                (File(root, cacDir).listFiles().orEmpty())
                    .filter { it.isFile && it.extension == "jar" }
                    .map { it.absolutePath }
                    .sorted(),
            gl4esLib = File(lwjglRoot, "renderer/gl4es/libgl4es_114.so").absolutePath,
            versionTag = tag,
            sdlDir = File(lwjglRoot, "sdl").absolutePath,
        )
    }

    private fun archJli(): String =
        when (abi()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86" -> "i386"
            else -> "amd64"
        }

    private fun ensureJre(
        jreDir: String,
        onProgress: (String) -> Unit,
    ) {
        val out = File(root, "java/$jreDir")
        if (jreDir == "jre21" || jreDir == "jre25") {
            extractFcl(jreDir, out, onProgress)
        } else {
            copyTree("app_runtime/java/$jreDir", out, onProgress)
        }
        // Android 从 app 私有目录加载 ELF 需要可执行位
        chmodTree(out)
    }

    /** FCL 分卷：universal + 当前 ABI 的二进制，解压到同一目录。 */
    private fun extractFcl(
        jreDir: String,
        out: File,
        onProgress: (String) -> Unit,
    ) {
        out.mkdirs()
        val parts = mutableListOf("universal.tar.xz")
        val abiPart = "bin-${tarAbi()}.tar.xz"
        if (assetExists("app_runtime/java/$jreDir/$abiPart")) {
            parts += abiPart
        } else {
            // jre25 上游没有 x86(32 位) 分卷：回退到 arm64 并明确记录
            parts += "bin-arm64.tar.xz"
            onProgress("上游缺少 $abiPart，回退 bin-arm64.tar.xz")
        }
        parts.forEach { part ->
            val path = "app_runtime/java/$jreDir/$part"
            onProgress("解压 $jreDir/$part …")
            assets.open(path).use { raw ->
                XZInputStream(raw).use { xz ->
                    TarExtractor(xz, out).extract()
                }
            }
        }
        chmodTree(out)
    }

    /** 递归复制 AssetManager 目录到 filesDir。空数组即文件，非空即目录。 */
    private fun copyTree(
        assetPath: String,
        dest: File,
        onProgress: (String) -> Unit,
    ) {
        val children = runCatching { assets.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) {
            // 文件：尝试打开，失败说明它其实是一个空目录
            runCatching {
                assets.open(assetPath).use { input ->
                    dest.parentFile?.mkdirs()
                    // 上一次 prepare 可能已按 W^X 去掉写位，覆盖前恢复，避免二次释放失败。
                    if (dest.exists()) dest.setWritable(true, false)
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            }.onFailure { dest.mkdirs() }
            return
        }
        dest.mkdirs()
        children
            .filterNot { it.endsWith(".gitkeep") || it == "README.md" || it == "SOURCES.md" }
            .forEach { child ->
                copyTree("$assetPath/$child", File(dest, child), onProgress)
            }
    }

    private fun chmodTree(dir: File) {
        if (!dir.exists()) return
        // Android 10 (API 29) 起 W^X：从 app 私有目录 execve 一个「可写」文件会被拒绝。
        // 高版本机型上跑 JRE8 尤其容易踩到，这里自动收紧，不暴露任何开关。
        val tighten = AndroidCompat.wxRequired
        dir.walkTopDown().forEach { f ->
            if (f.isFile && (f.extension == "so" || f.parentFile?.name == "bin" || f.name == "java")) {
                f.setWritable(true, false)
                f.setExecutable(true, false)
                if (tighten) f.setWritable(false, false)
            }
        }
    }

    private fun assetExists(path: String): Boolean =
        runCatching {
            assets.open(path).close()
            true
        }.getOrDefault(false)

    private fun readAssetText(path: String): String =
        runCatching {
            assets.open(path).bufferedReader().use { it.readText() }
        }.getOrDefault("")

    /** 诊断：统计 assets 里的真实文件数与体积，供关于页显示。 */
    fun assetStats(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L

        fun walk(path: String) {
            val children = runCatching { assets.list(path) }.getOrNull().orEmpty()
            if (children.isEmpty()) {
                runCatching {
                    val fd = assets.openFd(path)
                    count++
                    bytes += fd.length
                    fd.close()
                }.onFailure {
                    // 被 AAPT 压缩过的 asset 无法 openFd，退回流式统计
                    runCatching {
                        assets.open(path).use { input ->
                            val buf = ByteArray(64 * 1024)
                            var size = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                size += n
                            }
                            count++
                            bytes += size
                        }
                    }
                }
                return
            }
            children.forEach { walk("$path/$it") }
        }
        walk("app_runtime")
        return count to bytes
    }
}
