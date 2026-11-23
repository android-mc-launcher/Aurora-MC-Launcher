package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.ContentKind
import com.yuanbao.mcjlauncher.core.LauncherContainer

/** 第 8 页：实例内模组 / 存档 / 材质包 / 光影的真实文件管理。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ContentScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val settings by container.settings.state.collectAsState()
    val installed by container.games.installed.collectAsState()
    val instances by container.instances.instances.collectAsState()
    var kind by remember { mutableStateOf(ContentKind.MODS) }
    var versionId by remember { mutableStateOf(settings.selectedVersion) }
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("内容管理：扫描真实目录，启停通过文件重命名") }

    val candidates =
        (listOf(settings.selectedVersion) + installed.map { it.id } + instances.map { it.id })
            .filter { it.isNotBlank() }
            .distinct()
    val target = versionId.ifBlank { candidates.firstOrNull().orEmpty() }
    val items =
        remember(target, kind, settings.contentSort, refresh) {
            if (target.isBlank()) emptyList() else container.contentList(target, kind)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("内容") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { innerPadding ->
        AdaptivePanels(
            spec = spec,
            modifier = Modifier.padding(innerPadding),
            primary = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("实例内容 · $target") },
                        supportingContent = { Text(status) },
                    )
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("内容类型", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ContentKind.entries.forEach { k ->
                                FilterChip(
                                    selected = kind == k,
                                    onClick = { kind = k },
                                    label = { Text(k.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("排序", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("NAME" to "名称", "SIZE" to "体积", "TIME" to "时间").forEach { (v, label) ->
                                FilterChip(
                                    selected = settings.contentSort == v,
                                    onClick = { container.settings.update { s -> s.copy(contentSort = v) } },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("目标版本", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            candidates.take(8).forEach { id ->
                                AssistChip(
                                    onClick = {
                                        versionId = id
                                        refresh++
                                    },
                                    label = { Text(id.take(14)) },
                                )
                            }
                        }
                        Text(
                            if (target.isBlank()) "尚无已安装版本" else container.content.summary(target),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                AdaptiveInfoCard(spec)
            },
            secondary = {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${kind.label} · ${items.size} 项", style = MaterialTheme.typography.titleSmall)
                        if (items.isEmpty()) {
                            Text("该实例的 ${kind.dir} 目录暂无内容。")
                        } else {
                            items.forEach { item ->
                                ListItem(
                                    headlineContent = { Text(item.name) },
                                    supportingContent = {
                                        Text(
                                            "${item.sizeBytes / 1024L} KB · " +
                                                if (item.enabled) "已启用" else "已禁用",
                                        )
                                    },
                                    trailingContent = {
                                        AssistChip(
                                            onClick = {
                                                if (container.content.toggle(item)) {
                                                    refresh++
                                                    status = "已切换 ${item.name} 的启用状态"
                                                }
                                            },
                                            label = { Text(if (item.enabled) "禁用" else "启用") },
                                        )
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("版本依赖体检", style = MaterialTheme.typography.titleSmall)
                        val insight =
                            remember(target, refresh) {
                                if (target.isBlank()) null else container.inspectVersion(target)
                            }
                        if (insight == null) {
                            Text("选择版本后可检查 version.json 依赖落盘情况。")
                        } else {
                            Text(insight.summary)
                            insight.missing.take(8).forEach { node ->
                                ListItem(
                                    headlineContent = { Text(node.coordinate) },
                                    supportingContent = { Text("缺失：${node.path}") },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}
