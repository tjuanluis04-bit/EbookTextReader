package com.textreader.app.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.w3c.dom.Document
import org.w3c.dom.Element as XmlElement
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private data class RawTocEntry(val title: String, val href: String, val anchorId: String?)

/**
 * Lee un archivo .epub y permite:
 * - Obtener la lista de capítulos EXACTA, tomada del índice real del libro.
 * - Extraer el texto de un capítulo en formato Markdown, con las imágenes
 *   incrustadas EN EL LUGAR donde aparecen en el texto original (se extraen
 *   a un archivo temporal y se referencian como `![](file://...)`), y las
 *   tablas convertidas a tablas de Markdown de verdad (no texto suelto).
 */
class EpubParser(private val file: File) {

    private val zipFile = ZipFile(file)
    private lateinit var opfDir: String
    private var idToHref: Map<String, String> = emptyMap()
    private var coverManifestId: String? = null

    private var spineHrefs: List<String> = emptyList()
    private var chapters: List<EpubChapter> = emptyList()

    // Estado temporal usado solo durante una extracción (extractChapterMarkdown).
    private var currentFileDir: String = ""
    private var currentImageDir: File? = null
    private var imageCounter: Int = 0

    private val headingTags = mapOf(
        "h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6
    )
    private val blockContainerTags = setOf(
        "p", "div", "section", "article", "blockquote", "ul", "ol",
        "h1", "h2", "h3", "h4", "h5", "h6", "table", "figure"
    )

