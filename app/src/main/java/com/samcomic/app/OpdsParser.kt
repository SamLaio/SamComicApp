package com.samcomic.app

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class OpdsParser {
    fun parse(feedXml: String): OpdsFeed {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(StringReader(feedXml))

        var feedTitle = "OPDS"
        val entries = mutableListOf<OpdsEntry>()
        val feedLinks = mutableListOf<OpdsLink>()
        var currentEntry: MutableEntry? = null
        var currentText = ""
        var inAuthor = false
        var totalResults: Int? = null
        var itemsPerPage: Int? = null
        var startIndex: Int? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentText = ""
                    when (parser.name) {
                        "entry" -> currentEntry = MutableEntry()
                        "author" -> inAuthor = true
                        "link" -> {
                            val link = OpdsLink(
                                href = parser.getAttributeValue(null, "href").orEmpty(),
                                rel = parser.getAttributeValue(null, "rel").orEmpty(),
                                type = parser.getAttributeValue(null, "type").orEmpty(),
                                title = parser.getAttributeValue(null, "title").orEmpty()
                            )
                            if (currentEntry == null) {
                                feedLinks += link
                            } else {
                                currentEntry.links += link
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> currentText += parser.text.orEmpty()

                XmlPullParser.END_TAG -> {
                    val text = currentText.trim()
                    when (parser.name) {
                        "title" -> {
                            if (currentEntry == null) {
                                if (text.isNotBlank()) feedTitle = text
                            } else if (currentEntry.title.isBlank()) {
                                currentEntry.title = text
                            }
                        }

                        "id" -> currentEntry?.id = text
                        "name" -> if (inAuthor && text.isNotBlank()) currentEntry?.author = text
                        "summary", "content" -> {
                            if (text.isNotBlank() && currentEntry?.summary.isNullOrBlank()) {
                                currentEntry?.summary = text
                            }
                        }

                        "totalResults" -> if (currentEntry == null) totalResults = text.toIntOrNull()
                        "itemsPerPage" -> if (currentEntry == null) itemsPerPage = text.toIntOrNull()
                        "startIndex" -> if (currentEntry == null) startIndex = text.toIntOrNull()
                        "author" -> inAuthor = false
                        "entry" -> {
                            currentEntry?.let {
                                entries += OpdsEntry(
                                    title = it.title.ifBlank { "(Untitled)" },
                                    author = it.author.ifBlank { "Unknown" },
                                    id = it.id,
                                    summary = it.summary,
                                    links = it.links.toList()
                                )
                            }
                            currentEntry = null
                        }
                    }
                    currentText = ""
                }
            }
            eventType = parser.next()
        }

        return OpdsFeed(
            title = feedTitle,
            entries = entries,
            links = feedLinks,
            totalResults = totalResults,
            itemsPerPage = itemsPerPage,
            startIndex = startIndex
        )
    }

    private data class MutableEntry(
        var title: String = "",
        var author: String = "",
        var id: String = "",
        var summary: String = "",
        val links: MutableList<OpdsLink> = mutableListOf()
    )
}
