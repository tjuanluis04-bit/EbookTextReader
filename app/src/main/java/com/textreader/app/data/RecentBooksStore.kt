package com.textreader.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Guarda la lista de libros recientes (con su progreso, portada y modo de PDF elegido)
 * en un archivo JSON dentro del almacenamiento privado de la app. El orden de la lista
 * es el orden en que se muestran/reordenan en la pantalla de inicio.
 */
class RecentBooksStore(context: Context) {

    private val file = File(context.filesDir, "recent_books.json")

    @Synchronized
    fun loadAll(): MutableList<RecentBook> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<RecentBook>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ranges = mutableListOf<CustomRange>()
                o.optJSONArray("customRanges")?.let { rangesArr ->
                    for (j in 0 until rangesArr.length()) {
                        val r = rangesArr.getJSONObject(j)
                        ranges.add(CustomRange(r.getString("title"), r.getInt("start"), r.getInt("end")))
                    }
                }
                list.add(
                    RecentBook(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        fileType = o.getString("fileType"),
                        filePath = o.getString("filePath"),
                        coverPath = o.optString("coverPath", "").ifEmpty { null },
                        lastIndex = o.optInt("lastIndex", 0),
                        totalUnits = o.optInt("totalUnits", 1),
                        pdfMode = o.optString("pdfMode", "").ifEmpty { null },
                        customRanges = ranges,
                        addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                        lastOpenedAt = o.optLong("lastOpenedAt", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun saveAll(books: List<RecentBook>) {
        val arr = JSONArray()
        for (b in books) {
            val o = JSONObject()
            o.put("id", b.id)
            o.put("title", b.title)
            o.put("fileType", b.fileType)
            o.put("filePath", b.filePath)
            o.put("coverPath", b.coverPath ?: "")
            o.put("lastIndex", b.lastIndex)
            o.put("totalUnits", b.totalUnits)
            o.put("pdfMode", b.pdfMode ?: "")
            val rangesArr = JSONArray()
            for (r in b.customRanges) {
                val ro = JSONObject()
                ro.put("title", r.title)
                ro.put("start", r.startPage)
                ro.put("end", r.endPage)
                rangesArr.put(ro)
            }
            o.put("customRanges", rangesArr)
            o.put("addedAt", b.addedAt)
            o.put("lastOpenedAt", b.lastOpenedAt)
            arr.put(o)
        }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun upsert(book: RecentBook) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == book.id }
        if (idx >= 0) list[idx] = book else list.add(0, book)
        saveAll(list)
    }

    @Synchronized
    fun updateProgress(id: String, lastIndex: Int, totalUnits: Int) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx].lastIndex = lastIndex
            list[idx].totalUnits = totalUnits
            list[idx].lastOpenedAt = System.currentTimeMillis()
            saveAll(list)
        }
    }

    @Synchronized
    fun remove(id: String) {
        val list = loadAll()
        list.removeAll { it.id == id }
        saveAll(list)
    }

    @Synchronized
    fun reorder(newOrderIds: List<String>) {
        val list = loadAll()
        val byId = list.associateBy { it.id }
        val reordered = newOrderIds.mapNotNull { byId[it] }
        saveAll(reordered)
    }
}
