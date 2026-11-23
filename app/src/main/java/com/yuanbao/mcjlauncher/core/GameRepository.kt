package com.yuanbao.mcjlauncher.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** version.json 解析产物。 */
data class VersionMeta(
    val id: String,
    val mainClass: String,
    val javaMajor: Int,
    val assetIndexId: String,
    val assetIndexUrl: String,
    val assetIndexSha1: String,
    val assets: String,
    val clientUrl: String,
    val clientSha1: String,
    val clientSize: Long,
    val libraries: List<LibArtifact>,
    val gameArgs: List<String>,
    val jvmArgs: List<String>,
    val legacyArgs: String,
)

data class LibArtifact(
    val name: String,
    val path: String,
    val url: String,
    val sha1: String,
    val size: Long,
    val isLwjgl: Boolean,
)

private data class FetchTask(
    val label: String,
    val url: String,
    val dest: File,
    val sha1: String?,
    val size: Long,
)

/**
 * 真实版本管理与安装：
 * version_manifest_v2 → version.json → client.jar / libraries / assetIndex / assets objects，
 * 全部带 SHA-1 校验、缓存复用与真实字节进度。
 */
class GameRepository(
    context: Context,
    private val settings: SettingsRepository,
    private val sources: SourceResolver,
) {
    private val base = File(context.filesDir, "game")
    private val versionsDir = File(base, "versions")
    private val librariesDir = File(base, "libraries")
    private val assetsRoot = File(base, "assets")

    /** 每个源各存一份清单缓存，切换源时互不影响；兼容旧版单文件名。 */
    private fun manifestCache(source: DownloadSource): File = File(base, "version_manifest_${source.raw.lowercase()}.json")

    private val legacyManifestCache = File(base, "version_manifest_v2.json")

    private val _manifestSource = MutableStateFlow<DownloadSource?>(null)
    val manifestSource: StateFlow<DownloadSource?> = _manifestSource.asStateFlow()

    // 冷启动不解析清单 JSON：由 [warmUp] 在 IO 线程填充，避免 Application.onCreate 卡顿。
    private val _manifest = MutableStateFlow(emptyList())
    val manifest: StateFlow<List<VersionEntry>> = _manifest.asStateFlow()

    private val _installed = MutableStateFlow(emptyList<InstalledVersion>())
    val installed: StateFlow<List<InstalledVersion>> = _installed.asStateFlow()

    private val _progress = MutableStateFlow<InstallProgress?>(null)
    val progress: StateFlow<InstallProgress?> = _progress.asStateFlow()

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    /** 重试耗尽后的失败任务清单，供 UI 展示与重试。 */
    private val _failures = MutableStateFlow<List<InstallFailure>>(emptyList())
    val failures: StateFlow<List<InstallFailure>> = _failures.asStateFlow()

    @Volatile
    private var currentInstallJob: Job? = null

    val value: List<VersionEntry> get() = _manifest.value

    /** 取消当前安装（真正取消协程，下载会在下一个检查点停下）。 */
    fun cancelCurrent(): Boolean {
        val job = currentInstallJob ?: return false
        job.cancel(CancellationException("用户取消安装"))
        return true
    }

    fun clearFailures() {
        _failures.value = emptyList()
    }

    fun note(text: String) {
        _notes.value = text
    }

    /** 冷启动预热：在 IO 线程恢复清单缓存与已安装列表，不阻塞主线程首帧。 */
    suspend fun warmUp() =
        withContext(Dispatchers.IO) {
            _manifest.value = readAnyManifestCache()
            _installed.value = scanInstalled()
        }

    /**
     * 按当前下载源联网刷新版本清单。
     * 切换「官方源 / 镜像源」时会由 LauncherContainer 自动调用，保证列表来自对应源；
     * AUTO 则先探测官方速度再决定用哪个源。失败时回退该源的本地缓存。
     */
    suspend fun refreshManifest(): Result<List<VersionEntry>> =
        withContext(Dispatchers.IO) {
            val source = sources.resolve()
            val label = sources.info.value.summary
            runCatching {
                val url =
                    DownloadSources.withCustomMirror(
                        DownloadSources.manifestUrl(source),
                        settings.value.customMirrorUrl,
                    )
                val body =
                    Http.get(url).let { r ->
                        if (!r.ok) error("HTTP ${r.code} @ $url")
                        r.body
                    }
                base.mkdirs()
                manifestCache(source).writeText(body)
                val list = parseManifest(body)
                _manifest.value = list
                _manifestSource.value = source
                _installed.value = scanInstalled()
                note("清单已更新：${list.size} 个版本 · 来源 $label")
                list
            }.onFailure { e ->
                note("清单刷新失败（$label），使用本地缓存：${e.message}")
                _manifest.value = readManifestCache(source)
            }
        }

    /**
     * 安装一个版本（幂等：已校验通过的文件跳过）。
     *
     * 本轮扩展：库/资源并发下载（并发数可设）、单任务失败自动重试（次数可设）、
     * 自定义镜像根地址、可取消、失败任务清单汇总。
     */
    suspend fun install(id: String): Result<InstalledVersion> =
        withContext(Dispatchers.IO) {
            currentInstallJob = currentCoroutineContext().job
            try {
                runCatching {
                    val source = sources.resolve()
                    val sourceLabel = sources.info.value.summary
                    val customMirror = settings.value.customMirrorUrl
                    val concurrency = settings.value.downloadConcurrency.coerceIn(1, 16)
                    val retries = settings.value.downloadRetries.coerceIn(0, 5)
                    val semaphore = Semaphore(concurrency)

                    fun mirror(u: String): String = DownloadSources.withCustomMirror(u, customMirror)

                    val entry =
                        _manifest.value.firstOrNull { it.id == id }
                            ?: error("版本清单中没有 $id，请先刷新清单")
                    val versionDir = File(versionsDir, id).apply { mkdirs() }
                    val metaFile = File(versionDir, "$id.json")

                    note("下载 $id 的 version.json · 来源 $sourceLabel")
                    Http.download(
                        mirror(DownloadSources.mapUrl(entry.url, source)),
                        metaFile,
                        entry.sha1.takeIf { it.isNotBlank() },
                        -1L,
                        settings.value.verifyAssets,
                    )
                    val meta = parseVersion(metaFile.readText())

                    val tasks = mutableListOf<FetchTask>()
                    tasks +=
                        FetchTask(
                            "client.jar",
                            mirror(DownloadSources.mapUrl(meta.clientUrl, source)),
                            File(versionDir, "$id.jar"),
                            meta.clientSha1.takeIf { it.isNotBlank() },
                            meta.clientSize,
                        )
                    meta.libraries.forEach { lib ->
                        if (lib.url.isBlank()) return@forEach
                        tasks +=
                            FetchTask(
                                lib.name,
                                mirror(DownloadSources.mapUrl(lib.url, source)),
                                File(librariesDir, lib.path),
                                lib.sha1.takeIf { it.isNotBlank() },
                                lib.size,
                            )
                    }
                    val indexFile = File(assetsRoot, "indexes/${meta.assetIndexId}.json")
                    tasks +=
                        FetchTask(
                            "assetIndex/${meta.assetIndexId}",
                            mirror(DownloadSources.mapUrl(meta.assetIndexUrl, source)),
                            indexFile,
                            meta.assetIndexSha1.takeIf { it.isNotBlank() },
                            -1L,
                        )

                    val totalBytes = tasks.sumOf { if (it.size > 0) it.size else 0L }
                    val bytesDone = AtomicLong(0L)
                    val done = AtomicInteger(0)
                    val failed = ConcurrentLinkedQueue<InstallFailure>()
                    // 进度节流：按「≥120ms 或 ≥512KB」发射，文件开始/结束各强制刷新一次。
                    // 并发下所有进度状态都在 progressLock 内读写，避免竞态。
                    val progressLock = Any()
                    var lastProgressAt = 0L
                    var lastProgressBytes = 0L

                    fun emitProgress(
                        label: String,
                        force: Boolean,
                    ) {
                        synchronized(progressLock) {
                            val now = System.nanoTime() / 1_000_000L
                            val current = bytesDone.get()
                            if (!force && now - lastProgressAt < 120L &&
                                current - lastProgressBytes < 512L * 1024L
                            ) {
                                return
                            }
                            lastProgressAt = now
                            lastProgressBytes = current
                            _progress.value = InstallProgress(id, label, done.get(), tasks.size, current, totalBytes)
                        }
                    }

                    // 大文件先起、小文件填空隙，避免最大文件落在队尾形成长尾。
                    val orderedTasks = tasks.sortedByDescending { it.size }
                    supervisorScope {
                        orderedTasks
                            .map { t ->
                                async {
                                    semaphore.withPermit {
                                        ensureActive()
                                        var attempt = 0
                                        while (true) {
                                            try {
                                                val written =
                                                    Http.download(
                                                        t.url,
                                                        t.dest,
                                                        t.sha1,
                                                        t.size,
                                                        settings.value.verifyAssets,
                                                        parallelSegments = 4,
                                                    ) { n ->
                                                        bytesDone.addAndGet(n)
                                                        emitProgress("下载 ${t.label}", false)
                                                    }
                                                if (written == 0L && t.size > 0L) bytesDone.addAndGet(t.size)
                                                done.incrementAndGet()
                                                emitProgress("下载 ${t.label}", true)
                                                break
                                            } catch (ce: CancellationException) {
                                                throw ce
                                            } catch (e: Exception) {
                                                if (attempt >= retries) {
                                                    failed.add(InstallFailure(t.label, t.url, e.message ?: "下载失败"))
                                                    note("${t.label} 重试 ${attempt + 1} 次后失败：${e.message}")
                                                    break
                                                }
                                                attempt++
                                                // 指数退避 + 抖动，避免多个失败任务同时重试打爆 CDN。
                                                delay(
                                                    (200L shl attempt.coerceAtMost(3)) +
                                                        ThreadLocalRandom.current().nextLong(0, 120),
                                                )
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                    }

                    // 资产索引 → 分批并发下载资源对象（真实 Minecraft 资源，约数千个文件）
                    val objects = parseAssetIndex(indexFile.readText())
                    val objDone = AtomicInteger(0)
                    val objectFailures = ConcurrentLinkedQueue<InstallFailure>()
                    val objLock = Any()
                    var lastObjAt = 0L
                    // 不再按 64 个一批 awaitAll：整批铺开由 semaphore 恒定控并发，
                    // 消除「每批最慢的一个拖住整批」的屏障空转；连接复用让数千次请求共享握手。
                    supervisorScope {
                        objects
                            .map { (relPath, hash) ->
                                async {
                                    semaphore.withPermit {
                                        ensureActive()
                                        val target = File(assetsRoot, "objects/${hash.substring(0, 2)}/$hash")
                                        if (!target.isFile || target.length() == 0L) {
                                            val url = mirror(DownloadSources.assetObjectUrl(hash, source))
                                            runCatching {
                                                Http.download(url, target, hash, -1L, settings.value.verifyAssets)
                                            }.onFailure { e ->
                                                objectFailures.add(
                                                    InstallFailure("资源 $relPath", url, e.message ?: "下载失败"),
                                                )
                                            }
                                        }
                                        val n = objDone.incrementAndGet()
                                        val now = System.nanoTime() / 1_000_000L
                                        var emit = n == objects.size
                                        synchronized(objLock) {
                                            if (n % 25 == 0 && now - lastObjAt >= 120L) {
                                                lastObjAt = now
                                                emit = true
                                            }
                                        }
                                        if (emit) {
                                            _progress.value =
                                                InstallProgress(
                                                    id,
                                                    "资源文件 $n/${objects.size}",
                                                    done.get(),
                                                    tasks.size + objects.size,
                                                    bytesDone.get(),
                                                    totalBytes,
                                                )
                                        }
                                    }
                                }
                            }.awaitAll()
                    }

                    _failures.value = failed.toList() + objectFailures.toList()

                    val marker = File(versionDir, ".mcjl-installed")
                    marker.writeText(
                        JSONObject()
                            .apply {
                                put("id", meta.id)
                                put("javaMajor", meta.javaMajor)
                                put("mainClass", meta.mainClass)
                                put("assetIndexId", meta.assetIndexId)
                                put("installedAt", System.currentTimeMillis())
                            }.toString(),
                    )
                    _progress.value =
                        InstallProgress(
                            id,
                            "安装完成",
                            tasks.size + objects.size,
                            tasks.size + objects.size,
                            bytesDone.get(),
                            totalBytes,
                        )
                    note(
                        "$id 安装完成：${meta.libraries.size} 个库 / ${objects.size} 个资源 / Java ${meta.javaMajor}" +
                            " / 并发 $concurrency / 大文件最多 4 段 / 失败 ${_failures.value.size} / 来源 $sourceLabel",
                    )
                    _installed.value = scanInstalled()
                    val result =
                        InstalledVersion(
                            id = id,
                            dir = versionDir.absolutePath,
                            javaMajor = meta.javaMajor,
                            mainClass = meta.mainClass,
                            assetIndexId = meta.assetIndexId,
                        )
                    _progress.value = null
                    result
                }.onFailure { e ->
                    _progress.value = null
                    if (e is CancellationException) {
                        note("安装 $id 已取消")
                        throw e
                    }
                    note("安装 $id 失败：${e.message}")
                }
            } finally {
                currentInstallJob = null
            }
        }

    fun delete(id: String): Boolean {
        val ok = File(versionsDir, id).deleteRecursively()
        _installed.value = scanInstalled()
        note(if (ok) "已删除本地版本 $id" else "删除 $id 失败")
        return ok
    }

    /** 导出当前生效清单为真实 JSON 文件（供备份 / 分享）。 */
    fun exportManifest(): File {
        val dir = File(base, "exports").apply { mkdirs() }
        val f = File(dir, "version_manifest-${System.currentTimeMillis()}.json")
        f.writeText(
            JSONObject()
                .apply {
                    put("source", manifestSource.value?.raw ?: "UNKNOWN")
                    put("exportedAt", System.currentTimeMillis())
                    put(
                        "versions",
                        JSONArray().apply {
                            _manifest.value.forEach { v ->
                                put(
                                    JSONObject().apply {
                                        put("id", v.id)
                                        put("type", v.type)
                                        put("url", v.url)
                                        put("time", v.time)
                                        put("releaseTime", v.releaseTime)
                                        put("sha1", v.sha1)
                                    },
                                )
                            }
                        },
                    )
                }.toString(2),
        )
        note("清单已导出：${f.absolutePath}（${_manifest.value.size} 个版本）")
        return f
    }

    /** 单个已安装版本的真实磁盘占用。 */
    fun diskUsage(id: String): Long = sizeOf(File(versionsDir, id))

    private fun sizeOf(f: File): Long {
        if (f.isFile) return f.length()
        return f.listFiles().orEmpty().sumOf { sizeOf(it) }
    }

    fun isInstalled(id: String): Boolean = File(versionsDir, "$id/.mcjl-installed").isFile

    fun meta(id: String): VersionMeta? =
        runCatching {
            parseVersion(File(versionsDir, "$id/$id.json").readText())
        }.getOrNull()

    fun versionDir(id: String): File = File(versionsDir, id)

    fun gameAssetsDir(): File = assetsRoot

    fun librariesRoot(): File = librariesDir

    /** 拼 classpath：桌面 LWJGL 被排除，换成 assets 里的 Android 定制版。 */
    fun classpath(
        id: String,
        layout: RuntimeLayout,
    ): String {
        val m = meta(id) ?: return ""
        val jars = mutableListOf<String>()
        m.libraries.filterNot { it.isLwjgl }.forEach { lib ->
            val f = File(librariesDir, lib.path)
            if (f.isFile) jars += f.absolutePath
        }
        File(versionsDir, "$id/$id.jar").takeIf { it.isFile }?.let { jars += it.absolutePath }
        jars += layout.lwjglJars
        jars += layout.caciocavalloJars
        if (File(layout.jnaJar).isFile) jars += layout.jnaJar
        return jars.joinToString(File.pathSeparator)
    }

    private fun parseManifest(body: String): List<VersionEntry> {
        val arr = JSONObject(body).getJSONArray("versions")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VersionEntry(
                id = o.getString("id"),
                type = o.optString("type", "release"),
                url = o.optString("url", ""),
                time = o.optString("time", ""),
                releaseTime = o.optString("releaseTime", ""),
                sha1 = o.optString("sha1", ""),
            )
        }
    }

    private fun parseCache(file: File): List<VersionEntry> {
        if (!file.isFile) return emptyList()
        return runCatching { parseManifest(file.readText()) }.getOrDefault(emptyList())
    }

    private fun readManifestCache(source: DownloadSource): List<VersionEntry> = parseCache(manifestCache(source)).ifEmpty { parseCache(legacyManifestCache) }

    /** 冷启动：优先显示当前配置源对应的缓存，其次旧文件名，最后另一源的缓存。 */
    private fun readAnyManifestCache(): List<VersionEntry> {
        val primary =
            if (sources.configured() == DownloadSource.MIRROR) {
                DownloadSource.MIRROR
            } else {
                DownloadSource.OFFICIAL
            }
        val secondary = if (primary == DownloadSource.MIRROR) DownloadSource.OFFICIAL else DownloadSource.MIRROR
        val ordered =
            listOf(
                primary to manifestCache(primary),
                DownloadSource.OFFICIAL to legacyManifestCache,
                secondary to manifestCache(secondary),
            )
        for ((source, file) in ordered) {
            val list = parseCache(file)
            if (list.isNotEmpty()) {
                _manifestSource.value = source
                return list
            }
        }
        return emptyList()
    }

    private fun scanInstalled(): List<InstalledVersion> =
        (versionsDir.listFiles().orEmpty())
            .filter { it.isDirectory && File(it, ".mcjl-installed").isFile }
            .mapNotNull { dir ->
                runCatching {
                    val j = JSONObject(File(dir, ".mcjl-installed").readText())
                    InstalledVersion(
                        id = j.getString("id"),
                        dir = dir.absolutePath,
                        javaMajor = j.optInt("javaMajor", 8),
                        mainClass = j.optString("mainClass", "net.minecraft.client.main.Main"),
                        assetIndexId = j.optString("assetIndexId", ""),
                    )
                }.getOrNull()
            }.sortedByDescending { it.id }

    private fun parseVersion(body: String): VersionMeta {
        val j = JSONObject(body)
        val downloads = j.optJSONObject("downloads")
        val client = downloads?.optJSONObject("client")
        val assetIndex = j.optJSONObject("assetIndex")

        val libs = mutableListOf<LibArtifact>()
        val libArr = j.optJSONArray("libraries") ?: JSONArray()
        (0 until libArr.length()).forEach { i ->
            val o = libArr.getJSONObject(i)
            if (!rulesAllow(o.optJSONArray("rules"))) return@forEach
            val name = o.optString("name", "")
            val artifact = o.optJSONObject("downloads")?.optJSONObject("artifact") ?: return@forEach
            val path = artifact.optString("path", "")
            val url = artifact.optString("url", "")
            if (path.isBlank() || url.isBlank()) return@forEach
            libs +=
                LibArtifact(
                    name = name,
                    path = path,
                    url = url,
                    sha1 = artifact.optString("sha1", ""),
                    size = artifact.optLong("size", -1L),
                    isLwjgl = name.startsWith("org.lwjgl"),
                )
        }

        val arguments = j.optJSONObject("arguments")
        val gameArgs = mutableListOf<String>()
        val jvmArgs = mutableListOf<String>()
        arguments?.optJSONArray("game")?.let { a -> gameArgs += flattenArgs(a) }
        arguments?.optJSONArray("jvm")?.let { a -> jvmArgs += flattenArgs(a) }

        return VersionMeta(
            id = j.getString("id"),
            mainClass = j.optString("mainClass", "net.minecraft.client.main.Main"),
            javaMajor = j.optJSONObject("javaVersion")?.optInt("majorVersion", 8) ?: 8,
            assetIndexId = assetIndex?.optString("id", j.optString("assets", "")) ?: j.optString("assets", ""),
            assetIndexUrl = assetIndex?.optString("url", "") ?: "",
            assetIndexSha1 = assetIndex?.optString("sha1", "") ?: "",
            assets = j.optString("assets", ""),
            clientUrl = client?.optString("url", "") ?: "",
            clientSha1 = client?.optString("sha1", "") ?: "",
            clientSize = client?.optLong("size", -1L) ?: -1L,
            libraries = libs,
            gameArgs = gameArgs,
            jvmArgs = jvmArgs,
            legacyArgs = j.optString("minecraftArguments", ""),
        )
    }

    /** 新版 arguments 数组里元素可能是字符串，也可能是带 rules 的对象。 */
    private fun flattenArgs(arr: JSONArray): List<String> {
        val out = mutableListOf<String>()
        (0 until arr.length()).forEach { i ->
            when (val e = arr.get(i)) {
                is String -> {
                    out += e
                }

                is JSONObject -> {
                    if (rulesAllow(e.optJSONArray("rules"))) {
                        val value = e.opt("value")
                        when (value) {
                            is String -> {
                                out += value
                            }

                            is JSONArray -> {
                                (0 until value.length()).forEach { k ->
                                    (value.opt(k) as? String)?.let { out += it }
                                }
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /** Mojang rule 求值：Pojav 系启动器在 Android 上以 linux 身份匹配。 */
    private fun rulesAllow(rules: JSONArray?): Boolean {
        if (rules == null || rules.length() == 0) return true
        var allowed = false
        (0 until rules.length()).forEach { i ->
            val r = rules.getJSONObject(i)
            val action = r.optString("action", "allow")
            val os = r.optJSONObject("os")
            var osMatch = true
            if (os != null) {
                val name = os.optString("name", "")
                if (name.isNotBlank() && !name.equals("linux", true) && !name.equals("android", true)) osMatch = false
                val arch = os.optString("arch", "")
                if (arch.isNotBlank() && arch != "x86" && arch != "aarch64") osMatch = false
            }
            val features = r.optJSONObject("features")
            val featureMatch =
                features == null || features.length() == 0 ||
                    run {
                        // 自定义分辨率开启，其余 feature 默认视为不满足
                        var ok = true
                        features.keys().forEach { k ->
                            if (k != "has_custom_resolution" && features.optBoolean(k, false)) ok = false
                        }
                        ok
                    }
            if (osMatch && featureMatch) allowed = action == "allow"
        }
        return allowed
    }

    private fun parseAssetIndex(body: String): List<Pair<String, String>> {
        val objects = JSONObject(body).optJSONObject("objects") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        objects.keys().forEach { key ->
            val hash = objects.getJSONObject(key).optString("hash", "")
            if (hash.length >= 2) out += key to hash
        }
        return out
    }
}
