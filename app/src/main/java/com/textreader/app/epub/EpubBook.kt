package com.textreader.app.epub

/**
 * Un capítulo "exacto" según el índice (TOC) del libro: empieza en un archivo
 * del spine (opcionalmente en un punto interno marcado con un ancla `#id`) y
 * termina justo donde arranca el siguiente capítulo de la lista.
 */
data class EpubChapter(
    val title: String,
    val spineIndex: Int,
    val anchorId: String? = null
)

data class EpubBook(
    val title: String,
    val chapters: List<EpubChapter>
)
