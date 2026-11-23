package com.yuanbao.mcjlauncher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SDL 上报的一个手柄设备（来自引擎日志的真实事件流）。 */
data class GamepadDevice(
    val id: Int,
    val name: String,
    val at: Long,
)

/**
 * SDL 运行时状态仓库。
 * SDL / C++ 侧把设备事件写成 `[sdl] gamepad add id=0 name=...` 日志行，
 * 容器在日志泵里逐行喂给 [consume]，这里维护真实的设备列表 StateFlow。
 */
class SdlGamepadRepository {
    private val _devices = MutableStateFlow<List<GamepadDevice>>(emptyList())
    val devices: StateFlow<List<GamepadDevice>> = _devices.asStateFlow()

    private val pattern = Regex("\\[sdl\\] gamepad (add|remove) id=(\\d+) name=(.+)")

    /** 返回是否消费了一条设备事件，便于容器写诊断日志。 */
    fun consume(line: String): Boolean {
        val match = pattern.find(line) ?: return false
        val action = match.groupValues[1]
        val id = match.groupValues[2].toIntOrNull() ?: return false
        val name = match.groupValues[3].trim()
        val current = _devices.value.toMutableList()
        current.removeAll { it.id == id }
        if (action == "add") current += GamepadDevice(id, name, System.currentTimeMillis())
        val next = current.sortedBy { it.id }
        if (next != _devices.value) _devices.value = next
        return true
    }

    fun clear() {
        _devices.value = emptyList()
    }

    fun snapshot(): String =
        if (_devices.value.isEmpty()) {
            "无已连接手柄"
        } else {
            _devices.value.joinToString(" / ") { "#${it.id} ${it.name}" }
        }
}
