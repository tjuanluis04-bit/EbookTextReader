package com.textreader.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

data class PdfChapter(
    val title: String,
    val startPage: Int, // 0-based, inclusive
    val endPage: Int // 0-based, inclusive
)

data class PdfBook(
    val chapters: List<PdfChapter>,
    val pageCount: Int
)

/**
 * Extrae texto de un PDF de forma continua (uniendo todas las páginas de un bloque
 * en un solo texto), en vez de mostrarlo cortado página por página.
 *
 * Los PDF no tienen "capítulos" como tal, así que si el documento es corto se trata
 * como un único bloque de texto, y si es largo se lo divide en bloques de páginas
 * (no en páginas sueltas) para que copiarlo sea más cómodo.
 */
class PdfParser(context: Context, private val file: File) {

    companion object {
        private const val PAGES_PER_BLOCK = 25
    }

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    private var document: PDDocument? = null

    private fun open(): PDDocument {
        return document ?: PDDocument.load(file).also { document = it }
    }

    fun parse(): PdfBook {
        val doc = open()
        val pageCount = doc.numberOfPages

        val chapters = if (pageCount <= PAGES_PER_BLOCK) {
            listOf(PdfChapter("Documento completo", 0, pageCount - 1))
        } else {
            val list = ArrayList<PdfChapter>()
            var start = 0
            while (start < pageCount) {
                val end = minOf(start + PAGES_PER_BLOCK - 1, pageCount - 1)
                list.add(PdfChapter("Páginas ${start + 1} a ${end + 1}", start, end))
                start = end + 1
            }
            list
        }

        return PdfBook(chapters, pageCount)
    }

    /** Texto corrido de un rango de páginas, sin marcas de corte de página. */
    fun extractText(startPage: Int, endPage: Int): String {
        val doc = open()
        val stripper = PDFTextStripper()
        stripper.startPage = startPage + 1
        stripper.endPage = endPage + 1
        stripper.lineSeparator = "\n"
        return stripper.getText(doc).trim()
    }

    fun close() {
        document?.close()
        document = null
    }
}
