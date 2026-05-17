package com.samcomic.app

import android.content.Context
import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder

class OpdsRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val parser: OpdsParser = OpdsParser()
) {
    suspend fun loadFeed(url: String, username: String, password: String): OpdsFeed = withContext(Dispatchers.IO) {
        val xml = fetchText(url, username, password)
        val head = xml.trimStart().take(200).lowercase()
        if (head.startsWith("<html") || head.startsWith("<!doctype html")) {
            error("回傳的是 HTML，請輸入 OPDS Feed URL")
        }
        parser.parse(xml)
    }

    suspend fun downloadComic(
        context: Context,
        link: ReadableLink,
        title: String,
        username: String,
        password: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "sam-comic/current")
        workDir.deleteRecursively()
        workDir.mkdirs()

        val request = requestBuilder(link.url, username, password)
            .header("Accept", acceptHeader(link.extensionHint))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401) error("認證失敗，請檢查帳號密碼")
                error("下載失敗：HTTP ${response.code}")
            }
            val body = response.body ?: error("下載失敗：空白回應")
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.contains("text/html", ignoreCase = true)) {
                error("下載連結回傳 HTML，不是漫畫檔案")
            }

            val fileName = resolveFileName(
                contentDisposition = response.header("Content-Disposition").orEmpty(),
                url = link.url,
                title = title,
                extensionHint = link.extensionHint
            )
            val target = File(workDir, fileName)
            body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val totalBytes = body.contentLength()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var lastProgressEmit = 0L
                    onProgress(0L, totalBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit > 160L || downloadedBytes == totalBytes) {
                            onProgress(downloadedBytes, totalBytes)
                            lastProgressEmit = now
                        }
                    }
                }
            }
            target
        }
    }

    private fun fetchText(url: String, username: String, password: String): String {
        val request = requestBuilder(url, username, password)
            .header("Accept", "application/atom+xml,application/xml,text/xml,*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401) error("認證失敗，請檢查帳號密碼")
                error("讀取失敗：HTTP ${response.code}")
            }
            return response.body?.string() ?: error("讀取失敗：空白回應")
        }
    }

    private fun requestBuilder(url: String, username: String, password: String): Request.Builder {
        return Request.Builder().url(url).apply {
            if (username.isNotBlank()) {
                header("Authorization", Credentials.basic(username, password))
            }
        }
    }

    private fun acceptHeader(extension: String): String {
        return when (extension) {
            "pdf" -> "application/pdf,*/*"
            "epub" -> "application/epub+zip,*/*"
            "cbz" -> "application/vnd.comicbook+zip,application/x-cbz,application/zip,*/*"
            "zip" -> "application/zip,*/*"
            else -> "*/*"
        }
    }

    private fun resolveFileName(
        contentDisposition: String,
        url: String,
        title: String,
        extensionHint: String
    ): String {
        val serverName = extractFileName(contentDisposition)
        val guessedName = URLUtil.guessFileName(url, contentDisposition.ifBlank { null }, null)
        val rawName = serverName ?: guessedName.ifBlank { title.ifBlank { "comic" } }
        val sanitized = sanitizeFileName(rawName)
        val hasKnownExt = sanitized.lowercase().substringAfterLast('.', "") in setOf("pdf", "cbz", "zip", "epub")
        return if (hasKnownExt || extensionHint.isBlank()) {
            sanitized
        } else {
            "${sanitized.substringBeforeLast('.').ifBlank { sanitized }}.$extensionHint"
        }
    }

    private fun extractFileName(disposition: String): String? {
        if (disposition.isBlank()) return null
        val filenameStar = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
        if (!filenameStar.isNullOrBlank()) {
            val encoded = filenameStar.substringAfter("''", filenameStar)
            val decoded = runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }

        return Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(disposition)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
                .find(disposition)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.trim('"')
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "comic" }
    }
}
