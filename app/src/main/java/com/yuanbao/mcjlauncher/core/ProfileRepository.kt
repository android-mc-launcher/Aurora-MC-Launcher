package com.yuanbao.mcjlauncher.core

/** 一键配置预设：一套值覆盖渲染、内存、JVM、SDL 与悬浮窗。 */
data class ProfilePreset(
    val id: String,
    val label: String,
    val description: String,
    val memoryMb: Int,
    val renderer: String,
    val jvmPreset: String,
    val sdlMode: String,
    val lwjglVersion: String,
    val showFps: Boolean,
    val overlayCompact: Boolean,
    val sfpewEnabled: Boolean,
)

object ProfileRepository {
    val PRESETS: List<ProfilePreset> =
        listOf(
            ProfilePreset("LOW", "低配省电", "4GB 设备 / 串行 GC / 紧凑悬浮窗", 1536, "gl4es", "LOW", "AUTO", "3.3.3", false, true, false),
            ProfilePreset("BALANCED", "均衡（默认）", "2GB 内存 / 自动 SDL / 标准日志", 2048, "gl4es", "BALANCED", "AUTO", "3.3.3", false, false, false),
            ProfilePreset("PERFORMANCE", "高帧率", "4GB 内存 / G1 GC / FPS 显示", 4096, "gl4es", "PERFORMANCE", "AUTO", "3.4.1", true, false, false),
            ProfilePreset("COMPETITIVE", "竞技 / 手柄", "EGL 渲染 / 强制 SDL3 / HIDAPI", 3072, "egl", "PERFORMANCE", "SDL3", "3.4.1", true, true, false),
            ProfilePreset("COMPAT", "老版本兼容", "关闭 SDL + 开启 SFPEW 叠加层", 2048, "gl4es", "BALANCED", "OFF", "3.3.3", false, false, true),
        )

    fun apply(
        settings: SettingsRepository,
        id: String,
    ): Boolean {
        val preset = PRESETS.firstOrNull { it.id == id } ?: return false
        settings.update {
            it.copy(
                memoryMb = preset.memoryMb,
                renderer = preset.renderer,
                jvmPreset = preset.jvmPreset,
                sdlMode = preset.sdlMode,
                lwjglVersion = preset.lwjglVersion,
                showFps = preset.showFps,
                overlayCompact = preset.overlayCompact,
                sfpewEnabled = preset.sfpewEnabled,
                profilePreset = preset.id,
            )
        }
        return true
    }

    fun labelOf(id: String): String = PRESETS.firstOrNull { it.id == id }?.label ?: "自定义"
}
