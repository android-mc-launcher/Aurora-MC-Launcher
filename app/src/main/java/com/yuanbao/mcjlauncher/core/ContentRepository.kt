package com.yuanbao.mcjlauncher.core

import java.io.File

/** 实例内四类可管理内容。 */
enum class ContentKind(
    val label: String,
    val dir: String,
    val extensions: Set<String>,
) {
    MODS("模组", "mods", setOf("jar", "jar.disabled")),
    SAVES("存档", "saves", emptySet()),
    PACKS("材质包", "resourcepacks", setOf("zip", "zip.disabled")),
    SHADERS("光影", "shaderpacks", setOf("zip", "zip.disabled")),
}

/** 一条真实磁盘内容。 */
data class ContentItem(
    val kind: ContentKind,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val enabled: Boolean,
    val lastModified: Long,
)

/** 扫描实例目录、统计占用、启用 / 禁用内容（真实文件重命名）。 */
class ContentRepository(
    private val instances: InstanceRepository,
) {
    fun list(
        versionId: String,
        kind: ContentKind,
        sort: String = "NAME",
    ): List<ContentItem> {
        val dir = File(instances.gameDir(versionId, true), kind.dir)
        val files = dir.listFiles().orEmpty().filter { it.isDirectory || it.isFile }
        val items =
            files.map { f ->
                val enabled = !f.name.endsWith(".disabled")
                ContentItem(
                    kind = kind,
                    name = f.name.removeSuffix(".disabled"),
                    path = f.absolutePath,
                    sizeBytes = sizeOf(f),
                    enabled = enabled,
                    lastModified = f.lastModified(),
                )
            }
        return when (sort) {
            "SIZE" -> items.sortedByDescending { it.sizeBytes }
            "TIME" -> items.sortedByDescending { it.lastModified }
            else -> items.sortedBy { it.name.lowercase() }
        }
    }

    fun toggle(item: ContentItem): Boolean {
        val file = File(item.path)
        if (!file.exists()) return false
        val target =
            if (item.enabled) {
                File(file.parentFile, file.name + ".disabled")
            } else {
                File(file.parentFile, file.name.removeSuffix(".disabled"))
            }
        return file.renameTo(target)
    }

    fun counts(versionId: String): Map<ContentKind, Int> = ContentKind.entries.associateWith { list(versionId, it).size }

    fun totalBytes(versionId: String): Long = ContentKind.entries.sumOf { kind -> list(versionId, kind).sumOf { it.sizeBytes } }

    fun summary(versionId: String): String =
        counts(versionId).entries.joinToString(" · ") { "${it.key.label} ${it.value}" } +
            " · 合计 ${totalBytes(versionId) / 1024L / 1024L} MB"

    private fun sizeOf(file: File): Long =
        if (file.isFile) {
            file.length()
        } else {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
}
