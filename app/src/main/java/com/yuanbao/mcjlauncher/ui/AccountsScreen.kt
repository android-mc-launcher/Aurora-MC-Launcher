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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** 账号页：真实离线 UUID、真实微软 Device Code Flow、Keystore 加密凭据与持久化。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    container: LauncherContainer,
    spec: WindowSpec,
) {
    val scope = rememberCoroutineScope()
    val accounts by container.accounts.accounts.collectAsState()
    val auth by container.accounts.flow.collectAsState()
    val settings by container.settings.state.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftType by remember { mutableStateOf("离线") }
    var loginMode by remember { mutableStateOf("全部") }
    var status by remember { mutableStateOf("账号持久化在应用私有目录，token 经 Android Keystore 加密") }

    val shown =
        remember(accounts, loginMode) {
            accounts.filter { loginMode == "全部" || (if (loginMode == "离线") it.type == "offline" else it.type == "microsoft") }
        }
    val currentAccount = remember(accounts, settings.currentAccountId) { container.accounts.current() }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text(if (draftType == "离线") "玩家名（≤16 字符）" else "微软账号邮箱（仅备注）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("离线", "微软").forEach { mode ->
                            FilterChip(selected = draftType == mode, onClick = { draftType = mode }, label = { Text(mode) })
                        }
                    }
                    if (draftType == "微软" && settings.msClientId.isBlank()) {
                        Text(
                            "未配置 client_id：请先到「设置 → 正版登录」填入 Azure 应用 ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (draftType == "离线") {
                        runCatching { container.accounts.addOffline(draftName) }
                            .onSuccess { status = "已添加离线账号 ${it.name}（UUID ${it.id}）" }
                            .onFailure { status = "添加失败：${it.message}" }
                        draftName = ""
                        showAdd = false
                    } else {
                        showAdd = false
                        container.accounts.clearFlow()
                        scope.launch {
                            container.accounts
                                .loginMicrosoft(settings.msClientId)
                                .onSuccess { status = "正版登录成功：${it.name}" }
                                .onFailure { status = "登录失败：${it.message}" }
                        }
                    }
                }) { Text(if (draftType == "离线") "添加" else "开始正版登录") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAdd = false
                    draftName = ""
                }) { Text("取消") }
            },
        )
    }

    if (auth.active || auth.error.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { container.accounts.clearFlow() },
            title = { Text("微软正版登录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(auth.stage, style = MaterialTheme.typography.bodyMedium)
                    if (auth.userCode.isNotEmpty()) {
                        Text("验证码：${auth.userCode}", style = MaterialTheme.typography.titleLarge)
                        Text("打开：${auth.verificationUri}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "在浏览器输入上面的代码后，本页会自动轮询完成 Xbox Live / XSTS / Minecraft 认证。",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (auth.error.isNotEmpty()) {
                        Text(auth.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { container.accounts.clearFlow() }) { Text("关闭") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                actions = { IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, contentDescription = "添加账号") } },
            )
        },
    ) { innerPadding ->
        AdaptivePanels(
            spec = spec,
            modifier = Modifier.padding(innerPadding),
            primary = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("当前账号：${currentAccount?.name ?: "无"}") },
                        supportingContent = { Text(status) },
                        leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "离线", "微软").forEach { mode ->
                        FilterChip(selected = loginMode == mode, onClick = { loginMode = mode }, label = { Text(mode) })
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text("账号列表（${shown.size}）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        shown.forEach { account ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                RadioButton(
                                    selected = settings.currentAccountId == account.id,
                                    onClick = { container.accounts.select(account.id) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        account.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "UUID ${account.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                AssistChip(onClick = {}, label = { Text(if (account.type == "microsoft") "微软" else "离线") })
                                IconButton(onClick = { container.accounts.remove(account.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除账号", modifier = Modifier.size(20.dp))
                                }
                            }
                            HorizontalDivider()
                        }
                        if (shown.isEmpty()) {
                            Text(
                                "暂无账号，点右上角 + 添加。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            },
            secondary = {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("当前账号：${currentAccount?.name ?: "无"}") },
                        supportingContent = {
                            Text("类型 ${currentAccount?.type ?: "--"} · UUID ${currentAccount?.id ?: "--"}")
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val current = container.accounts.current()
                            if (current?.type == "microsoft") {
                                scope.launch {
                                    container.accounts
                                        .refreshIfNeeded(current)
                                        .onSuccess { status = "已静默续期：${it.name}" }
                                        .onFailure { status = "续期失败：${it.message}" }
                                }
                            } else {
                                status = "当前是离线账号，无需刷新 token"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("刷新凭据") }
                    OutlinedButton(
                        onClick = {
                            container.accounts.resetDemo()
                            status = "已重置为默认离线账号"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("恢复默认") }
                }
                Text(
                    "正版登录需要你在「设置 → 正版登录」填入自己的 Azure 应用 client_id（启用公共客户端流）。" +
                        "完整链路：Device Code → Xbox Live → XSTS → Minecraft Services → 校验所有权 → 角色 profile。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(16.dp))
                AdaptiveInfoCard(spec)
            },
        )
    }
}
