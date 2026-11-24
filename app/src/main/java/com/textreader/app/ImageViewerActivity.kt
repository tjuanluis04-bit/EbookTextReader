package com.textreader.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.textreader.app.databinding.ActivityImageViewerBinding
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Muestra una imagen en grande con zoom. Puede venir de un archivo, de una página de un PDF
 * (se renderiza en alta calidad) o de una imagen dentro de un EPUB. Permite guardarla.
 */
class ImageViewerActivity : BaseSaveActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IMAGE_PATH = "extra_image_path"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_PAGE_INDEX = "extra_page_index"
        const val EXTRA_EPUB_PATH = "extra_epub_path"
        const val EXTRA_EPUB_ENTRY = "extra_epub_entry"
        private const val MAX_DECODE_SIDE = 4096
    }

    private lateinit var binding: ActivityImageViewerBinding
    private var saver: (() -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadImage() }
            binding.progressBar.visibility = View.GONE
            if (bitmap == null) {
                Toast.makeText(this@ImageViewerActivity, getString(R.string.error_cargar_imagen), Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            binding.zoomImage.setImageBitmap(bitmap)
            binding.buttonSave.visibility = if (saver != null) View.VISIBLE else View.GONE
        }

        binding.buttonSave.setOnClickListener {
            ensureStoragePermission {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { saver?.invoke() == true }
                    val msg = if (ok) R.string.imagen_guardada else R.string.error_guardar_imagen
                    Toast.makeText(this@ImageViewerActivity, getString(msg), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadImage(): Bitmap? {
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val pdfPath = intent.getStringExtra(EXTRA_PDF_PATH)
        val pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, -1)
        val epubPath = intent.getStringExtra(EXTRA_EPUB_PATH)
        val epubEntry = intent.getStringExtra(EXTRA_EPUB_ENTRY)

        return when {
            imagePath != null -> {
                val name = File(imagePath).name
                saver = { GallerySaver.saveFile(this, imagePath, name) }
                ImageUtils.decodeFileSampled(imagePath, MAX_DECODE_SIDE)
            }
            pdfPath != null && pageIndex >= 0 -> {
                val parser = PdfParser(applicationContext, File(pdfPath))
                val bmp = try { parser.renderPageHighQuality(pageIndex) } finally { parser.close() }
                if (bmp != null) {
                    saver = { GallerySaver.saveBitmap(this, bmp, "pagina_${pageIndex + 1}.jpg") }
                }
                bmp
            }
            epubPath != null && epubEntry != null -> {
                val bytes = readZipEntry(File(epubPath), epubEntry) ?: return null
                val name = epubEntry.substringAfterLast('/')
                saver = { GallerySaver.saveBytes(this, bytes, name) }
                ImageUtils.decodeBytesSampled(bytes, MAX_DECODE_SIDE)
            }
            else -> null
        }
    }

    private fun readZipEntry(file: File, name: String): ByteArray? {
        return try {
            ZipFile(file).use { zip ->
                var entry = zip.getEntry(name)
                if (entry == null) {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.name.equals(name, ignoreCase = true)) {
                            entry = e
                            break
                        }
                    }
                }
                entry?.let { zip.getInputStream(it).readBytes() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
