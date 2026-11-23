package com.yuanbao.mcjlauncher.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.LauncherContainer
import com.yuanbao.mcjlauncher.core.LogCenter
import com.yuanbao.mcjlauncher.engine.NativeEngine
import com.yuanbao.mcjlauncher.overlay.FloatingWindowService
import kotlinx.coroutines.launch

/**
 * 启动页：全部数据来自真实仓库。
 * 版本 → 安装（真下载+校验） → 释放运行时 → 拼启动计划 → C++ 引擎拉起 JVM。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by container.settings.state.collectAsState()
    val logs by container.logs.collectAsState()
    val stats by container.stats.collectAsState()
    val running by container.running.collectAsState()
    val installed by container.games.installed.collectAsState()
    val manifest by container.games.manifest.collectAsState()
    val progress by container.games.progress.collectAsState()
    val notes by container.games.notes.collectAsState()
    val sourceInfo by container.sources.info.collectAsState()
    val lastLaunched by container.extras.lastLaunched.collectAsState()
    val installQueue by container.installQueue.collectAsState()
    val preflight by container.preflight.collectAsState()
    val failures by container.games.failures.collectAsState()
    val events by container.events.collectAsState()
    val extrasStats by container.extras.stats.collectAsState()
    val cpuText = "%.1f".format(stats.cpuPercent)
    val progressMb = "%.1f".format((progress?.bytesDone ?: 0L) / 1024.0 / 1024.0)
    // 引擎版本串固定，状态直接从已订阅的 stats 派生，省掉每次重组的两次 JNI。
    val engineVersion = remember { NativeEngine.engineVersion() }
    val currentAccount = remember(settings.currentAccountId) { container.accounts.current() }
    val resumeId = remember(lastLaunched, installed) { container.resumeVersion() }

    val candidates =
        remember(installed, manifest, settings.selectedVersion) {
            val ids = linkedSetOf<String>()
            if (settings.selectedVersion.isNotBlank()) ids += settings.selectedVersion
            installed.forEach { ids += it.id }
            if (ids.isEmpty()) manifest.take(4).forEach { ids += it.id }
            ids.toList()
        }
    var selected by remember(settings.selectedVersion) {
        mutableStateOf(settings.selectedVersion.ifBlank { candidates.firstOrNull() ?: "" })
    }
    var command by remember { mutableStateOf("") }
    var logQuery by remember { mutableStateOf("") }
    var logLevel by remember { mutableStateOf("全部") }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val filteredLogs =
        remember(logs, logQuery, logLevel) {
            LogCenter.filter(logs, logQuery, logLevel)
        }

    val overlayLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { overlayGranted = Settings.canDrawOverlays(context) }

    fun selectVersion(id: String) {
        selected = id
        container.settings.update { it.copy(selectedVersion = id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MC Java 启动器") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                actions = {
                    FilledTonalButton(onClick = {
                        scope.launch { container.games.refreshManifest() }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("刷新清单")
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptivePanels(
            spec = spec,
            modifier = Modifier.padding(innerPadding),
            primary = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("自研底层引擎", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            engineVersion,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "状态 ${engineStateLabel(stats.state)} · PID ${if (stats.pid > 0) stats.pid else "--"} · " +
                                "CPU $cpuText% · MEM ${stats.rssKb / 1024} MB · " +
                                "运行 ${stats.elapsedMs / 1000}s · 退出码 ${if (stats.exitCode >= 0) stats.exitCode else "--"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("快捷操作", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "上次启动：${lastLaunched.ifBlank { "无" }} · ${extrasStats.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    if (resumeId.isNotBlank()) {
                                        selectVersion(resumeId)
                                        scope.launch {
                                            container.launch(resumeId).onSuccess { r ->
                                                container.appendLogInteractive("[续玩] ${r.message} (code=${r.code})\n")
                                            }
                                        }
                                    }
                                },
                                enabled = resumeId.isNotBlank() && container.games.isInstalled(resumeId),
                                modifier = Modifier.weight(1f),
                            ) { Text("续玩 $resumeId") }
                            OutlinedButton(
                                onClick = { container.cancelInstall() },
                                enabled = installQueue.isNotEmpty() || progress != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("取消安装") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "安装队列：${installQueue.size} 个待装${if (installQueue.isEmpty()) "" else " · ${installQueue.joinToString().take(80)}"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        HorizontalDivider()
                        Text(
                            "启动预检：${preflight?.summary ?: "未执行 / 未开启"}",
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (preflight?.ok == false) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        Text(
                            "最近事件：${events.firstOrNull() ?: "无"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("游戏版本（${installed.size} 个已安装）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            candidates.take(5).forEach { id ->
                                FilterChip(
                                    selected = selected == id,
                                    onClick = { selectVersion(id) },
                                    label = { Text(id) },
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("当前账号：${currentAccount?.name ?: "无"}") },
                            supportingContent = {
                                Text(
                                    "Java ${settings.javaMode.label} · ${settings.memoryMb} MB · ${settings.renderer}" +
                                        "${if (settings.sfpewEnabled) "+SFPEW" else ""} · SDL ${settings.sdlMode} · " +
                                        "下载源 ${sourceInfo.effective.label}",
                                )
                            },
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("安装与提示", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            notes.ifBlank { "点「下载并安装」拉取 Mojang 官方文件（含 SHA-1 校验）" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        progress?.let { p ->
                            Text(
                                "${p.label} · ${p.done}/${p.total} 文件 · $progressMb MB",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                        }
                        Button(
                            onClick = {
                                if (selected.isNotBlank()) {
                                    selectVersion(selected)
                                    container.install(selected)
                                }
                            },
                            enabled = selected.isNotBlank() && progress == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (progress == null) "下载并安装 $selected" else "安装中…")
                        }
                    }
                }
            },
            secondary = {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("启动游戏", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    container.launch(selected).onSuccess { r ->
                                        container.appendLogInteractive("[启动] ${r.message} (code=${r.code})\n")
                                        if (r.code == 0 && Settings.canDrawOverlays(context)) {
                                            FloatingWindowService.start(context)
                                        }
                                    }
                                }
                            },
                            enabled = selected.isNotBlank() && container.games.isInstalled(selected),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (running) "重新启动（会先停止旧进程）" else "启动游戏")
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { container.stop() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("停止")
                            }
                            FilledTonalButton(
                                onClick = { FloatingWindowService.toggle(context) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("悬浮窗")
                            }
                        }
                    }
                }

                if (!overlayGranted) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("需要悬浮窗权限", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    overlayLauncher.launch(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("去授权 $Manifest.permission.SYSTEM_ALERT_WINDOW")
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("引擎日志", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LogCenter.LEVELS.forEach { lv ->
                                FilterChip(
                                    selected = logLevel == lv,
                                    onClick = { logLevel = lv },
                                    label = { Text(lv) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = logQuery,
                                onValueChange = { logQuery = it },
                                placeholder = { Text("过滤日志关键字") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = {
                                val f = container.logCenter.export(logs)
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(container.logCenter.shareFile(f), "分享日志"),
                                    )
                                }
                            }) { Text("导出") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            filteredLogs.ifBlank { "（无匹配输出；启动后会出现 JVM 与引擎日志）" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 96.dp, max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = command,
                                onValueChange = { command = it },
                                placeholder = { Text("向 JVM stdin 发送命令") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = {
                                if (command.isNotBlank()) {
                                    container.writeStdin(command)
                                    container.appendLogInteractive("[stdin] $command\n")
                                    command = ""
                                }
                            }) {
                                Icon(Icons.Default.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Text(
                    "逻辑已接通：版本清单 / 下载校验 / assets 释放 / classpath / 账号 / 设置 / 引擎进程。渲染画面仍依赖真机 GL4ES 桥接验证。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("下载失败 ${failures.size} 项", style = MaterialTheme.typography.titleSmall)
                        failures.take(4).forEach { f ->
                            Text(
                                "${f.label}：${f.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (failures.isEmpty()) {
                            Text(
                                "无失败记录；并发与重试耗尽的任务会列在这里。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Text("事件流", style = MaterialTheme.typography.labelMedium)
                        events.take(5).forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                AdaptiveInfoCard(spec)
            },
        )
    }
}

/** 从 C++ statsJson() 的 state 字段派生中文文案，避免额外一次 nativeState() JNI。 */
private fun engineStateLabel(state: String): String =
    when (state) {
        "IDLE" -> "空闲"
        "STARTING" -> "启动中"
        "RUNNING" -> "运行中"
        "STOPPING" -> "停止中"
        "FAILED" -> "失败"
        else -> state
    }
