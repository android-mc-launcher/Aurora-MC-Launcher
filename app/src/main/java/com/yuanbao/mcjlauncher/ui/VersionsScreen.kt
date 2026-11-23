package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.LauncherContainer
import kotlinx.coroutines.launch

/** 版本库：真实 Mojang 清单、真实下载安装进度、真实删除本地版本。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val scope = rememberCoroutineScope()
    val manifest by container.games.manifest.collectAsState()
    val installed by container.games.installed.collectAsState()
    val progress by container.games.progress.collectAsState()
    val notes by container.games.notes.collectAsState()
    val settings by container.settings.state.collectAsState()
    val manifestSource by container.games.manifestSource.collectAsState()
    val sourceInfo by container.sources.info.collectAsState()
    val favorites by container.extras.favorites.collectAsState()
    val recent by container.extras.recent.collectAsState()
    val installQueue by container.installQueue.collectAsState()

    var query by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("全部") }
    var javaFilter by remember { mutableStateOf("全部") }
    var onlyFavorites by remember { mutableStateOf(false) }

    // 清单可达数百条；用 remember 缓存过滤结果，避免日志/进度刷新时每次重组都全量过滤。
    val installedIds = remember(installed) { installed.map { it.id }.toSet() }
    val javaById = remember(installed) { installed.associate { it.id to it.javaMajor } }
    val shown =
        remember(manifest, query, channel, javaFilter, onlyFavorites, javaById, favorites) {
            manifest.filter { v ->
                val channelOk =
                    channel == "全部" ||
                        (if (channel == "正式版") v.type == "release" else v.type != "release")
                val major = javaById[v.id]
                val javaOk =
                    when (javaFilter) {
                        "Java 8" -> major != null && major <= 8
                        "Java 17" -> major != null && major in 9..17
                        "Java 21+" -> major != null && major >= 21
                        else -> true
                    }
                val favOk = !onlyFavorites || v.id in favorites
                v.id.contains(query, ignoreCase = true) && channelOk && javaOk && favOk
            }
        }

    // 详情面板：宽屏放右栏；窄窗口插到列表之前，避免被 weight(1f) 的 LazyColumn 挤掉。
    val detailPane: @Composable () -> Unit = {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("清单来源", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${manifestSource?.label ?: "本地缓存 / 未拉取"} · ${sourceInfo.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    notes.ifBlank { "点右上角刷新，从当前下载源拉取版本列表" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress?.let { p ->
                    Spacer(Modifier.height(6.dp))
                    Text("${p.label} · ${p.done}/${p.total}", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("批量与记录", style = MaterialTheme.typography.titleSmall)
                Text(
                    "筛选结果 ${shown.size} 个 · 安装队列 ${installQueue.size} 个待装",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { container.enqueueInstall(shown.take(5).map { it.id }) },
                        modifier = Modifier.weight(1f),
                    ) { Text("前 5 个入队") }
                    OutlinedButton(
                        onClick = { container.cancelInstall() },
                        enabled = installQueue.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("清空队列") }
                }
                Text(
                    "最近安装：${recent.take(4).joinToString { it.id }.ifBlank { "无" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        val detail =
            remember(manifest, settings.selectedVersion) {
                manifest.firstOrNull { it.id == settings.selectedVersion }
            }
        // remember 放在条件分支之外，保证选中版本变化时的组合位置稳定。
        val usage =
            remember(detail?.id, installed) {
                detail?.id?.let { container.games.diskUsage(it) } ?: 0L
            }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选中版本", style = MaterialTheme.typography.titleSmall)
                if (detail == null) {
                    Text(
                        "未选择版本；在左侧列表点「设为当前」即可查看这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(detail.id, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${if (detail.type == "release") "正式版" else "快照/实验版"} · ${detail.releaseTime.take(10)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "sha1 ${detail.sha1.take(12)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        detail.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "本地占用 ${usage / 1024} KB · 收藏 ${if (detail.id in favorites) "是" else "否"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { container.install(detail.id) },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (detail.id in installedIds) "校验/补全" else "安装") }
                        OutlinedButton(
                            onClick = { container.games.delete(detail.id) },
                            enabled = detail.id in installedIds,
                            modifier = Modifier.weight(1f),
                        ) { Text("删除") }
                    }
                }
            }
        }
        AdaptiveInfoCard(spec)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("版本库（${manifest.size}）") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                actions = {
                    IconButton(onClick = { scope.launch { container.games.refreshManifest() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新官方清单")
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptivePanels(
            spec = spec,
            modifier = Modifier.padding(innerPadding),
            primary = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("搜索版本号，如 1.21.8") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "正式版", "快照版").forEach { name ->
                        FilterChip(selected = channel == name, onClick = { channel = name }, label = { Text(name) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "Java 8", "Java 17", "Java 21+").forEach { name ->
                        FilterChip(selected = javaFilter == name, onClick = { javaFilter = name }, label = { Text(name) })
                    }
                    FilterChip(
                        selected = onlyFavorites,
                        onClick = { onlyFavorites = !onlyFavorites },
                        label = { Text("收藏 ${favorites.size}") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("当前选中：${settings.selectedVersion.ifBlank { "未选择" }}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "清单来源：${manifestSource?.label ?: "本地缓存 / 未拉取"} · ${sourceInfo.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            notes.ifBlank { if (manifest.isEmpty()) "清单为空，点右上角刷新拉取版本列表" else "已安装 ${installed.size} 个版本" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        progress?.let { p ->
                            Spacer(Modifier.height(8.dp))
                            Text("${p.label} · ${p.done}/${p.total}", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (spec.columns <= 1) detailPane()
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(if (spec.columns > 1) 320.dp else 260.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { v ->
                        val isInstalled = v.id in installedIds
                        val selected = settings.selectedVersion == v.id
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                if (selected) {
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    )
                                } else {
                                    CardDefaults.cardColors()
                                },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(v.id, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${if (v.type == "release") "正式版" else "快照/实验版"} · ${v.releaseTime.take(10)} · sha1 ${v.sha1.take(8)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { container.extras.toggleFavorite(v.id) }) {
                                        Icon(
                                            if (v.id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = "收藏",
                                            tint =
                                                if (v.id in favorites) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.outline
                                                },
                                        )
                                    }
                                    if (selected) {
                                        Icon(Icons.Default.Check, contentDescription = "已选中", tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    if (isInstalled) {
                                        IconButton(onClick = { container.games.delete(v.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除本地版本")
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                AssistChip(
                                    onClick = { container.settings.update { it.copy(selectedVersion = v.id) } },
                                    label = { Text(if (isInstalled) "已安装 · 点此设为当前" else "未安装 · 点此设为当前") },
                                )
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            container.settings.update { it.copy(selectedVersion = v.id) }
                                            container.install(v.id)
                                        },
                                        enabled = progress == null,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (isInstalled) "校验/补全" else "安装")
                                    }
                                    OutlinedButton(
                                        onClick = { container.games.note("${v.id} → ${v.url}") },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("查看 version.json 地址") }
                                }
                            }
                        }
                    }
                }
            },
            secondary = {
                if (spec.columns > 1) detailPane()
            },
        )
    }
}
