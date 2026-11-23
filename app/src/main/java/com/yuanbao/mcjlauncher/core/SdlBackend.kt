package com.yuanbao.mcjlauncher.core

import android.os.Build
import java.io.File

/** SDL 窗口 / 输入后端。AUTO 会把高版本 Java 自动映射到 SDL3 / SDL2。 */
enum class SdlMode(
    val label: String,
) {
    AUTO("自动（高版本 Java 启用）"),
    OFF("关闭（GLFW 窗口栈）"),
    SDL2("SDL2 2.32.57"),
    SDL3("SDL3 3.4.12"),
    ;

    companion object {
        fun from(raw: String): SdlMode = entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

/** 一次 SDL 决策的完整结果，供启动计划、预检、UI、悬浮窗与诊断共用。 */
data class SdlResolution(
    val requested: SdlMode,
    val active: SdlMode,
    val reason: String,
    val javaMajor: Int,
    val versionId: String,
    val libPath: String,
    val glfwShim: String,
    val versionTag: String,
    /** 选中 .so 的 ABI 目录名，例如 arm64-v8a。 */
    val abi: String = "",
    /** 选中 .so 所在目录的绝对路径，供 LD_LIBRARY_PATH 使用。 */
    val nativeDir: String = "",
    /** sdl2-compat 依赖的 libSDL3.so 所在目录。 */
    val dependencyDir: String = "",
) {
    val enabled: Boolean get() = active != SdlMode.OFF

    val windowBackend: String get() = if (enabled) active.name else "GLFW"

    val summary: String
        get() =
            if (enabled) {
                "$versionTag · Java $javaMajor · ${active.label} · $abi"
            } else {
                "GLFW · Java $javaMajor · $reason"
            }
}

/**
 * SDL 运行时常量。
 * `SDL3_VERSION` / `SDL2_VERSION` 是 assets 内**真实 ELF** 的版本（FCL 上游构建）；
 * `LATEST_*` 是 libsdl.org 官方最新稳定版，仅用于 UI 提示与替换脚本，不代表已内置。
 */
object SdlSpec {
    const val SDL3_VERSION = "3.4.12"
    const val SDL2_VERSION = "2.32.57"
    const val LATEST_SDL3_VERSION = "3.4.20"
    const val LATEST_SDL2_VERSION = "2.32.74"
    const val SDL3_LIB = "libSDL3.so"
    const val SDL2_LIB = "libSDL2.so"
    const val GLFW_SHIM = "libglfw_sdl.so"
    const val HIGH_JAVA_MAJOR = 17
    const val SDL3_JAVA_MAJOR = 21

    /** assets 内签入的 ABI 目录，与 Android Build.SUPPORTED_ABIS 命名一致。 */
    val BUNDLED_ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    /** 当前设备首选 ABI；不在内置清单时回退 arm64-v8a。 */
    fun currentAbi(): String {
        val preferred = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return if (preferred in BUNDLED_ABIS) preferred else "arm64-v8a"
    }

    fun versionTag(mode: SdlMode): String =
        when (mode) {
            SdlMode.SDL3 -> SDL3_VERSION
            SdlMode.SDL2 -> SDL2_VERSION
            else -> "none"
        }

    fun libName(mode: SdlMode): String =
        when (mode) {
            SdlMode.SDL3 -> SDL3_LIB
            SdlMode.SDL2 -> SDL2_LIB
            else -> ""
        }

    fun directory(mode: SdlMode): String =
        when (mode) {
            SdlMode.SDL3 -> "sdl3"
            SdlMode.SDL2 -> "sdl2"
            else -> ""
        }

    /** sdl2-compat 的 libSDL2.so 依赖 libSDL3.so，返回 SDL3 的 ABI 目录。 */
    fun dependencyDir(
        layout: RuntimeLayout,
        abi: String,
    ): String = File(File(layout.sdlDir, directory(SdlMode.SDL3)), abi).absolutePath
}

/**
 * SDL 决策器：SDL 作为窗口 / 输入后端**叠加**在现有渲染器之上，
 * POJAV_RENDERER 与 GL 库保持不变；GLFW shim 仅在库就位时替换。
 * 真实 .so 按 ABI 放在 `sdl/sdl2/<abi>/`、`sdl/sdl3/<abi>/`，找不到时回退平铺布局。
 */
object SdlResolver {
    /** 自动规则：Java 8 → 关闭；Java 17 → SDL2；Java 21 / 25 → SDL3。 */
    fun autoFor(javaMajor: Int): SdlMode =
        when {
            javaMajor >= SdlSpec.SDL3_JAVA_MAJOR -> SdlMode.SDL3
            javaMajor >= SdlSpec.HIGH_JAVA_MAJOR -> SdlMode.SDL2
            else -> SdlMode.OFF
        }

    fun resolve(
        settings: SettingsSnapshot,
        javaMajor: Int,
        versionId: String,
        layout: RuntimeLayout,
    ): SdlResolution {
        val requested = SdlMode.from(settings.sdlMode)
        val candidate =
            when {
                requested != SdlMode.AUTO -> requested
                settings.sdlHighJavaOnly -> autoFor(javaMajor)
                else -> SdlMode.SDL3
            }
        if (candidate == SdlMode.OFF) {
            return SdlResolution(
                requested = requested,
                active = SdlMode.OFF,
                reason = if (requested == SdlMode.AUTO) "低版本 Java 使用 GLFW 窗口栈" else "用户关闭",
                javaMajor = javaMajor,
                versionId = versionId,
                libPath = "",
                glfwShim = "",
                versionTag = "none",
            )
        }
        val abi = SdlSpec.currentAbi()
        val root = File(layout.sdlDir, SdlSpec.directory(candidate))
        val abiDir = File(root, abi)
        val lib = File(abiDir, SdlSpec.libName(candidate))
        // 兼容早期平铺放置：ABI 子目录缺失时再查根目录。
        val effectiveLib = if (lib.isFile) lib else File(root, SdlSpec.libName(candidate))
        val shim = File(effectiveLib.parentFile, SdlSpec.GLFW_SHIM)
        val dependencyDir = SdlSpec.dependencyDir(layout, abi)
        if (!effectiveLib.isFile) {
            return SdlResolution(
                requested = requested,
                active = SdlMode.OFF,
                reason = "library-missing: ${effectiveLib.absolutePath}",
                javaMajor = javaMajor,
                versionId = versionId,
                libPath = effectiveLib.absolutePath,
                glfwShim = "",
                versionTag = SdlSpec.versionTag(candidate),
                abi = abi,
                nativeDir = effectiveLib.parentFile.absolutePath,
                dependencyDir = dependencyDir,
            )
        }
        return SdlResolution(
            requested = requested,
            active = candidate,
            reason = "java-$javaMajor → ${candidate.name}",
            javaMajor = javaMajor,
            versionId = versionId,
            libPath = effectiveLib.absolutePath,
            glfwShim = if (shim.isFile) shim.absolutePath else "",
            versionTag = SdlSpec.versionTag(candidate),
            abi = abi,
            nativeDir = effectiveLib.parentFile.absolutePath,
            dependencyDir = dependencyDir,
        )
    }

    /** 注入到引擎进程的环境变量；底层渲染器参数完全不变。 */
    fun envFor(
        resolution: SdlResolution,
        settings: SettingsSnapshot,
        glLib: String,
    ): Map<String, String> {
        val env = linkedMapOf<String, String>()
        if (!resolution.enabled) {
            env["MCJL_WINDOW_BACKEND"] = "GLFW"
            if (resolution.reason.startsWith("library-missing")) {
                env["MCJL_SDL_SKIPPED"] = resolution.reason
            }
            return env
        }
        env["MCJL_WINDOW_BACKEND"] = resolution.active.name
        env["MCJL_SDL_LIB"] = resolution.libPath
        env["MCJL_SDL_VERSION"] = resolution.versionTag
        env["MCJL_SDL_JAVA_MAJOR"] = resolution.javaMajor.toString()
        env["MCJL_SDL_ABI"] = resolution.abi
        env["MCJL_SDL_LIB_DIR"] = resolution.nativeDir
        env["MCJL_SDL_DEPENDENCY_DIR"] = resolution.dependencyDir
        // sdl2-compat 的 libSDL2.so 以 libSDL3.so 为 NEEDED，必须让动态链接器找得到。
        if (resolution.active == SdlMode.SDL2) {
            env["MCJL_SDL_NEEDS_SDL3"] = SdlSpec.SDL3_VERSION
        }
        env["SDL_VIDEO_DRIVER"] = "android"
        env["SDL_OPENGL_LIBRARY"] = glLib
        env["SDL_EGL_LIBRARY"] = glLib
        if (settings.sdlGamepad) {
            env["MCJL_SDL_GAMEPAD"] = "1"
            env["SDL_JOYSTICK_HIDAPI"] = if (settings.sdlHidapi) "1" else "0"
        }
        if (settings.sdlTouchMouse) env["MCJL_SDL_TOUCH_MOUSE"] = "1"
        if (settings.sdlAudioBackend == "SDL") env["SDL_AUDIO_DRIVER"] = "android"
        env["MCJL_SDL_LOG_LEVEL"] = settings.sdlLogLevel
        return env
    }

    /** JVM 侧标记；glfw shim 缺失时保留原 GLFW 库，不写危险参数。 */
    fun jvmArgsFor(resolution: SdlResolution): List<String> {
        if (!resolution.enabled) return emptyList()
        val out = mutableListOf<String>()
        out += "-Dmcjl.window.backend=${resolution.active.name}"
        out += "-Dmcjl.sdl.version=${resolution.versionTag}"
        out += "-Dmcjl.sdl.abi=${resolution.abi}"
        out += "-Dmcjl.sdl.lib=${resolution.libPath}"
        if (resolution.glfwShim.isNotEmpty()) {
            out += "-Dorg.lwjgl.glfw.libname=${resolution.glfwShim}"
        } else {
            out += "-Dmcjl.sdl.glfw.shim=absent"
        }
        return out
    }
}
