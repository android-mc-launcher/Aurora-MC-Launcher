package com.yuanbao.mcjlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yuanbao.mcjlauncher.core.Diagnostics
import com.yuanbao.mcjlauncher.core.LauncherContainer
import com.yuanbao.mcjlauncher.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TOOLCHAIN =
    listOf(
        "Gradle" to "9.9.1",
        "AGP" to "9.5.1",
        "SDK Build Tools" to "37.0.0",
        "Kotlin / Compose 编译器" to "2.4.21",
        "JDK（构建 JVM）" to "OpenJDK 25.0.5",
        "XZ for Java" to "1.12",
        "Compose BOM" to "2026.11.01",
        "Material 3" to "1.5.0",
        "NDK" to "r30 / 30.0.16248370",
        "CMake" to "4.1.2",
        "C++" to "C++23",
        "minSdk / targetSdk / compileSdk" to "24 / 37 / 37.1",
    )

/** 关于页：真实运行时探测、真联网检查更新、真剪贴板复制。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsState()
    val sourceInfo by container.sources.info.collectAsState()
    var status by remember { mutableStateOf("运行时代理：版本/账号/运行时/引擎均已接通真实逻辑") }

    // 引擎版本串每次都带 state，纯常量部分缓存一次即可，避免每 200ms JNI 调用。
    val engineVersion = remember { NativeEngine.engineVersion() }
    // assetStats() 要递归遍历 516 个 assets 并 openFd；旧实现写在 composition 里，
    // 每次重组都在主线程做磁盘/AssetManager IO。改为 IO 线程异步算一次并缓存。
    val assetInfo by produceState(initialValue = 0 to 0L, key1 = container.runtime) {
        value = withContext(Dispatchers.IO) { container.runtime.assetStats() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { innerPadding ->
        AdaptivePanels(
            spec = spec,
            modifier = Modifier.padding(innerPadding),
            primary = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("MC Java 启动器", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "1.0.0-logic · 逻辑接通版",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(status, style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { scope.launch { status = Diagnostics.checkUpdate(container) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("检查更新") }
                    OutlinedButton(
                        onClick = {
                            val report = Diagnostics.report(context, container)
                            status = if (Diagnostics.copyToClipboard(context, report)) "诊断报告已复制到剪贴板" else "剪贴板不可用"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("复制诊断") }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "工具链精确版本",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        TOOLCHAIN.forEach { (name, value) ->
                            ListItem(headlineContent = { Text(name) }, trailingContent = {
                                Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            })
                            HorizontalDivider()
                        }
                    }
                }
            },
            secondary = {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "运行时诊断（实时）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        val (count, bytes) = assetInfo
                        val assetMb = "%.2f".format(bytes / 1024.0 / 1024.0)
                        listOf(
                            "引擎" to engineVersion,
                            "ABI" to container.runtime.abi(),
                            "assets 文件" to "$count 个 / $assetMb MB",
                            "清单 / 已安装" to "${container.games.value.size} / ${container.games.installed.value.size}",
                            "账号 / 当前" to "${container.accounts.value.size} / ${container.accounts.current()?.name ?: "无"}",
                            "Java 设置" to settings.javaMode.label,
                            "下载源" to sourceInfo.summary,
                            "慢速阈值" to "${settings.autoProbeMs} ms",
                            "官方 / 镜像端点" to "piston-meta.mojang.com ↔ bmclapi2.bangbang93.com",
                            "运行时目录" to container.runtime.runtimeRoot.absolutePath,
                        ).forEach { (k, v) ->
                            ListItem(headlineContent = { Text(k) }, supportingContent = {
                                Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            })
                            HorizontalDivider()
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "已接通的真实逻辑",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        listOf(
                            "版本" to "Mojang version_manifest / version.json / assetIndex 解析 + SHA-1 校验下载",
                            "运行时" to "assets 递归释放 + FCL 分卷 XZ/TAR 合并 + 按 ABI 选 native",
                            "启动" to "classpath / JVM 参数 / 游戏参数模板 / 环境变量 → C++ 引擎 JLI_Launch",
                            "账号" to "离线 UUID + 微软 Device Code → Xbox Live → XSTS → MC Services + Keystore 加密",
                            "设置" to "全部持久化并参与启动参数拼装",
                            "引擎" to "完整 JSON 计划 / stdin / 日志分级 / /proc CPU·MEM 采样 / 退出码",
                            "下载源" to "自动 / 官方 / 镜像三选一；自动按官方响应速度切镜像，切换即从对应源重拉版本列表",
                            "下载管线" to "并发 1–8 / 失败重试 / 自定义镜像根 / 可取消 / 失败清单",
                            "独立实例" to "每版本独立 mods·saves·resourcepacks，配置落盘、可复制、pre/post 钩子真起进程",
                            "启动预检" to "12 项基础检查 + SFPEW 开启时 2 项软检查：java/libjli/classpath/client.jar/资源索引/磁盘/内存/native/渲染库/ABI",
                            "SFPEW 兼容层" to "设置页开关，叠加在渲染器上：已内置 4 ABI 真实 libSimpleFPEWrapper.so（LGPL-3.0），GL/EGL 经 SFPEW_EGL 转发到底层；冲突模组/库缺失自动降级",
                            "崩溃归档" to "非 0 退出自动写日志快照 + 诊断报告，可查看 / 删除 / 分享",
                            "日志中心" to "落盘轮转 / 关键字与级别过滤 / 导出分享 / 系统信息",
                            "收藏与历史" to "收藏版本、最近安装、上次续玩、启动/安装/异常累计统计",
                            "安装队列" to "批量入队串行安装，清空队列会真正取消当前协程",
                            "悬浮窗" to "六页签：状态 / 性能折线 / 日志过滤 / 命令簿 / SDL 手柄 / 快捷设置，停止与强停接真实动作",
                            "SDL2 / SDL3" to "高版本 Java 自动映射：Java 17 → SDL2 2.32.57、Java 21/25 → SDL3 3.4.12；已内置 4 ABI 真实 ELF（官方最新 2.32.74 / 3.4.20），窗口与输入叠加在渲染器上，库缺失安全降级",
                            "图形页" to "SDL 后端选择、Java × MC 兼容矩阵、能力对照、注入环境变量与实时手柄列表",
                            "内容页" to "模组 / 存档 / 材质包 / 光影真实扫描、排序、启用禁用与 version.json 依赖树体检",
                            "配置预设" to "低配 / 均衡 / 高帧率 / 竞技 / 老版本兼容五套一键方案，48 字段全量 JSON 备份与恢复",
                            "日志分析" to "OOM / GL / SDL / JNI / 模组 / 资源 / 认证 / 进程八类线索归类与排查建议",
                            "性能历史" to "引擎 /proc 采样环形缓冲，CPU 折线图、峰值 / 均值 / 内存峰值报表",
                        ).forEach { (k, v) ->
                            ListItem(headlineContent = { Text(k) }, supportingContent = { Text(v) })
                            HorizontalDivider()
                        }
                    }
                }

                Text(
                    "真机边界：游戏画面渲染依赖 GL4ES / 定制 LWJGL 与 ANativeWindow 的实际桥接，" +
                        "需在设备上验证；微软正版登录需要你自己的 Azure client_id；沙盒无 Android SDK/NDK，未编译 APK。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(16.dp))
                AdaptiveInfoCard(spec)
            },
        )
    }
}
