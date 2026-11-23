package com.yuanbao.mcjlauncher.core

/** Mojang version_manifest_v2.json 里的一个版本条目。 */
data class VersionEntry(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String,
)

/** 本地已安装版本（由 .mcjl-installed 标记文件与 version.json 共同确定）。 */
data class InstalledVersion(
    val id: String,
    val dir: String,
    val javaMajor: Int,
    val mainClass: String,
    val assetIndexId: String,
)

/** Java 运行时选择：AUTO 由 version.json 的 javaVersion.majorVersion 决定。 */
enum class JavaMode(
    val label: String,
) {
    AUTO("自动选择"),
    JRE8("JRE 8"),
    JRE17("JRE 17"),
    JRE21("JRE 21"),
    JRE25("JRE 25"),
    ;

    fun directory(): String? =
        when (this) {
            AUTO -> null
            JRE8 -> "jre8"
            JRE17 -> "jre17"
            JRE21 -> "jre21"
            JRE25 -> "jre25"
        }

    companion object {
        fun from(raw: String): JavaMode = entries.firstOrNull { it.name == raw } ?: AUTO

        /** Minecraft 版本 → 所需 Java major 的映射（与 Mojang 官方一致）。 */
        fun forGame(javaMajor: Int): String =
            when {
                javaMajor >= 25 -> "jre25"
                javaMajor >= 21 -> "jre21"
                javaMajor >= 17 -> "jre17"
                else -> "jre8"
            }
    }
}

/** 账号。accessToken 落盘时经 Android Keystore AES-GCM 加密。 */
data class GameAccount(
    val id: String,
    val name: String,
    val type: String, // "offline" | "microsoft"
    val tokenCipher: String, // 加密后的 accessToken（离线账号为空串）
    val note: String,
    val expiresAt: Long = 0L,
)

/** 运行时释放后的完整布局，供启动参数拼装使用。 */
data class RuntimeLayout(
    val javaHome: String,
    val javaBin: String,
    val jliCandidates: List<String>,
    val nativeLibDir: String,
    val jnaJar: String,
    val jnaNativeDir: String,
    val lwjglJars: List<String>,
    val lwjglNativeDir: String,
    val rendererDir: String,
    val caciocavalloJars: List<String>,
    val gl4esLib: String,
    val versionTag: String,
    /** SDL2 / SDL3 运行时目录：sdl/sdl2 与 sdl/sdl3。 */
    val sdlDir: String,
)

/** 交给 C++ 自研引擎的完整启动计划。 */
data class LaunchPlan(
    val versionId: String,
    val javaHome: String,
    val javaBin: String,
    val workDir: String,
    val gameDir: String,
    val classpath: String,
    val mainClass: String,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val env: Map<String, String>,
    val jliCandidates: List<String>,
    val launchMode: String,
    val logLevel: Int,
)

/** 安装进度（真实文件数与下载字节数驱动）。 */
data class InstallProgress(
    val versionId: String,
    val label: String,
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** C++ 引擎上报的真实运行统计。 */
data class EngineStats(
    val state: String,
    val pid: Int,
    val cpuPercent: Float,
    val rssKb: Long,
    val elapsedMs: Long,
    val exitCode: Int,
)

/** 每个版本独立实例的启动配置（落盘在 instances/<id>/instance.json）。 */
data class InstanceConfig(
    val versionId: String = "",
    val memoryMb: Int = 0, // 0 = 跟随全局设置
    val extraJvmArgs: List<String> = emptyList(),
    val extraGameArgs: List<String> = emptyList(),
    val extraEnv: Map<String, String> = emptyMap(),
    val preLaunch: String = "",
    val postLaunch: String = "",
    val notes: String = "",
    val createdAt: Long = 0L,
)

/** 实例概览：真实目录 + 真实字节占用 + 配置。 */
data class InstanceInfo(
    val id: String,
    val dir: String,
    val gameDir: String,
    val sizeBytes: Long,
    val config: InstanceConfig,
    val modCount: Int,
    val saveCount: Int,
    val packCount: Int,
)

/** 启动预检的单条结果。 */
data class PreflightItem(
    val label: String,
    val level: String, // OK / WARN / ERROR
    val detail: String,
)

/** 启动预检报告：ERROR 会阻止启动，WARN 只提示。 */
data class PreflightReport(
    val versionId: String,
    val items: List<PreflightItem>,
    val at: Long = System.currentTimeMillis(),
) {
    val errors: List<PreflightItem> get() = items.filter { it.level == "ERROR" }
    val warnings: List<PreflightItem> get() = items.filter { it.level == "WARN" }
    val ok: Boolean get() = errors.isEmpty()
    val summary: String get() = "${items.size} 项检查 · ${errors.size} 错误 / ${warnings.size} 警告"
}

/** 崩溃归档条目：对应 crash-reports 目录下的真实文件。 */
data class CrashReport(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val at: Long,
)

/** 下载源健康探测结果。 */
data class SourceHealth(
    val label: String,
    val url: String,
    val ok: Boolean,
    val elapsedMs: Long,
    val httpCode: Int,
)

/** 单个下载任务失败记录（重试耗尽后）。 */
data class InstallFailure(
    val label: String,
    val url: String,
    val message: String,
)

/** 最近安装记录。 */
data class RecentInstall(
    val id: String,
    val at: Long,
)
