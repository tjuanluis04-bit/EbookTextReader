package com.textreader.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.roundToInt

data class PdfChapter(
    val title: String,
    val startPage: Int, // 0-based, inclusive
    val endPage: Int // 0-based, inclusive
)

data class PdfBook(
    val chapters: List<PdfChapter>,
    val pageCount: Int,
    /** false si el PDF no tiene texto real (por ejemplo, es un escaneo/imagen y necesitaría OCR). */
    val hasExtractableText: Boolean,
    /** true si los capítulos vienen de los marcadores reales del PDF (exactos), no de bloques de páginas. */
    val chaptersFromBookmarks: Boolean
)

/**
 * Extrae texto de un PDF de forma continua (uniendo todas las líneas de un párrafo
 * en un solo bloque, sin cortes de página) y en formato Markdown.
 *
 * Los capítulos se arman, en orden de preferencia:
 * 1. A partir de los marcadores/outline reales del PDF (si el archivo los trae),
 *    que son la fuente exacta de la estructura del documento.
 * 2. Si no hay marcadores, se divide en bloques de páginas como respaldo.
 *
 * Dentro de cada capítulo, los párrafos con una fuente notablemente más grande
 * que el resto del documento se tratan como subtítulos (## ...).
 */
class PdfParser(context: Context, private val file: File) {

    companion object {
        private const val PAGES_PER_BLOCK = 25
        private const val SAMPLE_PAGES_FOR_TEXT_CHECK = 15
        private const val MIN_CHARS_PER_SAMPLED_PAGE = 5
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
        val hasText = hasExtractableText(doc)

        if (!hasText) {
            return PdfBook(emptyList(), pageCount, hasExtractableText = false, chaptersFromBookmarks = false)
        }

        val bookmarkChapters = try {
            buildChaptersFromBookmarks(doc, pageCount)
        } catch (e: Exception) {
            emptyList()
        }

        return if (bookmarkChapters.isNotEmpty()) {
            PdfBook(bookmarkChapters, pageCount, hasExtractableText = true, chaptersFromBookmarks = true)
        } else {
            PdfBook(buildPageBlockChapters(pageCount), pageCount, hasExtractableText = true, chaptersFromBookmarks = false)
        }
    }

    /** Arma capítulos a partir de los marcadores (outline) de nivel superior del PDF, si existen. */
    private fun buildChaptersFromBookmarks(doc: PDDocument, pageCount: Int): List<PdfChapter> {
        val outline = doc.documentCatalog.documentOutline ?: return emptyList()

        val raw = ArrayList<Pair<String, Int>>()
        var item: PDOutlineItem? = outline.firstChild
        while (item != null) {
            val title = item.title?.trim().orEmpty()
            val pageIndex = resolveItemPageIndex(doc, item)
            if (title.isNotEmpty() && pageIndex != null && pageIndex in 0 until pageCount) {
                raw.add(title to pageIndex)
            }
            item = item.nextSibling
        }

        val cleaned = raw.distinctBy { it.second }.sortedBy { it.second }
        if (cleaned.isEmpty()) return emptyList()

        return cleaned.mapIndexed { index, (title, startPage) ->
            val endPage = if (index + 1 < cleaned.size) cleaned[index + 1].second - 1 else pageCount - 1
            PdfChapter(title, startPage, endPage.coerceAtLeast(startPage))
        }
    }

    private fun resolveItemPageIndex(doc: PDDocument, item: PDOutlineItem): Int? {
        return try {
            val page = item.findDestinationPage(doc) ?: return null
            indexOfPage(doc, page)
        } catch (e: Exception) {
            null
        }
    }

    private fun indexOfPage(doc: PDDocument, target: PDPage): Int? {
        var idx = 0
        for (page in doc.pages) {
            if (page.cosObject === target.cosObject) return idx
            idx++
        }
        return null
    }

