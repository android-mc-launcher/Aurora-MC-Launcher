package com.yuanbao.mcjlauncher.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 轻量用户数据：收藏版本、最近安装、上次启动版本、启动次数、安装次数、累计游戏时长。
 * 全部持久化到 SharedPreferences，StateFlow 供 UI 实时订阅。
 */
class ExtrasRepository(
    context: Context,
) {
    private val prefs =
        context.applicationContext
            .getSharedPreferences("mcjl_extras", Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(readFavorites())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _recent = MutableStateFlow(readRecent())
    val recent: StateFlow<List<RecentInstall>> = _recent.asStateFlow()

    private val _lastLaunched = MutableStateFlow(prefs.getString(K_LAST, "") ?: "")
    val lastLaunched: StateFlow<String> = _lastLaunched.asStateFlow()

    private val _stats = MutableStateFlow(readStats())
    val stats: StateFlow<ExtrasStats> = _stats.asStateFlow()

    val value: Set<String> get() = _favorites.value

    fun isFavorite(id: String): Boolean = id in _favorites.value

    fun toggleFavorite(id: String): Boolean {
        if (id.isBlank()) return false
        val next = if (id in _favorites.value) _favorites.value - id else _favorites.value + id
        prefs.edit().putStringSet(K_FAV, next).apply()
        _favorites.value = next
        return id in next
    }

    fun addRecent(id: String) {
        val next =
            (
                listOf(RecentInstall(id, System.currentTimeMillis())) +
                    _recent.value.filterNot { it.id == id }
            ).take(12)
        prefs.edit().putString(K_RECENT, toJson(next).toString()).apply()
        _recent.value = next
        bumpInstalls()
    }

    fun markLaunched(
        id: String,
        exitCode: Int,
        elapsedMs: Long,
    ) {
        prefs.edit().putString(K_LAST, id).apply()
        _lastLaunched.value = id
        val s = _stats.value
        val next =
            s.copy(
                launches = s.launches + 1,
                failures = s.failures + if (exitCode != 0) 1 else 0,
                playMs = s.playMs + elapsedMs.coerceAtLeast(0L),
                lastAt = System.currentTimeMillis(),
            )
        prefs.edit().putString(K_STATS, statsJson(next).toString()).apply()
        _stats.value = next
    }

    fun lastPlayable(installedIds: Set<String>): String {
        val last = _lastLaunched.value
        return if (last.isNotBlank() && last in installedIds) {
            last
        } else {
            _recent.value.firstOrNull { it.id in installedIds }?.id ?: installedIds.firstOrNull() ?: ""
        }
    }

    /** 导出用户数据为 JSON 文件，可用于备份。 */
    fun export(dest: File): File {
        dest.writeText(
            JSONObject()
                .apply {
                    put("favorites", JSONArray(_favorites.value.sorted()))
                    put("recent", toJson(_recent.value))
                    put("lastLaunched", _lastLaunched.value)
                    put("stats", statsJson(_stats.value))
                }.toString(2),
        )
        return dest
    }

    private fun bumpInstalls() {
        val next = _stats.value.copy(installs = _stats.value.installs + 1)
        prefs.edit().putString(K_STATS, statsJson(next).toString()).apply()
        _stats.value = next
    }

    private fun readFavorites(): Set<String> = prefs.getStringSet(K_FAV, emptySet())?.toSet() ?: emptySet()

    private fun readRecent(): List<RecentInstall> {
        val raw = prefs.getString(K_RECENT, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                RecentInstall(o.getString("id"), o.optLong("at", 0L))
            }
        }.getOrDefault(emptyList())
    }

    private fun readStats(): ExtrasStats {
        val raw = prefs.getString(K_STATS, null) ?: return ExtrasStats()
        return runCatching {
            val j = JSONObject(raw)
            ExtrasStats(
                launches = j.optInt("launches", 0),
                installs = j.optInt("installs", 0),
                failures = j.optInt("failures", 0),
                playMs = j.optLong("playMs", 0L),
                lastAt = j.optLong("lastAt", 0L),
            )
        }.getOrDefault(ExtrasStats())
    }

    private fun toJson(list: List<RecentInstall>): JSONArray =
        JSONArray().apply {
            list.forEach {
                put(
                    JSONObject().apply {
                        put("id", it.id)
                        put("at", it.at)
                    },
                )
            }
        }

    private fun statsJson(s: ExtrasStats): JSONObject =
        JSONObject().apply {
            put("launches", s.launches)
            put("installs", s.installs)
            put("failures", s.failures)
            put("playMs", s.playMs)
            put("lastAt", s.lastAt)
        }

    private companion object {
        const val K_FAV = "favorites"
        const val K_RECENT = "recentInstalls"
        const val K_LAST = "lastLaunched"
        const val K_STATS = "stats"
    }
}

data class ExtrasStats(
    val launches: Int = 0,
    val installs: Int = 0,
    val failures: Int = 0,
    val playMs: Long = 0L,
    val lastAt: Long = 0L,
) {
    val summary: String
        get() = "启动 $launches 次 · 安装 $installs 次 · 异常 $failures 次 · 累计 ${playMs / 60000} 分钟"
}
