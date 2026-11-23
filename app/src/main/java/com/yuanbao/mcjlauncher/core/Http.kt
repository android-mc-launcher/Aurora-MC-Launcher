package com.yuanbao.mcjlauncher.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLongArray

/** HTTP 响应（保留 status 以便 OAuth / Mojang 接口区分业务错误）。 */
data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    val ok: Boolean get() = code in 200..299

    fun json(): JSONObject = JSONObject(body)
}

/**
 * 真实网络层：HttpURLConnection，支持 GET / POST(form) / POST(json)、
 * 重定向、gzip、超时、下载到文件 + SHA-1 校验 + 断点续传（Range），
 * 以及供下载源自动切换使用的轻量探测 [probe]。
 *
 * 下载速度优化（全部保持原有语义）：
 * - 不再对每个请求 `disconnect()`，让底层连接池复用 TCP/TLS，省掉数千资源对象的握手；
 * - 256KB 缓冲，减少 read 系统调用；
 * - SHA-1 边下边算，不再写完后二次读盘；
 * - 大文件（≥12MB 且服务端确认支持 Range）走最多 4 段并发，失败自动回退单连接。
 */
object Http {
    private const val UA = "Aurora/1.0 (Android; Kotlin Launcher)"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val BUFFER_SIZE = 256 * 1024
    private const val PARALLEL_MIN_SIZE = 12L * 1024 * 1024
    private const val MAX_SEGMENTS = 4

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = request(url, "GET", null, null, headers)

    fun postForm(
        url: String,
        form: Map<String, String>,
    ): HttpResponse {
        val body =
            form.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
        return request(url, "POST", body, "application/x-www-form-urlencoded", emptyMap())
    }

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = request(url, "POST", json, "application/json", headers)