    /** Respaldo cuando el PDF no trae marcadores: bloques fijos de páginas. */
    private fun buildPageBlockChapters(pageCount: Int): List<PdfChapter> {
        if (pageCount <= PAGES_PER_BLOCK) {
            return listOf(PdfChapter("Documento completo", 0, pageCount - 1))
        }
        val list = ArrayList<PdfChapter>()
        var start = 0
        while (start < pageCount) {
            val end = minOf(start + PAGES_PER_BLOCK - 1, pageCount - 1)
            list.add(PdfChapter("Páginas ${start + 1} a ${end + 1}", start, end))
            start = end + 1
        }
        return list
    }

    /** Revisa una muestra de páginas para saber si el PDF tiene texto real o es solo imagen/escaneo. */
    private fun hasExtractableText(doc: PDDocument): Boolean {
        val sampleSize = minOf(doc.numberOfPages, SAMPLE_PAGES_FOR_TEXT_CHECK)
        if (sampleSize <= 0) return false
        val stripper = PDFTextStripper()
        stripper.startPage = 1
        stripper.endPage = sampleSize
        val text = stripper.getText(doc)
        val meaningfulChars = text.count { !it.isWhitespace() }
        return meaningfulChars > sampleSize * MIN_CHARS_PER_SAMPLED_PAGE
    }

    /** Texto en Markdown de un rango de páginas, corrido y con subtítulos detectados por tamaño de fuente. */
    fun extractMarkdown(startPage: Int, endPage: Int): String {
        val doc = open()
        val stripper = ParagraphCollectorStripper()
        stripper.startPage = startPage + 1
        stripper.endPage = endPage + 1
        stripper.sortByPosition = true
        stripper.getText(doc) // dispara los callbacks internos; no usamos el texto devuelto directamente

        val allSizes = stripper.paragraphFontSizes.flatten()
        val bodySize = mostCommonRoundedSize(allSizes)

        val sb = StringBuilder()
        for (i in stripper.paragraphTexts.indices) {
            val rawText = stripper.paragraphTexts[i].toString().trim().replace(Regex("\\s+"), " ")
            if (rawText.isEmpty()) continue

            val sizes = stripper.paragraphFontSizes[i]
            val avgSize = if (sizes.isNotEmpty()) sizes.average() else bodySize
            val looksLikeHeading = rawText.length < 120 && bodySize > 0.0

            val prefix = when {
                !looksLikeHeading -> ""
                avgSize >= bodySize * 1.4 -> "# "
                avgSize >= bodySize * 1.15 -> "## "
                avgSize >= bodySize * 1.05 -> "### "
                else -> ""
            }
            sb.append(prefix).append(rawText).append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun mostCommonRoundedSize(sizes: List<Double>): Double {
        if (sizes.isEmpty()) return 0.0
        val buckets = sizes.groupingBy { (it * 2).roundToInt() / 2.0 }.eachCount()
        return buckets.maxByOrNull { it.value }?.key ?: sizes.average()
    }

    fun close() {
        document?.close()
        document = null
    }

    /**
     * Agrupa el texto por párrafos (usando la detección de párrafos propia de PDFBox)
     * y guarda, para cada párrafo, el tamaño de fuente de cada línea que lo compone.
     */
    private class ParagraphCollectorStripper : PDFTextStripper() {
        val paragraphTexts = mutableListOf(StringBuilder())
        val paragraphFontSizes = mutableListOf(mutableListOf<Double>())

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            super.writeString(text, textPositions)
            if (text.isNotBlank()) {
                paragraphTexts.last().append(text).append(' ')
                textPositions.forEach { paragraphFontSizes.last().add(it.fontSizeInPt.toDouble()) }
            }
        }

        override fun writeParagraphSeparator() {
            super.writeParagraphSeparator()
            paragraphTexts.add(StringBuilder())
            paragraphFontSizes.add(mutableListOf())
        }
    }
}
