package com.yuanbao.mcjlauncher.core

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 登录流程的阶段，供账号页真实展示。 */
data class AuthFlow(
    val active: Boolean = false,
    val stage: String = "",
    val userCode: String = "",
    val verificationUri: String = "",
    val error: String = "",
)

/** 账号仓库：SharedPreferences 持久化 + Keystore 加密 token + 真实 OAuth 编排。 */
class AccountRepository(
    context: Context,
    private val settings: SettingsRepository,
) {
    private val prefs =
        context.applicationContext
            .getSharedPreferences("mcjl_accounts", Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(read())
    val accounts: StateFlow<List<GameAccount>> = _accounts.asStateFlow()

    private val _flow = MutableStateFlow(AuthFlow())
    val flow: StateFlow<AuthFlow> = _flow.asStateFlow()

    val value: List<GameAccount> get() = _accounts.value
    val currentId: String get() = settings.value.currentAccountId

    fun current(): GameAccount? =
        _accounts.value.firstOrNull { it.id == currentId }
            ?: _accounts.value.firstOrNull()

    fun token(account: GameAccount): String = SecurePrefs.decrypt(account.tokenCipher)

    fun select(id: String) {
        settings.update { it.copy(currentAccountId = id) }
    }

    /** 离线账号：UUID 按 Minecraft 离线规则由 "OfflinePlayer:<name>" 生成。 */
    fun addOffline(nameRaw: String): GameAccount {
        val name = nameRaw.trim()
        require(name.isNotBlank()) { "玩家名不能为空" }
        require(name.length <= 16) { "离线玩家名最多 16 字符" }
        val uuid =
            UUID
                .nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
                .toString()
        val account =
            GameAccount(
                id = uuid,
                name = name,
                type = "offline",
                tokenCipher = "",
                note = "离线 · UUID $uuid",
            )
        val next = _accounts.value.filterNot { it.id == uuid } + account
        persist(next)
        select(uuid)
        return account
    }

    /** Device Code Flow：拿 code → 轮询 → 完整 Xbox/MC 链路 → 落盘。 */
    suspend fun loginMicrosoft(clientId: String): Result<GameAccount> =
        runCatching {
            _flow.value = AuthFlow(active = true, stage = "正在申请设备代码…")
            val dc = MicrosoftAuth.requestDeviceCode(clientId)
            _flow.value =
                AuthFlow(
                    active = true,
                    stage = "请在浏览器打开验证页并输入代码",
                    userCode = dc.userCode,
                    verificationUri = dc.verificationUri,
                )
            val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
            var session: MicrosoftAuth.Session? = null
            while (System.currentTimeMillis() < deadline) {
                delay(dc.intervalSeconds * 1000L)
                session = MicrosoftAuth.pollOnce(clientId, dc.deviceCode)
                if (session != null) break
                _flow.value = _flow.value.copy(stage = "等待授权…（${dc.verificationUri}）")
            }
            val s = session ?: throw IllegalStateException("设备代码已过期，请重新登录")
            _flow.value = AuthFlow(active = true, stage = "校验游戏所有权与角色…")
            val account =
                GameAccount(
                    id = s.uuid,
                    name = s.name,
                    type = "microsoft",
                    tokenCipher = SecurePrefs.encrypt(s.accessToken),
                    note = "微软正版 · 到期 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.expiresAt))}",
                    expiresAt = s.expiresAt,
                )
            // refresh_token 单独加密保存，供静默续期
            prefs.edit().putString(REFRESH_PREFIX + s.uuid, SecurePrefs.encrypt(s.refreshToken)).apply()
            val next = _accounts.value.filterNot { it.id == s.uuid } + account
            persist(next)
            select(s.uuid)
            _flow.value = AuthFlow(active = false, stage = "登录成功：${s.name}")
            account
        }.onFailure { e ->
            _flow.value = AuthFlow(active = false, error = e.message ?: "登录失败")
        }

    /** 静默续期：token 过期时用 refresh_token 重跑链路。 */
    suspend fun refreshIfNeeded(account: GameAccount): Result<GameAccount> =
        runCatching {
            require(account.type == "microsoft") { "离线账号无需续期" }
            val cipherRefresh = prefs.getString(REFRESH_PREFIX + account.id, "") ?: ""
            val refresh = SecurePrefs.decrypt(cipherRefresh)
            require(refresh.isNotBlank()) { "没有 refresh_token，请重新登录" }
            val s = MicrosoftAuth.refresh(settings.value.msClientId, refresh)
            val updated =
                account.copy(
                    name = s.name,
                    tokenCipher = SecurePrefs.encrypt(s.accessToken),
                    expiresAt = s.expiresAt,
                    note = "微软正版 · 已续期",
                )
            persist(_accounts.value.map { if (it.id == account.id) updated else it })
            updated
        }

    fun remove(id: String) {
        prefs.edit().remove(REFRESH_PREFIX + id).apply()
        val next = _accounts.value.filterNot { it.id == id }
        persist(next)
        if (currentId == id) select(next.firstOrNull()?.id ?: "")
    }

    fun clearFlow() {
        _flow.value = AuthFlow()
    }

    fun resetDemo() {
        prefs.edit().clear().apply()
        val offline = addOffline("Steve")
        select(offline.id)
    }

    private fun persist(list: List<GameAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("type", a.type)
                    put("cipher", a.tokenCipher)
                    put("note", a.note)
                    put("expiresAt", a.expiresAt)
                },
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
        _accounts.value = list
    }

    private fun read(): List<GameAccount> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GameAccount(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = o.getString("type"),
                    tokenCipher = o.optString("cipher", ""),
                    note = o.optString("note", ""),
                    expiresAt = o.optLong("expiresAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_LIST = "accounts"
        const val REFRESH_PREFIX = "refresh:"
    }
}
