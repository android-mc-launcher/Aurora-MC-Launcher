package com.yuanbao.mcjlauncher.core

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 把「版本元数据 + 运行时布局 + 设置 + 账号」拼成 C++ 引擎可执行的完整启动计划。 */
object LaunchConfigBuilder {
    fun build(
        versionId: String,
        meta: VersionMeta,
        layout: RuntimeLayout,
        settings: SettingsSnapshot,
        account: GameAccount?,
        accessToken: String,
        gameRepository: GameRepository,
        instanceConfig: InstanceConfig = InstanceConfig(),
        instanceDir: File? = null,
    ): LaunchPlan {
        val gameDir = (instanceDir ?: File(gameRepository.versionDir(versionId), "instance")).apply { mkdirs() }
        val workDir = gameRepository.versionDir(versionId)
        val memoryMb = if (instanceConfig.memoryMb > 0) instanceConfig.memoryMb else settings.memoryMb
        val assetsDir = gameRepository.gameAssetsDir()
        // 只拼一次：旧实现在 vars / env / LaunchPlan 里各调一次 classpath()（含磁盘遍历）。
        val classpath = gameRepository.classpath(versionId, layout)

        /*
         * SFPEW(SimpleFPEWrapper) 兼容层：**叠加**在当前渲染器之上，不替换底层后端。
         * - 底层渲染器仍由 POJAV_RENDERER / MCJL_GL_BASE_LIB 指定（GL4ES / EGL wrapper）；
         * - 开启且 wrapper 就位时，LWJGL 的 GL/EGL 符号改从 libSimpleFPEWrapper.so 解析，
         *   wrapper 再经 SFPEW_EGL 指定的真实后端库拿到 EGL 与 GL 入口；
         * - 检测到自带固定功能管线的模组（Angelica / Actinium）时自动跳过，避免双层 FPE；
         * - wrapper 缺失时安全降级到原渲染器，不注入任何会破坏启动的参数。
         */
        val baseRendererLib = rendererLib(layout, settings.renderer)
        val sfpewLib = sfpewLib(layout)
        val sfpewAbi = SdlSpec.currentAbi()
        // 上游 init.cpp 用 getenv("SFPEW_EGL") 作为 backend 库名 / 路径去 dlopen，
        // 必须是真实库路径，不能写成不可解析的字面值；优先用 renderer 目录下的 EGL wrapper。
        val sfpewEglBackend = sfpewEglBackend(layout, baseRendererLib)
        val ownFpeMod = hasOwnFpeMod(gameDir)
        val wrapperPresent = File(sfpewLib).isFile
        val sfpewActive = settings.sfpewEnabled && !ownFpeMod && wrapperPresent
        val sfpewSkipReason =
            when {
                !settings.sfpewEnabled -> ""
                ownFpeMod -> "fpe-mod-conflict"
                !wrapperPresent -> "wrapper-missing"
                else -> ""
            }

        /*
         * SDL2 / SDL3 窗口与输入后端：**叠加**在现有渲染器之上，只接管窗口、手柄与触控，
         * POJAV_RENDERER / GL 库保持不变。自动模式按 Java major 映射：
         * 8 → GLFW；17 → SDL2；21 / 25 → SDL3（高版本 Java 才启用）。
         * 库缺失时写 MCJL_SDL_SKIPPED 并安全降级，不注入任何会破坏启动的参数。
         */
        val glLib = if (sfpewActive) sfpewLib else baseRendererLib
        val hasMods = File(gameDir, "mods").listFiles().orEmpty().any { it.name.endsWith(".jar", ignoreCase = true) }
        val sdlSettings =
            if (settings.sdlForceOnModded && hasMods && settings.sdlMode == "AUTO") {
                settings.copy(sdlMode = "SDL3")
            } else {
                settings
            }
        val sdl = SdlResolver.resolve(sdlSettings, meta.javaMajor, versionId, layout)
        val sdlEnv = SdlResolver.envFor(sdl, sdlSettings, glLib)
        val sdlJvm = SdlResolver.jvmArgsFor(sdl)
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val uuid = (account?.id ?: "00000000-0000-0000-0000-000000000000").replace("-", "")
        val name = account?.name ?: "Player"
        val userType = if (account?.type == "microsoft") "msa" else "legacy"
        val token = accessToken.ifBlank { "0" }

        val vars =
            mapOf(
                "auth_player_name" to name,
                "version_name" to versionId,
                "game_directory" to gameDir.absolutePath,
                "assets_root" to assetsDir.absolutePath,
                "game_assets" to File(assetsDir, "virtual/${meta.assets}").absolutePath,
                "assets_index_name" to meta.assetIndexId,
                "auth_uuid" to uuid,
                "auth_access_token" to token,
                "auth_session" to token,
                "user_type" to userType,
                "version_type" to "release",
                "resolution_width" to settings.resolutionWidth.toString(),
                "resolution_height" to settings.resolutionHeight.toString(),
                "natives_directory" to layout.lwjglNativeDir,
                "launcher_name" to "Aurora",
                "launcher_version" to "1.0.0",
                "classpath" to classpath,
                "classpath_separator" to File.pathSeparator,
                "library_directory" to gameRepository.librariesRoot().absolutePath,
            )

        val jvm = mutableListOf<String>()
        jvm += "-Xmx${memoryMb}m"
        jvm += "-Xms${minOf(512, memoryMb / 4)}m"
        jvm += "-Djava.home=${layout.javaHome}"
        jvm += "-Djava.library.path=${layout.nativeLibDir}"
        jvm += "-Djna.library.path=${layout.jnaNativeDir}"
        jvm += "-Djna.nosys=true"
        jvm += "-Dorg.lwjgl.librarypath=${layout.lwjglNativeDir}"
        jvm += "-Dorg.lwjgl.glfw.libname=${File(layout.lwjglNativeDir, "libglfw.so").absolutePath}"
        // SDL 的 GLFW shim 存在时后者覆盖同名 JVM 属性；缺失时保留原 GLFW。
        jvm.addAll(sdlJvm)
        jvm += "-Dorg.lwjgl.openal.libname=${File(layout.lwjglNativeDir, "libopenal.so.1").absolutePath}"
        // 底层渲染器保持不变；开启 SFPEW 时 GL / EGL 两个库都指向 wrapper，
        // 由上游 SFPEW_EGL 单独决定 wrapper 底下挂哪一个 EGL/GL 后端。
        jvm += "-Dorg.lwjgl.opengl.libname=${if (sfpewActive) sfpewLib else baseRendererLib}"
        if (sfpewActive) {
            jvm += "-Dorg.lwjgl.egl.libname=$sfpewLib"
            jvm += "-Dmcjl.sfpew.enabled=true"
            jvm += "-Dmcjl.sfpew.backend=$baseRendererLib"
            jvm += "-Dmcjl.sfpew.egl=$sfpewEglBackend"
            jvm += "-Dmcjl.sfpew.abi=$sfpewAbi"
        }
        jvm += "-Djava.awt.headless=false"
        jvm += "-Djava.io.tmpdir=${File(workDir, "tmp").apply { mkdirs() }.absolutePath}"
        jvm += "-Duser.home=${workDir.absolutePath}"
        jvm += "-Dlog4j2.formatMsgNoLookups=true"
        jvm += "-Dfml.ignoreInvalidMinecraftCertificates=true"
        jvm += "-Dminecraft.client.jar=${File(workDir, "$versionId.jar").absolutePath}"
        // JVM 预设：低占用 / 均衡 / 性能。内存 ≥3GB 时额外启用 G1 的短停顿目标。
        when (settings.jvmPreset.uppercase()) {
            "LOW" -> {
                jvm += "-XX:+UseSerialGC"
                jvm += "-XX:MaxHeapFreeRatio=20"
            }

            "PERFORMANCE" -> {
                jvm += "-XX:+UseG1GC"
                jvm += "-XX:MaxGCPauseMillis=50"
                jvm += "-XX:+ParallelRefProcEnabled"
            }

            else -> {
                if (memoryMb >= 3072) {
                    jvm += "-XX:+UseG1GC"
                    jvm += "-XX:MaxGCPauseMillis=80"
                }
            }
        }
        meta.jvmArgs.forEach { jvm += substitute(it, vars) }
        // 实例级 + 全局级自定义 JVM 参数（按空白切分，真实拼进启动计划）
        instanceConfig.extraJvmArgs.forEach { jvm += substitute(it, vars) }
        settings.extraJvmArgsGlobal
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { jvm += substitute(it, vars) }

        /*
         * 高 Android 机型 × 低 Java（JRE8 / 老 MC）的透明兼容：
         * version.json 的 jvmArgs 与用户自定义参数里可能带 Java 9+ 专属选项，
         * JRE8 遇到会直接 `Unrecognized option` 退出。这里按 javaMajor 自动消毒，
         * 不改设置项、不加开关，高 Java 原样通过。
         */
        val (effectiveJvm, sanitizedCount) = AndroidCompat.sanitizeJvmArgs(jvm, meta.javaMajor)

        val game = mutableListOf<String>()
        if (meta.legacyArgs.isNotBlank()) {
            game +=
                meta.legacyArgs
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { substitute(it, vars) }
        } else {
            game += meta.gameArgs.map { substitute(it, vars) }
        }
        // 补齐 Mojang 必需参数（老版本 minecraftArguments 可能缺项）
        addIfAbsent(game, "--username", name)
        addIfAbsent(game, "--version", versionId)
        addIfAbsent(game, "--gameDir", gameDir.absolutePath)
        addIfAbsent(game, "--assetsDir", assetsDir.absolutePath)
        addIfAbsent(game, "--assetIndex", meta.assetIndexId)
        addIfAbsent(game, "--uuid", uuid)
        addIfAbsent(game, "--accessToken", token)
        addIfAbsent(game, "--userType", userType)
        addIfAbsent(game, "--versionType", "release")
        addIfAbsent(game, "--width", settings.resolutionWidth.toString())
        addIfAbsent(game, "--height", settings.resolutionHeight.toString())
        addIfAbsent(game, "--nativesDirectory", layout.lwjglNativeDir)
        instanceConfig.extraGameArgs.forEach { game += substitute(it, vars) }

        val env = linkedMapOf<String, String>()
        env["JAVA_HOME"] = layout.javaHome
        // sdl2-compat 的 libSDL2.so 以 libSDL3.so 为 NEEDED，两个 ABI 目录都必须在搜索路径里。
        env["LD_LIBRARY_PATH"] =
            listOf(layout.nativeLibDir, sdl.dependencyDir, sdl.nativeDir)
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(File.pathSeparator)
        env["PATH"] = "${File(layout.javaHome, "bin").absolutePath}:${System.getenv("PATH") ?: "/system/bin"}"
        env["HOME"] = workDir.absolutePath
        env["TMPDIR"] = File(workDir, "tmp").absolutePath
        env["LIBGL_ES"] = "1"
        env["POJAV_RENDERER"] = settings.renderer
        env["POJAV_EXEC_TYPE"] = "jli"
        env["MCJL_ABI"] = abi
        env["MCJL_VERSION"] = versionId
        env["MCJL_RUNTIME_TAG"] = layout.versionTag
        env["MCJL_LOG_LEVEL"] = settings.logLevel
        env["MCJL_CLASSPATH"] = classpath
        env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
        env["JAVA_TOOL_OPTIONS"] = AndroidCompat.toolOptions(meta.javaMajor)
        env["MCJL_INSTANCE"] = gameDir.absolutePath
        env["MCJL_SFPEW_REQUESTED"] = settings.sfpewEnabled.toString()
        if (sfpewActive) {
            // SFPEW_EGL 是上游 libSimpleFPEWrapper.so 真正读取的环境变量（init.cpp 的 getenv），
            // 值是后端 EGL/GL 库名或绝对路径；POJAVEXEC_EGL 是 FCL / Pojav 生态的同名口径。
            // 两者都指向真实存在的库，wrapper 才能 dlopen 成功并转发 GL/EGL 调用。
            env["SFPEW_EGL"] = sfpewEglBackend
            env["POJAVEXEC_EGL"] = sfpewEglBackend
            env["MCJL_SFPEW_LAYER"] = "1"
            env["MCJL_SFPEW_LIB"] = sfpewLib
            env["MCJL_SFPEW_ABI"] = sfpewAbi
            env["MCJL_SFPEW_EGL_BACKEND"] = sfpewEglBackend
            env["MCJL_SFPEW_BACKEND"] = baseRendererLib
            env["MCJL_GL_BASE_LIB"] = baseRendererLib
        } else if (sfpewSkipReason.isNotEmpty()) {
            env["MCJL_SFPEW_SKIPPED"] = sfpewSkipReason
        }
        // SDL 环境变量在实例自定义 env 之前写入，保证用户级覆盖仍为最高优先级。
        sdlEnv.forEach { (k, v) -> env[k] = v }
        env["MCJL_SDL_REQUESTED"] = settings.sdlMode
        env["MCJL_SDL_BUNDLED_VERSION"] = sdl.versionTag
        // 高 Android × 低 Java 的自动适配结果，供引擎日志与诊断报告读取（非设置项）。
        AndroidCompat.envFor(meta.javaMajor, layout.javaHome, sanitizedCount).forEach { (k, v) -> env[k] = v }
        instanceConfig.extraEnv.forEach { (k, v) -> if (k.isNotBlank()) env[k] = substitute(v, vars) }

        return LaunchPlan(
            versionId = versionId,
            javaHome = layout.javaHome,
            javaBin = layout.javaBin,
            workDir = workDir.absolutePath,
            gameDir = gameDir.absolutePath,
            classpath = classpath,
            mainClass = meta.mainClass,
            jvmArgs = effectiveJvm,
            gameArgs = game,
            env = env,
            jliCandidates = layout.jliCandidates,
            launchMode = "jli",
            logLevel = logLevelNumber(settings.logLevel),
        )
    }

