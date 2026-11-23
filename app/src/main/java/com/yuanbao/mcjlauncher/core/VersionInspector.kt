package com.yuanbao.mcjlauncher.core

import org.json.JSONObject
import java.io.File

/** version.json 中一个 library 的本地落盘检查。 */
data class LibraryNode(
    val coordinate: String,
    val path: String,
    val present: Boolean,
    val sizeBytes: Long,
)

/** 一个已安装版本的依赖树体检结果。 */
data class VersionInsight(
    val versionId: String,
    val javaMajor: Int,
    val mainClass: String,
    val libraries: List<LibraryNode>,
) {
    val missing: List<LibraryNode> get() = libraries.filter { !it.present }

    val totalBytes: Long get() = libraries.sumOf { it.sizeBytes }

    val summary: String get() = "${libraries.size} 个依赖 · 缺失 ${missing.size} · 本地 ${totalBytes / 1024L / 1024L} MB"
}

/** 读取真实 version.json 与 libraries 目录，生成依赖树与缺失清单。 */
object VersionInspector {
    fun inspect(
        versionDir: File,
        versionId: String,
        javaMajor: Int,
        mainClass: String,
    ): VersionInsight {
        val json = runCatching { JSONObject(File(versionDir, "version.json").readText()) }.getOrDefault(JSONObject())
        val libs = json.optJSONArray("libraries")
        val root = File(versionDir.parentFile, "libraries")
        val nodes = mutableListOf<LibraryNode>()
        if (libs != null) {
            for (i in 0 until libs.length()) {
                val coordinate = libs.optJSONObject(i)?.optString("name").orEmpty()
                if (coordinate.isBlank()) continue
                val path = coordinateToPath(coordinate)
                val file = File(root, path)
                nodes += LibraryNode(coordinate, path, file.isFile, if (file.isFile) file.length() else 0L)
            }
        }
        return VersionInsight(versionId, javaMajor, mainClass, nodes)
    }

    /** Maven 坐标 group:artifact:version → group/artifact/version/artifact-version.jar。 */
    fun coordinateToPath(coordinate: String): String {
        val parts = coordinate.split(":")
        if (parts.size < 3) return coordinate.replace(':', '/')
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        return "$group/$artifact/$version/$artifact-$version.jar"
    }
}
