package com.yuanbao.mcjlauncher.core

import org.json.JSONObject
import java.io.IOException

/**
 * 微软正版登录（Device Code Flow → Xbox Live → XSTS → Minecraft Services）。
 *
 * 这是官方公开的标准链路，不含任何私有凭据：client_id 由用户在设置里填自己的
 * Azure 应用（需启用「允许公共客户端流」），默认空值会返回明确错误而不是伪造账号。
 */
object MicrosoftAuth {
    private const val DEVICE_CODE_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
    private const val TOKEN_URL =
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private const val XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_LOGIN_URL =
        "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val ENTITLEMENTS_URL =
        "https://api.minecraftservices.com/entitlements/mcstore"
    private const val PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
    private const val SCOPE = "XboxLive.signin offline_access"

    data class DeviceCode(
        val userCode: String,
        val deviceCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresIn: Int,
    )

    data class Session(
        val name: String,
        val uuid: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
    )

    fun requireClientId(clientId: String): String {
        if (clientId.isBlank()) {
            throw IOException(
                "未配置 Microsoft OAuth client_id：请在「设置 → 正版登录」填入自己的 Azure 应用 ID",
            )
        }
        return clientId.trim()
    }

    fun requestDeviceCode(clientId: String): DeviceCode {
        val r =
            Http.postForm(
                DEVICE_CODE_URL,
                mapOf("client_id" to requireClientId(clientId), "scope" to SCOPE),
            )
        if (!r.ok) throw IOException("devicecode HTTP ${r.code}: ${r.body.take(200)}")
        val j = r.json()
        return DeviceCode(
            userCode = j.getString("user_code"),
            deviceCode = j.getString("device_code"),
            verificationUri = j.optString("verification_uri", "https://www.microsoft.com/link"),
            intervalSeconds = j.optInt("interval", 5).coerceAtLeast(3),
            expiresIn = j.optInt("expires_in", 900),
        )
    }

    /** 单次轮询；返回 null 表示用户尚未完成授权（authorization_pending）。 */
    fun pollOnce(
        clientId: String,
        deviceCode: String,
    ): Session? {
        val r =
            Http.postForm(
                TOKEN_URL,
                mapOf(
                    "client_id" to requireClientId(clientId),
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to deviceCode,
                ),
            )
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        if (!r.ok || j == null) {
            when (j?.optString("error")) {
                "authorization_pending", "slow_down" -> return null
                "expired_token" -> throw IOException("设备代码已过期，请重新发起登录")
                "authorization_declined" -> throw IOException("用户拒绝了授权")
                else -> throw IOException("token HTTP ${r.code}: ${r.body.take(200)}")
            }
        }
        val msToken = j.getString("access_token")
        val refresh = j.optString("refresh_token", "")
        return completeChain(msToken, refresh, j.optLong("expires_in", 3600))
    }

    /** 用 refresh_token 静默续期，避免重复弹设备码。 */
    fun refresh(
        clientId: String,
        refreshToken: String,
    ): Session {
        val r =
            Http.postForm(
                TOKEN_URL,
                mapOf(
                    "client_id" to requireClientId(clientId),
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "scope" to SCOPE,
                ),
            )
        if (!r.ok) throw IOException("refresh HTTP ${r.code}: ${r.body.take(200)}")
        val j = r.json()
        return completeChain(
            j.getString("access_token"),
            j.optString("refresh_token", refreshToken),
            j.optLong("expires_in", 3600),
        )
    }

    /** Xbox Live → XSTS → Minecraft → 校验所有权 → 取 profile。 */
    private fun completeChain(
        msToken: String,
        refresh: String,
        expiresIn: Long,
    ): Session {
        val xblBody =
            JSONObject()
                .apply {
                    put(
                        "Properties",
                        JSONObject().apply {
                            put("AuthMethod", "RPS")
                            put("SiteName", "user.auth.xboxlive.com")
                            put("RpsTicket", "d=$msToken")
                        },
                    )
                    put("RelyingParty", "http://auth.xboxlive.com")
                    put("TokenType", "JWT")
                }.toString()
        val xbl =
            Http.postJson(
                XBL_AUTH_URL,
                xblBody,
                mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            )
        if (!xbl.ok) throw IOException("Xbox Live 认证失败 HTTP ${xbl.code}: ${xbl.body.take(200)}")
        val xblJson = xbl.json()
        val xblToken = xblJson.getString("Token")
        val uhs =
            xblJson
                .getJSONObject("DisplayClaims")
                .getJSONArray("xui")
                .getJSONObject(0)
                .getString("uhs")

        val xstsBody =
            JSONObject()
                .apply {
                    put(
                        "Properties",
                        JSONObject().apply {
                            put("SandboxId", "RETAIL")
                            put("UserTokens", org.json.JSONArray().put(xblToken))
                        },
                    )
                    put("RelyingParty", "rp://api.minecraftservices.com/")
                    put("TokenType", "JWT")
                }.toString()
        val xsts =
            Http.postJson(
                XSTS_URL,
                xstsBody,
                mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            )
        if (!xsts.ok) throw IOException("XSTS 认证失败 HTTP ${xsts.code}: ${xsts.body.take(200)}")
        val xstsToken = xsts.json().getString("Token")

        val identity = "XBL3.0 x=$uhs;$xstsToken"
        val mc =
            Http.postJson(
                MC_LOGIN_URL,
                JSONObject().put("identityToken", identity).toString(),
                mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            )
        if (!mc.ok) throw IOException("Minecraft 登录失败 HTTP ${mc.code}: ${mc.body.take(200)}")
        val mcToken = mc.json().getString("access_token")

        val bearer = mapOf("Authorization" to "Bearer $mcToken")
        val entitlements = Http.get(ENTITLEMENTS_URL, bearer)
        if (!entitlements.ok) throw IOException("所有权校验失败 HTTP ${entitlements.code}")
        val items = entitlements.json().optJSONArray("items")
        if (items == null || items.length() == 0) {
            throw IOException("该微软账号未拥有 Minecraft: Java Edition，无法登录正版")
        }

        val profile = Http.get(PROFILE_URL, bearer)
        if (!profile.ok) throw IOException("读取角色失败 HTTP ${profile.code}: ${profile.body.take(200)}")
        val p = profile.json()
        return Session(
            name = p.getString("name"),
            uuid = p.getString("id"),
            accessToken = mcToken,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
        )
    }
}
