package com.textreader.app.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.w3c.dom.Document
import org.w3c.dom.Element as XmlElement
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lee un archivo .epub (que es un .zip con XHTML adentro) y permite:
 * - Obtener la lista de capítulos (con título, tomado del índice del libro).
 * - Extraer el texto completo y corrido de un capítulo, sin cortes de página.
 */
class EpubParser(private val file: File) {

    private val zipFile = ZipFile(file)
    private lateinit var opfDir: String

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

        // Spine: orden real de lectura de los capítulos
        val spineItems = opfDoc.getElementsByTagName("itemref")
        val spineHrefs = ArrayList<String>()
        for (i in 0 until spineItems.length) {
            val itemref = spineItems.item(i) as XmlElement
            val idref = itemref.getAttribute("idref")
            idToHref[idref]?.let { spineHrefs.add(resolvePath(opfDir, it)) }
        }

        // Títulos de capítulos: intenta leerlos del NCX (EPUB2) o del Nav (EPUB3)
        val hrefToTitle = HashMap<String, String>()
        try {
            if (ncxId != null) {
                val ncxHref = resolvePath(opfDir, idToHref[ncxId]!!)
                val ncxDoc = parseXml(readEntryAsString(ncxHref))
                val ncxDir = ncxHref.substringBeforeLast('/', "")
                val navPoints = ncxDoc.getElementsByTagName("navPoint")
                for (i in 0 until navPoints.length) {
                    val navPoint = navPoints.item(i) as XmlElement
                    val labelText = navPoint.getElementsByTagName("text").item(0)?.textContent ?: continue
                    val contentEl = navPoint.getElementsByTagName("content").item(0) as? XmlElement ?: continue
                    val src = contentEl.getAttribute("src").substringBefore('#')
                    if (src.isNotEmpty()) {
                        hrefToTitle[resolvePath(ncxDir, src)] = labelText.trim()
                    }
                }
            } else if (navHref != null) {
                val navPath = resolvePath(opfDir, navHref)
                val navDir = navPath.substringBeforeLast('/', "")
                val soup = Jsoup.parse(readEntryAsString(navPath))
                val navEl = soup.select("nav").firstOrNull()
                navEl?.select("a")?.forEach { a ->
                    val href = a.attr("href").substringBefore('#')
                    if (href.isNotEmpty()) {
                        hrefToTitle[resolvePath(navDir, href)] = a.text().trim()
                    }
                }
            }
        } catch (e: Exception) {
            // Si el índice no se puede leer, seguimos con títulos genéricos.
        }

        val chapters = spineHrefs.mapIndexed { index, href ->
            EpubChapter(
                title = hrefToTitle[href]?.takeIf { it.isNotBlank() } ?: "Capítulo ${index + 1}",
                href = href
            )
        }

        return EpubBook(title = bookTitle, chapters = chapters)
    }

    /** Devuelve el texto completo y corrido de un capítulo, sin cortes de página. */
    fun extractChapterText(href: String): String {
        val doc = Jsoup.parse(readEntryAsString(href))
        val sb = StringBuilder()
        appendReadableText(doc.body(), sb)
        return sb.toString().trim()
    }

    private fun appendReadableText(element: Element, sb: StringBuilder) {
        val blockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "article")
        for (node in element.childNodes()) {
            if (node !is Element) continue
            val tag = node.tagName().lowercase()
            when {
                tag == "script" || tag == "style" -> continue
                tag == "br" -> sb.append("\n")
                tag in blockTags -> {
                    val text = node.text().trim()
                    if (text.isNotEmpty()) sb.append(text).append("\n\n")
                }
                else -> appendReadableText(node, sb)
            }
        }
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
