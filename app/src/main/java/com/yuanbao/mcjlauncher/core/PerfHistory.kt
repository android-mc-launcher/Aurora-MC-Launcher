package com.yuanbao.mcjlauncher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一个性能采样点：由引擎 /proc 真实统计驱动。 */
data class PerfSample(
    val at: Long,
    val cpuPercent: Float,
    val rssMb: Int,
    val elapsedMs: Long,
)

/** 环形性能历史：每次统计变化写入，按 1 秒节流，供悬浮窗折线图与工具页报表使用。 */
class PerfHistory(
    private val capacity: Int = 120,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<PerfSample>()
    private val _points = MutableStateFlow<List<PerfSample>>(emptyList())
    val points: StateFlow<List<PerfSample>> = _points.asStateFlow()

    private var lastAt = 0L

    fun add(stats: EngineStats) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastAt < 900L) return
            lastAt = now
            buffer += PerfSample(now, stats.cpuPercent, (stats.rssKb / 1024L).toInt(), stats.elapsedMs)
            while (buffer.size > capacity) buffer.removeFirst()
            _points.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            lastAt = 0L
            _points.value = emptyList()
        }
    }

    fun peakCpu(): Float = synchronized(lock) { buffer.maxOfOrNull { it.cpuPercent } ?: 0f }

    fun averageCpu(): Float =
        synchronized(lock) {
            if (buffer.isEmpty()) 0f else buffer.map { it.cpuPercent }.average().toFloat()
        }

    fun peakMemoryMb(): Int = synchronized(lock) { buffer.maxOfOrNull { it.rssMb } ?: 0 }

    fun report(): String =
        "采样 ${points.value.size} 点 · CPU 峰值 ${"%.1f".format(peakCpu())}% · " +
            "均值 ${"%.1f".format(averageCpu())}% · MEM 峰值 ${peakMemoryMb()} MB"
}