    /**
     * 轻量探测下载源速度：GET + `Range: bytes=0-0`，只读取 1 字节。
     * timeoutMs 同时作为 connect / read 超时与"缓慢"判定阈值；
     * 非 2xx、超时、连接失败或总耗时超过阈值都返回 ok=false。
     */
    fun probe(
        url: String,
        timeoutMs: Int,
    ): ProbeResult {
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Range", "bytes=0-0")
                }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { it.read(ByteArray(1)) }
            val elapsed = (System.nanoTime() - start) / 1_000_000L
            ProbeResult(code in 200..299 && elapsed <= timeoutMs, elapsed, code)
        } catch (e: Exception) {
            ProbeResult(
                ok = false,
                elapsedMs = (System.nanoTime() - start) / 1_000_000L,
                httpCode = -1,
                error = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>,
    ): HttpResponse {
        var current = url
        repeat(5) { hop ->
            val conn =
                (URL(current).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Encoding", "gzip")
                    setRequestProperty("Connection", "keep-alive")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (body != null) {
                        doOutput = true
                        contentType?.let { setRequestProperty("Content-Type", it) }
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("重定向无 Location: $current")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text =
                stream?.use { s ->
                    val raw =
                        if (conn.contentEncoding == "gzip") {
                            java.util.zip.GZIPInputStream(s)
                        } else {
                            s
                        }
                    raw.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } ?: ""
            val hdrs =
                conn.headerFields.entries
                    .filter { it.key != null && it.value.isNotEmpty() }
                    .associate { it.key.lowercase() to it.value.first() }
            // 不调用 disconnect()：流关闭后连接自动回到连接池，后续请求可复用 TCP/TLS。
            return HttpResponse(code, text, hdrs)
        }
        throw IOException("重定向次数过多: $url")
    }

    /**
     * 下载到 dest；已存在且 sha1 匹配则跳过。返回实际写入字节数。
     *
     * [parallelSegments] ≥ 2 且文件有 size 元数据、大小 ≥ 12MB 时，先尝试多段并发；
     * 服务端不支持 Range 或任一段失败会清理分段并回退单连接（断点续传仍生效）。
     */
    fun download(
        url: String,
        dest: File,
        expectedSha1: String? = null,
        expectedSize: Long = -1L,
        verify: Boolean = true,
        parallelSegments: Int = 0,
        onBytes: (Long) -> Unit = {},
    ): Long {
        if (dest.exists() && verify && matches(dest, expectedSha1, expectedSize)) {
            onBytes(dest.length())
            return 0L
        }
        if (parallelSegments >= 2 && expectedSize >= PARALLEL_MIN_SIZE) {
            val fast = tryParallel(url, dest, expectedSha1, expectedSize, verify, parallelSegments)
            if (fast != null) {
                onBytes(fast)
                return fast
            }
        }
        return downloadSingle(url, dest, expectedSha1, expectedSize, verify, onBytes)
    }

    /** 单连接下载：Range 断点续传 + 流式 SHA-1，作为多段失败时的可靠回退。 */
    private fun downloadSingle(
        url: String,
        dest: File,
        expectedSha1: String?,
        expectedSize: Long,
        verify: Boolean,
        onBytes: (Long) -> Unit,
    ): Long {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, dest.name + ".part")
        var existing = if (partial.exists()) partial.length() else 0L
        if (existing > 0L) {
            // 校验已下载片段长度，服务端不支持 Range 时从头重下
            if (expectedSize > 0L && existing >= expectedSize) existing = 0L
        }
        val conn =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "keep-alive")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code: $url")
        }
        val append = code == 206 && existing > 0L
        if (!append) existing = 0L
        val needDigest = verify && !expectedSha1.isNullOrBlank()
        val md = MessageDigest.getInstance("SHA-1")
        // 续传时先把已有前缀喂给摘要，避免二次全量读盘。
        if (append && needDigest) {
            partial.inputStream().use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
        }
        var written = 0L
        conn.inputStream.use { input ->
            FileOutputStream(partial, append).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (needDigest) md.update(buf, 0, n)
                    written += n
                    onBytes(n.toLong())
                }
            }
        }
        // 不 disconnect：让连接回池复用（数千个小文件时握手开销是大头）。
        if (needDigest) {
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedSha1, ignoreCase = true)) {
                partial.delete()
                throw IOException("SHA-1 校验失败: $url (期望 $expectedSha1, 实际 $actual)")
            }
        }
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) throw IOException("重命名失败: ${partial.path}")
        return written
    }

    /**
     * 大文件多段并发：服务端返回 206 + Content-Range 时按最多 4 段同时拉取，合并后校验。
     * 返回 null 表示「不适用或失败」，调用方回退单连接；分段临时文件在 finally 里清理。
     */
    private fun tryParallel(
        url: String,
        dest: File,
        expectedSha1: String?,
        expectedSize: Long,
        verify: Boolean,
        segments: Int,
    ): Long? {
        val n = segments.coerceIn(2, MAX_SEGMENTS)
        val total = rangeCapableLength(url)
        if (total < PARALLEL_MIN_SIZE || (expectedSize > 0L && total != expectedSize)) return null
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, dest.name + ".part")
        if (partial.exists()) partial.delete()
        val parts = (0 until n).map { File(dest.parentFile, "${dest.name}.part.seg$it") }
        val pool = Executors.newFixedThreadPool(n)
        val got = AtomicLongArray(n)
        val errors = ConcurrentLinkedQueue<Throwable>()
        return try {
            val chunk = total / n
            val futures =
                (0 until n).map { i ->
                    val start = i * chunk
                    val end = if (i == n - 1) total - 1 else start + chunk - 1
                    pool.submit(
                        Runnable {
                            try {
                                val c =
                                    (URL(url).openConnection() as HttpURLConnection).apply {
                                        requestMethod = "GET"
                                        connectTimeout = CONNECT_TIMEOUT
                                        readTimeout = READ_TIMEOUT
                                        instanceFollowRedirects = true
                                        setRequestProperty("User-Agent", UA)
                                        setRequestProperty("Accept-Encoding", "identity")
                                        setRequestProperty("Connection", "keep-alive")
                                        setRequestProperty("Range", "bytes=$start-$end")
                                    }
                                if (c.responseCode != 206) throw IOException("分段 $i 返回 ${c.responseCode}")
                                c.inputStream.use { input ->
                                    FileOutputStream(parts[i]).use { out ->
                                        val buf = ByteArray(BUFFER_SIZE)
                                        while (true) {
                                            val r = input.read(buf)
                                            if (r < 0) break
                                            out.write(buf, 0, r)
                                            got.addAndGet(i, r.toLong())
                                        }
                                    }
                                }
                                // 不 disconnect：段连接同样回池复用。
                            } catch (t: Throwable) {
                                errors.add(t)
                            }
                        },
                    )
                }
            futures.forEach { it.get() }
            if (errors.isNotEmpty() || parts.any { !it.isFile }) {
                throw IOException("分段下载失败: ${errors.firstOrNull()?.message ?: "缺段"}")
            }
            val needDigest = verify && !expectedSha1.isNullOrBlank()
            val md = MessageDigest.getInstance("SHA-1")
            FileOutputStream(partial).use { out ->
                parts.forEach { f ->
                    f.inputStream().use { input ->
                        val buf = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            if (needDigest) md.update(buf, 0, r)
                        }
                    }
                }
            }
            if (needDigest) {
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha1, ignoreCase = true)) {
                    throw IOException("SHA-1 校验失败(分段): $url")
                }
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) throw IOException("重命名失败: ${partial.path}")
            var written = 0L
            for (i in 0 until n) written += got.get(i)
            written
        } catch (e: Exception) {
            partial.delete()
            null
        } finally {
            pool.shutdownNow()
            parts.forEach { it.delete() }
            partial.delete()
        }
    }

    /** 探测服务端是否支持 Range；返回 Content-Range 里的总长度，不支持返回 -1。 */
    private fun rangeCapableLength(url: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Range", "bytes=0-0")
                }
            if (conn.responseCode != 206) return -1L
            val cr = conn.getHeaderField("Content-Range") ?: return -1L
            conn.inputStream.use { it.read(ByteArray(1)) }
            cr.substringAfterLast('/').toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        } finally {
            conn?.disconnect()
        }
    }

    private fun matches(
        file: File,
        sha1: String?,
        size: Long,
    ): Boolean {
        if (size > 0L && file.length() != size) return false
        if (sha1.isNullOrBlank()) return true
        return sha1(file).equals(sha1, ignoreCase = true)
    }

    fun sha1(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha1Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
