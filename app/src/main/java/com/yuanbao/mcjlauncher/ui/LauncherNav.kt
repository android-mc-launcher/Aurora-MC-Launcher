package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.LocalLauncherContainer
import com.yuanbao.mcjlauncher.ui.theme.LauncherTheme

enum class LauncherTab(
    val label: String,
) {
    LAUNCH("启动"),
    VERSIONS("版本库"),
    ACCOUNTS("账号"),
    SETTINGS("设置"),
    TOOLS("工具"),
    SDL("图形"),
    CONTENT("内容"),
    ABOUT("关于"),
}

fun LauncherTab.icon(): ImageVector =
    when (this) {
        LauncherTab.LAUNCH -> Icons.Default.PlayArrow
        LauncherTab.VERSIONS -> Icons.Default.List
        LauncherTab.ACCOUNTS -> Icons.Default.Person
        LauncherTab.SETTINGS -> Icons.Default.Settings
        LauncherTab.TOOLS -> Icons.Default.Build
        LauncherTab.SDL -> Icons.Default.Star
        LauncherTab.CONTENT -> Icons.Default.CheckCircle
        LauncherTab.ABOUT -> Icons.Default.Info
    }

/** UI 根：所有页面共享同一个 LauncherContainer，导航形态与内容栏数按窗口实测自动切换。 */
@Composable
fun LauncherRoot() {
    val container = LocalLauncherContainer.current
    val settings by container.settings.state.collectAsState()
    var tab by remember { mutableStateOf(LauncherTab.LAUNCH) }
    val systemDark = isSystemInDarkTheme()

    LauncherTheme(
        darkTheme =
            when (settings.themeMode) {
                "LIGHT" -> false
                "DARK" -> true
                else -> systemDark
            },
        dynamicColor = settings.dynamicColor,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AdaptiveRoot { spec ->
                AdaptiveNavigation(spec = spec, tab = tab, onTab = { tab = it }) {
                    when (tab) {
                        LauncherTab.LAUNCH -> LauncherScreen(container, spec)
                        LauncherTab.VERSIONS -> VersionsScreen(container, spec)
                        LauncherTab.ACCOUNTS -> AccountsScreen(container, spec)
                        LauncherTab.SETTINGS -> SettingsScreen(container, spec)
                        LauncherTab.TOOLS -> ToolsScreen(container, spec)
                        LauncherTab.SDL -> SdlScreen(container, spec)
                        LauncherTab.CONTENT -> ContentScreen(container, spec)
                        LauncherTab.ABOUT -> AboutScreen(container, spec)
                    }
                }
            }
        }
    }
}

/**
 * 自适应导航壳：
 * - 紧凑窗口（分屏 / 系统忽略横屏请求后的竖屏）：底部 NavigationBar；
 * - 常规横屏手机 / 中小平板：左侧 NavigationRail，把宝贵的竖向空间留给内容；
 * - 大屏平板 / 桌面窗口：PermanentNavigationDrawer 常驻抽屉。
 *
 * 页面自身只有一层 Scaffold（含 TopAppBar），由它统一处理状态栏 / 导航栏 insets，
 * 避免根壳再叠一层 Scaffold 造成横屏侧边被重复留白。
 */
@Composable
private fun AdaptiveNavigation(
    spec: WindowSpec,
    tab: LauncherTab,
    onTab: (LauncherTab) -> Unit,
    content: @Composable () -> Unit,
) {
    when (spec.navType) {
        NavType.BAR -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { content() }
                NavigationBar {
                    LauncherTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { onTab(item) },
                            icon = { Icon(item.icon(), contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        }

        NavType.RAIL -> {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    header = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "MC Java 启动器",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    },
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        LauncherTab.entries.forEach { item ->
                            NavigationRailItem(
                                selected = tab == item,
                                onClick = { onTab(item) },
                                icon = { Icon(item.icon(), contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }

        NavType.DRAWER -> {
            PermanentNavigationDrawer(
                modifier = Modifier.fillMaxSize(),
                drawerContent = {
                    PermanentDrawerSheet(modifier = Modifier.width(240.dp)) {
                        Text(
                            "MC Java 启动器",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                        )
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            LauncherTab.entries.forEach { item ->
                                NavigationDrawerItem(
                                    selected = tab == item,
                                    onClick = { onTab(item) },
                                    icon = { Icon(item.icon(), contentDescription = item.label) },
                                    label = { Text(item.label) },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                },
            ) {
                content()
            }
        }
    }
}
