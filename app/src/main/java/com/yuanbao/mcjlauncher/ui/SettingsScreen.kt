package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.DownloadSource
import com.yuanbao.mcjlauncher.core.JavaMode
import com.yuanbao.mcjlauncher.core.LauncherContainer
import kotlinx.coroutines.delay

/** 主题模式（字符串持久化在 SettingsRepository）。 */
enum class ThemeMode(
    val raw: String,
    val label: String,
) {
    SYSTEM("SYSTEM", "跟随系统"),
    LIGHT("LIGHT", "浅色"),
    DARK("DARK", "深色"),
}

private val RENDER_BACKENDS =
    listOf(
        "gl4es" to "GL4ES（assets 已含）",
        "anativewindow" to "ANativeWindow / EGL wrapper",
        "egl" to "EGL 直连",
        "virgl" to "VirGL（需补 swrast_dri.so）",
    )
private val LOG_LEVELS = listOf("DEBUG", "INFO", "WARN", "ERROR")
private val LWJGL_VERSIONS = listOf("3.3.3", "3.4.1")

/** 设置页：每一项都写进 SharedPreferences，并参与真实启动计划拼装。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val context = LocalContext.current
    val settings by container.settings.state.collectAsState()
    val sourceInfo by container.sources.info.collectAsState()

    var javaMenu by remember { mutableStateOf(false) }
    var sdlMenu by remember { mutableStateOf(false) }
    var sdlAudioMenu by remember { mutableStateOf(false) }
    var sdlLogMenu by remember { mutableStateOf(false) }
    var overlayTabMenu by remember { mutableStateOf(false) }
    var presetStatus by remember { mutableStateOf("五套预设一键应用，48 字段备份可导出恢复") }
    var backendMenu by remember { mutableStateOf(false) }
    var logMenu by remember { mutableStateOf(false) }
    var lwjglMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("设置已持久化，重启后保持；启动时真实参与参数拼装") }

    // Slider / TextField 的高频输入只改本地草稿，松手或停止输入 350ms 后才写仓库。
    // 旧实现每帧都 update()：全量写 SharedPreferences + 发射 settings，5 个页面和悬浮窗一起重组。
    var memoryDraft by remember { mutableFloatStateOf(settings.memoryMb.toFloat()) }
    var logLinesDraft by remember { mutableFloatStateOf(settings.logLines.toFloat()) }
    var widthDraft by remember { mutableStateOf(settings.resolutionWidth.toString()) }
    var heightDraft by remember { mutableStateOf(settings.resolutionHeight.toString()) }

    LaunchedEffect(settings.memoryMb) { memoryDraft = settings.memoryMb.toFloat() }
    LaunchedEffect(settings.logLines) { logLinesDraft = settings.logLines.toFloat() }
    LaunchedEffect(settings.resolutionWidth) { widthDraft = settings.resolutionWidth.toString() }
    LaunchedEffect(settings.resolutionHeight) { heightDraft = settings.resolutionHeight.toString() }

    // 输入防抖：避免逐字符触发写盘与全应用重组；外部恢复默认时草稿已由上面的同步回写。
    LaunchedEffect(widthDraft) {
        val w = widthDraft.toIntOrNull() ?: return@LaunchedEffect
        delay(350L)
        val v = w.coerceIn(320, 3840)
        if (v != container.settings.value.resolutionWidth) {
            container.settings.update { s -> s.copy(resolutionWidth = v) }
        }
    }
    LaunchedEffect(heightDraft) {
        val h = heightDraft.toIntOrNull() ?: return@LaunchedEffect
        delay(350L)
        val v = h.coerceIn(240, 2160)
        if (v != container.settings.value.resolutionHeight) {
            container.settings.update { s -> s.copy(resolutionHeight = v) }
        }
    }

    // 自定义镜像 / 全局 JVM 参数同样用 350ms 防抖，避免逐字符写盘。
    var customMirrorDraft by remember { mutableStateOf(settings.customMirrorUrl) }
    var extraJvmDraft by remember { mutableStateOf(settings.extraJvmArgsGlobal) }
    LaunchedEffect(settings.customMirrorUrl) { customMirrorDraft = settings.customMirrorUrl }
    LaunchedEffect(settings.extraJvmArgsGlobal) { extraJvmDraft = settings.extraJvmArgsGlobal }
    LaunchedEffect(customMirrorDraft) {
        delay(350L)
        if (customMirrorDraft != container.settings.value.customMirrorUrl) {
            container.settings.update { s -> s.copy(customMirrorUrl = customMirrorDraft.trim()) }
        }
    }
    LaunchedEffect(extraJvmDraft) {
        delay(350L)
        if (extraJvmDraft != container.settings.value.extraJvmArgsGlobal) {
            container.settings.update { s -> s.copy(extraJvmArgsGlobal = extraJvmDraft) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { innerPadding ->
        AdaptivePanels(
            spec = spec,
            modifier = Modifier.padding(innerPadding),
            primary = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text("真实设置") }, supportingContent = { Text(status) })
                }

                // ---- SDL2 / SDL3 窗口与输入后端 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("SDL 图形与输入", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        dropdownRow(
                            "SDL 模式",
                            com.yuanbao.mcjlauncher.core.SdlMode
                                .from(settings.sdlMode)
                                .label,
                            sdlMenu,
                            { sdlMenu = true },
                            { sdlMenu = false },
                        ) {
                            com.yuanbao.mcjlauncher.core.SdlMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        container.settings.update { it.copy(sdlMode = mode.name) }
                                        sdlMenu = false
                                        status = "SDL 设为 ${mode.label}：高版本 Java 自动启用，库缺失时降级 GLFW"
                                    },
                                )
                            }
                        }
                        settingSwitch("仅高版本 Java 自动启用", settings.sdlHighJavaOnly) { v ->
                            container.settings.update { it.copy(sdlHighJavaOnly = v) }
                        }
                        settingSwitch("SDL 手柄", settings.sdlGamepad) { v ->
                            container.settings.update { it.copy(sdlGamepad = v) }
                        }
                        settingSwitch("HIDAPI", settings.sdlHidapi) { v ->
                            container.settings.update { it.copy(sdlHidapi = v) }
                        }
                        settingSwitch("触控转鼠标", settings.sdlTouchMouse) { v ->
                            container.settings.update { it.copy(sdlTouchMouse = v) }
                        }
                        settingSwitch("整合包强制 SDL3", settings.sdlForceOnModded) { v ->
                            container.settings.update { it.copy(sdlForceOnModded = v) }
                        }
                        dropdownRow(
                            "SDL 音频后端",
                            settings.sdlAudioBackend,
                            sdlAudioMenu,
                            { sdlAudioMenu = true },
                            { sdlAudioMenu = false },
                        ) {
                            listOf("AUTO", "OPENAL", "SDL").forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v) },
                                    onClick = {
                                        container.settings.update { it.copy(sdlAudioBackend = v) }
                                        sdlAudioMenu = false
                                    },
                                )
                            }
                        }
                        dropdownRow(
                            "SDL 日志级别",
                            settings.sdlLogLevel,
                            sdlLogMenu,
                            { sdlLogMenu = true },
                            { sdlLogMenu = false },
                        ) {
                            listOf("DEBUG", "INFO", "WARN", "ERROR").forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v) },
                                    onClick = {
                                        container.settings.update { it.copy(sdlLogLevel = v) }
                                        sdlLogMenu = false
                                    },
                                )
                            }
                        }
                        Text(
                            "自动规则：Java 8 → GLFW / Java 17 → SDL2 2.32.57 / Java 21·25 → SDL3 3.4.12（内置真实 ELF）；" +
                                "官方最新稳定为 2.32.74 / 3.4.20；" +
                                "SDL 叠加在渲染器之上，POJAV_RENDERER 不变。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                // ---- 配置预设与全量备份 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("配置预设", style = MaterialTheme.typography.titleSmall)
                        Text(presetStatus, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            com.yuanbao.mcjlauncher.core.ProfileRepository.PRESETS.forEach { preset ->
                                androidx.compose.material3.AssistChip(
                                    onClick = {
                                        if (container.applyProfile(preset.id)) {
                                            presetStatus = "已应用：${preset.label} · ${preset.description}"
                                        }
                                    },
                                    label = { Text(preset.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    val file = java.io.File(context.filesDir, "backups/settings.json")
                                    presetStatus =
                                        if (container.exportSettingsTo(file)) {
                                            "已导出 ${com.yuanbao.mcjlauncher.core.SettingsBackup.FIELD_COUNT} 字段到 ${file.absolutePath}"
                                        } else {
                                            "导出失败"
                                        }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("导出备份") }
                            FilledTonalButton(
                                onClick = {
                                    val file = java.io.File(context.filesDir, "backups/settings.json")
                                    presetStatus = if (container.importSettingsFrom(file)) "已恢复备份" else "备份不存在"
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("恢复备份") }
                        }
                        settingSwitch("启动前自动快照", settings.autoBackupOnLaunch) { v ->
                            container.settings.update { it.copy(autoBackupOnLaunch = v) }
                        }
                    }
                }

                // ---- 悬浮控制台 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("悬浮控制台", style = MaterialTheme.typography.titleSmall)
                        dropdownRow(
                            "默认页签",
                            OverlayTab.from(settings.overlayTab).label,
                            overlayTabMenu,
                            { overlayTabMenu = true },
                            { overlayTabMenu = false },
                        ) {
                            OverlayTab.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    onClick = {
                                        container.settings.update { it.copy(overlayTab = t.name) }
                                        overlayTabMenu = false
                                    },
                                )
                            }
                        }
                        Text("透明度 ${settings.overlayAlpha}%", style = MaterialTheme.typography.labelMedium)
                        androidx.compose.material3.Slider(
                            value = settings.overlayAlpha.toFloat(),
                            onValueChange = { v ->
                                container.settings.update { it.copy(overlayAlpha = v.toInt().coerceIn(40, 100)) }
                            },
                            valueRange = 40f..100f,
                        )
                        settingSwitch("性能曲线", settings.overlayShowGraph) { v ->
                            container.settings.update { it.copy(overlayShowGraph = v) }
                        }
                        settingSwitch("拖拽手势", settings.overlayGesture) { v ->
                            container.settings.update { it.copy(overlayGesture = v) }
                        }
                        settingSwitch("紧凑布局", settings.overlayCompact) { v ->
                            container.settings.update { it.copy(overlayCompact = v) }
                        }
                        settingSwitch("启动后自动展开", settings.autoExpandOverlay) { v ->
                            container.settings.update { it.copy(autoExpandOverlay = v) }
                        }
                    }
                }

                // ---- Java 版本（AUTO 时按 version.json 的 javaVersion 决定） ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Java 版本", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("运行时 Java", modifier = Modifier.weight(1f))
                            Box {
                                FilledTonalButton(onClick = { javaMenu = true }) { Text(settings.javaMode.label) }
                                DropdownMenu(expanded = javaMenu, onDismissRequest = { javaMenu = false }) {
                                    JavaMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            onClick = {
                                                container.settings.update { it.copy(javaMode = mode) }
                                                javaMenu = false
                                                status = "Java 版本设为「${mode.label}」：启动时会选 assets/app_runtime/java/${mode.directory() ?: "自动"}"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("默认：自动选择") },
                            supportingContent = { Text("AUTO 读取 version.json 的 javaVersion.majorVersion → jre8/17/21/25") },
                        )
                    }
                }

                // ---- 运行参数 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("运行参数", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Text("内存分配：${memoryDraft.toInt()} MB（松手后写入 -Xmx）")
                        Slider(
                            value = memoryDraft,
                            onValueChange = { memoryDraft = it },
                            onValueChangeFinished = {
                                container.settings.update { it.copy(memoryMb = memoryDraft.toInt()) }
                            },
                            valueRange = 512f..8192f,
                            steps = 59,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("日志保留行数：${logLinesDraft.toInt()}")
                        Slider(
                            value = logLinesDraft,
                            onValueChange = { logLinesDraft = it },
                            onValueChangeFinished = {
                                container.settings.update { it.copy(logLines = logLinesDraft.toInt()) }
                            },
                            valueRange = 1000f..12000f,
                            steps = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = widthDraft,
                                onValueChange = { widthDraft = it },
                                label = { Text("宽") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = heightDraft,
                                onValueChange = { heightDraft = it },
                                label = { Text("高") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                    }
                }

                // ---- 外观 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("外观", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode.raw,
                                    onClick = { container.settings.update { it.copy(themeMode = mode.raw) } },
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("动态配色（Android 12+）") },
                            trailingContent = {
                                Switch(
                                    checked = settings.dynamicColor,
                                    onCheckedChange = { v -> container.settings.update { it.copy(dynamicColor = v) } },
                                )
                            },
                        )
                    }
                }
            },
            secondary = {
                // ---- 渲染与日志 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("渲染与日志", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        dropdownRow(
                            "渲染后端",
                            RENDER_BACKENDS.firstOrNull { it.first == settings.renderer }?.second ?: settings.renderer,
                            backendMenu,
                            { backendMenu = true },
                            { backendMenu = false },
                        ) {
                            RENDER_BACKENDS.forEach { (value, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    container.settings.update { it.copy(renderer = value) }
                                    backendMenu = false
                                    status = "渲染后端设为 $label（真实写入 -Dorg.lwjgl.opengl.libname）"
                                })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Text(
                            "兼容层（叠加在渲染器之上，不替换底层后端）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        settingSwitch("SFPEW 兼容层", settings.sfpewEnabled) { v ->
                            container.settings.update { it.copy(sfpewEnabled = v) }
                            status =
                                if (v) {
                                    "SFPEW 已开启：GL/EGL 符号经 wrapper 转发到 ${settings.renderer}，底层渲染器不变"
                                } else {
                                    "SFPEW 已关闭：直接走 ${settings.renderer} 渲染器"
                                }
                        }
                        Text(
                            "SFPEW = SimpleFPEWrapper，OpenGL 固定功能管线兼容 wrapper。" +
                                "开启后 LWJGL 从内置真实 libSimpleFPEWrapper.so 解析 GL/EGL 符号，wrapper 经 SFPEW_EGL 转发到当前 EGL/GLES 后端；" +
                                "POJAV_RENDERER 仍是 ${settings.renderer}。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "当前组合：${RENDER_BACKENDS.firstOrNull { it.first == settings.renderer }?.second ?: settings.renderer}" +
                                if (settings.sfpewEnabled) " ＋ SFPEW 叠加层" else "（无兼容层）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "assets 已内置 4 ABI 真实 libSimpleFPEWrapper.so（LGPL-3.0）：wrapper 缺失或检测到自带 FPE 的模组（Angelica / Actinium）时自动跳过本次叠加，" +
                                "安全降级回原渲染器，不会拦住启动。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(8.dp))
                        dropdownRow(
                            "LWJGL 版本",
                            settings.lwjglVersion,
                            lwjglMenu,
                            { lwjglMenu = true },
                            { lwjglMenu = false },
                        ) {
                            LWJGL_VERSIONS.forEach { v ->
                                DropdownMenuItem(text = { Text(v) }, onClick = {
                                    container.settings.update { it.copy(lwjglVersion = v) }
                                    lwjglMenu = false
                                    status = "LWJGL 设为 $v：启动时会释放对应 assets 目录"
                                })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        dropdownRow(
                            "日志级别",
                            settings.logLevel,
                            logMenu,
                            { logMenu = true },
                            { logMenu = false },
                        ) {
                            LOG_LEVELS.forEach { level ->
                                DropdownMenuItem(text = { Text(level) }, onClick = {
                                    container.settings.update { it.copy(logLevel = level) }
                                    logMenu = false
                                })
                            }
                        }
                    }
                }

                // ---- 下载源 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("下载源（默认自动）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DownloadSource.entries.forEach { src ->
                                FilterChip(
                                    selected = DownloadSource.from(settings.downloadSource) == src,
                                    onClick = {
                                        container.settings.update { it.copy(downloadSource = src.raw) }
                                        status = "已切换为「${src.label}」，正在从该源重新拉取版本列表…"
                                    },
                                    label = { Text(src.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("当前生效：${sourceInfo.summary}", style = MaterialTheme.typography.bodyMedium)
                                if (sourceInfo.probeMs >= 0) {
                                    Text(
                                        "官方源探测耗时 ${sourceInfo.probeMs} ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    DownloadSource.from(settings.downloadSource).desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("自动判定阈值：${settings.autoProbeMs} ms", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(800, 1500, 3000).forEach { ms ->
                                FilterChip(
                                    selected = settings.autoProbeMs == ms,
                                    onClick = {
                                        container.settings.update { it.copy(autoProbeMs = ms) }
                                        status = "慢速阈值改为 ${ms}ms，自动模式将重新探测官方源"
                                    },
                                    label = { Text("${ms}ms") },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "自动模式先对官方版本清单发一次带 Range 的轻量探测；超过阈值、超时或不可达即判为" +
                                "「缓慢」并切到镜像源。决策缓存 5 分钟，切换设置会立即重新探测并重新拉取版本列表。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            "镜像源 = BMCLAPI（bmclapi2.bangbang93.com）；版本清单、version.json、client.jar、" +
                                "libraries、assetIndex 与资源对象会统一按官方文档规则重写域名，SHA-1 仍按官方值校验。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            "切换官方源 → 从官方源拉列表；切换镜像源 → 从镜像源拉列表；安装时同样按当前源下载。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // ---- 下载管线 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("下载管线", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "连接复用 · 256KB 缓冲 · 流式校验 · 大文件最多 4 段并发（不支持 Range 自动回退）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("并发下载数：${settings.downloadConcurrency}", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 4, 8, 12, 16).forEach { n ->
                                FilterChip(
                                    selected = settings.downloadConcurrency == n,
                                    onClick = { container.settings.update { it.copy(downloadConcurrency = n) } },
                                    label = { Text("$n") },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("失败重试次数：${settings.downloadRetries}", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 1, 2, 3).forEach { n ->
                                FilterChip(
                                    selected = settings.downloadRetries == n,
                                    onClick = { container.settings.update { it.copy(downloadRetries = n) } },
                                    label = { Text("$n") },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customMirrorDraft,
                            onValueChange = { customMirrorDraft = it },
                            label = { Text("自定义镜像根地址") },
                            placeholder = { Text("https://mirror.example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "留空用 BMCLAPI；填写后把已映射的镜像 URL 换到该根地址，路径规则不变。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                // ---- 实例与启动 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "实例与启动",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        settingSwitch("独立实例目录（mods / saves 隔离）", settings.isolateInstances) { v ->
                            container.settings.update { it.copy(isolateInstances = v) }
                        }
                        settingSwitch("启动前预检（缺失文件直接拦截）", settings.preflightEnabled) { v ->
                            container.settings.update { it.copy(preflightEnabled = v) }
                        }
                        settingSwitch("崩溃自动归档", settings.crashArchiveEnabled) { v ->
                            container.settings.update { it.copy(crashArchiveEnabled = v) }
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("JVM 参数预设：${settings.jvmPreset}", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("LOW", "BALANCED", "PERFORMANCE").forEach { p ->
                                    FilterChip(
                                        selected = settings.jvmPreset == p,
                                        onClick = { container.settings.update { it.copy(jvmPreset = p) } },
                                        label = { Text(p) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = extraJvmDraft,
                                onValueChange = { extraJvmDraft = it },
                                label = { Text("全局附加 JVM 参数") },
                                placeholder = { Text("-Dfml.foo=bar -XX:+...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "实例配置（覆盖内存 / 额外参数 / pre-post 钩子）在「工具」页管理。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                // ---- 自动化 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "自动化",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        settingSwitch("记住上次启动版本（续玩）", settings.autoResume) { v ->
                            container.settings.update { it.copy(autoResume = v) }
                        }
                        settingSwitch("引擎日志同步落盘", settings.logToFile) { v ->
                            container.settings.update { it.copy(logToFile = v) }
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                "实例：${container.instances.value.size} 个 · 崩溃报告：${container.crashes.value.size} 份",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        container.instances.refresh()
                                        container.crashes.refresh()
                                        status = "实例与崩溃列表已刷新"
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("刷新") }
                                OutlinedButton(
                                    onClick = {
                                        val n = container.crashes.clear()
                                        status = "已清空 $n 份崩溃报告"
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("清空崩溃") }
                            }
                        }
                    }
                }

                // ---- 正版登录 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("正版登录（Microsoft OAuth）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = settings.msClientId,
                            onValueChange = { v -> container.settings.update { it.copy(msClientId = v) } },
                            label = { Text("Azure 应用 client_id") },
                            placeholder = { Text("需在 Azure 启用「允许公共客户端流」") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "client_id 只保存在本机设置，不会硬编码进 APK；留空时正版登录会给出明确提示。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                // ---- 开关组 ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "开关组",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        settingSwitch("启动后自动展开悬浮窗", settings.autoExpandOverlay) { v ->
                            container.settings.update { it.copy(autoExpandOverlay = v) }
                        }
                        settingSwitch("日志自动滚动", settings.autoScrollLog) { v ->
                            container.settings.update { it.copy(autoScrollLog = v) }
                        }
                        settingSwitch("性能监控面板", settings.perfMonitor) { v ->
                            container.settings.update { it.copy(perfMonitor = v) }
                        }
                        settingSwitch("下载时校验 SHA-1", settings.verifyAssets) { v ->
                            container.settings.update { it.copy(verifyAssets = v) }
                        }
                        settingSwitch("全屏沉浸模式", settings.immersive) { v ->
                            container.settings.update { it.copy(immersive = v) }
                        }
                        settingSwitch("显示帧率徽标", settings.showFps) { v ->
                            container.settings.update { it.copy(showFps = v) }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        container.settings.resetDefaults()
                        status = "已恢复默认设置（真实清库）"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("恢复默认") }

                Text(
                    "所有设置都写入 SharedPreferences 并在启动时参与 JVM 参数 / 环境变量拼装。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(16.dp))
                AdaptiveInfoCard(spec)
            },
        )
    }
}

@Composable
private fun dropdownRow(
    label: String,
    value: String,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            FilledTonalButton(onClick = onOpen) { Text(value) }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = { content() })
        }
    }
}

@Composable
private fun settingSwitch(
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
