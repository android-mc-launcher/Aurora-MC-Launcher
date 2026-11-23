package com.yuanbao.mcjlauncher.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志落盘、轮转、过滤、导出与系统分享。全部是真实文件操作。 */
class LogCenter(
    private val context: Context,
) {
    private val root = File(context.filesDir, "logs").apply { mkdirs() }
    private val latest = File(root, "latest.log")
    private val rotated = File(root, "latest.1.log")

    /** 追加一行；超过 2MB 时滚动一份，避免日志无限增长拖慢 IO。 */
    fun append(line: String) {
        runCatching {
            if (!latest.exists()) latest.createNewFile()
            if (latest.length() > 2L * 1024L * 1024L) {
                if (rotated.exists()) rotated.delete()
                latest.renameTo(rotated)
                latest.createNewFile()
            }
            latest.appendText(line)
        }
    }

    /** 导出当前内存日志为带时间戳的独立文件。 */
    fun export(logs: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val f = File(root, "export-$stamp.log")
        f.writeText(logs)
        return f
    }

    fun clearDisk() {
        runCatching {
            if (latest.exists()) latest.delete()
            if (rotated.exists()) rotated.delete()
            root
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith("export-") }
                .forEach { it.delete() }
        }
    }

    fun diskStats(): Pair<Int, Long> {
        val fs = root.listFiles().orEmpty().filter { it.isFile }
        return fs.size to fs.sumOf { it.length() }
    }

    /** 通过已声明的 FileProvider 生成 content:// URI，供系统分享。 */
    fun uri(file: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

    fun shareText(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun shareFile(file: File): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    companion object {
        /** 按关键字 + 级别过滤日志行，供 UI 做 derivedStateOf。 */
        fun filter(
            logs: String,
            query: String,
            level: String,
        ): String {
            if (logs.isEmpty()) return logs
            val q = query.trim()
            val lines = logs.lineSequence()
            return lines
                .filter { line ->
                    val levelOk =
                        when (level) {
                            "ERROR" -> line.contains("ERROR", true) || line.contains("失败", true) || line.contains("Exception", true)
                            "WARN" -> line.contains("WARN", true) || line.contains("警告", true)
                            "ENGINE" -> line.contains("[engine]", true) || line.contains("[启动]", true) || line.contains("[运行时]", true)
                            else -> true
                        }
                    val queryOk = q.isEmpty() || line.contains(q, ignoreCase = true)
                    levelOk && queryOk
                }.joinToString("\n")
        }

        val LEVELS = listOf("全部", "ENGINE", "WARN", "ERROR")
    }
}
