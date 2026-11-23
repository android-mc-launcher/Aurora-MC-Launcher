package com.yuanbao.mcjlauncher.core

import org.json.JSONObject
import java.io.File

/** 全量设置备份：48 个字段逐项写入 JSON，可导出到文件或从文件恢复。 */
object SettingsBackup {
    const val FIELD_COUNT = 48

    fun toJson(s: SettingsSnapshot): String {
        val o = JSONObject()
        o.put("schema", 1)
        o.put("javaMode", s.javaMode.name)
        o.put("themeMode", s.themeMode)
        o.put("dynamicColor", s.dynamicColor)
        o.put("memoryMb", s.memoryMb)
        o.put("logLines", s.logLines)
        o.put("renderer", s.renderer)
        o.put("logLevel", s.logLevel)
        o.put("lwjglVersion", s.lwjglVersion)
        o.put("resolutionWidth", s.resolutionWidth)
        o.put("resolutionHeight", s.resolutionHeight)
        o.put("autoExpandOverlay", s.autoExpandOverlay)
        o.put("autoScrollLog", s.autoScrollLog)
        o.put("perfMonitor", s.perfMonitor)
        o.put("verifyAssets", s.verifyAssets)
        o.put("immersive", s.immersive)
        o.put("showFps", s.showFps)
        o.put("msClientId", s.msClientId)
        o.put("selectedVersion", s.selectedVersion)
        o.put("currentAccountId", s.currentAccountId)
        o.put("downloadSource", s.downloadSource)
        o.put("autoProbeMs", s.autoProbeMs)
        o.put("downloadConcurrency", s.downloadConcurrency)
        o.put("downloadRetries", s.downloadRetries)
        o.put("customMirrorUrl", s.customMirrorUrl)
        o.put("isolateInstances", s.isolateInstances)
        o.put("preflightEnabled", s.preflightEnabled)
        o.put("crashArchiveEnabled", s.crashArchiveEnabled)
        o.put("autoResume", s.autoResume)
        o.put("logToFile", s.logToFile)
        o.put("jvmPreset", s.jvmPreset)
        o.put("extraJvmArgsGlobal", s.extraJvmArgsGlobal)
        o.put("sfpewEnabled", s.sfpewEnabled)
        o.put("sdlMode", s.sdlMode)
        o.put("sdlHighJavaOnly", s.sdlHighJavaOnly)
        o.put("sdlGamepad", s.sdlGamepad)
        o.put("sdlHidapi", s.sdlHidapi)
        o.put("sdlTouchMouse", s.sdlTouchMouse)
        o.put("sdlAudioBackend", s.sdlAudioBackend)
        o.put("sdlLogLevel", s.sdlLogLevel)
        o.put("sdlForceOnModded", s.sdlForceOnModded)
        o.put("overlayTab", s.overlayTab)
        o.put("overlayAlpha", s.overlayAlpha)
        o.put("overlayCompact", s.overlayCompact)
        o.put("overlayShowGraph", s.overlayShowGraph)
        o.put("overlayGesture", s.overlayGesture)
        o.put("profilePreset", s.profilePreset)
        o.put("autoBackupOnLaunch", s.autoBackupOnLaunch)
        o.put("contentSort", s.contentSort)
        return o.toString(2)
    }

    fun fromJson(json: String): Result<SettingsSnapshot> =
        runCatching {
            val o = JSONObject(json)
            SettingsSnapshot(
                javaMode = JavaMode.from(o.optString("javaMode", "AUTO")),
                themeMode = o.optString("themeMode", "SYSTEM"),
                dynamicColor = o.optBoolean("dynamicColor", true),
                memoryMb = o.optInt("memoryMb", 2048),
                logLines = o.optInt("logLines", 6000),
                renderer = o.optString("renderer", "gl4es"),
                logLevel = o.optString("logLevel", "INFO"),
                lwjglVersion = o.optString("lwjglVersion", "3.3.3"),
                resolutionWidth = o.optInt("resolutionWidth", 1280),
                resolutionHeight = o.optInt("resolutionHeight", 720),
                autoExpandOverlay = o.optBoolean("autoExpandOverlay", false),
                autoScrollLog = o.optBoolean("autoScrollLog", true),
                perfMonitor = o.optBoolean("perfMonitor", false),
                verifyAssets = o.optBoolean("verifyAssets", true),
                immersive = o.optBoolean("immersive", false),
                showFps = o.optBoolean("showFps", false),
                msClientId = o.optString("msClientId", ""),
                selectedVersion = o.optString("selectedVersion", ""),
                currentAccountId = o.optString("currentAccountId", ""),
                downloadSource = o.optString("downloadSource", "AUTO"),
                autoProbeMs = o.optInt("autoProbeMs", 1500),
                downloadConcurrency = o.optInt("downloadConcurrency", 8),
                downloadRetries = o.optInt("downloadRetries", 2),
                customMirrorUrl = o.optString("customMirrorUrl", ""),
                isolateInstances = o.optBoolean("isolateInstances", true),
                preflightEnabled = o.optBoolean("preflightEnabled", true),
                crashArchiveEnabled = o.optBoolean("crashArchiveEnabled", true),
                autoResume = o.optBoolean("autoResume", true),
                logToFile = o.optBoolean("logToFile", false),
                jvmPreset = o.optString("jvmPreset", "BALANCED"),
                extraJvmArgsGlobal = o.optString("extraJvmArgsGlobal", ""),
                sfpewEnabled = o.optBoolean("sfpewEnabled", false),
                sdlMode = o.optString("sdlMode", "AUTO"),
                sdlHighJavaOnly = o.optBoolean("sdlHighJavaOnly", true),
                sdlGamepad = o.optBoolean("sdlGamepad", true),
                sdlHidapi = o.optBoolean("sdlHidapi", true),
                sdlTouchMouse = o.optBoolean("sdlTouchMouse", true),
                sdlAudioBackend = o.optString("sdlAudioBackend", "AUTO"),
                sdlLogLevel = o.optString("sdlLogLevel", "INFO"),
                sdlForceOnModded = o.optBoolean("sdlForceOnModded", false),
                overlayTab = o.optString("overlayTab", "STATUS"),
                overlayAlpha = o.optInt("overlayAlpha", 100),
                overlayCompact = o.optBoolean("overlayCompact", false),
                overlayShowGraph = o.optBoolean("overlayShowGraph", true),
                overlayGesture = o.optBoolean("overlayGesture", true),
                profilePreset = o.optString("profilePreset", "BALANCED"),
                autoBackupOnLaunch = o.optBoolean("autoBackupOnLaunch", false),
                contentSort = o.optString("contentSort", "NAME"),
            )
        }

    fun exportTo(
        file: File,
        s: SettingsSnapshot,
    ): Boolean =
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(toJson(s))
            true
        }.getOrDefault(false)

    fun importFrom(file: File): Result<SettingsSnapshot> = runCatching { JSONObject(file.readText()) }.flatMap { fromJson(it.toString()) }

    /** 把备份应用回仓库（单点 update 保证只写变化键）。 */
    fun applyTo(
        settings: SettingsRepository,
        snapshot: SettingsSnapshot,
    ) {
        settings.update { snapshot }
    }
}
