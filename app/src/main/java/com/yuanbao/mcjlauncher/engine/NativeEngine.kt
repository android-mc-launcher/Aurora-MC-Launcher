package com.yuanbao.mcjlauncher.engine

import android.content.Context
import com.yuanbao.mcjlauncher.core.EngineStats
import com.yuanbao.mcjlauncher.core.LaunchConfigBuilder
import com.yuanbao.mcjlauncher.core.LaunchPlan
import org.json.JSONObject

/**
 * 自研底层引擎的 Kotlin 门面。
 *
 * 所有 external 方法**没有** `Java_xxx` 静态导出符号，
 * 全部由 C++ 侧 `JNI_OnLoad` 调用 `RegisterNatives` 动态注册（见 jni_bridge.cpp）。
 * 传入的是 [LaunchPlan] 序列化出的完整启动计划。
 */
object NativeEngine {
    init {
        System.loadLibrary("mcjl_engine")
    }

    external fun engineVersion(): String

    external fun nativeInit(
        dataDir: String,
        nativeDir: String,
    ): Boolean

    external fun nativeStartGame(planJson: String): Int

    external fun nativeStopGame(): Boolean

    external fun nativePollLog(): String

    external fun nativeIsRunning(): Boolean

    external fun nativeGetStats(): String

    external fun nativeSetLogLevel(level: Int)

    external fun nativeWriteStdin(text: String): Boolean

    external fun nativeKill(): Boolean

    external fun nativeExitCode(): Int

    external fun nativeState(): Int

    fun init(context: Context): Boolean =
        nativeInit(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
        )

    /** 把拼装好的完整启动计划交给 C++ 引擎真正拉起 JVM。 */
    fun start(plan: LaunchPlan): GameStartResult {
        val code = nativeStartGame(LaunchConfigBuilder.toJson(plan))
        return GameStartResult(code, describe(code))
    }

    fun statsJson(): String = runCatching { nativeGetStats() }.getOrDefault("{}")

    fun parseStats(json: String): EngineStats {
        val j = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        return EngineStats(
            state = j.optString("state", "IDLE"),
            pid = j.optInt("pid", -1),
            cpuPercent = j.optDouble("cpuPercent", 0.0).toFloat(),
            rssKb = j.optLong("rssKb", 0L),
            elapsedMs = j.optLong("elapsedMs", 0L),
            exitCode = j.optInt("exitCode", -1),
        )
    }

    fun stateLabel(): String =
        when (nativeState()) {
            0 -> "空闲"
            1 -> "启动中"
            2 -> "运行中"
            3 -> "停止中"
            4 -> "失败"
            else -> "未知"
        }

    private fun describe(code: Int): String =
        when (code) {
            0 -> "引擎已拉起 JVM 进程"
            1 -> "启动计划解析失败"
            2 -> "引擎已在运行"
            3 -> "引擎尚未初始化"
            4 -> "创建管道失败"
            5 -> "fork 失败"
            6 -> "工作目录不可用"
            7 -> "找不到 java 或 libjli.so"
            else -> "未知返回码 $code"
        }
}

/** 启动结果（proguard 中保留，供 JNI/UI 共用）。 */
data class GameStartResult(
    val code: Int,
    val message: String,
)