    private fun rendererLib(
        layout: RuntimeLayout,
        renderer: String,
    ): String =
        when (renderer) {
            "gl4es" -> layout.gl4esLib
            "anativewindow", "egl" -> File(layout.rendererDir, "libEGL_wrapper.so").absolutePath
            else -> layout.gl4esLib
        }

    /** 上游 AAR 内真实库名（不是 libSFPEW.so）。 */
    const val SFPEW_LIB_NAME = "libSimpleFPEWrapper.so"

    /** 兼容早期用户自行放置的旧命名。 */
    const val SFPEW_LEGACY_LIB_NAME = "libSFPEW.so"

    /** 内置 4 个 ABI 的真实 ELF；按设备 ABI 选，再依次回退平铺布局与旧命名。 */
    const val SFPEW_BUILD_DATE = "2026-10-31"

    /** SFPEW_EGL 的真实取值：优先 EGL wrapper，缺失时退回当前渲染器库。 */
    fun sfpewEglBackend(
        layout: RuntimeLayout,
        baseRendererLib: String,
    ): String =
        File(layout.rendererDir, "libEGL_wrapper.so").let {
            if (it.isFile) it.absolutePath else baseRendererLib
        }

    fun sfpewLib(layout: RuntimeLayout): String {
        val dir = File(layout.rendererDir)
        val byAbi = File(File(dir, SdlSpec.currentAbi()), SFPEW_LIB_NAME)
        val flat = File(dir, SFPEW_LIB_NAME)
        val legacy = File(dir, SFPEW_LEGACY_LIB_NAME)
        return when {
            byAbi.isFile -> byAbi.absolutePath
            flat.isFile -> flat.absolutePath
            else -> legacy.absolutePath
        }
    }

