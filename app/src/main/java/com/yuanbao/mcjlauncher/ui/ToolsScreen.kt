package com.yuanbao.mcjlauncher.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.Diagnostics
import com.yuanbao.mcjlauncher.core.LauncherContainer
import kotlinx.coroutines.launch

/**
 * 工具页：把本轮新增的运维能力集中到一个真实可操作的页面。
 * 网络自检、系统信息、启动预检、崩溃归档、日志/清单导出、实例复制、事件流。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val health by container.sources.health.collectAsState()
    val crashes by container.crashes.reports.collectAsState()
    val instances by container.instances.instances.collectAsState()
    val failures by container.games.failures.collectAsState()
    val events by container.events.collectAsState()
    val stats by container.extras.stats.collectAsState()
    val preflight by container.preflight.collectAsState()
    val logs by container.logs.collectAsState()
    val sourceInfo by container.sources.info.collectAsState()

    var status by remember { mutableStateOf("工具页：全部动作都落到真实文件 / 真实网络 / 真实进程") }
    var toolStatus by remember { mutableStateOf("日志分析 / 配置备份 / SDL 矩阵都读取真实数据") }
    var reportText by remember { mutableStateOf("") }
    var newInstanceId by remember { mutableStateOf("") }

    val systemInfo = remember { Diagnostics.systemInfo(context, container) }

    fun shareFile(file: java.io.File) {
        runCatching {
            context.startActivity(
                Intent.createChooser(container.logCenter.shareFile(file), "分享文件"),
            )
        }.onFailure { status = "分享失败：${it.message}" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
    ) { innerPadding ->
        AdaptivePanels(
            spec = spec,
            modifier = Modifier.padding(innerPadding),
            primary = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("运维工具") },
                        supportingContent = { Text(status) },
                    )
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("日志分析器", style = MaterialTheme.typography.titleSmall)
                        Text(toolStatus, style = MaterialTheme.typography.labelSmall)
                        val insights = remember(logs) { container.analyzeLogs() }
                        if (insights.isEmpty()) {
                            Text("当前日志未命中已知异常模式。")
                        } else {
                            insights.forEach { item ->
                                ListItem(
                                    headlineContent = { Text("${item.category} · ${item.title} ×${item.count}") },
                                    supportingContent = { Text(item.advice) },
                                )
                                HorizontalDivider()
                            }
                        }
                        Text(
                            com.yuanbao.mcjlauncher.core.LogAnalyzer
                                .summary(logs),
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("运行状态报表", style = MaterialTheme.typography.titleSmall)
                        Text(container.perfHistory.report())
                        Text("手柄：${container.gamepads.snapshot()}")
                        Text("命令簿：${container.commands.pinned.value.size} 条收藏 / ${container.commands.history.value.size} 条历史")
                        FilledTonalButton(onClick = { container.perfHistory.clear() }) { Text("清空性能采样") }
                        FilledTonalButton(onClick = { container.commands.clearHistory() }) { Text("清空命令历史") }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SDL 兼容矩阵", style = MaterialTheme.typography.titleSmall)
                        com.yuanbao.mcjlauncher.core.SdlCompatibility.MATRIX.forEach { row ->
                            ListItem(
                                headlineContent = { Text("Java ${row.javaMajor} → ${row.backend.name}") },
                                supportingContent = { Text("${row.mcRange} · ${row.note}") },
                            )
                            HorizontalDivider()
                        }
                        Text("生效：${container.sdlInfo.value?.summary ?: "尚未启动"}")
                        Text(
                            com.yuanbao.mcjlauncher.core.SdlCompatibility
                                .matrixText(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("网络自检", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "生效源：${sourceInfo.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        status = "正在探测官方源与镜像源…"
                                        val r = container.sources.healthCheck()
                                        status = "自检完成：" + r.joinToString { "${it.label}=${if (it.ok) "可用" else "不可用"} ${it.elapsedMs}ms" }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("探测两端") }
                            OutlinedButton(
                                onClick = { scope.launch { container.games.refreshManifest() } },
                                modifier = Modifier.weight(1f),
                            ) { Text("刷新清单") }
                        }
                        if (health.isEmpty()) {
                            Text(
                                "尚未探测。会带 Range 各发一次轻量请求，不下载完整清单。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        } else {
                            health.forEach { h ->
                                ListItem(
                                    headlineContent = { Text("${h.label}：${if (h.ok) "可用" else "不可用"}") },
                                    supportingContent = {
                                        Text(
                                            "${h.elapsedMs} ms · HTTP ${h.httpCode} · ${h.url}",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "系统信息（真实读取）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        systemInfo.forEach { (k, v) ->
                            ListItem(headlineContent = { Text(k) }, supportingContent = {
                                Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            })
                            HorizontalDivider()
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("启动预检", style = MaterialTheme.typography.titleSmall)
                        if (preflight == null) {
                            Text(
                                "尚未执行。启动游戏且开启预检后，这里会显示逐项结果。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        } else {
                            preflight?.let { p ->
                                Text("${p.versionId} · ${p.summary}", style = MaterialTheme.typography.bodyMedium)
                                p.items.forEach {
                                    Text(
                                        "${it.level}  ${it.label}：${it.detail}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color =
                                            if (it.level == "ERROR") {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            secondary = {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("导出与分享", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    val f = container.logCenter.export(logs)
                                    status = "日志已导出：${f.name}"
                                    shareFile(f)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("导出日志") }
                            OutlinedButton(
                                onClick = {
                                    val f = container.games.exportManifest()
                                    status = "清单已导出：${f.name}"
                                    shareFile(f)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("导出清单") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val f = java.io.File(context.filesDir, "exports/user-data.json")
                                    f.parentFile?.mkdirs()
                                    container.extras.export(f)
                                    status = "用户数据已导出：${f.name}"
                                    shareFile(f)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("导出用户数据") }
                            OutlinedButton(
                                onClick = {
                                    container.logCenter.clearDisk()
                                    status = "磁盘日志已清空"
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("清空磁盘日志") }
                        }
                        Text(
                            "日志落盘：${container.logCenter.diskStats().let { "${it.first} 文件 / ${it.second} B" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("崩溃归档（${crashes.size}）", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                container.crashes.refresh()
                                status = "已刷新崩溃报告列表"
                            }) { Text("刷新") }
                            OutlinedButton(onClick = {
                                val n = container.crashes.clear()
                                status = "已清空 $n 份报告"
                            }) { Text("清空") }
                        }
                        crashes.take(8).forEach { r ->
                            ListItem(
                                headlineContent = { Text(r.name, style = MaterialTheme.typography.labelSmall) },
                                supportingContent = { Text("${r.sizeBytes} B") },
                                trailingContent = {
                                    FilledTonalButton(onClick = {
                                        reportText = container.crashes.read(r)
                                        status = "正在查看 ${r.name}"
                                    }) { Text("查看") }
                                },
                            )
                            HorizontalDivider()
                        }
                        if (crashes.isEmpty()) {
                            Text(
                                "暂无崩溃报告。非 0 退出时会自动归档日志快照 + 诊断报告。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (reportText.isNotEmpty()) {
                            Text(
                                reportText.takeLast(4000),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("独立实例（${instances.size}）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${stats.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        instances.take(6).forEach { ins ->
                            ListItem(
                                headlineContent = { Text(ins.id) },
                                supportingContent = {
                                    Text(
                                        "${ins.sizeBytes / 1024} KB · ${ins.modCount} 模组 / ${ins.saveCount} 存档 / ${ins.packCount} 材质包",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                        OutlinedTextField(
                            value = newInstanceId,
                            onValueChange = { newInstanceId = it },
                            label = { Text("复制首个实例到新 ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(
                            onClick = {
                                val src = instances.firstOrNull()?.id ?: ""
                                val ok = container.instances.duplicate(src, newInstanceId.trim())
                                status = if (ok) "已复制 $src → $newInstanceId" else "复制失败（源不存在或目标已存在）"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("复制实例") }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("事件流 / 下载失败", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "下载失败 ${failures.size} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (failures.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                        )
                        failures.take(3).forEach {
                            Text("${it.label}：${it.message}", style = MaterialTheme.typography.labelSmall)
                        }
                        events.take(8).forEach { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)) }
                        Spacer(Modifier.height(4.dp))
                        AssistChip(onClick = { container.games.clearFailures() }, label = { Text("清空失败记录") })
                    }
                }

                AdaptiveInfoCard(spec)
            },
        )
    }
}
