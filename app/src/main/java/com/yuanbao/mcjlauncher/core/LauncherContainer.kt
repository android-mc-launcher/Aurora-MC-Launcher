package com.yuanbao.mcjlauncher.core

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.yuanbao.mcjlauncher.engine.GameStartResult
import com.yuanbao.mcjlauncher.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全应用唯一的依赖容器：设置 / 账号 / 版本 / 运行时 / 引擎日志与统计总线。
 * 所有 UI 通过 [LocalLauncherContainer] 取到同一份真实状态，不再各自 remember 假数据。
 */
class LauncherContainer(
    private val app: Application,
) {
    val settings = SettingsRepository(app)
    val accounts = AccountRepository(app, settings)
    val sources = SourceResolver(settings)
    val games = GameRepository(app, settings, sources)
    val runtime = RuntimeRepository(app)

    // 本轮大幅扩展：独立实例、收藏/历史、日志中心、崩溃归档、安装队列、事件流。
    val instances = InstanceRepository(app)
    val extras = ExtrasRepository(app)
    val logCenter = LogCenter(app)
    val crashes = CrashArchive(app)
    val gamepads = SdlGamepadRepository()
    val perfHistory = PerfHistory()
    val commands = CommandBook(app)
    val content = ContentRepository(instances)
    val profiles = ProfileRepository
    private val _sdlInfo = MutableStateFlow<SdlResolution?>(null)
    val sdlInfo: StateFlow<SdlResolution?> = _sdlInfo.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _preflight = MutableStateFlow<PreflightReport?>(null)
    val preflight: StateFlow<PreflightReport?> = _preflight.asStateFlow()

    private val _installQueue = MutableStateFlow<List<String>>(emptyList())
    val installQueue: StateFlow<List<String>> = _installQueue.asStateFlow()

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    private val installChannel = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var lastVersionId: String = ""

    @Volatile
    private var lastLaunchAt: Long = 0L

    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs.asStateFlow()

    /**
     * 日志权威缓冲：只在这里做一次裁剪与一次 toString。
     * 旧实现是 `(_logs.value + line).takeLast(keep)`，每 200ms 复制两遍最多 20000 字符的字符串，
     * 启动期间几十条日志还会各自触发一次全应用重组。现在改成「追加 → 标脏 → 泵统一发布」。
     */
    private val logLock = Any()
    private val logBuffer = StringBuilder(16 * 1024)

    @Volatile
    private var logDirty = false

    private val idleStats = EngineStats("IDLE", -1, 0f, 0L, 0L, -1)
    private val _stats = MutableStateFlow(idleStats)
    val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    init {
        // 清单 JSON 解析放在 IO，避免冷启动首帧被阻塞。
        scope.launch(Dispatchers.IO) { games.warmUp() }
        startPumps()
        observeDownloadSourceChanges()
        startInstallWorker()
        pushEvent("启动器就绪")
    }

    /**
     * 切换「官方源 / 镜像源 / 自动」或调整慢速阈值时：清空自动决策缓存，
     * 立即从新生效的源重新拉取版本清单，让列表与设置保持一致。
     */
    private fun observeDownloadSourceChanges() {
        scope.launch {
            var previous = settings.value.downloadSource to settings.value.autoProbeMs
            settings.state.collect { s ->
                val current = s.downloadSource to s.autoProbeMs
                if (current == previous) return@collect
                previous = current
                val label = DownloadSource.from(s.downloadSource).label
                sources.invalidate()
                appendLogInteractive("[下载源] 切换为「$label」(阈值 ${s.autoProbeMs}ms)，正在重新拉取版本清单…\n")
                games
                    .refreshManifest()
                    .onSuccess { list ->
                        appendLog("[下载源] ${sources.info.value.summary} · 清单 ${list.size} 个版本\n")
                    }.onFailure { e ->
                        appendLog("[下载源] 清单刷新失败：${e.message}\n")
                    }
            }
        }
    }

    /** 串行安装队列：批量加入的版本按 FIFO 真实逐个安装，不并发抢同一个进度总线。 */
    private fun startInstallWorker() {
        scope.launch {
            for (id in installChannel) {
                _installQueue.value = _installQueue.value.drop(1)
                appendLogInteractive("[队列] 开始安装 $id（待装 ${_installQueue.value.size}）\n")
                games
                    .install(id)
                    .onSuccess {
                        extras.addRecent(id)
                        instances.ensure(id)
                        pushEvent("安装完成 $id")
                    }.onFailure { e -> pushEvent("安装失败 $id：${e.message}") }
            }
        }
    }

    fun enqueueInstall(ids: List<String>) {
        val valid = ids.filter { it.isNotBlank() }
        valid.forEach { installChannel.trySend(it) }
        _installQueue.value = _installQueue.value + valid
        appendLogInteractive("[队列] 加入 ${valid.size} 个版本，共 ${_installQueue.value.size} 个待装\n")
        pushEvent("加入安装队列：${valid.joinToString()}")
    }

    fun install(id: String) = enqueueInstall(listOf(id))

    /** 清空待装队列并真正取消当前下载协程。 */
    fun cancelInstall(): Boolean {
        val pending = _installQueue.value.size
        while (installChannel.tryReceive().getOrNull() != null) { /* drain */ }
        _installQueue.value = emptyList()
        val cancelled = games.cancelCurrent()
        appendLogInteractive("[队列] 清空 $pending 个待装 · 当前任务取消=$cancelled\n")
        pushEvent("取消安装队列")
        return cancelled || pending > 0
    }

    fun resumeVersion(): String =
        extras.lastPlayable(
            games.installed.value
                .map { it.id }
                .toSet(),
        )

    private fun pushEvent(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _events.value = (listOf("$stamp  $text") + _events.value).take(30)
    }

    /** 实例级 preLaunch / postLaunch：真实起一个 sh 进程，退出码写进日志。 */
    private fun runHook(
        cmd: String,
        tag: String,
    ) {
        if (cmd.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
                val code = p.waitFor()
                appendLogInteractive("[钩子] $tag 退出码 $code\n")
            }.onFailure { e ->
                appendLogInteractive("[钩子] $tag 执行失败：${e.message}\n")
            }
        }
    }

    /** 游戏进程退出：归档崩溃报告、记录统计、执行 postLaunch 钩子。 */
    private fun onGameExited(statsNow: EngineStats) {
        val id = lastVersionId
        val elapsed = if (lastLaunchAt > 0L) System.currentTimeMillis() - lastLaunchAt else statsNow.elapsedMs
        if (statsNow.exitCode != 0 && settings.value.crashArchiveEnabled) {
            val report =
                runCatching {
                    crashes.archive(id, statsNow.exitCode, _logs.value, Diagnostics.report(app, this))
                }.getOrNull()
            appendLogInteractive(
                "[守护] 非正常退出 code=${statsNow.exitCode} · 崩溃报告 ${report?.name ?: "写入失败"}\n",
            )
        }
        extras.markLaunched(id, statsNow.exitCode, elapsed)
        if (id.isNotBlank()) {
            val post = instances.get(id).postLaunch
            if (post.isNotBlank()) runHook(post, "postLaunch:$id")
        }
        pushEvent("进程退出 code=${statsNow.exitCode} · ${id.ifBlank { "未知版本" }}")
        lastLaunchAt = 0L
    }

    /**
     * 集中轮询 C++ 引擎，广播给主界面与悬浮窗，避免两边争抢同一个 pipe。
     *
     * 流畅度要点：
     * - 空闲时降到 500ms、运行时 200ms，少一半 JNI 调用；
     * - 空闲不解析 stats JSON（只在运行中与「刚退出的那一拍」采样，保留最终退出码）；
     * - 统计值相等时不写 StateFlow，避免无意义重组；
     * - 日志合批：一轮里所有 appendLog 只产生一次 StateFlow 发射。
     */
    private fun startPumps() {
        scope.launch {
            var wasRunning = false
            while (isActive) {
                val line = runCatching { NativeEngine.nativePollLog() }.getOrDefault("")
                if (line.isNotEmpty()) {
                    gamepads.consume(line)
                    appendLog(line)
                }
                val runningNow = runCatching { NativeEngine.nativeIsRunning() }.getOrDefault(false)
                _running.value = runningNow
                if (runningNow || wasRunning) {
                    val next =
                        runCatching { NativeEngine.parseStats(NativeEngine.nativeGetStats()) }
                            .getOrDefault(_stats.value)
                    if (next != _stats.value) {
                        _stats.value = next
                        perfHistory.add(next)
                    }
                }
                if (wasRunning && !runningNow) onGameExited(_stats.value)
                wasRunning = runningNow
                publishLogs()
                delay(if (runningNow) 200L else 500L)
            }
        }
    }

    /** 追加到缓冲，不立即发 StateFlow；由 [publishLogs] 在泵的一轮末尾统一发布。 */
    fun appendLog(line: String) {
        if (line.isEmpty()) return
        val keep = settings.value.logLines.coerceIn(500, 20_000)
        synchronized(logLock) {
            logBuffer.append(line)
            if (logBuffer.length > keep) {
                logBuffer.delete(0, logBuffer.length - keep)
            }
            logDirty = true
        }
        if (settings.value.logToFile) logCenter.append(line)
    }

    /** 把缓冲一次性发布给 Compose；无新日志时不产生任何状态发射。 */
    private fun publishLogs() {
        synchronized(logLock) {
            if (!logDirty) return
            logDirty = false
            // 赋值也放在锁内，避免 clearLogs() 与发布交错导致旧内容被写回。
            _logs.value = logBuffer.toString()
        }
    }

    /** 用户交互产生的日志（stdin、启动结果）走这里，不等下一轮泵，立即显示。 */
    fun appendLogInteractive(line: String) {
        appendLog(line)
        publishLogs()
    }

    fun clearLogs() {
        synchronized(logLock) {
            logBuffer.setLength(0)
            logDirty = false
        }
        _logs.value = ""
    }

    /**
     * 完整启动编排（真实链路）：
     * version.json → 实例配置 → 选 Java → 释放/合并运行时 → 拼 classpath 与 JVM 参数
     * → 启动预检 → preLaunch 钩子 → 交给 C++ 引擎 → 退出后崩溃归档 / postLaunch / 统计。
     */
    suspend fun launch(versionId: String): Result<GameStartResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.value
                val meta =
                    games.meta(versionId)
                        ?: error("版本 $versionId 尚未安装，请先在版本库安装")
                appendLog("[启动] 解析 version.json：Java ${meta.javaMajor} / ${meta.mainClass}\n")

                val instanceConfig = instances.ensure(versionId)
                val isolated = s.isolateInstances
                val gameDir = instances.gameDir(versionId, isolated)
                val effectiveMemory = if (instanceConfig.memoryMb > 0) instanceConfig.memoryMb else s.memoryMb
                appendLog("[实例] $gameDir · 隔离=$isolated · 覆盖内存=${instanceConfig.memoryMb}MB\n")

                val layout =
                    runtime.prepare(
                        javaMode = s.javaMode,
                        javaMajor = meta.javaMajor,
                        lwjglVersion = s.lwjglVersion,
                        verify = s.verifyAssets,
                    ) { msg -> appendLog("[运行时] $msg\n") }
                appendLog("[运行时] javaHome=${layout.javaHome}\n")
                if (s.autoBackupOnLaunch) {
                    val backup = File(app.filesDir, "backups/settings-${System.currentTimeMillis()}.json")
                    if (SettingsBackup.exportTo(backup, s)) {
                        appendLog("[备份] 启动前配置快照：${backup.absolutePath}\n")
                    }
                }
                appendLog("[运行时] jli=${layout.jliCandidates.firstOrNull() ?: "未找到"}\n")

                val account = accounts.current()
                val token = account?.let { accounts.token(it) } ?: ""
                if (account == null) appendLog("[账号] 未选择账号，使用匿名离线身份\n")

                val plan =
                    LaunchConfigBuilder.build(
                        versionId = versionId,
                        meta = meta,
                        layout = layout,
                        settings = s,
                        account = account,
                        accessToken = token,
                        gameRepository = games,
                        instanceConfig = instanceConfig,
                        instanceDir = if (isolated) gameDir else null,
                    )

                if (s.preflightEnabled) {
                    val report =
                        LaunchGuard.preflight(
                            context = app,
                            versionId = versionId,
                            meta = meta,
                            layout = layout,
                            settings = s,
                            games = games,
                            instances = instances,
                            classpath = plan.classpath,
                            effectiveMemoryMb = effectiveMemory,
                        )
                    _preflight.value = report
                    appendLog(LaunchGuard.toLogText(report))
                    if (!report.ok) {
                        error("启动预检未通过：${report.errors.joinToString { it.label }}")
                    }
                } else {
                    _preflight.value = null
                }

                val pre = instanceConfig.preLaunch
                if (pre.isNotBlank()) {
                    appendLog("[钩子] 执行 preLaunch：$pre\n")
                    runHook(pre, "preLaunch:$versionId")
                }

                _sdlInfo.value = SdlResolver.resolve(s, meta.javaMajor, versionId, layout)
                when {
                    plan.env["MCJL_WINDOW_BACKEND"] == "SDL3" || plan.env["MCJL_WINDOW_BACKEND"] == "SDL2" -> {
                        appendLog(
                            "[窗口] ${plan.env["MCJL_WINDOW_BACKEND"]} ${plan.env["MCJL_SDL_VERSION"]} 接管窗口 / 手柄 · " +
                                "Java ${meta.javaMajor} · ABI ${plan.env["MCJL_SDL_ABI"]} · " +
                                "库 ${plan.env["MCJL_SDL_LIB"]} · 渲染器仍为 ${s.renderer}\n",
                        )
                    }

                    plan.env["MCJL_SDL_SKIPPED"] != null -> {
                        appendLog("[窗口] SDL 库未就位（${plan.env["MCJL_SDL_SKIPPED"]}），按 GLFW 启动\n")
                    }
                }
                when {
                    plan.env["MCJL_SFPEW_LAYER"] == "1" -> {
                        appendLog(
                            "[渲染] ${s.renderer} ＋ SFPEW 叠加层：GL/EGL 符号经 ${plan.env["MCJL_SFPEW_LIB"]} " +
                                "（ABI ${plan.env["MCJL_SFPEW_ABI"]}）由 SFPEW_EGL=${plan.env["SFPEW_EGL"]} " +
                                "转发到底层 ${plan.env["MCJL_GL_BASE_LIB"]}\n",
                        )
                    }

                    plan.env["MCJL_SFPEW_SKIPPED"] != null -> {
                        appendLog(
                            "[渲染] SFPEW 已开启但本次跳过（${plan.env["MCJL_SFPEW_SKIPPED"]}），按 ${s.renderer} 原渲染器启动\n",
                        )
                    }
                }
                NativeEngine.nativeSetLogLevel(plan.logLevel)
                appendLog("[启动] classpath ${plan.classpath.split(File.pathSeparator).size} 项 / ${plan.jvmArgs.size} JVM 参数 / ${plan.gameArgs.size} 游戏参数\n")

                // 若引擎仍在运行则先停止，避免重复启动
                if (NativeEngine.nativeIsRunning()) {
                    appendLog("[启动] 检测到旧进程，先停止\n")
                    NativeEngine.nativeStopGame()
                    delay(600L)
                }
                lastVersionId = versionId
                lastLaunchAt = System.currentTimeMillis()
                pushEvent("启动 $versionId")
                NativeEngine.start(plan)
            }.onFailure { e ->
                appendLogInteractive("[启动] 失败：${e.message}\n")
                pushEvent("启动失败：${e.message}")
            }
        }

    fun stop(): Boolean {
        val ok = NativeEngine.nativeStopGame()
        appendLogInteractive("[引擎] stop=$ok\n")
        return ok
    }

    fun kill(): Boolean = NativeEngine.nativeKill()

    fun writeStdin(text: String): Boolean {
        val ok = NativeEngine.nativeWriteStdin(text)
        if (ok) commands.record(text)
        return ok
    }

    /** 配置预设、备份、日志分析、内容管理与版本体检的真实入口。 */
    fun applyProfile(id: String): Boolean = profiles.apply(settings, id)

    fun exportSettings(): String = SettingsBackup.toJson(settings.value)

    fun importSettings(json: String): Boolean =
        SettingsBackup
            .fromJson(json)
            .onSuccess { SettingsBackup.applyTo(settings, it) }
            .isSuccess

    fun exportSettingsTo(file: java.io.File): Boolean = SettingsBackup.exportTo(file, settings.value)

    fun importSettingsFrom(file: java.io.File): Boolean =
        SettingsBackup
            .importFrom(file)
            .onSuccess { SettingsBackup.applyTo(settings, it) }
            .isSuccess

    fun analyzeLogs(): List<LogInsight> = LogAnalyzer.analyze(_logs.value)

    fun contentList(
        versionId: String,
        kind: ContentKind,
    ): List<ContentItem> = content.list(versionId, kind, settings.value.contentSort)

    fun inspectVersion(versionId: String): VersionInsight {
        val meta = games.meta(versionId)
        return VersionInspector.inspect(
            games.versionDir(versionId),
            versionId,
            meta?.javaMajor ?: 8,
            meta?.mainClass ?: "",
        )
    }
}

val LocalLauncherContainer =
    staticCompositionLocalOf<LauncherContainer> {
        error("LauncherContainer 未提供：请确认 LauncherApp 已初始化并用 CompositionLocalProvider 注入")
    }