    /** 自带固定功能管线的模组与 SFPEW 双层叠加会冲突，命中则自动跳过兼容层。 */
    fun hasOwnFpeMod(gameDir: File): Boolean =
        File(gameDir, "mods").listFiles().orEmpty().any { f ->
            f.isFile && f.name.endsWith(".jar", ignoreCase = true) &&
                (
                    f.name.contains("angelica", ignoreCase = true) ||
                        f.name.contains("actinium", ignoreCase = true)
                )
        }

    private fun addIfAbsent(
        list: MutableList<String>,
        flag: String,
        value: String,
    ) {
        if (!list.contains(flag)) {
            list += flag
            list += value
        }
    }

    private fun substitute(
        template: String,
        vars: Map<String, String>,
    ): String {
        var out = template
        vars.forEach { (k, v) -> out = out.replace("\${$k}", v) }
        return out
    }

    private fun logLevelNumber(level: String): Int =
        when (level.uppercase()) {
            "DEBUG" -> 0
            "INFO" -> 1
            "WARN" -> 2
            "ERROR" -> 3
            else -> 1
        }

    /** 把 LaunchPlan 序列化成 C++ 引擎读取的完整 JSON 启动计划。 */
    fun toJson(plan: LaunchPlan): String =
        JSONObject()
            .apply {
                put("versionId", plan.versionId)
                put("javaHome", plan.javaHome)
                put("javaBin", plan.javaBin)
                put("workDir", plan.workDir)
                put("gameDir", plan.gameDir)
                put("classpath", plan.classpath)
                put("mainClass", plan.mainClass)
                put("launchMode", plan.launchMode)
                put("logLevel", plan.logLevel)
                put("jvmArgs", JSONArray(plan.jvmArgs))
                put("gameArgs", JSONArray(plan.gameArgs))
                put("jliCandidates", JSONArray(plan.jliCandidates))
                put("env", JSONObject().apply { plan.env.forEach { (k, v) -> put(k, v) } })
            }.toString()
}
