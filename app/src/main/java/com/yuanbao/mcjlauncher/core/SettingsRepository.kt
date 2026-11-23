package com.yuanbao.mcjlauncher.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 全部设置的快照。所有字段都会持久化到 SharedPreferences，重启不再恢复默认。 */
data class SettingsSnapshot(
    val javaMode: JavaMode = JavaMode.AUTO,
    val themeMode: String = "SYSTEM",
    val dynamicColor: Boolean = true,
    val memoryMb: Int = 2048,
    val logLines: Int = 6000,
    val renderer: String = "gl4es",
    val logLevel: String = "INFO",
    val lwjglVersion: String = "3.3.3",
    val resolutionWidth: Int = 1280,
    val resolutionHeight: Int = 720,
    val autoExpandOverlay: Boolean = false,
    val autoScrollLog: Boolean = true,
    val perfMonitor: Boolean = false,
    val verifyAssets: Boolean = true,
    val immersive: Boolean = false,
    val showFps: Boolean = false,
    val msClientId: String = "",
    val selectedVersion: String = "",
    val currentAccountId: String = "",
    val downloadSource: String = "AUTO",
    val autoProbeMs: Int = 1500,
    val downloadConcurrency: Int = 8,
    val downloadRetries: Int = 2,
    val customMirrorUrl: String = "",
    val isolateInstances: Boolean = true,
    val preflightEnabled: Boolean = true,
    val crashArchiveEnabled: Boolean = true,
    val autoResume: Boolean = true,
    val logToFile: Boolean = false,
    val jvmPreset: String = "BALANCED",
    val extraJvmArgsGlobal: String = "",
    /** SFPEW(SimpleFPEWrapper) 兼容层：叠加在当前渲染器之上，不替换底层后端。默认关闭。 */
    val sfpewEnabled: Boolean = false,
    val sdlMode: String = "AUTO",
    val sdlHighJavaOnly: Boolean = true,
    val sdlGamepad: Boolean = true,
    val sdlHidapi: Boolean = true,
    val sdlTouchMouse: Boolean = true,
    val sdlAudioBackend: String = "AUTO",
    val sdlLogLevel: String = "INFO",
    val sdlForceOnModded: Boolean = false,
    val overlayTab: String = "STATUS",
    val overlayAlpha: Int = 100,
    val overlayCompact: Boolean = false,
    val overlayShowGraph: Boolean = true,
    val overlayGesture: Boolean = true,
    val profilePreset: String = "BALANCED",
    val autoBackupOnLaunch: Boolean = false,
    val contentSort: String = "NAME",
)

