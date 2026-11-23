package com.yuanbao.mcjlauncher.core

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.yuanbao.mcjlauncher.BuildConfig
import com.yuanbao.mcjlauncher.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 关于页的真实运行时诊断（不再是静态文本）。 */
object Diagnostics {
    fun report(
        context: Context,
        container: LauncherContainer,
    ): String {
        val app = context.applicationContext
        val (assetCount, assetBytes) = container.runtime.assetStats()
        val runtimeExists = File(container.runtime.runtimeRoot, "java").isDirectory
        val settings = container.settings.value
        val assetMb = "%.2f".format(assetBytes / 1024.0 / 1024.0)
        val freeMb = "%.1f".format(app.filesDir.usableSpace / 1024.0 / 1024.0)
        return buildString {
            appendLine("Aurora 诊断报告")
            appendLine("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine("应用：${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("引擎：${NativeEngine.engineVersion()}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(
                "高 Android × 低 Java 兼容：API ${AndroidCompat.sdkInt} / 页 ${AndroidCompat.pageSize()}B / " +
                    "W^X 去写位 ${AndroidCompat.wxRequired} / 高版本系统 ${AndroidCompat.highAndroid}",
            )
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")} → 运行时 ${container.runtime.abi()}")
            appendLine("assets：$assetCount 个真实文件 / $assetMb MB")
            appendLine("运行时已释放：$runtimeExists (${container.runtime.runtimeRoot.absolutePath})")
            appendLine("清单版本数：${container.games.value.size} / 已安装：${container.games.installed.value.size}")
            appendLine("账号数：${container.accounts.value.size} / 当前：${container.accounts.current()?.name ?: "无"}")
            appendLine("设置：Java=${settings.javaMode.label} / ${settings.memoryMb}MB / ${settings.renderer} / ${settings.logLevel}")
            appendLine("SDL：配置=${settings.sdlMode} / 高版本 Java 限定=${settings.sdlHighJavaOnly} / 生效=${container.sdlInfo.value?.summary ?: "尚未启动"}")
            appendLine("手柄：${container.gamepads.snapshot()} / 性能：${container.perfHistory.report()}")
            appendLine("命令簿：收藏 ${container.commands.pinned.value.size} 条 / 历史 ${container.commands.history.value.size} 条")
            appendLine("日志分析：${LogAnalyzer.summary(container.logs.value)}")
            val si = container.sources.info.value
            appendLine("下载源：配置=${si.configured.label} / 生效=${si.effective.label} / ${si.reason}")
            appendLine(
                "清单来源：${container.games.manifestSource.value
                    ?.label ?: "未拉取"}",
            )
            appendLine("慢速阈值：${settings.autoProbeMs} ms")
            appendLine("官方端点：${DownloadSources.OFFICIAL_MANIFEST}")
            appendLine("镜像端点：${DownloadSources.MIRROR_MANIFEST}")
            appendLine("磁盘可用：$freeMb MB")
            appendLine("引擎状态：${NativeEngine.statsJson()}")
            appendLine("独立实例：${container.instances.value.size} 个")
            appendLine("崩溃报告：${container.crashes.value.size} 份")
            appendLine("安装队列：${container.installQueue.value.size} 个待装")
            appendLine("下载失败：${container.games.failures.value.size} 项")
            appendLine("收藏版本：${container.extras.value.size} 个")
            appendLine("启动统计：${container.extras.stats.value.summary}")
            appendLine("启动预检：${container.preflight.value?.summary ?: "未执行/未开启"}")
            appendLine(
                "设置：并发 ${settings.downloadConcurrency} / 重试 ${settings.downloadRetries} / " +
                    "实例隔离 ${settings.isolateInstances} / 预检 ${settings.preflightEnabled} / " +
                    "崩溃归档 ${settings.crashArchiveEnabled} / 日志落盘 ${settings.logToFile} / JVM ${settings.jvmPreset}",
            )
            appendLine("自定义镜像：${settings.customMirrorUrl.ifBlank { "（未设置）" }}")
            val sfpewItem =
                container.preflight.value
                    ?.items
                    ?.firstOrNull { it.label == "SFPEW wrapper" }
            appendLine(
                "SFPEW 兼容层：${if (settings.sfpewEnabled) "开启（叠加）" else "关闭"} · " +
                    "底层渲染器 ${settings.renderer} · wrapper ${sfpewItem?.detail ?: "尚未预检"} · " +
                    "真实库 ${LaunchConfigBuilder.SFPEW_LIB_NAME}（${LaunchConfigBuilder.SFPEW_BUILD_DATE} 构建，4 ABI 内置）",
            )
            appendLine("日志落盘：${container.logCenter.diskStats().let { "${it.first} 个文件 / ${it.second} B" }}")
        }
    }

    /** 工具页展示的真实系统信息（ActivityManager / StatFs / Runtime / Build）。 */
    fun systemInfo(
        context: Context,
        container: LauncherContainer,
    ): List<Pair<String, String>> {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val stat = StatFs(app.filesDir.absolutePath)
        val rt = Runtime.getRuntime()
        val mb = 1024.0 * 1024.0
        return listOf(
            "设备" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "系统" to "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            "ABI 列表" to Build.SUPPORTED_ABIS.joinToString(", "),
            "运行时 ABI" to container.runtime.abi(),
            "设备总内存" to if (mi.totalMem > 0) "%.0f MB".format(mi.totalMem / mb) else "未知",
            "当前可用内存" to if (mi.availMem > 0) "%.0f MB".format(mi.availMem / mb) else "未知",
            "低内存标记" to mi.lowMemory.toString(),
            "应用堆上限" to "%.0f MB".format(rt.maxMemory() / mb),
            "已用堆" to "%.0f MB".format((rt.totalMemory() - rt.freeMemory()) / mb),
            "数据区总空间" to "%.0f MB".format(stat.totalBytes / mb),
            "数据区可用" to "%.0f MB".format(stat.availableBytes / mb),
            "进程数上限线索" to (am?.memoryClass ?: 0).let { "$it MB (memoryClass)" },
        )
    }

    fun copyToClipboard(
        context: Context,
        text: String,
    ): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Aurora 诊断", text))
        return true
    }

    /** 真联网检查：拉取 Mojang 清单，比对当前缓存里的 latest.release。 */
    suspend fun checkUpdate(container: LauncherContainer): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = container.sources.resolve()
                val r = Http.get(DownloadSources.manifestUrl(source))
                if (!r.ok) return@runCatching "检查失败：HTTP ${r.code} @ ${source.label}"
                val latest = r.json().getJSONObject("latest")
                "Mojang 最新正式版 ${latest.optString("release", "?")} / 快照 " +
                    "${latest.optString("snapshot", "?")} · 来源 ${source.label}"
            }.getOrElse { "检查失败：${it.message ?: "网络不可达"}" }
        }
}
