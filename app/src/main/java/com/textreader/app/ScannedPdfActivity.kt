package com.textreader.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.textreader.app.databinding.ActivityScannedPdfBinding
import com.textreader.app.epub.EpubParser
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Para libros sin texto real (escaneados, PDF o EPUB): como no se puede copiar texto, se muestra
 * cada página/imagen para verla en grande y guardarla (una por una o todas de una vez), en
 * buena calidad.
 */
class ScannedPdfActivity : BaseSaveActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_FILE_TYPE = "extra_file_type"
        private const val PDF_THUMB_SCALE = 0.4f
        private const val EPUB_THUMB_SIDE = 360
    }

    private lateinit var binding: ActivityScannedPdfBinding
    private lateinit var filePath: String
    private lateinit var fileName: String
    private var isEpub = false

    private var pdfParser: PdfParser? = null
    private var epubParser: EpubParser? = null
    private var epubImages: List<String> = emptyList()
    private var itemCount = 0

    // Un solo hilo para todo lo que toca el PDF/EPUB (los lectores no son thread-safe).
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val scanDispatcher = scanExecutor.asCoroutineDispatcher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannedPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        if (path == null) {
            finish()
            return
        }
        filePath = path
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.app_name)
        isEpub = intent.getStringExtra(EXTRA_FILE_TYPE) == "epub"
        title = fileName

        binding.recyclerPages.layoutManager = GridLayoutManager(this, 3)
        binding.buttonSaveAllPages.setOnClickListener { ensureStoragePermission { saveAll() } }

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = withContext(scanDispatcher) { openSource() }
            binding.progressBar.visibility = View.GONE
            if (!ok || itemCount == 0) {
                Toast.makeText(this@ScannedPdfActivity, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
                return@launch
            }
            binding.recyclerPages.adapter = PageGridAdapter(
                scope = lifecycleScope,
                dispatcher = scanDispatcher,
                count = itemCount,
                loadThumb = { index -> loadThumbnail(index) },
                onOpen = { index -> openLarge(index) },
                onSave = { index -> ensureStoragePermission { saveOne(index) } }
            )
            binding.textPageCount.text = getString(
                if (isEpub) R.string.imagenes_detectadas else R.string.paginas_detectadas,
                itemCount
            )
            binding.buttonSaveAllPages.visibility = View.VISIBLE
        }
    }

    private fun openSource(): Boolean {
        return try {
            if (isEpub) {
                val parser = EpubParser(File(filePath))
                parser.parse()
                epubParser = parser
                epubImages = parser.listBookImages()
                itemCount = epubImages.size
            } else {
                val parser = PdfParser(applicationContext, File(filePath))
                pdfParser = parser
                itemCount = parser.pageCount()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Se ejecuta en el hilo de escaneo. */
    private fun loadThumbnail(index: Int) = try {
        if (isEpub) {
            epubParser?.readImageBytes(epubImages[index])?.let { ImageUtils.decodeBytesSampled(it, EPUB_THUMB_SIDE) }
        } else {
            pdfParser?.renderPageBitmap(index, PDF_THUMB_SCALE)
        }
    } catch (e: Exception) {
        null
    }

    private fun openLarge(index: Int) {
        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra(ImageViewerActivity.EXTRA_TITLE, "$fileName · ${index + 1}/$itemCount")
            if (isEpub) {
                putExtra(ImageViewerActivity.EXTRA_EPUB_PATH, filePath)
                putExtra(ImageViewerActivity.EXTRA_EPUB_ENTRY, epubImages[index])
            } else {
                putExtra(ImageViewerActivity.EXTRA_PDF_PATH, filePath)
                putExtra(ImageViewerActivity.EXTRA_PAGE_INDEX, index)
            }
        }
        startActivity(intent)
    }

    /** Guarda un elemento en calidad completa. Se ejecuta en el hilo de escaneo. */
    private fun saveItemFullQuality(index: Int): Boolean {
        return try {
            if (isEpub) {
                val entry = epubImages[index]
                val bytes = epubParser?.readImageBytes(entry) ?: return false
                val ext = entry.substringAfterLast('.', "jpg").lowercase().filter { it.isLetterOrDigit() }.ifBlank { "jpg" }
                GallerySaver.saveBytes(this, bytes, "imagen_${index + 1}.$ext")
            } else {
                val bmp = pdfParser?.renderPageHighQuality(index) ?: return false
                val ok = GallerySaver.saveBitmap(this, bmp, "pagina_${index + 1}.jpg")
                bmp.recycle()
                ok
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun saveOne(index: Int) {
        lifecycleScope.launch {
            val ok = withContext(scanDispatcher) { saveItemFullQuality(index) }
            val msg = if (ok) R.string.imagen_guardada else R.string.error_guardar_imagen
            Toast.makeText(this@ScannedPdfActivity, getString(msg), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAll() {
        binding.buttonSaveAllPages.isEnabled = false
        lifecycleScope.launch {
            var saved = 0
            for (i in 0 until itemCount) {
                binding.textPageCount.text = getString(R.string.guardando_progreso, i + 1, itemCount)
                val ok = withContext(scanDispatcher) { saveItemFullQuality(i) }
                if (ok) saved++
            }
            binding.textPageCount.text = getString(
                if (isEpub) R.string.imagenes_detectadas else R.string.paginas_detectadas,
                itemCount
            )
            binding.buttonSaveAllPages.isEnabled = true
            Toast.makeText(
                this@ScannedPdfActivity,
                getString(R.string.imagenes_guardadas, saved, itemCount),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val pdf = pdfParser
        val epub = epubParser
        scanExecutor.execute {
            try { pdf?.close() } catch (e: Exception) { }
            try { epub?.close() } catch (e: Exception) { }
        }
        scanExecutor.shutdown()
    }
}
