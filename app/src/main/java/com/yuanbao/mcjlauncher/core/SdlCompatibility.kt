package com.yuanbao.mcjlauncher.core

/** Java major 与 SDL 后端的兼容矩阵行。 */
data class SdlMatrixRow(
    val javaMajor: Int,
    val mcRange: String,
    val backend: SdlMode,
    val note: String,
)

/** SDL 能力清单（用于图形页与关于页展示）。 */
data class SdlCapability(
    val title: String,
    val sdl2: String,
    val sdl3: String,
)

object SdlCompatibility {
    val MATRIX: List<SdlMatrixRow> =
        listOf(
            SdlMatrixRow(8, "1.0 – 1.16.5", SdlMode.OFF, "老版本走 GLFW + GL4ES，避免 SDL 窗口栈重复接管"),
            SdlMatrixRow(17, "1.17 – 1.20.4", SdlMode.SDL2, "LWJGL3 窗口与手柄枚举交给 SDL2"),
            SdlMatrixRow(21, "1.20.5 – 1.21.x", SdlMode.SDL3, "新版手柄 API 与 EGL 上下文由 SDL3 提供"),
            SdlMatrixRow(25, "26.x 及以上", SdlMode.SDL3, "最高版本 Java 直接用 SDL3 3.4.12（内置真实 ELF）"),
        )

    val CAPABILITIES: List<SdlCapability> =
        listOf(
            SdlCapability("窗口 / EGL 上下文", "SDL_CreateWindow + EGL", "SDL_CreateWindow + SDL_GPU 可选"),
            SdlCapability("手柄枚举", "SDL_NumJoysticks / SDL_JoystickOpen", "SDL_GetGamepads / SDL_OpenGamepad"),
            SdlCapability("触控转鼠标", "SDL_HINT_TOUCH_MOUSE_EVENTS", "SDL_HINT_TOUCH_MOUSE_EVENTS"),
            SdlCapability("音频", "SDL_AUDIO_DRIVER=android", "流式 audio stream + 逻辑设备"),
            SdlCapability("HIDAPI", "SDL_JOYSTICK_HIDAPI", "SDL_HINT_JOYSTICK_HIDAPI 系列"),
            SdlCapability("许可证", "zlib", "zlib"),
        )

    /** 关键环境变量说明，UI 直接渲染成列表。 */
    val HINTS: List<Pair<String, String>> =
        listOf(
            "SDL_VIDEO_DRIVER" to "android（由 Android EGL 后端创建上下文）",
            "SDL_OPENGL_LIBRARY" to "指向当前生效的 GL 库（SFPEW 开启时为 wrapper）",
            "SDL_EGL_LIBRARY" to "与 GL 库同源，保证上下文由同一侧创建与销毁",
            "SDL_JOYSTICK_HIDAPI" to "开启手柄 HIDAPI，改善 PS / Xbox 手柄识别",
            "MCJL_WINDOW_BACKEND" to "SDL2 / SDL3 / GLFW，供 C++ 引擎与日志分类",
            "MCJL_SDL_VERSION" to "记录实际 SDL 版本标签，写入诊断报告",
        )

    fun rowFor(javaMajor: Int): SdlMatrixRow = MATRIX.lastOrNull { it.javaMajor <= javaMajor } ?: MATRIX.first()

    fun recommended(javaMajor: Int): String = rowFor(javaMajor).backend.label

    fun matrixText(): String =
        MATRIX.joinToString("\n") {
            "Java ${it.javaMajor} | ${it.mcRange} | ${it.backend.name} | ${it.note}"
        }
}
