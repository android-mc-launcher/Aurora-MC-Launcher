package com.yuanbao.mcjlauncher.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import com.yuanbao.mcjlauncher.core.DownloadSource
import com.yuanbao.mcjlauncher.core.DownloadSources
import com.yuanbao.mcjlauncher.core.Http
import java.io.File
import java.util.zip.ZipInputStream

sealed interface InstallState {
    data object Idle : InstallState

    data class Downloading(
        val progress: Int,
        val label: String,
    ) : InstallState

    data class Installing(
        val label: String,
    ) : InstallState

    data class Ready(
        val path: String,
    ) : InstallState

    data class Failed(
        val reason: String,
    ) : InstallState
}

data class Artifact(
    val name: String,
    val url: String,
    val destRelative: String,
    val sha1: String = "",
    val size: Long = -1L,
)

/**
 * Mojang 官方与运行时资源地址。
 * 主安装链路走 GameRepository + DownloadSources；这里是 DownloadManager 备用路径，
 * 构造 Artifact 时显式传 [DownloadSource] 即可得到对应源的 URL。
 */
object LauncherArtifacts {
    const val VERSION_MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    const val ASSET_BASE_URL = "https://resources.download.minecraft.net"

    fun versionManifest(source: DownloadSource = DownloadSource.OFFICIAL): Artifact =
        Artifact(
            name = "version_manifest_v2",
            url = DownloadSources.manifestUrl(source),
            destRelative = "game/version_manifest_${source.raw.lowercase()}.json",
        )

    fun assetObject(
        hash: String,
        source: DownloadSource = DownloadSource.OFFICIAL,
    ): Artifact =
        Artifact(
            name = hash,
            url = DownloadSources.mapUrl("$ASSET_BASE_URL/${hash.substring(0, 2)}/$hash", source),
            destRelative = "game/assets/objects/${hash.substring(0, 2)}/$hash",
            sha1 = hash,
        )
}

/**
 * 内置下载 / 安装工具：两条真实路径。
 * - [downloadArtifact]：基于 HttpURLConnection 的直连下载（断点 + SHA-1 校验），
 *   主要安装流程由 GameRepository 使用；
 * - [enqueue] / [query] / [registerCompletion]：系统 DownloadManager 路径，
 *   用于大文件后台下载与通知栏进度。
 * 解压带 Zip Slip 防护，全部真实可用。
 */
class DownloadInstallManager(
    private val context: Context,
) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** 直连下载一个 artifact 到 app 私有目录，返回目标文件。 */
    fun downloadArtifact(
        artifact: Artifact,
        verify: Boolean = true,
        onBytes: (Long) -> Unit = {},
    ): Result<File> =
        runCatching {
            val dest = File(context.filesDir, artifact.destRelative)
            Http.download(
                artifact.url,
                dest,
                artifact.sha1.takeIf { it.isNotBlank() },
                artifact.size,
                verify,
                onBytes,
            )
            dest
        }

    /** 系统 DownloadManager 后台下载（带通知栏进度）。 */
    fun enqueue(artifact: Artifact): Long {
        val request =
            DownloadManager.Request(Uri.parse(artifact.url)).apply {
                setTitle("MC Java · ${artifact.name}")
                setDescription("内置下载器：${artifact.name}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setDestinationInExternalFilesDir(context, "downloads", "${artifact.name}.bin")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }
        return dm.enqueue(request)
    }

    fun query(downloadId: Long): InstallState {
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return InstallState.Idle
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use InstallState.Idle
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val progress = if (total > 0) ((soFar * 100L) / total).toInt() else -1
            when (status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> {
                    InstallState.Downloading(progress, "下载中 ${if (progress >= 0) "$progress%" else ""}")
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    InstallState.Installing("下载完成，待安装")
                }

                DownloadManager.STATUS_PAUSED -> {
                    InstallState.Downloading(progress, "已暂停")
                }

                DownloadManager.STATUS_FAILED -> {
                    InstallState.Failed("下载失败（status=$status）")
                }

                else -> {
                    InstallState.Downloading(progress, "状态 $status")
                }
            }
        }
    }

    fun localFile(artifact: Artifact): File? {
        val external = context.getExternalFilesDir("downloads") ?: return null
        return File(external, "${artifact.name}.bin").takeIf { it.isFile }
    }

    /** 把 zip/natives 包解包到 app 私有目录（带 Zip Slip 防护）。 */
    fun installFromZip(
        zipFile: File,
        dest: File,
    ): Boolean =
        runCatching {
            if (!zipFile.exists()) return@runCatching false
            dest.mkdirs()
            val canonicalDest = dest.canonicalPath
            ZipInputStream(zipFile.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val target = File(dest, entry.name)
                    if (!target.canonicalPath.startsWith(canonicalDest + File.separator) &&
                        target.canonicalPath != canonicalDest
                    ) {
                        throw SecurityException("Zip Slip 拦截: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                }
            }
            true
        }.getOrDefault(false)

    /** 注册下载完成广播，返回反注册闭包（供 DisposableEffect 使用）。 */
    fun registerCompletion(onComplete: (Long) -> Unit): () -> Unit {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
                    if (id != -1L) onComplete(id)
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return { runCatching { context.unregisterReceiver(receiver) } }
    }
}
