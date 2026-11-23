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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.LauncherContainer
import com.yuanbao.mcjlauncher.core.SdlCompatibility
import com.yuanbao.mcjlauncher.core.SdlMode
import com.yuanbao.mcjlauncher.core.SdlResolver
import com.yuanbao.mcjlauncher.core.SdlSpec

/** 第 7 页：SDL2 / SDL3 图形与输入后端。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SdlScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val settings by container.settings.state.collectAsState()
    val sdl by container.sdlInfo.collectAsState()
    val pads by container.gamepads.devices.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var logMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("SDL 后端与 Java major 联动，改动会真实写入启动计划") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图形 / SDL") },
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
                        headlineContent = { Text("SDL 窗口与输入后端") },
                        supportingContent = { Text(status) },
                    )
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("后端选择", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SDL 模式", modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { menu = true }) {
                                Text(SdlMode.from(settings.sdlMode).label)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                SdlMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = {
                                            container.settings.update { s -> s.copy(sdlMode = mode.name) }
                                            menu = false
                                            status = "SDL 设为 ${mode.label}；自动规则 Java 17 → SDL2 / Java 21+ → SDL3"
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                        SdlSwitch("仅高版本 Java 自动启用", settings.sdlHighJavaOnly) { v ->
                            container.settings.update { s -> s.copy(sdlHighJavaOnly = v) }
                        }
                        SdlSwitch("SDL 手柄", settings.sdlGamepad) { v ->
                            container.settings.update { s -> s.copy(sdlGamepad = v) }
                        }
                        SdlSwitch("HIDAPI", settings.sdlHidapi) { v ->
                            container.settings.update { s -> s.copy(sdlHidapi = v) }
                        }
                        SdlSwitch("触控转鼠标", settings.sdlTouchMouse) { v ->
                            container.settings.update { s -> s.copy(sdlTouchMouse = v) }
                        }
                        SdlSwitch("整合包强制 SDL3", settings.sdlForceOnModded) { v ->
                            container.settings.update { s -> s.copy(sdlForceOnModded = v) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("音频后端", modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { audioMenu = true }) { Text(settings.sdlAudioBackend) }
                            DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }) {
                                listOf("AUTO", "OPENAL", "SDL").forEach { v ->
                                    DropdownMenuItem(
                                        text = { Text(v) },
                                        onClick = {
                                            container.settings.update { s -> s.copy(sdlAudioBackend = v) }
                                            audioMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SDL 日志级别", modifier = Modifier.weight(1f))
                            FilledTonalButton(onClick = { logMenu = true }) { Text(settings.sdlLogLevel) }
                            DropdownMenu(expanded = logMenu, onDismissRequest = { logMenu = false }) {
                                listOf("DEBUG", "INFO", "WARN", "ERROR").forEach { v ->
                                    DropdownMenuItem(
                                        text = { Text(v) },
                                        onClick = {
                                            container.settings.update { s -> s.copy(sdlLogLevel = v) }
                                            logMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                AdaptiveInfoCard(spec)
            },
            secondary = {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前生效", style = MaterialTheme.typography.titleSmall)
                        Text(sdl?.summary ?: "尚未启动；按 Java major 预览：${SdlCompatibility.recommended(21)}")
                        Text("SDL3 ${SdlSpec.SDL3_VERSION} · SDL2 ${SdlSpec.SDL2_VERSION} · 许可证 zlib")
                        Text("自动规则：Java 8 → GLFW / Java 17 → SDL2 / Java 21 或 25 → SDL3")
                        AssistChip(onClick = { container.gamepads.clear() }, label = { Text("清空手柄列表") })
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Java × MC 兼容矩阵", style = MaterialTheme.typography.titleSmall)
                        SdlCompatibility.MATRIX.forEach { row ->
                            ListItem(
                                headlineContent = { Text("Java ${row.javaMajor} → ${row.backend.name}") },
                                supportingContent = { Text("${row.mcRange} · ${row.note}") },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SDL2 / SDL3 能力对照", style = MaterialTheme.typography.titleSmall)
                        SdlCompatibility.CAPABILITIES.forEach { c ->
                            ListItem(
                                headlineContent = { Text(c.title) },
                                supportingContent = { Text("SDL2：${c.sdl2} / SDL3：${c.sdl3}") },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("注入的环境变量", style = MaterialTheme.typography.titleSmall)
                        SdlCompatibility.HINTS.forEach { (k, v) ->
                            ListItem(headlineContent = { Text(k) }, supportingContent = { Text(v) })
                            HorizontalDivider()
                        }
                        Text("已连接手柄 ${pads.size} 台 · 自动映射 ${SdlResolver.autoFor(21).name}")
                    }
                }
            },
        )
    }
}

@Composable
private fun SdlSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
    HorizontalDivider()
}
