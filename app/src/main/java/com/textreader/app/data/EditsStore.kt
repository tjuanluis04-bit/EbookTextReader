package com.textreader.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ChapterEdit(val text: String, val editedAt: Long = System.currentTimeMillis())

/**
 * Guarda el texto editado a mano de capítulos/partes puntuales, sin tocar el
 * archivo original. Queda asociado al libro (por su id interno) y al índice
 * del capítulo, así que si el libro se borra de la app y se vuelve a subir
 * (lo que genera un id nuevo), la edición anterior no se aplica.
 */
class EditsStore(context: Context) {

    private val file = File(context.filesDir, "chapter_edits.json")

    private fun key(bookId: String, chapterIndex: Int) = "$bookId::$chapterIndex"

    @Synchronized
    private fun loadRaw(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized
    private fun saveRaw(obj: JSONObject) {
        file.writeText(obj.toString())
    }

    @Synchronized
    fun get(bookId: String, chapterIndex: Int): ChapterEdit? {
        val obj = loadRaw()
        val k = key(bookId, chapterIndex)
        if (!obj.has(k)) return null
        val entry = obj.getJSONObject(k)
        return ChapterEdit(entry.getString("text"), entry.optLong("editedAt", System.currentTimeMillis()))
    }

    @Synchronized
    fun hasEdit(bookId: String, chapterIndex: Int): Boolean = get(bookId, chapterIndex) != null

    /** Devuelve el conjunto de índices de capítulo editados para un libro (para marcarlos en el índice). */
    @Synchronized
    fun editedIndexesForBook(bookId: String): Set<Int> {
        val obj = loadRaw()
        val prefix = "$bookId::"
        val result = mutableSetOf<Int>()
        val keysIterator = obj.keys()
        while (keysIterator.hasNext()) {
            val k = keysIterator.next()
            if (k.startsWith(prefix)) {
                k.substring(prefix.length).toIntOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    @Synchronized
    fun save(bookId: String, chapterIndex: Int, text: String) {
        val obj = loadRaw()
        val entry = JSONObject()
        entry.put("text", text)
        entry.put("editedAt", System.currentTimeMillis())
        obj.put(key(bookId, chapterIndex), entry)
        saveRaw(obj)
    }

    @Synchronized
    fun removeForBook(bookId: String) {
        val obj = loadRaw()
        val prefix = "$bookId::"
        val toRemove = mutableListOf<String>()
        val keysIterator = obj.keys()
        while (keysIterator.hasNext()) {
            val k = keysIterator.next()
            if (k.startsWith(prefix)) toRemove.add(k)
        }
        toRemove.forEach { obj.remove(it) }
        saveRaw(obj)
    }
}
