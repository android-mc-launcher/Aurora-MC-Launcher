package com.yuanbao.mcjlauncher.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动预检：在把计划交给 C++ 引擎之前，真实检查文件、磁盘、内存与 ABI。
 * 任何 ERROR 都会阻止启动并写进日志；WARN 只提示，不伪造通过。
 */
object LaunchGuard {
    fun preflight(
        context: Context,
        versionId: String,
        meta: VersionMeta,
        layout: RuntimeLayout,
        settings: SettingsSnapshot,
        games: GameRepository,
        instances: InstanceRepository,
        classpath: String,
        effectiveMemoryMb: Int = settings.memoryMb,
    ): PreflightReport {
        val items = mutableListOf<PreflightItem>()

        fun add(
            label: String,
            ok: Boolean,
            detail: String,
            fatal: Boolean = false,
        ) {
            items +=
                PreflightItem(
                    label,
                    if (ok) {
                        "OK"
                    } else if (fatal) {
                        "ERROR"
                    } else {
                        "WARN"
                    },
                    detail,
                )
        }

        add("Java 可执行文件", File(layout.javaBin).isFile, layout.javaBin, fatal = true)
        val jli = layout.jliCandidates.firstOrNull { File(it).isFile }
        add("libjli.so", jli != null, jli ?: "候选：${layout.jliCandidates.joinToString()}", fatal = true)

        val cp = classpath.split(File.pathSeparator).filter { it.isNotBlank() }
        val missing = cp.filterNot { File(it).isFile }
        add(
            "classpath 完整性",
            missing.isEmpty(),
            "${cp.size} 项 / 缺失 ${missing.size}${if (missing.isEmpty()) "" else "：${missing.take(3).joinToString()}"}",
            fatal = true,
        )

        val jar = File(games.versionDir(versionId), "$versionId.jar")
        add("client.jar", jar.isFile, "${jar.absolutePath} (${jar.length()} B)", fatal = true)
        val idx = File(games.gameAssetsDir(), "indexes/${meta.assetIndexId}.json")
        add("资源索引", idx.isFile, idx.absolutePath, fatal = true)

        val gameDir = instances.gameDir(versionId, settings.isolateInstances).apply { mkdirs() }
        val probe = File(gameDir, ".mcjl-write-test")
        val writable =
            runCatching {
                probe.writeText("ok")
                probe.delete()
            }.isSuccess
        add("实例目录可写", writable, gameDir.absolutePath, fatal = true)

        val stat = StatFs(context.filesDir.absolutePath)
        val freeMb = stat.availableBytes / 1024L / 1024L
        add("磁盘可用空间", freeMb >= 256L, "$freeMb MB（建议 ≥256 MB）")

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val totalMb = if (mi.totalMem > 0L) mi.totalMem / 1024L / 1024L else 0L
        val memMb = effectiveMemoryMb
        val memOk = totalMb <= 0L || memMb <= (totalMb * 0.75f).toInt()
        add("内存分配合理性", memOk, "-Xmx${memMb}m / 设备约 ${totalMb}MB")

        val nativeSo = File(layout.lwjglNativeDir).listFiles().orEmpty().count { it.name.endsWith(".so") }
        add("LWJGL native", nativeSo > 0, "${layout.lwjglNativeDir} 内 $nativeSo 个 .so", fatal = true)

        val renderer =
            when (settings.renderer) {
                "gl4es" -> layout.gl4esLib
                else -> File(layout.rendererDir, "libEGL_wrapper.so").absolutePath
            }
        add("渲染库", File(renderer).isFile, renderer)

        // SFPEW 是叠加层：开启时额外做两项软检查（WARN，不拦截启动）。
        if (settings.sfpewEnabled) {
            val wrapper = LaunchConfigBuilder.sfpewLib(layout)
            add(
                "SFPEW wrapper",
                File(wrapper).isFile,
                if (File(wrapper).isFile) {
                    "ABI ${SdlSpec.currentAbi()} · ${File(wrapper).name} · $wrapper"
                } else {
                    "$wrapper 缺失 → 本次安全降级到 ${settings.renderer}"
                },
            )
            val eglBackend = LaunchConfigBuilder.sfpewEglBackend(layout, renderer)
            add(
                "SFPEW EGL 后端",
                File(eglBackend).isFile,
                "SFPEW_EGL 将指向 $eglBackend（wrapper 用 dlopen 拿 EGL/GL 入口）",
            )
            val ownFpe =
                LaunchConfigBuilder.hasOwnFpeMod(
                    instances.gameDir(versionId, settings.isolateInstances),
                )
            add(
                "SFPEW 冲突模组",
                !ownFpe,
                if (ownFpe) {
                    "检测到自带 FPE 的模组（Angelica / Actinium）→ 本次自动跳过叠加层"
                } else {
                    "无冲突，叠加层可生效；底层仍为 ${settings.renderer}"
                },
            )
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"
        add("设备 ABI", abi.isNotBlank() && abi != "未知", abi)

        // 高 Android × 低 Java 的透明兼容检查：16KB 页对齐（WARN，不拦截）。
        val page = AndroidCompat.pageSize()
        val align = AndroidCompat.jreAlignment(layout.javaHome)
        val aligned = AndroidCompat.jreAligned(layout.javaHome)
        add(
            "页大小 / JRE 对齐",
            aligned,
            "API ${AndroidCompat.sdkInt} · 页 ${page}B · ${File(layout.javaBin).name} 最小 p_align=${align}B" +
                if (aligned) " → 可被 linker 加载" else " → 16KB 页设备可能 dlopen 失败",
        )
        if (AndroidCompat.isLegacyJava(meta.javaMajor) && AndroidCompat.highAndroid) {
            val probeArgs =
                meta.jvmArgs + settings.extraJvmArgsGlobal.split(Regex("\\s+")).filter { it.isNotBlank() }
            val (_, removed) = AndroidCompat.sanitizeJvmArgs(probeArgs, meta.javaMajor)
            add(
                "低 Java × 高 Android",
                true,
                "JRE8 不升级 · 本版本清单/自定义参数中自动过滤 $removed 个 Java 9+ 选项 · W^X 去写位 · hsperfdata 关闭",
            )
        }

        // SDL 后端软检查（WARN，不拦截启动）：Java 版本映射与库就位情况。
        val sdl = SdlResolver.resolve(settings, meta.javaMajor, versionId, layout)
        add(
            "SDL 窗口后端",
            sdl.enabled || sdl.reason.contains("低版本") || sdl.reason.contains("用户关闭"),
            "${sdl.windowBackend} · ${sdl.summary} · 自动规则 ${SdlResolver.autoFor(meta.javaMajor).name}",
        )
        if (sdl.reason.startsWith("library-missing")) {
            add("SDL 运行时库", false, "${sdl.reason} → 本次降级为 GLFW，启动不受影响")
        } else if (sdl.enabled) {
            add("SDL 真实库（按 ABI）", File(sdl.libPath).isFile, "${sdl.abi} · ${sdl.libPath}")
            if (sdl.active.name == "SDL2") {
                val dep = File(sdl.dependencyDir, SdlSpec.SDL3_LIB)
                add("SDL3 依赖库", dep.isFile, "sdl2-compat 需要 ${dep.absolutePath}")
            }
        }

        add(
            "下载校验",
            settings.verifyAssets,
            if (settings.verifyAssets) "SHA-1 已启用" else "已关闭（不推荐）",
        )

        return PreflightReport(versionId, items)
    }

    fun toLogText(report: PreflightReport): String =
        buildString {
            appendLine("[预检] ${report.versionId} · ${report.summary}")
            report.items.forEach { appendLine("[预检] ${it.level} ${it.label}：${it.detail}") }
        }
}

/**
 * 崩溃归档：进程非 0 退出时把日志快照 + 诊断报告写成真实文件，
 * 支持列表 / 读取 / 删除 / 清空。目录在 filesDir/crash-reports，可被 FileProvider 分享。
 */
class CrashArchive(
    context: Context,
) {
    private val root = File(context.filesDir, "crash-reports").apply { mkdirs() }

    private val _reports = MutableStateFlow(listReports())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    val value: List<CrashReport> get() = _reports.value

    fun archive(
        versionId: String,
        exitCode: Int,
        logs: String,
        diagnostics: String,
    ): CrashReport? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val name = "crash-$stamp-${versionId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt"
        val f = File(root, name)
        f.writeText(
            buildString {
                appendLine("Aurora 崩溃报告")
                appendLine("版本：$versionId")
                appendLine("退出码：$exitCode")
                appendLine("时间：$stamp")
                appendLine()
                appendLine("==== 引擎日志快照 ====")
                appendLine(logs.takeLast(200_000))
                appendLine()
                appendLine("==== 诊断报告 ====")
                appendLine(diagnostics)
            },
        )
        refresh()
        return _reports.value.firstOrNull { it.name == name }
    }

    fun read(report: CrashReport): String =
        runCatching {
            File(report.path).readText()
        }.getOrDefault("（报告不可读）")

    fun delete(report: CrashReport): Boolean {
        val ok = File(report.path).delete()
        refresh()
        return ok
    }

    fun clear(): Int {
        val n = root.listFiles().orEmpty().count { it.isFile && it.delete() }
        refresh()
        return n
    }

    fun refresh() {
        _reports.value = listReports()
    }

    private fun listReports(): List<CrashReport> =
        (root.listFiles().orEmpty())
            .filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".txt") }
            .map {
                CrashReport(
                    name = it.name,
                    path = it.absolutePath,
                    sizeBytes = it.length(),
                    at = it.lastModified(),
                )
            }.sortedByDescending { it.at }
}