/** 设置仓库：SharedPreferences 持久化 + StateFlow 供 Compose 订阅。 */
class SettingsRepository(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mcjl_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    val value: SettingsSnapshot get() = _state.value

    private fun read(): SettingsSnapshot =
        SettingsSnapshot(
            javaMode = JavaMode.from(prefs.getString(K_JAVA_MODE, "AUTO") ?: "AUTO"),
            themeMode = prefs.getString(K_THEME, "SYSTEM") ?: "SYSTEM",
            dynamicColor = prefs.getBoolean(K_DYNAMIC, true),
            memoryMb = prefs.getInt(K_MEMORY, 2048),
            logLines = prefs.getInt(K_LOG_LINES, 6000),
            renderer = prefs.getString(K_RENDERER, "gl4es") ?: "gl4es",
            logLevel = prefs.getString(K_LOG_LEVEL, "INFO") ?: "INFO",
            lwjglVersion = prefs.getString(K_LWJGL, "3.3.3") ?: "3.3.3",
            resolutionWidth = prefs.getInt(K_WIDTH, 1280),
            resolutionHeight = prefs.getInt(K_HEIGHT, 720),
            autoExpandOverlay = prefs.getBoolean(K_AUTO_EXPAND, false),
            autoScrollLog = prefs.getBoolean(K_AUTO_SCROLL, true),
            perfMonitor = prefs.getBoolean(K_PERF_MONITOR, false),
            verifyAssets = prefs.getBoolean(K_VERIFY, true),
            immersive = prefs.getBoolean(K_IMMERSIVE, false),
            showFps = prefs.getBoolean(K_SHOW_FPS, false),
            msClientId = prefs.getString(K_MS_CLIENT_ID, "") ?: "",
            selectedVersion = prefs.getString(K_SELECTED_VERSION, "") ?: "",
            currentAccountId = prefs.getString(K_ACCOUNT_ID, "") ?: "",
            downloadSource = prefs.getString(K_DOWNLOAD_SOURCE, "AUTO") ?: "AUTO",
            autoProbeMs = prefs.getInt(K_AUTO_PROBE_MS, 1500),
            downloadConcurrency = prefs.getInt(K_CONCURRENCY, 8),
            downloadRetries = prefs.getInt(K_RETRIES, 2),
            customMirrorUrl = prefs.getString(K_CUSTOM_MIRROR, "") ?: "",
            isolateInstances = prefs.getBoolean(K_ISOLATE, true),
            preflightEnabled = prefs.getBoolean(K_PREFLIGHT, true),
            crashArchiveEnabled = prefs.getBoolean(K_CRASH_ARCHIVE, true),
            autoResume = prefs.getBoolean(K_AUTO_RESUME, true),
            logToFile = prefs.getBoolean(K_LOG_TO_FILE, false),
            jvmPreset = prefs.getString(K_JVM_PRESET, "BALANCED") ?: "BALANCED",
            extraJvmArgsGlobal = prefs.getString(K_EXTRA_JVM, "") ?: "",
            sfpewEnabled = prefs.getBoolean(K_SFPEW, false),
            sdlMode = prefs.getString(K_SDL_MODE, "AUTO") ?: "AUTO",
            sdlHighJavaOnly = prefs.getBoolean(K_SDL_HIGH_JAVA, true),
            sdlGamepad = prefs.getBoolean(K_SDL_GAMEPAD, true),
            sdlHidapi = prefs.getBoolean(K_SDL_HIDAPI, true),
            sdlTouchMouse = prefs.getBoolean(K_SDL_TOUCH_MOUSE, true),
            sdlAudioBackend = prefs.getString(K_SDL_AUDIO, "AUTO") ?: "AUTO",
            sdlLogLevel = prefs.getString(K_SDL_LOG_LEVEL, "INFO") ?: "INFO",
            sdlForceOnModded = prefs.getBoolean(K_SDL_FORCE_MODDED, false),
            overlayTab = prefs.getString(K_OVERLAY_TAB, "STATUS") ?: "STATUS",
            overlayAlpha = prefs.getInt(K_OVERLAY_ALPHA, 100),
            overlayCompact = prefs.getBoolean(K_OVERLAY_COMPACT, false),
            overlayShowGraph = prefs.getBoolean(K_OVERLAY_GRAPH, true),
            overlayGesture = prefs.getBoolean(K_OVERLAY_GESTURE, true),
            profilePreset = prefs.getString(K_PROFILE, "BALANCED") ?: "BALANCED",
            autoBackupOnLaunch = prefs.getBoolean(K_AUTO_BACKUP, false),
            contentSort = prefs.getString(K_CONTENT_SORT, "NAME") ?: "NAME",
        )

    /**
     * 单点更新：只把变化的键写进 SharedPreferences；值没变时不发 StateFlow。
     * 后者能避免重复点击/相同输入触发全应用重组，前者减少拖动时的编辑开销。
     */
    fun update(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        val old = _state.value
        val next = transform(old)
        if (next == old) return
        prefs
            .edit()
            .apply {
                fun str(
                    key: String,
                    a: String,
                    b: String,
                ) {
                    if (a != b) putString(key, b)
                }

                fun int(
                    key: String,
                    a: Int,
                    b: Int,
                ) {
                    if (a != b) putInt(key, b)
                }

                fun bool(
                    key: String,
                    a: Boolean,
                    b: Boolean,
                ) {
                    if (a != b) putBoolean(key, b)
                }
                str(K_JAVA_MODE, old.javaMode.name, next.javaMode.name)
                str(K_THEME, old.themeMode, next.themeMode)
                bool(K_DYNAMIC, old.dynamicColor, next.dynamicColor)
                int(K_MEMORY, old.memoryMb, next.memoryMb)
                int(K_LOG_LINES, old.logLines, next.logLines)
                str(K_RENDERER, old.renderer, next.renderer)
                str(K_LOG_LEVEL, old.logLevel, next.logLevel)
                str(K_LWJGL, old.lwjglVersion, next.lwjglVersion)
                int(K_WIDTH, old.resolutionWidth, next.resolutionWidth)
                int(K_HEIGHT, old.resolutionHeight, next.resolutionHeight)
                bool(K_AUTO_EXPAND, old.autoExpandOverlay, next.autoExpandOverlay)
                bool(K_AUTO_SCROLL, old.autoScrollLog, next.autoScrollLog)
                bool(K_PERF_MONITOR, old.perfMonitor, next.perfMonitor)
                bool(K_VERIFY, old.verifyAssets, next.verifyAssets)
                bool(K_IMMERSIVE, old.immersive, next.immersive)
                bool(K_SHOW_FPS, old.showFps, next.showFps)
                str(K_MS_CLIENT_ID, old.msClientId, next.msClientId)
                str(K_SELECTED_VERSION, old.selectedVersion, next.selectedVersion)
                str(K_ACCOUNT_ID, old.currentAccountId, next.currentAccountId)
                str(K_DOWNLOAD_SOURCE, old.downloadSource, next.downloadSource)
                int(K_AUTO_PROBE_MS, old.autoProbeMs, next.autoProbeMs)
                int(K_CONCURRENCY, old.downloadConcurrency, next.downloadConcurrency)
                int(K_RETRIES, old.downloadRetries, next.downloadRetries)
                str(K_CUSTOM_MIRROR, old.customMirrorUrl, next.customMirrorUrl)
                bool(K_ISOLATE, old.isolateInstances, next.isolateInstances)
                bool(K_PREFLIGHT, old.preflightEnabled, next.preflightEnabled)
                bool(K_CRASH_ARCHIVE, old.crashArchiveEnabled, next.crashArchiveEnabled)
                bool(K_AUTO_RESUME, old.autoResume, next.autoResume)
                bool(K_LOG_TO_FILE, old.logToFile, next.logToFile)
                str(K_JVM_PRESET, old.jvmPreset, next.jvmPreset)
                str(K_EXTRA_JVM, old.extraJvmArgsGlobal, next.extraJvmArgsGlobal)
                bool(K_SFPEW, old.sfpewEnabled, next.sfpewEnabled)
                str(K_SDL_MODE, old.sdlMode, next.sdlMode)
                bool(K_SDL_HIGH_JAVA, old.sdlHighJavaOnly, next.sdlHighJavaOnly)
                bool(K_SDL_GAMEPAD, old.sdlGamepad, next.sdlGamepad)
                bool(K_SDL_HIDAPI, old.sdlHidapi, next.sdlHidapi)
                bool(K_SDL_TOUCH_MOUSE, old.sdlTouchMouse, next.sdlTouchMouse)
                str(K_SDL_AUDIO, old.sdlAudioBackend, next.sdlAudioBackend)
                str(K_SDL_LOG_LEVEL, old.sdlLogLevel, next.sdlLogLevel)
                bool(K_SDL_FORCE_MODDED, old.sdlForceOnModded, next.sdlForceOnModded)
                str(K_OVERLAY_TAB, old.overlayTab, next.overlayTab)
                int(K_OVERLAY_ALPHA, old.overlayAlpha, next.overlayAlpha)
                bool(K_OVERLAY_COMPACT, old.overlayCompact, next.overlayCompact)
                bool(K_OVERLAY_GRAPH, old.overlayShowGraph, next.overlayShowGraph)
                bool(K_OVERLAY_GESTURE, old.overlayGesture, next.overlayGesture)
                str(K_PROFILE, old.profilePreset, next.profilePreset)
                bool(K_AUTO_BACKUP, old.autoBackupOnLaunch, next.autoBackupOnLaunch)
                str(K_CONTENT_SORT, old.contentSort, next.contentSort)
            }.apply()
        _state.value = next
    }

    fun resetDefaults() {
        prefs.edit().clear().apply()
        _state.value = read()
    }

    private companion object {
        const val K_JAVA_MODE = "javaMode"
        const val K_THEME = "themeMode"
        const val K_DYNAMIC = "dynamicColor"
        const val K_MEMORY = "memoryMb"
        const val K_LOG_LINES = "logLines"
        const val K_RENDERER = "renderer"
        const val K_LOG_LEVEL = "logLevel"
        const val K_LWJGL = "lwjglVersion"
        const val K_WIDTH = "width"
        const val K_HEIGHT = "height"
        const val K_AUTO_EXPAND = "autoExpandOverlay"
        const val K_AUTO_SCROLL = "autoScrollLog"
        const val K_PERF_MONITOR = "perfMonitor"
        const val K_VERIFY = "verifyAssets"
        const val K_IMMERSIVE = "immersive"
        const val K_SHOW_FPS = "showFps"
        const val K_MS_CLIENT_ID = "msClientId"
        const val K_SELECTED_VERSION = "selectedVersion"
        const val K_ACCOUNT_ID = "currentAccountId"
        const val K_DOWNLOAD_SOURCE = "downloadSource"
        const val K_AUTO_PROBE_MS = "autoProbeMs"
        const val K_CONCURRENCY = "downloadConcurrency"
        const val K_RETRIES = "downloadRetries"
        const val K_CUSTOM_MIRROR = "customMirrorUrl"
        const val K_ISOLATE = "isolateInstances"
        const val K_PREFLIGHT = "preflightEnabled"
        const val K_CRASH_ARCHIVE = "crashArchiveEnabled"
        const val K_AUTO_RESUME = "autoResume"
        const val K_LOG_TO_FILE = "logToFile"
        const val K_JVM_PRESET = "jvmPreset"
        const val K_EXTRA_JVM = "extraJvmArgsGlobal"
        const val K_SFPEW = "sfpewEnabled"
        const val K_SDL_MODE = "sdlMode"
        const val K_SDL_HIGH_JAVA = "sdlHighJavaOnly"
        const val K_SDL_GAMEPAD = "sdlGamepad"
        const val K_SDL_HIDAPI = "sdlHidapi"
        const val K_SDL_TOUCH_MOUSE = "sdlTouchMouse"
        const val K_SDL_AUDIO = "sdlAudioBackend"
        const val K_SDL_LOG_LEVEL = "sdlLogLevel"
        const val K_SDL_FORCE_MODDED = "sdlForceOnModded"
        const val K_OVERLAY_TAB = "overlayTab"
        const val K_OVERLAY_ALPHA = "overlayAlpha"
        const val K_OVERLAY_COMPACT = "overlayCompact"
        const val K_OVERLAY_GRAPH = "overlayShowGraph"
        const val K_OVERLAY_GESTURE = "overlayGesture"
        const val K_PROFILE = "profilePreset"
        const val K_AUTO_BACKUP = "autoBackupOnLaunch"
        const val K_CONTENT_SORT = "contentSort"
    }
}
