package com.yuanbao.mcjlauncher.core

/** 一类日志诊断结论。 */
data class LogInsight(
    val category: String,
    val title: String,
    val count: Int,
    val advice: String,
)

/** 启动日志归类器：把真实日志行映射成可行动的排查建议。 */
object LogAnalyzer {
    private data class Rule(
        val category: String,
        val title: String,
        val pattern: Regex,
        val advice: String,
    )

    private val RULES =
        listOf(
            Rule("内存", "OutOfMemory / 堆不足", Regex("OutOfMemoryError|java heap space", RegexOption.IGNORE_CASE), "降低设置页内存分配，或换用低占用 JVM 预设"),
            Rule("图形", "GL / EGL 上下文失败", Regex("EGL_BAD|GL_INVALID|eglMakeCurrent|GL4ES|libgl4es", RegexOption.IGNORE_CASE), "切换渲染后端，或开启 SFPEW 兼容层"),
            Rule("窗口", "SDL 窗口 / 手柄异常", Regex("\\[sdl\\]|SDL_|libSDL", RegexOption.IGNORE_CASE), "到图形页检查 SDL 库是否就位、Java 版本映射是否匹配"),
            Rule("JNI", "本地库加载失败", Regex("UnsatisfiedLinkError|dlopen|JNI_OnLoad|libmcjl", RegexOption.IGNORE_CASE), "确认 ABI 与 assets 释放目录一致，重新准备运行时"),
            Rule("模组", "类缺失 / 模组冲突", Regex("ClassNotFoundException|NoSuchMethodError|Mixin apply|ModLoadingException", RegexOption.IGNORE_CASE), "检查 mods 目录依赖版本，或禁用自带 FPE 的模组"),
            Rule("资源", "资源索引 / 下载校验", Regex("assetIndex|sha1|SHA-1|checksum|资源索引", RegexOption.IGNORE_CASE), "重新校验资源，或切换下载源后重拉清单"),
            Rule("认证", "账号令牌", Regex("Invalid token|401|authlib|microsoft", RegexOption.IGNORE_CASE), "重新登录微软账号，或改用离线身份"),
            Rule("进程", "非正常退出", Regex("exit=|SIGSEGV|SIGABRT|ANR", RegexOption.IGNORE_CASE), "查看崩溃归档与退出码，必要时提高日志级别重现"),
        )

    fun analyze(logs: String): List<LogInsight> {
        if (logs.isBlank()) return emptyList()
        val lines = logs.lineSequence().toList()
        return RULES
            .map { rule ->
                val count = lines.count { rule.pattern.containsMatchIn(it) }
                LogInsight(rule.category, rule.title, count, rule.advice)
            }.filter { it.count > 0 }
            .sortedByDescending { it.count }
    }

    fun errorLines(logs: String): List<String> =
        logs
            .lineSequence()
            .filter {
                it.contains("ERROR", ignoreCase = true) ||
                    it.contains("Exception", ignoreCase = true) ||
                    it.contains("fatal", ignoreCase = true)
            }.take(40)
            .toList()

    fun summary(logs: String): String {
        val insights = analyze(logs)
        return if (insights.isEmpty()) {
            "未匹配到已知异常模式，共 ${logs.lines().size} 行日志"
        } else {
            "${insights.size} 类线索 · ${insights.sumOf { it.count }} 条命中 · 首位：${insights.first().title}"
        }
    }
}