    fun parse(): EpubBook {
        val containerXml = readEntryAsString("META-INF/container.xml")
        val containerDoc = parseXml(containerXml)
        val rootfileNode = containerDoc.getElementsByTagName("rootfile").item(0) as XmlElement
        val opfPath = rootfileNode.getAttribute("full-path")
        opfDir = opfPath.substringBeforeLast('/', "")

        val opfXml = readEntryAsString(opfPath)
        val opfDoc = parseXml(opfXml)

        val titleNodes = opfDoc.getElementsByTagName("dc:title")
        val bookTitle = if (titleNodes.length > 0) titleNodes.item(0).textContent.trim() else file.nameWithoutExtension

        val manifestItems = opfDoc.getElementsByTagName("item")
        val idToHrefMap = HashMap<String, String>()
        var ncxId: String? = null
        var navHref: String? = null
        var coverId: String? = null
        for (i in 0 until manifestItems.length) {
            val item = manifestItems.item(i) as XmlElement
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            val properties = item.getAttribute("properties")
            idToHrefMap[id] = href
            if (mediaType == "application/x-dtbncx+xml") ncxId = id
            if (properties.contains("nav")) navHref = href
            if (properties.contains("cover-image")) coverId = id
        }
        idToHref = idToHrefMap

        if (coverId == null) {
            val metas = opfDoc.getElementsByTagName("meta")
            for (i in 0 until metas.length) {
                val meta = metas.item(i) as XmlElement
                if (meta.getAttribute("name") == "cover") {
                    coverId = meta.getAttribute("content").ifBlank { null }
                    break
                }
            }
        }
        coverManifestId = coverId

        val spineItems = opfDoc.getElementsByTagName("itemref")
        val spineList = ArrayList<String>()
        for (i in 0 until spineItems.length) {
            val itemref = spineItems.item(i) as XmlElement
            val idref = itemref.getAttribute("idref")
            idToHrefMap[idref]?.let { spineList.add(resolvePath(opfDir, it)) }
        }
        spineHrefs = spineList

        val rawToc: List<RawTocEntry> = try {
            if (ncxId != null) {
                readTopLevelTocFromNcx(idToHrefMap.getValue(ncxId))
            } else if (navHref != null) {
                readTopLevelTocFromNav(navHref)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        val resolvedChapters = ArrayList<EpubChapter>()
        for (entry in rawToc) {
            val spineIndex = spineHrefs.indexOf(entry.href)
            if (spineIndex >= 0 && entry.title.isNotBlank()) {
                resolvedChapters.add(EpubChapter(entry.title, spineIndex, entry.anchorId))
            }
        }

        chapters = if (resolvedChapters.isNotEmpty()) {
            resolvedChapters
        } else {
            spineHrefs.mapIndexed { index, _ -> EpubChapter("Capítulo ${index + 1}", index, null) }
        }

        return EpubBook(title = bookTitle, chapters = chapters)
    }

    private fun readTopLevelTocFromNcx(ncxManifestHref: String): List<RawTocEntry> {
        val ncxHref = resolvePath(opfDir, ncxManifestHref)
        val ncxDoc = parseXml(readEntryAsString(ncxHref))
        val ncxDir = ncxHref.substringBeforeLast('/', "")

        val navMap = ncxDoc.getElementsByTagName("navMap").item(0) ?: return emptyList()
        val topNavPoints = directChildren(navMap, "navPoint")

        val entries = ArrayList<RawTocEntry>()
        for (navPoint in topNavPoints) {
            val label = directChild(navPoint, "navLabel")?.let { directChild(it, "text") }?.textContent?.trim()
            val content = directChild(navPoint, "content") ?: continue
            val src = content.getAttribute("src")
            if (label.isNullOrBlank() || src.isBlank()) continue
            val (hrefPart, anchor) = splitHrefAnchor(src)
            entries.add(RawTocEntry(label, resolvePath(ncxDir, hrefPart), anchor))
        }
        return entries
    }

    private fun readTopLevelTocFromNav(navManifestHref: String): List<RawTocEntry> {
        val navPath = resolvePath(opfDir, navManifestHref)
        val navDir = navPath.substringBeforeLast('/', "")
        val soup = Jsoup.parse(readEntryAsString(navPath))

        val tocNav = soup.select("nav[epub|type=toc]").firstOrNull() ?: soup.select("nav").firstOrNull()
            ?: return emptyList()
        val topOl = tocNav.children().firstOrNull { it.tagName().equals("ol", ignoreCase = true) }
            ?: return emptyList()

        val entries = ArrayList<RawTocEntry>()
        for (li in topOl.children()) {
            if (!li.tagName().equals("li", ignoreCase = true)) continue
            val a = li.children().firstOrNull { it.tagName().equals("a", ignoreCase = true) } ?: continue
            val hrefRaw = a.attr("href")
            if (hrefRaw.isBlank()) continue
            val (hrefPart, anchor) = splitHrefAnchor(hrefRaw)
            entries.add(RawTocEntry(a.text().trim(), resolvePath(navDir, hrefPart), anchor))
        }
        return entries
    }

    /**
     * Texto en Markdown de un capítulo. Las imágenes del capítulo se extraen
     * a archivos dentro de [imageDir] y quedan referenciadas en el lugar
     * exacto donde aparecían en el texto original.
     */
    fun extractChapterMarkdown(index: Int, imageDir: File): String {
        currentImageDir = imageDir
        imageCounter = 0
        imageDir.mkdirs()

        val sb = StringBuilder()
        for ((fileDir, element) in chapterElementsWithDir(index)) {
            currentFileDir = fileDir
            appendMarkdownBlockElement(element, sb)
        }
        return sb.toString().trim()
    }

    /** Imágenes (nombre de archivo + bytes) que aparecen dentro de un capítulo (sin incrustar). */
    fun imagesInChapter(index: Int): List<Pair<String, ByteArray>> {
        val images = ArrayList<Pair<String, ByteArray>>()
        for ((fileDir, element) in chapterElementsWithDir(index)) {
            val imgs = if (element.tagName().equals("img", true)) listOf(element) else element.select("img")
            for (img in imgs) {
                val src = img.attr("src").ifBlank { img.attr("xlink:href") }
                if (src.isBlank()) continue
                try {
                    val resolved = resolvePath(fileDir, src)
                    val bytes = readEntryAsBytes(resolved)
                    if (bytes != null) images.add(resolved.substringAfterLast('/') to bytes)
                } catch (e: Exception) {
                    // se omite
                }
            }
        }
        return images
    }

    fun coverImage(): Pair<String, ByteArray>? {
        val id = coverManifestId ?: return firstImageInBook()
        val href = idToHref[id] ?: return firstImageInBook()
        val resolved = resolvePath(opfDir, href)
        val bytes = readEntryAsBytes(resolved) ?: return firstImageInBook()
        return resolved.substringAfterLast('/') to bytes
    }

    private fun firstImageInBook(): Pair<String, ByteArray>? {
        if (spineHrefs.isEmpty()) return null
        return imagesInChapter(0).firstOrNull()
    }

    /** (directorio del archivo, elemento) para cada elemento de nivel superior que compone el capítulo. */
    private fun chapterElementsWithDir(index: Int): List<Pair<String, Element>> {
        val chapter = chapters.getOrNull(index) ?: return emptyList()
        val next = chapters.getOrNull(index + 1)
        val startSpine = chapter.spineIndex
        val endSpine = next?.spineIndex ?: (spineHrefs.size - 1)

        val result = ArrayList<Pair<String, Element>>()
        for (spineIdx in startSpine..endSpine) {
            if (spineIdx !in spineHrefs.indices) continue
            val href = spineHrefs[spineIdx]
            val fileDir = href.substringBeforeLast('/', "")
            val startAnchor = if (spineIdx == startSpine) chapter.anchorId else null
            val endAnchor = if (next != null && spineIdx == next.spineIndex) next.anchorId else null
            for (element in chapterFileChunk(href, startAnchor, endAnchor)) {
                result.add(fileDir to element)
            }
        }
        return result
    }

    private fun chapterFileChunk(href: String, startAnchor: String?, endAnchor: String?): List<Element> {
        val doc = Jsoup.parse(readEntryAsString(href))
        val body = doc.body()
        val children = body.children()
        val startIdx = startAnchor?.let { findTopLevelChunkIndex(body, it) } ?: 0
        val endIdx = endAnchor?.let { findTopLevelChunkIndex(body, it) } ?: children.size
        val safeStart = startIdx.coerceIn(0, children.size)
        val safeEnd = endIdx.coerceIn(safeStart, children.size)
        return children.subList(safeStart, safeEnd)
    }

    private fun findTopLevelChunkIndex(body: Element, anchorId: String): Int? {
        val target = body.getElementById(anchorId)
            ?: body.select("a[name=$anchorId]").firstOrNull()
            ?: return null
        var node: Element? = target
        while (node != null && node.parent() !== body) {
            node = node.parent()
        }
        if (node == null) return null
        val children = body.children()
        for (i in children.indices) {
            if (children[i] === node) return i
        }
        return null
    }

    private fun appendMarkdownBlockElement(node: Element, sb: StringBuilder) {
        val tag = node.tagName().lowercase()
        when {
            tag == "script" || tag == "style" -> return

            tag == "img" -> {
                emitImageMarkdown(node)?.let { sb.append(it).append("\n\n") }
            }

            tag == "table" -> {
                val md = convertTableToMarkdown(node)
                if (md.isNotBlank()) sb.append(md).append("\n\n")
            }

            tag == "figure" -> {
                node.children().forEach { appendMarkdownBlockElement(it, sb) }
            }

            tag == "figcaption" -> {
                val text = inlineMarkdown(node).trim()
                if (text.isNotEmpty()) sb.append('*').append(text).append("*\n\n")
            }

            headingTags.containsKey(tag) -> {
                val text = inlineMarkdown(node).trim()
                if (text.isNotEmpty()) {
                    sb.append("#".repeat(headingTags.getValue(tag))).append(' ').append(text).append("\n\n")
                }
            }

            tag == "blockquote" -> {
                val text = inlineMarkdown(node).trim()
                if (text.isNotEmpty()) {
                    text.split("\n").forEach { line -> sb.append("> ").append(line.trim()).append('\n') }
                    sb.append('\n')
                }
            }

            tag == "li" -> {
                val text = inlineMarkdown(node).trim()
                if (text.isNotEmpty()) sb.append("- ").append(text).append('\n')
            }

            tag == "ul" || tag == "ol" -> {
                node.children().forEach { appendMarkdownBlockElement(it, sb) }
                sb.append('\n')
            }

            tag in blockContainerTags -> {
                val hasBlockChildren = node.children().any { it.tagName().lowercase() in blockContainerTags }
                if (hasBlockChildren) {
                    node.children().forEach { appendMarkdownBlockElement(it, sb) }
                } else {
                    val text = inlineMarkdown(node).trim()
                    if (text.isNotEmpty()) sb.append(text).append("\n\n")
                }
            }

            else -> node.children().forEach { appendMarkdownBlockElement(it, sb) }
        }
    }

    /** Convierte un <table> HTML a una tabla real de Markdown (no texto suelto). */
    private fun convertTableToMarkdown(table: Element): String {
        val rows = table.select("> thead > tr, > tbody > tr, > tr")
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        rows.forEachIndexed { rowIndex, row ->
            val cells = row.select("> th, > td")
            if (cells.isEmpty()) return@forEachIndexed
            sb.append("| ")
            cells.forEach { cell ->
                val text = cell.text().trim().replace("|", "\\|").replace("\n", " ")
                sb.append(text).append(" | ")
            }
            sb.append('\n')
            if (rowIndex == 0) {
                sb.append('|')
                repeat(cells.size) { sb.append(" --- |") }
                sb.append('\n')
            }
        }
        return sb.toString().trim()
    }

    /** Extrae la imagen al directorio de trabajo y devuelve su referencia en Markdown, o null si falla. */
    private fun emitImageMarkdown(img: Element): String? {
        val src = img.attr("src").ifBlank { img.attr("xlink:href") }
        if (src.isBlank()) return null
        val dir = currentImageDir ?: return null
        return try {
            val resolved = resolvePath(currentFileDir, src)
            val bytes = readEntryAsBytes(resolved) ?: return null
            val ext = resolved.substringAfterLast('.', "jpg").lowercase().filter { it.isLetterOrDigit() }.ifBlank { "jpg" }.take(4)
            imageCounter++
            val outFile = File(dir, "img_$imageCounter.$ext")
            outFile.writeBytes(bytes)
            "![](file://${outFile.absolutePath})"
        } catch (e: Exception) {
            null
        }
    }

    private fun inlineMarkdown(element: Element): String {
        val sb = StringBuilder()
        for (node in element.childNodes()) {
            appendInlineNode(node, sb)
        }
        return sb.toString()
    }

    private fun appendInlineNode(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> {
                val tag = node.tagName().lowercase()
                when (tag) {
                    "script", "style" -> Unit
                    "br" -> sb.append("  \n")
                    "img" -> emitImageMarkdown(node)?.let { sb.append("\n\n").append(it).append("\n\n") }
                    "strong", "b" -> {
                        sb.append("**")
                        node.childNodes().forEach { appendInlineNode(it, sb) }
                        sb.append("**")
                    }
                    "em", "i" -> {
                        sb.append("*")
                        node.childNodes().forEach { appendInlineNode(it, sb) }
                        sb.append("*")
                    }
                    else -> node.childNodes().forEach { appendInlineNode(it, sb) }
                }
            }
            else -> Unit
        }
    }

    private fun splitHrefAnchor(raw: String): Pair<String, String?> {
        val hrefPart = raw.substringBefore('#')
        val anchor = raw.substringAfter('#', "").ifEmpty { null }
        return hrefPart to anchor
    }

    private fun directChild(parent: org.w3c.dom.Node, tagName: String): XmlElement? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE && child.nodeName == tagName) {
                return child as XmlElement
            }
        }
        return null
    }

    private fun directChildren(parent: org.w3c.dom.Node, tagName: String): List<XmlElement> {
        val result = ArrayList<XmlElement>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE && child.nodeName == tagName) {
                result.add(child as XmlElement)
            }
        }
        return result
    }

    private fun readEntryAsString(path: String): String {
        val bytes = readEntryAsBytes(path) ?: throw FileNotFoundException("No se encontró '$path' dentro del EPUB")
        return String(bytes, Charsets.UTF_8)
    }

    private fun readEntryAsBytes(path: String): ByteArray? {
        var entry = zipFile.getEntry(path)
        if (entry == null) {
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.name.equals(path, ignoreCase = true)) {
                    entry = e
                    break
                }
            }
        }
        val found = entry ?: return null
        return zipFile.getInputStream(found).use { it.readBytes() }
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        return builder.parse(xml.byteInputStream(Charsets.UTF_8))
    }

    private fun resolvePath(baseDir: String, relative: String): String {
        if (relative.startsWith("/")) return relative.removePrefix("/")
        val base = if (baseDir.isEmpty()) "" else "$baseDir/"
        val parts = (base + relative).split("/")
        val stack = ArrayList<String>()
        for (part in parts) {
            when (part) {
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                ".", "" -> Unit
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    fun close() {
        zipFile.close()
    }
}
