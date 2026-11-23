package com.yuanbao.mcjlauncher.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多实例仓库：每个版本拥有独立游戏目录（mods / saves / resourcepacks /
 * screenshots / crash-reports / logs），配置以 instance.json 真实落盘。
 *
 * 与 GameRepository 的 versions/<id>/instance 兼容：实例根在 filesDir/instances/<id>，
 * 由 LaunchConfigBuilder 直接当作 --gameDir 使用，实现存档与模组隔离。
 */
class InstanceRepository(
    context: Context,
) {
    private val root = File(context.filesDir, "instances")
    private val legacyRoot = File(context.filesDir, "game/versions")

    private val _instances = MutableStateFlow(emptyList<InstanceInfo>())
    val instances: StateFlow<List<InstanceInfo>> = _instances.asStateFlow()

    val value: List<InstanceInfo> get() = _instances.value

    init {
        root.mkdirs()
        refresh()
    }

    fun dir(id: String): File = File(root, sanitize(id))

    /** 游戏目录：实例隔离开启时用 instances/<id>，否则回退旧版 versions/<id>/instance。 */
    fun gameDir(
        id: String,
        isolated: Boolean,
    ): File = if (isolated) dir(id) else File(legacyRoot, "$id/instance")

    /** 确保实例目录骨架存在，返回配置（不存在则创建默认配置）。 */
    fun ensure(id: String): InstanceConfig {
        val d = dir(id)
        SUBDIRS.forEach { File(d, it).mkdirs() }
        val cfgFile = File(d, "instance.json")
        if (!cfgFile.isFile) {
            save(InstanceConfig(versionId = id, createdAt = System.currentTimeMillis()))
        }
        refresh()
        return read(cfgFile)
    }

    fun get(id: String): InstanceConfig {
        val f = File(dir(id), "instance.json")
        return if (f.isFile) read(f) else InstanceConfig(versionId = id)
    }

    fun save(config: InstanceConfig) {
        val d = dir(config.versionId)
        d.mkdirs()
        SUBDIRS.forEach { File(d, it).mkdirs() }
        File(d, "instance.json").writeText(toJson(config).toString())
        refresh()
    }

    fun update(
        id: String,
        transform: (InstanceConfig) -> InstanceConfig,
    ) {
        save(transform(get(id)).copy(versionId = id))
    }

    /** 复制实例（含 mods / saves / 配置），用于做实验分支而不破坏原存档。 */
    fun duplicate(
        id: String,
        newId: String,
    ): Boolean {
        val src = dir(id)
        val dst = dir(newId)
        if (!src.isDirectory || dst.exists()) return false
        val ok = runCatching { src.copyRecursively(dst, overwrite = false) }.isSuccess
        if (ok) {
            val cfg =
                read(File(dst, "instance.json")).copy(
                    versionId = newId,
                    createdAt = System.currentTimeMillis(),
                )
            File(dst, "instance.json").writeText(toJson(cfg).toString())
        }
        refresh()
        return ok
    }

    fun delete(id: String): Boolean {
        val ok = dir(id).deleteRecursively()
        refresh()
        return ok
    }

    fun sizeBytes(id: String): Long = sizeOf(dir(id))

    fun refresh() {
        _instances.value =
            (root.listFiles().orEmpty())
                .filter { it.isDirectory && File(it, "instance.json").isFile }
                .map { d ->
                    val cfg = read(File(d, "instance.json"))
                    InstanceInfo(
                        id = d.name,
                        dir = d.absolutePath,
                        gameDir = d.absolutePath,
                        sizeBytes = sizeOf(d),
                        config = cfg,
                        modCount = File(d, "mods").listFiles().orEmpty().count { it.isFile },
                        saveCount = File(d, "saves").listFiles().orEmpty().count { it.isDirectory },
                        packCount = File(d, "resourcepacks").listFiles().orEmpty().count { it.isFile },
                    )
                }.sortedByDescending { it.id }
    }

    /** 导出配置为 JSON 文本，供复制/分享。 */
    fun export(id: String): String = toJson(get(id)).toString()

    /** 导入配置文本（只覆盖允许字段，id 以目标为准）。 */
    fun import(
        id: String,
        json: String,
    ): Boolean =
        runCatching {
            val j = JSONObject(json)
            save(
                InstanceConfig(
                    versionId = id,
                    memoryMb = j.optInt("memoryMb", 0),
                    extraJvmArgs = stringList(j.optJSONArray("extraJvmArgs")),
                    extraGameArgs = stringList(j.optJSONArray("extraGameArgs")),
                    extraEnv = stringMap(j.optJSONObject("extraEnv")),
                    preLaunch = j.optString("preLaunch", ""),
                    postLaunch = j.optString("postLaunch", ""),
                    notes = j.optString("notes", ""),
                    createdAt = j.optLong("createdAt", System.currentTimeMillis()),
                ),
            )
            true
        }.getOrDefault(false)

    private fun read(f: File): InstanceConfig =
        runCatching {
            val j = JSONObject(f.readText())
            InstanceConfig(
                versionId = j.optString("versionId", f.parentFile?.name ?: ""),
                memoryMb = j.optInt("memoryMb", 0),
                extraJvmArgs = stringList(j.optJSONArray("extraJvmArgs")),
                extraGameArgs = stringList(j.optJSONArray("extraGameArgs")),
                extraEnv = stringMap(j.optJSONObject("extraEnv")),
                preLaunch = j.optString("preLaunch", ""),
                postLaunch = j.optString("postLaunch", ""),
                notes = j.optString("notes", ""),
                createdAt = j.optLong("createdAt", 0L),
            )
        }.getOrDefault(InstanceConfig(versionId = f.parentFile?.name ?: ""))

    private fun toJson(c: InstanceConfig): JSONObject =
        JSONObject().apply {
            put("versionId", c.versionId)
            put("memoryMb", c.memoryMb)
            put("extraJvmArgs", JSONArray(c.extraJvmArgs))
            put("extraGameArgs", JSONArray(c.extraGameArgs))
            put("extraEnv", JSONObject().apply { c.extraEnv.forEach { (k, v) -> put(k, v) } })
            put("preLaunch", c.preLaunch)
            put("postLaunch", c.postLaunch)
            put("notes", c.notes)
            put("createdAt", c.createdAt)
        }

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    private fun stringMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k, "") }
        return out
    }

    private fun sizeOf(f: File): Long {
        if (f.isFile) return f.length()
        return f.listFiles().orEmpty().sumOf { sizeOf(it) }
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        val SUBDIRS = listOf("mods", "saves", "resourcepacks", "screenshots", "crash-reports", "logs", ".minecraft")
    }
}
