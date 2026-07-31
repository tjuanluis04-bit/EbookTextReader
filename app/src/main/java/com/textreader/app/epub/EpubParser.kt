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

/** Una entrada cruda del índice (TOC) del EPUB antes de resolverla contra el spine. */
private data class RawTocEntry(val title: String, val href: String, val anchorId: String?)

/**
 * Lee un archivo .epub (que es un .zip con XHTML adentro) y permite:
 * - Obtener la lista de capítulos EXACTA, tomada del índice real del libro
 *   (toc.ncx / nav.xhtml), no de la lista cruda de archivos. Un capítulo puede
 *   abarcar varios archivos, o varios capítulos pueden compartir un mismo
 *   archivo separados por anclas internas (#id).
 * - Extraer el texto de un capítulo en formato Markdown: separa párrafos,
 *   distingue subtítulos (## ...), negrita/itálica, citas y listas.
 */
class EpubParser(private val file: File) {

    private val zipFile = ZipFile(file)
    private lateinit var opfDir: String

    private var spineHrefs: List<String> = emptyList()
    private var chapters: List<EpubChapter> = emptyList()

    private val headingTags = mapOf(
        "h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6
    )
    private val blockContainerTags = setOf(
        "p", "div", "section", "article", "blockquote", "ul", "ol",
        "h1", "h2", "h3", "h4", "h5", "h6"
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

        // Manifest: id -> href / media-type
        val manifestItems = opfDoc.getElementsByTagName("item")
        val idToHref = HashMap<String, String>()
        var ncxId: String? = null
        var navHref: String? = null
        for (i in 0 until manifestItems.length) {
            val item = manifestItems.item(i) as XmlElement
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            val properties = item.getAttribute("properties")
            idToHref[id] = href
            if (mediaType == "application/x-dtbncx+xml") ncxId = id
            if (properties.contains("nav")) navHref = href
        }

        // Spine: orden real de lectura de los archivos del libro
        val spineItems = opfDoc.getElementsByTagName("itemref")
        val spineList = ArrayList<String>()
        for (i in 0 until spineItems.length) {
            val itemref = spineItems.item(i) as XmlElement
            val idref = itemref.getAttribute("idref")
            idToHref[idref]?.let { spineList.add(resolvePath(opfDir, it)) }
        }
        spineHrefs = spineList

        // Índice real del libro (solo el nivel superior = capítulos, no sub-secciones)
        val rawToc: List<RawTocEntry> = try {
            if (ncxId != null) {
                readTopLevelTocFromNcx(idToHref.getValue(ncxId))
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
            // Respaldo: el EPUB no tiene índice utilizable, un capítulo por archivo del spine.
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
     * Texto en Markdown de un capítulo, cortando exactamente donde termina
     * (justo antes de que empiece el siguiente capítulo del índice), incluso
     * si eso cae en medio de un archivo o abarca varios archivos.
     */
    fun extractChapterMarkdown(index: Int): String {
        val chapter = chapters.getOrNull(index) ?: return ""
        val next = chapters.getOrNull(index + 1)
        val startSpine = chapter.spineIndex
        val endSpine = next?.spineIndex ?: (spineHrefs.size - 1)

        val sb = StringBuilder()
        for (spineIdx in startSpine..endSpine) {
            if (spineIdx !in spineHrefs.indices) continue
            val startAnchor = if (spineIdx == startSpine) chapter.anchorId else null
            val endAnchor = if (next != null && spineIdx == next.spineIndex) next.anchorId else null
            val chunk = chapterFileChunk(spineHrefs[spineIdx], startAnchor, endAnchor)
            chunk.forEach { appendMarkdownBlockElement(it, sb) }
        }
        return sb.toString().trim()
    }

    /** Devuelve los elementos de nivel superior del <body> de un archivo, recortados entre dos anclas. */
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

    /** Ubica a qué hijo directo del <body> pertenece un elemento con id/anchor dado. */
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

    /** Convierte el contenido inline de un elemento (texto, negrita, itálica, saltos) a Markdown. */
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
        val found = entry ?: throw FileNotFoundException("No se encontró '$path' dentro del EPUB")
        return zipFile.getInputStream(found).bufferedReader(Charsets.UTF_8).readText()
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
