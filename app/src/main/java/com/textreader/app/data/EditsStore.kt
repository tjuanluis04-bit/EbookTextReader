package com.textreader.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ChapterEdit(val text: String, val editedAt: Long = System.currentTimeMillis())

/**
 * Guarda el texto editado a mano de capítulos/partes, sin tocar el archivo original.
 *
 * Cada edición queda asociada al libro, a una "configuración" (scope) y al índice de la parte.
 * La configuración distingue, por ejemplo, "marcadores" de "rangos propios": así, si cambias
 * la forma de abrir un PDF, las ediciones de una configuración no se pegan en partes distintas
 * de otra. Si vuelves a la misma configuración, tus ediciones reaparecen.
 */
class EditsStore(context: Context) {

    private val file = File(context.filesDir, "chapter_edits.json")

    private fun key(bookId: String, scope: String, index: Int) = "$bookId::$scope::$index"
    private fun legacyKey(bookId: String, index: Int) = "$bookId::$index"

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

    /** Pasa las ediciones guardadas con el formato anterior (sin scope) a la configuración actual. */
    private fun migrateLegacy(obj: JSONObject, bookId: String, scope: String): Boolean {
        val prefix = "$bookId::"
        val legacyKeys = mutableListOf<String>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k.startsWith(prefix)) {
                val rest = k.substring(prefix.length)
                if (rest.isNotEmpty() && rest.all { c -> c.isDigit() }) legacyKeys.add(k)
            }
        }
        if (legacyKeys.isEmpty()) return false
        for (k in legacyKeys) {
            val index = k.substring(prefix.length).toInt()
            obj.put(key(bookId, scope, index), obj.getJSONObject(k))
            obj.remove(k)
        }
        return true
    }

    @Synchronized
    fun get(bookId: String, scope: String, chapterIndex: Int): ChapterEdit? {
        val obj = loadRaw()
        if (migrateLegacy(obj, bookId, scope)) saveRaw(obj)
        val k = key(bookId, scope, chapterIndex)
        if (!obj.has(k)) return null
        val entry = obj.getJSONObject(k)
        return ChapterEdit(entry.getString("text"), entry.optLong("editedAt", System.currentTimeMillis()))
    }

    /** Índices de las partes editadas de un libro en la configuración dada (para marcarlas en el índice). */
    @Synchronized
    fun editedIndexesForBook(bookId: String, scope: String): Set<Int> {
        val obj = loadRaw()
        if (migrateLegacy(obj, bookId, scope)) saveRaw(obj)
        val prefix = "$bookId::$scope::"
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
    fun save(bookId: String, scope: String, chapterIndex: Int, text: String) {
        val obj = loadRaw()
        val entry = JSONObject()
        entry.put("text", text)
        entry.put("editedAt", System.currentTimeMillis())
        obj.put(key(bookId, scope, chapterIndex), entry)
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
