package com.textreader.app.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.abs
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
    /** true si el PDF trae marcadores/outline reales utilizables como capítulos exactos. */
    val hasBookmarks: Boolean
)

/**
 * Extrae texto de un PDF de forma continua (uniendo todas las líneas de un párrafo
 * en un solo bloque, sin cortes de página) y en formato Markdown.
 *
 * Los capítulos se pueden obtener de tres formas (elegidas por el usuario en la UI):
 * 1. A partir de los marcadores/outline reales del PDF.
 * 2. A partir de rangos de páginas definidos a mano por el usuario.
 * 3. El libro entero como un único texto corrido.
 *
 * Dentro del texto, los párrafos con una fuente notablemente más grande que el
 * resto del documento se tratan como subtítulos (## ...). Los saltos de párrafo
 * se detectan por el espacio vertical real entre líneas (no por heurísticas
 * genéricas de PDFBox), para evitar que oraciones se corten a mitad de párrafo.
 */
class PdfParser(context: Context, private val file: File) {

    companion object {
        private const val SAMPLE_PAGES_FOR_TEXT_CHECK = 15
        private const val MIN_CHARS_PER_SAMPLED_PAGE = 5
        private const val PARAGRAPH_GAP_FACTOR = 1.8
    }

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    private var document: PDDocument? = null

    private fun open(): PDDocument {
        return document ?: PDDocument.load(file).also { document = it }
    }

    fun pageCount(): Int = open().numberOfPages

    fun parse(): PdfBook {
        val doc = open()
        val pageCount = doc.numberOfPages
        val hasText = hasExtractableText(doc)

        if (!hasText) {
            return PdfBook(emptyList(), pageCount, hasExtractableText = false, hasBookmarks = false)
        }

        val bookmarkChapters = try {
            buildChaptersFromBookmarks(doc, pageCount)
        } catch (e: Exception) {
            emptyList()
        }

        return PdfBook(bookmarkChapters, pageCount, hasExtractableText = true, hasBookmarks = bookmarkChapters.isNotEmpty())
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

    /** Texto en Markdown de un rango de páginas, corrido, con párrafos y subtítulos detectados con precisión. */
    fun extractMarkdown(startPage: Int, endPage: Int): String {
        val doc = open()
        val stripper = LineCollectorStripper()
        stripper.startPage = startPage + 1
        stripper.endPage = endPage + 1
        stripper.sortByPosition = true
        stripper.getText(doc)

        val lines = stripper.lines
        if (lines.isEmpty()) return ""

        val bodySize = mostCommonRoundedSize(lines.map { it.fontSize })

        // Agrupamos líneas en párrafos según el salto vertical real entre ellas,
        // en vez de confiar en la heurística de párrafo por defecto de PDFBox
        // (que en algunos PDF corta oraciones a mitad de párrafo).
        data class Para(val sb: StringBuilder = StringBuilder(), val sizes: MutableList<Double> = mutableListOf())
        val paragraphs = mutableListOf(Para())

        for (i in lines.indices) {
            val line = lines[i]
            if (i > 0) {
                val prev = lines[i - 1]
                val samePage = line.page == prev.page
                val gap = abs(line.y - prev.y)
                val isNewParagraph = samePage && prev.fontSize > 0 && gap > prev.fontSize * PARAGRAPH_GAP_FACTOR
                if (isNewParagraph) paragraphs.add(Para())
            }
            val current = paragraphs.last()
            current.sb.append(line.text.trim()).append(' ')
            current.sizes.add(line.fontSize)
        }

        val sb = StringBuilder()
        for (p in paragraphs) {
            val rawText = p.sb.toString().trim().replace(Regex("\\s+"), " ")
            if (rawText.isEmpty()) continue
            val avgSize = if (p.sizes.isNotEmpty()) p.sizes.average() else bodySize
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

    /** Imágenes incrustadas dentro de un rango de páginas. */
    fun extractImages(startPage: Int, endPage: Int): List<Bitmap> {
        val doc = open()
        val images = ArrayList<Bitmap>()
        for (pageIdx in startPage..endPage) {
            if (pageIdx !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(pageIdx)
            try {
                collectImagesFromResources(page.resources, images)
            } catch (e: Exception) {
                // página con recursos no legibles: se omite
            }
        }
        return images
    }

    private fun collectImagesFromResources(resources: com.tom_roush.pdfbox.pdmodel.PDResources?, out: MutableList<Bitmap>) {
        resources ?: return
        for (name in resources.xObjectNames) {
            try {
                when (val xobj = resources.getXObject(name)) {
                    is PDImageXObject -> out.add(xobj.image)
                    is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject -> collectImagesFromResources(xobj.resources, out)
                    else -> Unit
                }
            } catch (e: Exception) {
                // imagen individual corrupta/no soportada: se omite
            }
        }
    }

    /** Miniatura de la primera página, para usar como portada en la lista de recientes. */
    fun renderCoverBitmap(maxWidthPx: Int = 240): Bitmap? {
        return try {
            val doc = open()
            if (doc.numberOfPages <= 0) return null
            val renderer = PDFRenderer(doc)
            val full = renderer.renderImage(0, 1f)
            val scale = maxWidthPx.toFloat() / full.width.toFloat()
            if (scale >= 1f) full else Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        document?.close()
        document = null
    }

    /** Una línea de texto extraída, con su posición vertical, tamaño de fuente y página. */
    private data class Line(val text: String, val fontSize: Double, val y: Double, val page: Int)

    private class LineCollectorStripper : PDFTextStripper() {
        val lines = mutableListOf<Line>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            super.writeString(text, textPositions)
            if (text.isNotBlank() && textPositions.isNotEmpty()) {
                val avgSize = textPositions.map { it.fontSizeInPt.toDouble() }.average()
                val avgY = textPositions.map { it.yDirAdj.toDouble() }.average()
                lines.add(Line(text, avgSize, avgY, currentPageNo))
            }
        }
    }
}
