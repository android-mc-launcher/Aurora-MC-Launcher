package com.yuanbao.mcjlauncher.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** 控制台命令簿：收藏与历史都真实落到 SharedPreferences。 */
class CommandBook(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences("mcjl_commands", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(loadArray(K_PINNED))
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    private val _history = MutableStateFlow(loadArray(K_HISTORY))
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private fun loadArray(key: String): List<String> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun save(
        key: String,
        list: List<String>,
    ) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun record(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty()) return false
        val next = (listOf(cmd) + _history.value.filter { it != cmd }).take(30)
        _history.value = next
        save(K_HISTORY, next)
        return true
    }

    fun pin(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty() || _pinned.value.contains(cmd)) return false
        val next = (_pinned.value + cmd).take(24)
        _pinned.value = next
        save(K_PINNED, next)
        return true
    }

    fun unpin(command: String) {
        val next = _pinned.value.filter { it != command }
        _pinned.value = next
        save(K_PINNED, next)
    }

    fun clearHistory() {
        _history.value = emptyList()
        save(K_HISTORY, emptyList())
    }

    fun export(): String =
        org.json
            .JSONObject()
            .put("pinned", JSONArray(_pinned.value))
            .put("history", JSONArray(_history.value))
            .toString(2)

    private companion object {
        const val K_PINNED = "pinned"
        const val K_HISTORY = "history"
    }
}
