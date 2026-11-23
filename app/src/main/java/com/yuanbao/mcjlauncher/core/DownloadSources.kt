package com.yuanbao.mcjlauncher.core

import com.yuanbao.mcjlauncher.download.LauncherArtifacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 下载源选择。AUTO 为默认：官方源缓慢/不可达时自动切到镜像源。 */
enum class DownloadSource(
    val raw: String,
    val label: String,
    val desc: String,
) {
    AUTO("AUTO", "自动", "官方源响应慢或不可达时自动切到镜像源"),
    OFFICIAL("OFFICIAL", "官方源", "Mojang / piston-meta 官方端点"),
    MIRROR("MIRROR", "镜像源", "BMCLAPI 国内镜像（bmclapi2.bangbang93.com）"),
    ;

    companion object {
        fun from(raw: String): DownloadSource = entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

/** 当前下载源的实时状态，供设置页 / 版本库 / 关于页显示。 */
data class SourceInfo(
    val configured: DownloadSource = DownloadSource.AUTO,
    val effective: DownloadSource = DownloadSource.OFFICIAL,
    val reason: String = "尚未探测",
    val probeMs: Long = -1L,
    val probedAt: Long = 0L,
) {
    val summary: String
        get() =
            if (configured == DownloadSource.AUTO) {
                "自动 → ${effective.label}（$reason）"
            } else {
                "${configured.label}（手动指定）"
            }
}

/**
 * 官方源 ↔ BMCLAPI 镜像的 URL 映射。
 *
 * BMCLAPI 100% 兼容官方目录结构，但**镜像返回的版本清单里 url 字段仍是官方域名**
 * （为保证文件哈希一致不改写内容），所以必须由启动器本地做域名重写。
 * 规则依据 BMCLAPI 官方文档，并补齐新版 piston-meta / piston-data 域名。
 */
object DownloadSources {
    val OFFICIAL_MANIFEST: String = LauncherArtifacts.VERSION_MANIFEST_URL
    const val MIRROR_HOST = "https://bmclapi2.bangbang93.com"
    const val MIRROR_MANIFEST = "$MIRROR_HOST/mc/game/version_manifest_v2.json"

    private const val OFFICIAL_ASSETS = "https://resources.download.minecraft.net"

    /** 长前缀必须排在短前缀之前，避免 "launchermeta" 抢先匹配。 */
    private val REPLACEMENTS: List<Pair<String, String>> =
        listOf(
            "https://resources.download.minecraft.net" to "$MIRROR_HOST/assets",
            "http://resources.download.minecraft.net" to "$MIRROR_HOST/assets",
            "https://libraries.minecraft.net" to "$MIRROR_HOST/maven",
            "http://libraries.minecraft.net" to "$MIRROR_HOST/maven",
            "https://piston-meta.mojang.com" to MIRROR_HOST,
            "https://piston-data.mojang.com" to MIRROR_HOST,
            "https://launchermeta.mojang.com" to MIRROR_HOST,
            "http://launchermeta.mojang.com" to MIRROR_HOST,
            "https://launcher.mojang.com" to MIRROR_HOST,
            "http://launcher.mojang.com" to MIRROR_HOST,
        )

    fun manifestUrl(source: DownloadSource): String =
        when (source) {
            DownloadSource.MIRROR -> MIRROR_MANIFEST
            else -> OFFICIAL_MANIFEST
        }

    fun assetObjectUrl(
        hash: String,
        source: DownloadSource,
    ): String = mapUrl("$OFFICIAL_ASSETS/${hash.substring(0, 2)}/$hash", source)

    /**
     * 自定义镜像：把已经映射到 BMCLAPI 的 URL 换到用户填写的镜像根地址。
     * 用户在设置里填 `https://mirror.example.com`（可带路径），只换 host 前缀，
     * `/maven`、`/assets`、`/mc/...` 等官方兼容路径保持不变。
     * 空值原样返回，因此所有既有映射与 SHA-1 校验逻辑都不受影响。
     */
    fun withCustomMirror(
        url: String,
        customMirror: String,
    ): String {
        val host = customMirror.trim().trimEnd('/')
        if (host.isBlank()) return url
        if (!host.startsWith("http://") && !host.startsWith("https://")) return url
        return url.replace(MIRROR_HOST, host)
    }

    /** 自定义镜像生效时的清单地址（供健康探测与诊断显示）。 */
    fun customManifestUrl(customMirror: String): String = withCustomMirror(MIRROR_MANIFEST, customMirror)

    /** 把任意官方下载 URL 改写到镜像源；官方源或无法识别的 URL 原样返回。 */
    fun mapUrl(
        url: String,
        source: DownloadSource,
    ): String {
        if (source != DownloadSource.MIRROR || url.isBlank()) return url
        if (url.startsWith(MIRROR_HOST)) return url
        for ((official, mirror) in REPLACEMENTS) {
            if (url.startsWith(official)) return mirror + url.removePrefix(official)
        }
        return url
    }
}

/** 一次轻量探测的结果。 */
data class ProbeResult(
    val ok: Boolean,
    val elapsedMs: Long,
    val httpCode: Int,
    val error: String = "",
)

/**
 * 下载源决策器。
 *
 * - 手动指定 OFFICIAL / MIRROR：直接生效；
 * - AUTO：用 [Http.probe] 对官方版本清单做一次带 Range 的轻量探测，
 *   超过阈值（设置里的 autoProbeMs）或连接失败/超时 → 判定"缓慢"并切到镜像源；
 *   决策缓存 5 分钟，避免每次下载都探测。切换设置会调用 [invalidate] 清空。
 *
 * [resolve] 会发真实网络请求，只能在 Dispatchers.IO 调用；UI 只读 [info]。
 */
class SourceResolver(
    private val settings: SettingsRepository,
) {
    private val _info = MutableStateFlow(SourceInfo())
    val info: StateFlow<SourceInfo> = _info.asStateFlow()

    /** 工具页「网络自检」的真实结果：官方与镜像各探测一次。 */
    private val _health = MutableStateFlow<List<SourceHealth>>(emptyList())
    val health: StateFlow<List<SourceHealth>> = _health.asStateFlow()

    @Volatile
    private var cached: Pair<DownloadSource, Long>? = null

    private val cacheTtlMs = 5 * 60 * 1000L

    fun configured(): DownloadSource = DownloadSource.from(settings.value.downloadSource)

    fun invalidate() {
        cached = null
    }

    /** 真联网双端探测：返回每端 HTTP 状态与耗时，并写入 [health] 供 UI 显示。 */
    suspend fun healthCheck(): List<SourceHealth> =
        withContext(Dispatchers.IO) {
            val threshold = settings.value.autoProbeMs.coerceIn(300, 10_000)
            val mirrorUrl = DownloadSources.customManifestUrl(settings.value.customMirrorUrl)
            val official = Http.probe(DownloadSources.OFFICIAL_MANIFEST, threshold)
            val mirror = Http.probe(mirrorUrl, threshold)
            val list =
                listOf(
                    SourceHealth(
                        label = "官方源",
                        url = DownloadSources.OFFICIAL_MANIFEST,
                        ok = official.ok,
                        elapsedMs = official.elapsedMs,
                        httpCode = official.httpCode,
                    ),
                    SourceHealth(
                        label = "镜像源",
                        url = mirrorUrl,
                        ok = mirror.ok,
                        elapsedMs = mirror.elapsedMs,
                        httpCode = mirror.httpCode,
                    ),
                )
            _health.value = list
            list
        }

    fun resolve(): DownloadSource {
        val configured = configured()
        if (configured != DownloadSource.AUTO) {
            publish(configured, configured, "手动指定", -1L, System.currentTimeMillis())
            return configured
        }

        val now = System.currentTimeMillis()
        cached?.let { (source, at) ->
            if (now - at < cacheTtlMs) {
                publish(configured, source, "沿用 5 分钟内的自动决策", -1L, at)
                return source
            }
        }

        val threshold = settings.value.autoProbeMs.coerceIn(300, 10_000)
        val probe = Http.probe(DownloadSources.OFFICIAL_MANIFEST, threshold)
        val chosen = if (probe.ok) DownloadSource.OFFICIAL else DownloadSource.MIRROR
        cached = chosen to now

        val reason =
            if (probe.ok) {
                "官方响应 ${probe.elapsedMs}ms ≤ ${threshold}ms，保持官方源"
            } else {
                val detail =
                    when {
                        probe.httpCode > 0 -> "官方 HTTP ${probe.httpCode}（不可用，${probe.elapsedMs}ms）"
                        probe.error.isNotBlank() -> "官方 ${probe.error}（${probe.elapsedMs}ms）"
                        else -> "官方响应 ${probe.elapsedMs}ms 超过阈值"
                    }
                "$detail → 切到镜像源（阈值 ${threshold}ms）"
            }
        publish(configured, chosen, reason, probe.elapsedMs, now)
        return chosen
    }

    private fun publish(
        configured: DownloadSource,
        effective: DownloadSource,
        reason: String,
        probeMs: Long,
        at: Long,
    ) {
        val next = SourceInfo(configured, effective, reason, probeMs, at)
        if (_info.value != next) _info.value = next
    }
}
