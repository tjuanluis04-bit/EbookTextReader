package com.textreader.app.epub

data class EpubChapter(
    val title: String,
    val href: String
)

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>
)
