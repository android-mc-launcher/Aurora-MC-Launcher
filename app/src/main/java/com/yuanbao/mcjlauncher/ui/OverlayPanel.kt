package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.Diagnostics
import com.yuanbao.mcjlauncher.core.EngineStats
import com.yuanbao.mcjlauncher.core.LauncherContainer
import com.yuanbao.mcjlauncher.engine.NativeEngine

/**
 * 游戏内悬浮控制台：六个功能页签，状态 / 性能 / 日志 / 命令 / 手柄 / 快捷设置。
 * 所有数据都来自真实来源：引擎 /proc 采样、共享日志总线、SDL 事件流、SharedPreferences。
 */
@Composable
fun OverlayPanel(
    container: LauncherContainer,
    expanded: Boolean,
    running: Boolean,
    logs: String,
    stats: EngineStats,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onOpenLauncher: () -> Unit,
) {
    val context = LocalContext.current
    val settings by container.settings.state.collectAsState()
    val sdl by container.sdlInfo.collectAsState()
    val pads by container.gamepads.devices.collectAsState()
    val points by container.perfHistory.points.collectAsState()
    val pinned by container.commands.pinned.collectAsState()
    val history by container.commands.history.collectAsState()
    val lastVersion by container.extras.lastLaunched.collectAsState()

    var tab by remember { mutableStateOf(OverlayTab.from(settings.overlayTab)) }
    var command by remember { mutableStateOf("") }
    var logQuery by remember { mutableStateOf("") }
    var logLevel by remember { mutableStateOf("ALL") }
    var hint by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val panelWidth = shortSide.coerceIn(300, 480).dp
    val maxHeight = (configuration.screenHeightDp * 0.72f).dp

    Card(
        modifier =
            Modifier
                .width(panelWidth)
                .heightIn(max = maxHeight)
                .graphicsLayer(alpha = settings.overlayAlpha / 100f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(if (settings.overlayCompact) 8.dp else 12.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FloatingActionButton(
                    onClick = onToggle,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.Home,
                        contentDescription = "展开或收起",
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("MCJL 控制台", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${if (running) "● 运行中 pid ${if (stats.pid > 0) stats.pid else "--"}" else "○ 已停止"} · " +
                            "${lastVersion.ifBlank { "--" }} · ${sdl?.windowBackend ?: "GLFW"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = onOpenLauncher) {
                    Icon(Icons.Default.Close, contentDescription = "返回启动器")
                }
            }

            if (!expanded) {
                OverlayMiniStats(stats)
                return@Column
            }

            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
                OverlayTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = {
                            tab = t
                            container.settings.update { s -> s.copy(overlayTab = t.name) }
                        },
                        text = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            when (tab) {
                OverlayTab.STATUS -> {
                    OverlayStatusTab(container, stats, logs, settings.showFps)
                }

                OverlayTab.PERF -> {
                    OverlayPerfTab(container, points, settings.overlayShowGraph)
                }

                OverlayTab.LOG -> {
                    OverlayLogTab(logs, logQuery, { logQuery = it }, logLevel, { logLevel = it })
                }

                OverlayTab.COMMAND -> {
                    OverlayCommandTab(
                        command,
                        { command = it },
                        pinned,
                        history,
                        onSend = { cmd ->
                            hint = if (container.writeStdin(cmd)) "已发送：$cmd" else "进程 stdin 不可用"
                        },
                        onPin = { cmd -> container.commands.pin(cmd) },
                    )
                }

                OverlayTab.GAMEPAD -> {
                    OverlayGamepadCard(pads)
                }

                OverlayTab.QUICK -> {
                    OverlayQuickSettings(
                        settings = container.settings,
                        logLevels = listOf("DEBUG", "INFO", "WARN", "ERROR"),
                        onLogLevel = { level ->
                            container.settings.update { s -> s.copy(logLevel = level) }
                            NativeEngine.nativeSetLogLevel(
                                when (level) {
                                    "DEBUG" -> 1
                                    "WARN" -> 3
                                    "ERROR" -> 4
                                    else -> 2
                                },
                            )
                            hint = "日志级别已下发引擎：$level"
                        },
                    )
                }
            }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
                FilledTonalButton(onClick = onForceStop, modifier = Modifier.weight(1f)) { Text("强停") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = {
                        container.clearLogs()
                        hint = "日志已清空"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("清日志") }
                FilledTonalButton(
                    onClick = {
                        hint = if (Diagnostics.copyToClipboard(context, logs)) "日志已复制" else "剪贴板不可用"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("复制日志") }
            }
            if (hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun OverlayMiniStats(stats: EngineStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OverlayStat("CPU", "%.1f%%".format(stats.cpuPercent))
        OverlayStat("MEM", "${stats.rssKb / 1024} MB")
        OverlayStat("时长", "${stats.elapsedMs / 1000}s")
    }
}

@Composable
private fun OverlayStatusTab(
    container: LauncherContainer,
    stats: EngineStats,
    logs: String,
    showFps: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayStat("CPU", "%.1f%%".format(stats.cpuPercent))
            OverlayStat("MEM", "${stats.rssKb / 1024} MB")
            OverlayStat("时长", "${stats.elapsedMs / 1000}s")
        }
        if (showFps) {
            Text(
                "FPS 需渲染桥接上报；CPU/MEM 为引擎 /proc 真实采样",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            "state=${stats.state} · exit=${if (stats.exitCode >= 0) stats.exitCode else "--"} · rss=${stats.rssKb} KB",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        AssistChip(
            onClick = {},
            label = { Text("预检 ${container.preflight.value?.summary ?: "未执行"}") },
        )
        OverlayLogInsights(logs)
    }
}

@Composable
private fun OverlayPerfTab(
    container: LauncherContainer,
    points: List<com.yuanbao.mcjlauncher.core.PerfSample>,
    showGraph: Boolean,
) {
    Column {
        Text(container.perfHistory.report(), style = MaterialTheme.typography.bodySmall)
        if (showGraph) OverlayPerfChart(points)
        Spacer(Modifier.height(4.dp))
        FilledTonalButton(onClick = { container.perfHistory.clear() }) { Text("清空采样") }
    }
}

@Composable
private fun OverlayLogTab(
    logs: String,
    query: String,
    onQuery: (String) -> Unit,
    level: String,
    onLevel: (String) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("搜索日志") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ALL", "INFO", "WARN", "ERROR").forEach { l ->
                FilterChip(selected = level == l, onClick = { onLevel(l) }, label = { Text(l) })
            }
        }
        val filtered =
            logs
                .lineSequence()
                .filter { line ->
                    (query.isBlank() || line.contains(query, ignoreCase = true)) &&
                        (level == "ALL" || line.contains(level, ignoreCase = true))
                }.takeLast(120)
                .joinToString("\n")
        Text(
            filtered.ifBlank { "（无匹配日志）" },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.heightIn(max = 180.dp),
        )
    }
}

@Composable
private fun OverlayCommandTab(
    command: String,
    onCommand: (String) -> Unit,
    pinned: List<String>,
    history: List<String>,
    onSend: (String) -> Unit,
    onPin: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = command,
            onValueChange = onCommand,
            label = { Text("发送到游戏 stdin") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(
                onClick = {
                    onSend(command)
                    onCommand("")
                },
                modifier = Modifier.weight(1f),
                enabled = command.isNotBlank(),
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("发送")
            }
            FilledTonalButton(onClick = { onPin(command) }, enabled = command.isNotBlank()) { Text("收藏") }
        }
        OverlayCommandBook(
            pinned = pinned,
            history = history,
            onRun = onSend,
        )
    }
}

@Composable
private fun OverlayStat(
    label: String,
    value: String,
) {
    AssistChip(onClick = {}, label = { Text("$label $value") })
}
