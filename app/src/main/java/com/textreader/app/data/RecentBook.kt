package com.textreader.app.data

object PdfMode {
    const val BOOKMARKS = "bookmarks"
    const val CUSTOM = "custom"
    const val FULL_BOOK = "fullbook"
}

data class CustomRange(
    val title: String,
    val startPage: Int,
    val endPage: Int
)

data class RecentBook(
    val id: String,
    var title: String,
    val fileType: String, // "epub" | "pdf"
    val filePath: String,
    var coverPath: String? = null,
    var lastIndex: Int = 0,
    var totalUnits: Int = 1,
    var pdfMode: String? = null,
    var customRanges: List<CustomRange> = emptyList(),
    var addedAt: Long = System.currentTimeMillis(),
    var lastOpenedAt: Long = System.currentTimeMillis()
)

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
