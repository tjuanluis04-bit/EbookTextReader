package com.textreader.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** Guarda imágenes en la galería del teléfono (Pictures/LectorDeTexto). */
object GallerySaver {

    fun mimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun insert(context: Context, displayName: String, mime: String, writer: (java.io.OutputStream) -> Unit): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LectorDeTexto")
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val out = context.contentResolver.openOutputStream(uri) ?: return false
            out.use { writer(it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Guarda los bytes originales (sin recomprimir). */
    fun saveBytes(context: Context, bytes: ByteArray, displayName: String): Boolean {
        return insert(context, displayName, mimeFromName(displayName)) { it.write(bytes) }
    }

    /** Copia el archivo tal cual, conservando su calidad original. */
    fun saveFile(context: Context, path: String, displayName: String): Boolean {
        return try {
            saveBytes(context, File(path).readBytes(), displayName)
        } catch (e: Exception) {
            false
        }
    }

    /** Guarda un bitmap como JPEG de alta calidad (para páginas renderizadas). */
    fun saveBitmap(context: Context, bitmap: Bitmap, displayName: String, quality: Int = 93): Boolean {
        return insert(context, displayName, "image/jpeg") { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}

object ImageUtils {

    private fun sampleFor(width: Int, height: Int, maxLongSide: Int): Int {
        var sample = 1
        val longSide = maxOf(width, height)
        while (longSide / (sample * 2) >= maxLongSide) sample *= 2
        return sample
    }

    fun decodeFileSampled(path: String, maxLongSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxLongSide) }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    fun decodeBytesSampled(bytes: ByteArray, maxLongSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxLongSide) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    /** Decodifica una imagen ajustada al ancho dado (amplía hasta 2x si es chica), para mostrarla en el texto. */
    fun decodeFileToWidth(path: String, targetWidth: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val scale = (targetWidth.toFloat() / bmp.width).coerceAtMost(2f)
            if (kotlin.math.abs(scale - 1f) < 0.02f) bmp
            else Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } catch (e: Exception) {
            null
        }
    }
}
