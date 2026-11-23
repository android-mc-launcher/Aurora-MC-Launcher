package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.GamepadDevice
import com.yuanbao.mcjlauncher.core.LogAnalyzer
import com.yuanbao.mcjlauncher.core.PerfSample
import com.yuanbao.mcjlauncher.core.SdlCompatibility
import com.yuanbao.mcjlauncher.core.SdlMode
import com.yuanbao.mcjlauncher.core.SettingsRepository

/** 悬浮控制台的六个功能页签，选择结果持久化到设置。 */
enum class OverlayTab(
    val label: String,
) {
    STATUS("状态"),
    PERF("性能"),
    LOG("日志"),
    COMMAND("命令"),
    GAMEPAD("手柄"),
    QUICK("快捷"),
    ;

    companion object {
        fun from(raw: String): OverlayTab = entries.firstOrNull { it.name == raw } ?: STATUS
    }
}

/** 真实 CPU 采样折线图：数据来自 C++ 引擎 /proc 统计。 */
@Composable
fun OverlayPerfChart(
    points: List<PerfSample>,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.fillMaxWidth().height(110.dp).padding(horizontal = 4.dp)) {
        drawLine(axis, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
        drawLine(axis, Offset(1f, 0f), Offset(1f, size.height), 2f)
        if (points.size >= 2) {
            val ceiling = (points.maxOf { it.cpuPercent } / 100f).coerceAtLeast(0.25f)
            val step = size.width / (points.size - 1)
            for (i in 1 until points.size) {
                val y0 = size.height - (points[i - 1].cpuPercent / 100f / ceiling) * size.height
                val y1 = size.height - (points[i].cpuPercent / 100f / ceiling) * size.height
                drawLine(
                    color = line,
                    start = Offset((i - 1) * step, y0.coerceIn(0f, size.height)),
                    end = Offset(i * step, y1.coerceIn(0f, size.height)),
                    strokeWidth = 3f,
                )
            }
        } else {
            drawLine(
                color = Color.Gray,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f,
            )
        }
    }
}

/** 手柄页：设备由 SDL 日志事件流真实维护。 */
@Composable
fun OverlayGamepadCard(devices: List<GamepadDevice>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SDL 手柄 · ${devices.size} 台", style = MaterialTheme.typography.titleSmall)
            if (devices.isEmpty()) {
                Text(
                    "尚无设备事件。启动带 SDL 后端的版本后，插拔手柄会写入 [sdl] 日志并出现在这里。",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                devices.forEach { d ->
                    ListItem(
                        headlineContent = { Text("#${d.id} ${d.name}") },
                        supportingContent = { Text("接入时间 ${d.at} · SDL 事件流") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 命令簿：收藏与历史都来自 SharedPreferences，点击直接发到进程 stdin。 */
@Composable
fun OverlayCommandBook(
    pinned: List<String>,
    history: List<String>,
    onRun: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("命令簿", style = MaterialTheme.typography.titleSmall)
            Text("收藏（点击执行）", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pinned.isEmpty()) {
                    Text("暂无收藏", style = MaterialTheme.typography.labelSmall)
                } else {
                    pinned.take(8).forEach { cmd ->
                        AssistChip(onClick = { onRun(cmd) }, label = { Text(cmd.take(18)) })
                    }
                }
            }
            HorizontalDivider()
            Text("最近发送", style = MaterialTheme.typography.labelMedium)
            history.take(6).forEach { cmd ->
                ListItem(
                    headlineContent = { Text(cmd, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        AssistChip(onClick = { onRun(cmd) }, label = { Text("重发") })
                    },
                )
            }
        }
    }
}

/** 快捷设置：日志级别真实下发引擎，其余项写设置并立即生效于悬浮窗。 */
@Composable
fun OverlayQuickSettings(
    settings: SettingsRepository,
    logLevels: List<String>,
    onLogLevel: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("快捷设置", style = MaterialTheme.typography.titleSmall)
            Text("日志级别（立即下发引擎）", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                logLevels.forEach { level ->
                    FilterChip(
                        selected = settings.value.logLevel == level,
                        onClick = { onLogLevel(level) },
                        label = { Text(level) },
                    )
                }
            }
            HorizontalDivider()
            Text("悬浮窗透明度 ${settings.value.overlayAlpha}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.value.overlayAlpha.toFloat(),
                onValueChange = { v -> settings.update { s -> s.copy(overlayAlpha = v.toInt().coerceIn(40, 100)) } },
                valueRange = 40f..100f,
            )
            FilterChip(
                selected = settings.value.overlayShowGraph,
                onClick = { settings.update { s -> s.copy(overlayShowGraph = !s.overlayShowGraph) } },
                label = { Text("性能曲线") },
            )
            FilterChip(
                selected = settings.value.overlayGesture,
                onClick = { settings.update { s -> s.copy(overlayGesture = !s.overlayGesture) } },
                label = { Text("拖拽手势") },
            )
            FilterChip(
                selected = settings.value.overlayCompact,
                onClick = { settings.update { s -> s.copy(overlayCompact = !s.overlayCompact) } },
                label = { Text("紧凑布局") },
            )
            Text(
                "SDL 自动规则：${SdlCompatibility.recommended(21)}（Java 21+）· " +
                    "${SdlCompatibility.recommended(17)}（Java 17）· ${SdlMode.OFF.label}（Java 8）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 日志线索卡片：把真实日志归类成可行动的排查结论。 */
@Composable
fun OverlayLogInsights(logs: String) {
    val insights = LogAnalyzer.analyze(logs)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("日志线索 · ${LogAnalyzer.summary(logs)}", style = MaterialTheme.typography.titleSmall)
            insights.take(4).forEach { item ->
                ListItem(
                    headlineContent = { Text("${item.category} · ${item.title} ×${item.count}") },
                    supportingContent = { Text(item.advice, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}
