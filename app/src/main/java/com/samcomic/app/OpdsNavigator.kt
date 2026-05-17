package com.samcomic.app

import java.net.URI

class OpdsNavigator {
    private var currentFeedUrl: String = ""
    private var currentFeedLinks: List<OpdsLink> = emptyList()

    fun updateFeedContext(url: String, links: List<OpdsLink>) {
        currentFeedUrl = url
        currentFeedLinks = links
    }

    fun reset() {
        currentFeedUrl = ""
        currentFeedLinks = emptyList()
    }

    fun resolveFromCurrent(href: String): String {
        return resolveUrl(currentFeedUrl, href)
    }

    fun pickNavigationLink(entry: OpdsEntry): OpdsLink? {
        return entry.links.firstOrNull { link ->
            val type = link.type.lowercase()
            val rel = link.rel.lowercase()
            type.contains("application/atom+xml") ||
                type.contains("application/opds") ||
                rel.contains("subsection") ||
                rel.contains("collection")
        }
    }

    fun readableLinks(entry: OpdsEntry): List<ReadableLink> {
        return entry.links.mapNotNull { link ->
            val resolved = resolveFromCurrent(link.href).ifBlank { return@mapNotNull null }
            val extension = extensionHint(link.type, resolved)
            if (!isSupportedComic(link.type, resolved, extension)) return@mapNotNull null
            val label = link.title.ifBlank {
                when (extension) {
                    "pdf" -> "PDF"
                    "cbz" -> "CBZ"
                    "zip" -> "ZIP"
                    "epub" -> "EPUB"
                    else -> "開啟"
                }
            }
            ReadableLink(
                label = label,
                url = resolved,
                extensionHint = extension,
                mimeType = link.type
            )
        }.distinctBy { it.url }
    }

    fun feedLink(rel: String): String? {
        val link = currentFeedLinks.firstOrNull { it.rel.contains(rel, ignoreCase = true) } ?: return null
        return resolveFromCurrent(link.href).ifBlank { null }
    }

    private fun isSupportedComic(type: String, url: String, extension: String): Boolean {
        if (extension in setOf("pdf", "cbz", "zip", "epub")) return true
        val normalized = type.lowercase().substringBefore(";").trim()
        return normalized in setOf(
            "application/pdf",
            "application/epub+zip",
            "application/zip",
            "application/x-zip-compressed",
            "application/x-cbz",
            "application/vnd.comicbook+zip"
        ) || url.lowercase().substringBefore('?').let { path ->
            path.endsWith(".cbz") || path.endsWith(".epub")
        }
    }

    private fun extensionHint(type: String, url: String): String {
        val pathExt = url.lowercase()
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
        if (pathExt in setOf("pdf", "cbz", "zip", "epub")) return pathExt

        return when (type.lowercase().substringBefore(";").trim()) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "application/x-cbz", "application/vnd.comicbook+zip" -> "cbz"
            "application/zip", "application/x-zip-compressed" -> "zip"
            else -> ""
        }
    }

    private fun resolveUrl(baseUrl: String, href: String): String {
        if (href.isBlank()) return ""
        return runCatching {
            if (baseUrl.isBlank()) href else URI(baseUrl).resolve(href).toString()
        }.getOrElse { href }
    }
}
