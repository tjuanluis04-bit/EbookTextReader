package com.textreader.app.epub

data class EpubChapter(
    val title: String,
    val spineIndex: Int,
    val anchorId: String? = null
)

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>
)
