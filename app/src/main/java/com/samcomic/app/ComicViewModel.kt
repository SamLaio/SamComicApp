package com.samcomic.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.security.MessageDigest

private const val PROGRESS_PREFS_NAME = "sam_comic_reading_progress"

data class ComicUiState(
    val opdsUrl: String = "",
    val username: String = "",
    val password: String = "",
    val loadingFeed: Boolean = false,
    val loadingReader: Boolean = false,
    val feedTitle: String = "",
    val entries: List<OpdsEntry> = emptyList(),
    val canGoBack: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canSearch: Boolean = false,
    val status: String = "",
    val catalogPageLabel: String = "",
    val catalogVersion: Int = 0,
    val downloadingComic: Boolean = false,
    val downloadProgress: Float? = null,
    val successfulConnectionVersion: Int = 0,
    val successfulOpdsUrl: String = "",
    val successfulUsername: String = "",
    val successfulPassword: String = "",
    val error: String? = null,
    val document: ComicDocument? = null,
    val pageIndex: Int = 0,
    val pageBitmap: Bitmap? = null,
    val secondPageBitmap: Bitmap? = null,
    val showTwoPages: Boolean = false,
    val reverseTwoPageOrder: Boolean = false
)

class ComicViewModel(
    private val repository: OpdsRepository = OpdsRepository(),
    private val navigator: OpdsNavigator = OpdsNavigator(),
    private val comicCache: ComicCache = ComicCache()
) : ViewModel() {
    private val _uiState = MutableStateFlow(ComicUiState())
    val uiState: StateFlow<ComicUiState> = _uiState.asStateFlow()
    private val history = ArrayDeque<String>()
    private var currentFeedUrl: String = ""
    private var renderJob: Job? = null
    private var connectionVersion = 0
    private var catalogVersion = 0
    private var readQueue: List<QueuedComic> = emptyList()
    private var readQueueIndex: Int = -1
    private var activeProgress: ActiveReadingProgress? = null
    private var knownSearchTemplate: String? = null

    fun updateOpdsUrl(value: String) {
        _uiState.value = _uiState.value.copy(opdsUrl = value)
    }

    fun updateUsername(value: String) {
        _uiState.value = _uiState.value.copy(username = value)
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value)
    }

    fun loadRootFeed() {
        val url = _uiState.value.opdsUrl.trim()
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "請輸入 OPDS Feed URL")
            return
        }
        history.clear()
        navigator.reset()
        knownSearchTemplate = null
        _uiState.value = _uiState.value.copy(canSearch = false)
        loadFeed(url, replaceInputUrl = true, rememberSuccessfulConnection = true)
    }

    fun openNavigation(entry: OpdsEntry) {
        val link = navigator.pickNavigationLink(entry) ?: return
        val target = navigator.resolveFromCurrent(link.href)
        if (target.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "無法解析子分類連結")
            return
        }
        if (currentFeedUrl.isNotBlank()) history.addLast(currentFeedUrl)
        loadFeed(target, replaceInputUrl = false, rememberSuccessfulConnection = false)
    }

    fun goBack() {
        if (history.isEmpty()) return
        loadFeed(history.removeLast(), replaceInputUrl = false, rememberSuccessfulConnection = false)
    }

    fun goNext() {
        navigator.feedLink("next")?.let {
            loadFeed(it, replaceInputUrl = false, rememberSuccessfulConnection = false)
        }
    }

    fun goPrevious() {
        navigator.feedLink("previous")?.let {
            loadFeed(it, replaceInputUrl = false, rememberSuccessfulConnection = false)
        }
            ?: navigator.feedLink("prev")?.let {
                loadFeed(it, replaceInputUrl = false, rememberSuccessfulConnection = false)
            }
    }

    fun searchCatalog(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "請輸入搜尋關鍵字")
            return
        }
        val target = navigator.searchUrl(trimmed)
        if (target.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(error = "這個 OPDS 目錄沒有提供搜尋功能")
            return
        }
        if (currentFeedUrl.isNotBlank()) history.addLast(currentFeedUrl)
        loadFeed(
            targetUrl = target,
            replaceInputUrl = false,
            rememberSuccessfulConnection = false,
            statusMessage = "搜尋 OPDS 中"
        )
    }

    fun readableLinks(entry: OpdsEntry): List<ReadableLink> {
        return navigator.readableLinks(entry)
    }

    fun hasNavigation(entry: OpdsEntry): Boolean {
        return navigator.pickNavigationLink(entry) != null
    }

    fun readingProgressLabel(context: Context, entry: OpdsEntry, link: ReadableLink): String? {
        val key = progressKey(entry, link)
        val prefs = context.getSharedPreferences(PROGRESS_PREFS_NAME, Context.MODE_PRIVATE)
        val pageCountKey = "${key}_page_count"
        if (!prefs.contains(pageCountKey)) return null
        val pageCount = prefs.getInt(pageCountKey, 0)
        if (pageCount <= 0) return null
        val pageIndex = prefs.getInt("${key}_page", 0).coerceIn(0, pageCount - 1)
        return "${pageIndex + 1} / $pageCount"
    }

    fun downloadAndOpen(context: Context, entry: OpdsEntry, link: ReadableLink) {
        readQueue = buildReadQueue(entry, link)
        readQueueIndex = readQueue.indexOfFirst { queued ->
            queued.entry.id == entry.id && queued.entry.title == entry.title && queued.link.url == link.url
        }
        if (readQueueIndex == -1) {
            readQueue = listOf(QueuedComic(entry = entry, link = link))
            readQueueIndex = 0
        }
        downloadAndOpenQueued(context.applicationContext, entry, link)
    }

    fun openExternalFile(context: Context, uri: Uri, mimeType: String?) {
        readQueue = emptyList()
        readQueueIndex = -1
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loadingReader = true,
                downloadingComic = false,
                status = "準備開啟外部檔案",
                downloadProgress = null,
                error = null
            )
            runCatching {
                val info = externalFileInfo(context.applicationContext, uri, mimeType)
                val file = copyExternalFileToCache(context.applicationContext, uri, info.fileName)
                info to comicCache.open(file, info.title)
            }.onSuccess { (info, document) ->
                val progressKey = sha256("external|$uri|${info.fileName}")
                val restoredPage = loadSavedPage(context.applicationContext, progressKey, document.pageCount)
                activeProgress = ActiveReadingProgress(
                    key = progressKey,
                    title = info.title
                )
                _uiState.value = _uiState.value.copy(
                    loadingReader = false,
                    downloadingComic = false,
                    status = "已開啟：${document.title}",
                    downloadProgress = null,
                    document = document,
                    pageIndex = restoredPage
                )
                saveReadingProgress(context.applicationContext, restoredPage, document.pageCount)
                renderCurrentPage()
            }.onFailure { ex ->
                activeProgress = null
                _uiState.value = _uiState.value.copy(
                    loadingReader = false,
                    downloadingComic = false,
                    status = "",
                    downloadProgress = null,
                    error = ex.message ?: "無法開啟外部檔案"
                )
            }
        }
    }

    private fun downloadAndOpenQueued(context: Context, entry: OpdsEntry, link: ReadableLink) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loadingReader = true,
                downloadingComic = true,
                status = "下載中：${entry.title}",
                downloadProgress = 0f,
                error = null
            )
            runCatching {
                val file = repository.downloadComic(
                    context = context.applicationContext,
                    link = link,
                    title = entry.title,
                    username = _uiState.value.username,
                    password = _uiState.value.password
                ) { downloadedBytes, totalBytes ->
                    val progress = if (totalBytes > 0L) {
                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    val percent = progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = progress,
                        status = "下載中：${entry.title}$percent"
                    )
                }
                _uiState.value = _uiState.value.copy(status = "準備開啟：${entry.title}")
                comicCache.open(file, entry.title)
            }.onSuccess { document ->
                val progressKey = progressKey(entry, link)
                val restoredPage = loadSavedPage(context, progressKey, document.pageCount)
                activeProgress = ActiveReadingProgress(
                    key = progressKey,
                    title = entry.title
                )
                _uiState.value = _uiState.value.copy(
                    loadingReader = false,
                    downloadingComic = false,
                    status = "已開啟：${document.title}",
                    downloadProgress = null,
                    document = document,
                    pageIndex = restoredPage
                )
                saveReadingProgress(context, restoredPage, document.pageCount)
                renderCurrentPage()
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    loadingReader = false,
                    downloadingComic = false,
                    status = "",
                    downloadProgress = null,
                    error = ex.message ?: "無法開啟漫畫"
                )
            }
        }
    }

    fun nextPage(context: Context) {
        val document = _uiState.value.document ?: return
        val currentPage = _uiState.value.pageIndex
        val pageStep = pageStep()
        val lastVisiblePage = if (_uiState.value.showTwoPages) {
            (currentPage + 1).coerceAtMost(document.pageCount - 1)
        } else {
            currentPage
        }
        if (lastVisiblePage >= document.pageCount - 1) {
            saveReadingProgress(context.applicationContext, currentPage, document.pageCount)
            openNextQueuedComic(context.applicationContext)
            return
        }
        val next = (currentPage + pageStep).coerceAtMost(document.pageCount - 1)
        if (next != currentPage) {
            _uiState.value = _uiState.value.copy(pageIndex = next)
            saveReadingProgress(context.applicationContext, next, document.pageCount)
            renderCurrentPage()
        }
    }

    fun previousPage(context: Context) {
        val document = _uiState.value.document ?: return
        val pageStep = pageStep()
        val previous = (_uiState.value.pageIndex - pageStep).coerceAtLeast(0)
        if (_uiState.value.pageIndex <= 0) {
            saveReadingProgress(context.applicationContext, 0, document.pageCount)
            openPreviousQueuedComic(context.applicationContext)
            return
        }
        if (previous != _uiState.value.pageIndex) {
            _uiState.value = _uiState.value.copy(pageIndex = previous)
            saveReadingProgress(context.applicationContext, previous, document.pageCount)
            renderCurrentPage()
        }
    }

    fun advanceOnePage(context: Context) {
        val document = _uiState.value.document ?: return
        if (!_uiState.value.showTwoPages) return
        val next = (_uiState.value.pageIndex + 1).coerceAtMost(document.pageCount - 1)
        if (next != _uiState.value.pageIndex) {
            _uiState.value = _uiState.value.copy(pageIndex = next)
            saveReadingProgress(context.applicationContext, next, document.pageCount)
            renderCurrentPage()
        }
    }

    fun goToPage(context: Context, displayPage: Int) {
        val document = _uiState.value.document ?: return
        val target = (displayPage - 1).coerceIn(0, document.pageCount - 1)
        if (target != _uiState.value.pageIndex) {
            _uiState.value = _uiState.value.copy(pageIndex = target)
            saveReadingProgress(context.applicationContext, target, document.pageCount)
            renderCurrentPage()
        }
    }

    fun setTwoPageMode(enabled: Boolean) {
        if (_uiState.value.showTwoPages == enabled) return
        _uiState.value.secondPageBitmap?.recycle()
        val alignedPageIndex = if (enabled) {
            alignToTwoPageSpread(_uiState.value.pageIndex)
        } else {
            _uiState.value.pageIndex
        }
        _uiState.value = _uiState.value.copy(
            showTwoPages = enabled,
            secondPageBitmap = null,
            pageIndex = alignedPageIndex
        )
        renderCurrentPage()
    }

    fun toggleTwoPageOrder() {
        if (!_uiState.value.showTwoPages) return
        _uiState.value = _uiState.value.copy(
            reverseTwoPageOrder = !_uiState.value.reverseTwoPageOrder
        )
    }

    fun closeReader() {
        renderJob?.cancel()
        _uiState.value.pageBitmap?.recycle()
        _uiState.value.secondPageBitmap?.recycle()
        _uiState.value = _uiState.value.copy(
            document = null,
            pageBitmap = null,
            secondPageBitmap = null,
            pageIndex = 0,
            loadingReader = false,
            downloadingComic = false,
            reverseTwoPageOrder = false,
            status = "",
            downloadProgress = null
        )
        activeProgress = null
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun loadFeed(
        targetUrl: String,
        replaceInputUrl: Boolean,
        rememberSuccessfulConnection: Boolean,
        statusMessage: String = "讀取 OPDS 中"
    ) {
        viewModelScope.launch {
            val attemptedUsername = _uiState.value.username
            val attemptedPassword = _uiState.value.password
            _uiState.value = _uiState.value.copy(
                loadingFeed = true,
                status = statusMessage,
                downloadingComic = false,
                downloadProgress = null,
                error = null
            )
            runCatching {
                repository.loadFeed(
                    url = targetUrl,
                    username = _uiState.value.username,
                    password = _uiState.value.password
                )
            }.onSuccess { feed ->
                currentFeedUrl = targetUrl
                navigator.updateFeedContext(targetUrl, feed.links)
                val newSearchTemplate = resolveSearchTemplate()
                if (!newSearchTemplate.isNullOrBlank()) {
                    knownSearchTemplate = newSearchTemplate
                }
                navigator.updateSearchTemplate(newSearchTemplate ?: knownSearchTemplate)
                _uiState.value = _uiState.value.copy(
                    opdsUrl = if (replaceInputUrl) targetUrl else _uiState.value.opdsUrl,
                    loadingFeed = false,
                    feedTitle = feed.title,
                    entries = feed.entries,
                    canGoBack = history.isNotEmpty(),
                    canGoNext = navigator.feedLink("next") != null,
                    canGoPrevious = navigator.feedLink("previous") != null || navigator.feedLink("prev") != null,
                    canSearch = navigator.canSearch(),
                    status = "共 ${feed.entries.size} 筆",
                    catalogPageLabel = catalogPageLabel(feed),
                    catalogVersion = ++catalogVersion,
                    successfulConnectionVersion = if (rememberSuccessfulConnection) {
                        connectionVersion += 1
                        connectionVersion
                    } else {
                        _uiState.value.successfulConnectionVersion
                    },
                    successfulOpdsUrl = if (rememberSuccessfulConnection) targetUrl else _uiState.value.successfulOpdsUrl,
                    successfulUsername = if (rememberSuccessfulConnection) attemptedUsername else _uiState.value.successfulUsername,
                    successfulPassword = if (rememberSuccessfulConnection) attemptedPassword else _uiState.value.successfulPassword
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    loadingFeed = false,
                    status = "",
                    error = ex.message ?: "讀取 OPDS 失敗"
                )
            }
        }
    }

    private suspend fun resolveSearchTemplate(): String? {
        navigator.directSearchTemplate()?.let { return it }
        val descriptionUrl = navigator.searchDescriptionUrl() ?: return null
        val template = runCatching {
            repository.loadOpenSearchTemplate(
                url = descriptionUrl,
                username = _uiState.value.username,
                password = _uiState.value.password
            )
        }.getOrNull()
        return template?.let { navigator.resolveTemplate(descriptionUrl, it) }
    }

    private fun catalogPageLabel(feed: OpdsFeed): String {
        val totalResults = feed.totalResults ?: return ""
        val itemsPerPage = feed.itemsPerPage
            ?.takeIf { it > 0 }
            ?: feed.entries.size.takeIf { it > 0 }
            ?: return ""
        val totalPages = ((totalResults + itemsPerPage - 1) / itemsPerPage).coerceAtLeast(1)
        val startIndex = feed.startIndex?.takeIf { it > 0 } ?: 1
        val currentPage = (((startIndex - 1) / itemsPerPage) + 1).coerceIn(1, totalPages)
        return "$currentPage / $totalPages"
    }

    private fun renderCurrentPage() {
        val document = _uiState.value.document ?: return
        val index = _uiState.value.pageIndex
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loadingReader = true, downloadingComic = false)
            runCatching {
                val first = comicCache.renderPage(document, index)
                val second = if (_uiState.value.showTwoPages && index + 1 < document.pageCount) {
                    comicCache.renderPage(document, index + 1)
                } else {
                    null
                }
                first to second
            }.onSuccess { (bitmap, secondBitmap) ->
                _uiState.value.pageBitmap?.recycle()
                _uiState.value.secondPageBitmap?.recycle()
                _uiState.value = _uiState.value.copy(
                    loadingReader = false,
                    pageBitmap = bitmap,
                    secondPageBitmap = secondBitmap,
                    error = null
                )
            }.onFailure { ex ->
                _uiState.value = _uiState.value.copy(
                    loadingReader = false,
                    error = ex.message ?: "頁面渲染失敗"
                )
            }
        }
    }

    private fun pageStep(): Int = if (_uiState.value.showTwoPages) 2 else 1

    private fun alignToTwoPageSpread(pageIndex: Int): Int {
        return if (pageIndex <= 0) 0 else pageIndex - (pageIndex % 2)
    }

    private fun buildReadQueue(currentEntry: OpdsEntry, currentLink: ReadableLink): List<QueuedComic> {
        return _uiState.value.entries.mapNotNull { entry ->
            val readableLinks = navigator.readableLinks(entry)
            if (entry.id == currentEntry.id &&
                entry.title == currentEntry.title &&
                readableLinks.any { it.url == currentLink.url }
            ) {
                return@mapNotNull QueuedComic(entry = entry, link = currentLink)
            }
            readableLinks.firstOrNull()?.let { link ->
                QueuedComic(entry = entry, link = link)
            }
        }
    }

    private fun openNextQueuedComic(context: Context) {
        val nextIndex = readQueueIndex + 1
        val next = readQueue.getOrNull(nextIndex)
        if (next == null) {
            _uiState.value = _uiState.value.copy(status = "已到最後一本")
            return
        }
        readQueueIndex = nextIndex
        downloadAndOpenQueued(context, next.entry, next.link)
    }

    private fun openPreviousQueuedComic(context: Context) {
        val previousIndex = readQueueIndex - 1
        val previous = readQueue.getOrNull(previousIndex)
        if (previous == null) {
            _uiState.value = _uiState.value.copy(status = "已到第一本")
            return
        }
        readQueueIndex = previousIndex
        downloadAndOpenQueued(context, previous.entry, previous.link)
    }

    private fun progressKey(entry: OpdsEntry, link: ReadableLink): String {
        val libraryUrl = _uiState.value.opdsUrl.ifBlank { currentFeedUrl }
        val rawKey = listOf(
            libraryUrl,
            entry.id.ifBlank { entry.title },
            entry.title,
            link.url
        ).joinToString("|")
        return sha256(rawKey)
    }

    private fun loadSavedPage(context: Context, key: String, pageCount: Int): Int {
        val prefs = context.getSharedPreferences(PROGRESS_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("${key}_page", 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    private fun saveReadingProgress(context: Context, pageIndex: Int, pageCount: Int) {
        val progress = activeProgress ?: return
        context.getSharedPreferences(PROGRESS_PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt("${progress.key}_page", pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
            putInt("${progress.key}_page_count", pageCount)
            putString("${progress.key}_title", progress.title)
            putLong("${progress.key}_updated_at", System.currentTimeMillis())
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private suspend fun copyExternalFileToCache(context: Context, uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "sam-comic/external/current")
        workDir.deleteRecursively()
        workDir.mkdirs()
        val target = File(workDir, fileName)
        val input = context.contentResolver.openInputStream(uri) ?: error("無法讀取外部檔案")
        input.use { source ->
            target.outputStream().buffered().use { output ->
                source.copyTo(output)
            }
        }
        target
    }

    private fun externalFileInfo(context: Context, uri: Uri, intentMimeType: String?): ExternalFileInfo {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        val rawName = displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "external_comic"
        val mimeType = intentMimeType ?: runCatching { resolver.getType(uri) }.getOrNull()
        val extension = supportedExternalExtension(rawName, mimeType)
        val sanitizedName = sanitizeExternalFileName(rawName)
        val fileName = if (sanitizedName.lowercase(Locale.ROOT).substringAfterLast('.', "") == extension) {
            sanitizedName
        } else {
            "${sanitizedName.substringBeforeLast('.', sanitizedName)}.$extension"
        }
        return ExternalFileInfo(
            fileName = fileName,
            title = fileName.substringBeforeLast('.', fileName)
        )
    }

    private fun supportedExternalExtension(fileName: String, mimeType: String?): String {
        val extension = fileName
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        if (extension in setOf("cbz", "epub", "pdf", "zip")) return extension

        return when (mimeType?.lowercase(Locale.ROOT)?.substringBefore(";")?.trim()) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "application/x-cbz", "application/vnd.comicbook+zip" -> "cbz"
            "application/zip", "application/x-zip-compressed" -> "zip"
            else -> error("不支援的外部檔案格式，請選擇 CBZ、EPUB、PDF 或 ZIP")
        }
    }

    private fun sanitizeExternalFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "external_comic" }
    }

    private data class QueuedComic(
        val entry: OpdsEntry,
        val link: ReadableLink
    )

    private data class ActiveReadingProgress(
        val key: String,
        val title: String
    )

    private data class ExternalFileInfo(
        val fileName: String,
        val title: String
    )
}
