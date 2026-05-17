package com.samcomic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

sealed class ComicDocument {
    abstract val title: String
    abstract val pageCount: Int

    data class ImageArchive(
        override val title: String,
        val archiveFile: File,
        val pages: List<File>
    ) : ComicDocument() {
        override val pageCount: Int = pages.size
    }

    data class Pdf(
        override val title: String,
        val file: File,
        override val pageCount: Int
    ) : ComicDocument()
}

class ComicCache {
    suspend fun open(file: File, title: String): ComicDocument = withContext(Dispatchers.IO) {
        when (file.extension.lowercase(Locale.ROOT)) {
            "pdf" -> openPdf(file, title)
            "cbz", "zip", "epub" -> openArchive(file, title)
            else -> error("不支援的檔案格式：${file.extension}")
        }
    }

    suspend fun renderPage(document: ComicDocument, index: Int): Bitmap = withContext(Dispatchers.IO) {
        when (document) {
            is ComicDocument.ImageArchive -> renderImagePage(document, index)
            is ComicDocument.Pdf -> renderPdfPage(document, index)
        }
    }

    private fun openPdf(file: File, title: String): ComicDocument.Pdf {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) error("PDF 沒有可讀頁面")
                ComicDocument.Pdf(title = title, file = file, pageCount = renderer.pageCount)
            }
        }
    }

    private fun openArchive(file: File, title: String): ComicDocument.ImageArchive {
        val pagesDir = File(file.parentFile, "pages")
        pagesDir.deleteRecursively()
        pagesDir.mkdirs()

        val extracted = mutableListOf<ExtractedPage>()
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            var entry = zip.nextEntry
            var pageIndex = 0
            while (entry != null) {
                val originalName = entry.name.orEmpty()
                if (!entry.isDirectory && isImagePath(originalName) && !originalName.contains("__MACOSX")) {
                    val extension = originalName.substringAfterLast('.', "jpg")
                    val pageFile = File(pagesDir, "page_${pageIndex.toString().padStart(5, '0')}.$extension")
                    pageFile.outputStream().buffered().use { output ->
                        zip.copyTo(output)
                    }
                    extracted += ExtractedPage(originalName = originalName, file = pageFile)
                    pageIndex += 1
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val pages = extracted
            .sortedWith(compareBy<ExtractedPage> { naturalSortKey(it.originalName) }.thenBy { it.originalName })
            .map { it.file }
        if (pages.isEmpty()) error("壓縮檔裡沒有支援的圖片頁面")
        return ComicDocument.ImageArchive(title = title, archiveFile = file, pages = pages)
    }

    private fun renderImagePage(document: ComicDocument.ImageArchive, index: Int): Bitmap {
        val pageFile = document.pages.getOrNull(index) ?: error("頁面不存在")
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(pageFile.absolutePath, options) ?: error("圖片解碼失敗")
    }

    private fun renderPdfPage(document: ComicDocument.Pdf, index: Int): Bitmap {
        val descriptor = ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.MODE_READ_ONLY)
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val page = renderer.openPage(index)
                page.use {
                    val scale = 2
                    val bitmap = Bitmap.createBitmap(
                        page.width * scale,
                        page.height * scale,
                        Bitmap.Config.ARGB_8888
                    )
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    private fun isImagePath(path: String): Boolean {
        val ext = path.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    }

    private fun naturalSortKey(value: String): String {
        return Regex("\\d+").replace(value.lowercase(Locale.ROOT)) { match ->
            match.value.padStart(12, '0')
        }
    }

    private data class ExtractedPage(
        val originalName: String,
        val file: File
    )
}
