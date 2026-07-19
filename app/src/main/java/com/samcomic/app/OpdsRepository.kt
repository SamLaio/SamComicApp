package com.samcomic.app

import android.content.Context
import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.URLDecoder
import java.io.StringReader

class OpdsRepository(
    private val client: OkHttpClient = OkHttpClient(),
    private val parser: OpdsParser = OpdsParser()
) {
    private val downloadCacheDirName = "sam-comic/downloads"

    suspend fun loadFeed(url: String, username: String, password: String): OpdsFeed = withContext(Dispatchers.IO) {
        val xml = fetchText(url, username, password)
        val head = xml.trimStart().take(200).lowercase()
        if (head.startsWith("<html") || head.startsWith("<!doctype html")) {
            error("回傳的是 HTML，請輸入 OPDS Feed URL")
        }
        parser.parse(xml)
    }

    suspend fun loadOpenSearchTemplate(url: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        parseOpenSearchTemplate(fetchText(url, username, password))
    }

    suspend fun downloadComic(
        context: Context,
        cacheKey: String,
        link: ReadableLink,
        title: String,
        username: String,
        password: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val workDir = downloadWorkDir(context, cacheKey)
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
            val temp = File(workDir, "$fileName.download")
            target.delete()
            temp.delete()

            try {
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
                if (totalBytes > 0L && downloadedBytes != totalBytes) {
                    error("下載不完整，請重新下載")
                }
                if (!temp.renameTo(target)) {
                    error("無法完成下載檔案寫入")
                }
                workDir.setLastModified(System.currentTimeMillis())
                target.setLastModified(System.currentTimeMillis())
            } finally {
                if (temp.exists()) {
                    temp.delete()
                }
            }
            target
        }
    }

    suspend fun cachedDownloadFile(context: Context, cacheKey: String, skipCacheKey: String? = null): File? = withContext(Dispatchers.IO) {
        cleanupDownloadedComics(context, skipCacheKey)
        val workDir = downloadWorkDir(context, cacheKey)
        val file = workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".download") }
            ?.maxByOrNull { it.lastModified() }
        if (file != null) {
            touchDownloadedComic(file)
        }
        file
    }

    suspend fun scanDownloadedComicKeys(context: Context, skipCacheKey: String? = null): Set<String> = withContext(Dispatchers.IO) {
        cleanupDownloadedComics(context, skipCacheKey)
        downloadBaseDir(context).listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.filter { dir -> dir.listFiles()?.any { it.isFile && !it.name.endsWith(".download") } == true }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
    }

    fun touchDownloadedComic(file: File) {
        val dir = file.parentFile ?: return
        val now = System.currentTimeMillis()
        file.setLastModified(now)
        dir.setLastModified(now)
    }

    private fun cleanupDownloadedComics(context: Context, skipCacheKey: String? = null) {
        val baseDir = downloadBaseDir(context)
        if (!baseDir.exists()) return
        val now = System.currentTimeMillis()
        baseDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name == skipCacheKey) return@forEach
            if (now - dir.lastModified() > DOWNLOAD_CACHE_TTL_MILLIS) {
                dir.deleteRecursively()
            }
        }
    }

    private fun downloadBaseDir(context: Context): File {
        return File(context.cacheDir, downloadCacheDirName)
    }

    private fun downloadWorkDir(context: Context, cacheKey: String): File {
        return File(downloadBaseDir(context), cacheKey)
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

    private fun parseOpenSearchTemplate(xml: String): String? {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(StringReader(xml))

        var fallbackTemplate: String? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "Url") {
                val template = parser.getAttributeValue(null, "template").orEmpty()
                val type = parser.getAttributeValue(null, "type").orEmpty().lowercase()
                if (template.contains("{searchTerms", ignoreCase = true)) {
                    if (type.contains("atom") || type.contains("opds") || type.contains("xml")) {
                        return template
                    }
                    if (fallbackTemplate == null) fallbackTemplate = template
                }
            }
            eventType = parser.next()
        }
        return fallbackTemplate
    }

    private companion object {
        private const val DOWNLOAD_CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}
