package com.textreader.app.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

data class PdfChapter(
    val title: String,
    val startPage: Int,
    val endPage: Int
)

data class PdfBook(
    val chapters: List<PdfChapter>,
    val pageCount: Int,
    val hasExtractableText: Boolean,
    val hasBookmarks: Boolean
)

/**
 * Extrae texto de un PDF de forma continua y en Markdown, con las imágenes
 * incrustadas en el lugar donde aparecen (aproximado: los PDF no tienen una
 * estructura de documento tan clara como el HTML, así que la posición se
 * calcula comparando la altura vertical de cada imagen con la del texto).
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
    private var renderer: PDFRenderer? = null

    private fun open(): PDDocument {
        return document ?: PDDocument.load(file).also { document = it }
    }

    private fun getRenderer(): PDFRenderer {
        return renderer ?: PDFRenderer(open()).also { renderer = it }
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

    /**
     * Texto en Markdown de un rango de páginas. Si se pasa [imageDir], además
     * extrae las imágenes de esas páginas y las incrusta en el lugar que les
     * corresponde según su posición vertical en la página.
     */
    fun extractMarkdown(startPage: Int, endPage: Int, imageDir: File? = null): String {
        val doc = open()
        val stripper = LineCollectorStripper()
        stripper.startPage = startPage + 1
        stripper.endPage = endPage + 1
        stripper.sortByPosition = true
        stripper.getText(doc)

        val lines = stripper.lines.toMutableList()

        if (imageDir != null) {
            try {
                imageDir.mkdirs()
                var imgCounter = 0
                for (pageIdx in startPage..endPage) {
                    if (pageIdx !in 0 until doc.numberOfPages) continue
                    try {
                        val page = doc.getPage(pageIdx)
                        val pageHeight = page.mediaBox.height
                        val finder = ImagePositionFinder()
                        finder.processPage(page)
                        for (found in finder.found) {
                            try {
                                imgCounter++
                                val outFile = File(imageDir, "pdfimg_${pageIdx}_$imgCounter.jpg")
                                FileOutputStream(outFile).use { out ->
                                    found.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                                val topDownY = (pageHeight - found.y).toDouble()
                                lines.add(Line("", 0.0, topDownY, pageIdx + 1, "![](file://${outFile.absolutePath})"))
                            } catch (e: Exception) {
                                // una imagen puntual no se pudo guardar: se omite
                            }
                        }
                    } catch (e: Exception) {
                        // la página no se pudo analizar en busca de imágenes: se omite
                    }
                }
                lines.sortWith(compareBy({ it.page }, { it.y }))
            } catch (e: Exception) {
                // si falla la extracción de imágenes, seguimos solo con el texto
            }
        }

        if (lines.isEmpty()) return ""

        val bodySize = mostCommonRoundedSize(lines.filter { it.imageMarkdown == null }.map { it.fontSize })

        data class Para(val sb: StringBuilder = StringBuilder(), val sizes: MutableList<Double> = mutableListOf())
        val paragraphs = mutableListOf(Para())

        for (i in lines.indices) {
            val line = lines[i]
            if (i > 0) {
                val prev = lines[i - 1]
                val samePage = line.page == prev.page
                val gap = abs(line.y - prev.y)
                val isImageBoundary = line.imageMarkdown != null || prev.imageMarkdown != null
                val isNewParagraph = isImageBoundary ||
                    (samePage && prev.fontSize > 0 && gap > prev.fontSize * PARAGRAPH_GAP_FACTOR)
                if (isNewParagraph) paragraphs.add(Para())
            }
            val current = paragraphs.last()
            if (line.imageMarkdown != null) {
                current.sb.append(line.imageMarkdown)
            } else {
                current.sb.append(line.text.trim()).append(' ')
                current.sizes.add(line.fontSize)
            }
        }

        val sb = StringBuilder()
        for (p in paragraphs) {
            val raw = p.sb.toString().trim()
            if (raw.isEmpty()) continue
            if (raw.startsWith("![](file://")) {
                sb.append(raw).append("\n\n")
                continue
            }
            val rawText = raw.replace(Regex("\\s+"), " ")
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

    /** Renderiza una página completa como imagen (para el modo "sin texto": guardar páginas como imagen). */
    fun renderPageBitmap(pageIndex: Int, scale: Float = 1.3f): Bitmap? {
        return try {
            getRenderer().renderImage(pageIndex, scale)
        } catch (e: Exception) {
            null
        }
    }

    fun renderCoverBitmap(maxWidthPx: Int = 240): Bitmap? {
        return try {
            val doc = open()
            if (doc.numberOfPages <= 0) return null
            val full = getRenderer().renderImage(0, 1f)
            val scale = maxWidthPx.toFloat() / full.width.toFloat()
            if (scale >= 1f) full else Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        document?.close()
        document = null
        renderer = null
    }

    private data class Line(
        val text: String,
        val fontSize: Double,
        val y: Double,
        val page: Int,
        val imageMarkdown: String? = null
    )

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

    /** Recorre el contenido de una página buscando imágenes y su posición vertical real. */
    private class ImagePositionFinder : PDFStreamEngine() {
        data class Found(val bitmap: Bitmap, val y: Float)
        val found = mutableListOf<Found>()

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.getOrNull(0) as? COSName
                if (name != null) {
                    try {
                        when (val xobject = resources?.getXObject(name)) {
                            is PDImageXObject -> {
                                val ctm = graphicsState.currentTransformationMatrix
                                found.add(Found(xobject.image, ctm.translateY))
                            }
                            is PDFormXObject -> showForm(xobject)
                            else -> Unit
                        }
                    } catch (e: Exception) {
                        // XObject individual no legible: se omite
                    }
                }
            } else {
                super.processOperator(operator, operands)
            }
        }
    }
}
