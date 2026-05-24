package com.samcomic.app

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val links: List<OpdsLink>,
    val totalResults: Int? = null,
    val itemsPerPage: Int? = null,
    val startIndex: Int? = null
)

data class OpdsEntry(
    val title: String,
    val author: String,
    val id: String,
    val summary: String,
    val links: List<OpdsLink>
)

data class OpdsLink(
    val href: String,
    val rel: String,
    val type: String,
    val title: String
)

data class ReadableLink(
    val label: String,
    val url: String,
    val extensionHint: String,
    val mimeType: String
)
