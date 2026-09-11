package com.textreader.app.data

/** Modo de obtención de capítulos elegido para un PDF (no aplica a EPUB). */
object PdfMode {
    const val BOOKMARKS = "bookmarks"
    const val CUSTOM = "custom"
    const val FULL_BOOK = "fullbook"
}

/** Un rango de páginas definido a mano por el usuario (modo "mis propias partes"). */
data class CustomRange(
    val title: String,
    val startPage: Int, // 0-based, inclusive
    val endPage: Int // 0-based, inclusive
)

/** Un libro (EPUB o PDF) que el usuario abrió, guardado para la pantalla de inicio. */
data class RecentBook(
    val id: String,
    var title: String,
    val fileType: String, // "epub" | "pdf"
    val filePath: String, // copia persistente del archivo, dentro de filesDir
    var coverPath: String? = null,
    var lastIndex: Int = 0, // capítulo/parte donde se quedó
    var totalUnits: Int = 1, // cantidad total de capítulos/partes (para la barra de progreso)
    var pdfMode: String? = null, // solo para PDF
    var customRanges: List<CustomRange> = emptyList(), // solo si pdfMode == CUSTOM
    var addedAt: Long = System.currentTimeMillis(),
    var lastOpenedAt: Long = System.currentTimeMillis()
)

/** Convierte una lista de rangos personalizados a JSON, para pasarla entre pantallas. */
fun customRangesToJson(ranges: List<CustomRange>): String {
    val arr = org.json.JSONArray()
    ranges.forEach { r ->
        val o = org.json.JSONObject()
        o.put("title", r.title)
        o.put("start", r.startPage)
        o.put("end", r.endPage)
        arr.put(o)
    }
    return arr.toString()
}

/** Reconstruye la lista de rangos personalizados desde el JSON guardado/pasado entre pantallas. */
fun customRangesFromJson(json: String?): List<CustomRange> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CustomRange(o.getString("title"), o.getInt("start"), o.getInt("end"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}
