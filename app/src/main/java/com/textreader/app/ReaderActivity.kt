package com.textreader.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.textreader.app.data.CustomRange
import com.textreader.app.data.PdfMode
import com.textreader.app.data.RecentBooksStore
import com.textreader.app.data.customRangesFromJson
import com.textreader.app.databinding.ActivityReaderBinding
import com.textreader.app.epub.EpubParser
import com.textreader.app.pdf.PdfChapter
import com.textreader.app.pdf.PdfParser
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_TYPE = "extra_file_type"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"
        const val EXTRA_FULL_BOOK = "extra_full_book"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_PDF_MODE = "extra_pdf_mode"
        const val EXTRA_CUSTOM_RANGES_JSON = "extra_custom_ranges_json"

        private const val PREFS_NAME = "reader_prefs"
        private const val PREF_ALIGNMENT = "text_alignment"
        private const val PREF_FONT_SCALE = "font_scale"
        private const val ALIGN_LEFT = "LEFT"
        private const val ALIGN_CENTER = "CENTER"
        private const val ALIGN_RIGHT = "RIGHT"
        private const val ALIGN_JUSTIFY = "JUSTIFY"
        private const val BASE_TEXT_SIZE_SP = 16f
        private const val MIN_FONT_SCALE = 0.7f
        private const val MAX_FONT_SCALE = 2.0f
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var markwon: Markwon
    private lateinit var store: RecentBooksStore

    private lateinit var filePath: String
    private lateinit var fileType: String
    private var bookId: String? = null
    private var pdfMode: String? = null
    private var fullBookMode: Boolean = false
    private var fullBookTitle: String = ""

    // Parsers reutilizados entre capítulos (no se vuelven a abrir/parsear cada vez: más velocidad).
    private var epubParser: EpubParser? = null
    private var pdfParser: PdfParser? = null
    private var epubChapterTitles: List<String> = emptyList()
    private var pdfChapters: List<PdfChapter> = emptyList()
    private var pdfPageCount: Int = 0

    private var currentIndex: Int = 0
    private var totalChapters: Int = 1
    private var chapterMarkdown: String = ""
    private var chapterTitle: String = ""
    private var currentImages: List<Bitmap> = emptyList()
    private var loadJob: Job? = null
    private lateinit var imageAdapter: ImageAdapter

    private var fontScale: Float = 1.0f
    private var pendingSaveImage: Pair<Bitmap, String>? = null

    private val requestStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingSaveImage
        pendingSaveImage = null
        if (granted && pending != null) {
            reallySaveImage(pending.first, pending.second)
        } else if (!granted) {
            Toast.makeText(this, getString(R.string.permiso_denegado), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markwon = Markwon.create(this)
        store = RecentBooksStore(this)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        val type = intent.getStringExtra(EXTRA_FILE_TYPE)
        val startIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        pdfMode = intent.getStringExtra(EXTRA_PDF_MODE)
        fullBookMode = intent.getBooleanExtra(EXTRA_FULL_BOOK, false)
        fullBookTitle = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.libro_completo_titulo)

        if (path == null || type == null) {
            finish()
            return
        }
        filePath = path
        fileType = type

        imageAdapter = ImageAdapter(emptyList()) { bitmap, index -> onSaveImageClicked(bitmap, index) }
        binding.recyclerImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerImages.adapter = imageAdapter

        setupAlignmentButtons()
        applyAlignment(loadSavedAlignment())
        fontScale = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(PREF_FONT_SCALE, 1.0f)
        applyFontScale()

        binding.buttonFontSmaller.setOnClickListener { changeFontScale(-0.1f) }
        binding.buttonFontLarger.setOnClickListener { changeFontScale(0.1f) }
        binding.buttonChapterList.setOnClickListener { showChapterListDialog() }

        binding.buttonCopyChapter.setOnClickListener {
            copyToClipboard(chapterTitle, markdownToPlainText(chapterMarkdown))
        }
        binding.buttonPrevChapter.setOnClickListener {
            if (currentIndex > 0) loadChapter(currentIndex - 1)
        }
        binding.buttonNextChapter.setOnClickListener {
            if (currentIndex < totalChapters - 1) loadChapter(currentIndex + 1)
        }

        if (fullBookMode) {
            binding.buttonPrevChapter.visibility = View.GONE
            binding.buttonNextChapter.visibility = View.GONE
        }

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { setupBookSource() }
            if (!ok) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ReaderActivity, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
                return@launch
            }
            loadChapter(startIndex.coerceIn(0, (totalChapters - 1).coerceAtLeast(0)))
        }
    }

    /** Abre el archivo y arma la lista de capítulos UNA sola vez (se reutiliza en toda la sesión de lectura). */
    private fun setupBookSource(): Boolean {
        return try {
            if (fileType == "epub") {
                val parser = EpubParser(File(filePath))
                val book = parser.parse()
                epubParser = parser
                epubChapterTitles = book.chapters.map { it.title }
                totalChapters = epubChapterTitles.size.coerceAtLeast(1)
            } else {
                val parser = PdfParser(applicationContext, File(filePath))
                pdfPageCount = parser.pageCount()
                pdfParser = parser

                pdfChapters = when {
                    fullBookMode -> listOf(PdfChapter(fullBookTitle, 0, (pdfPageCount - 1).coerceAtLeast(0)))
                    pdfMode == PdfMode.CUSTOM -> {
                        val ranges: List<CustomRange> = customRangesFromJson(intent.getStringExtra(EXTRA_CUSTOM_RANGES_JSON))
                        ranges.map { PdfChapter(it.title, it.startPage, it.endPage) }
                    }
                    else -> parser.parse().chapters // marcadores
                }
                totalChapters = pdfChapters.size.coerceAtLeast(1)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Carga un capítulo/parte (por índice) dentro de esta misma pantalla, sin volver a parsear el libro entero. */
    private fun loadChapter(index: Int) {
        loadJob?.cancel()
        setLoadingState()

        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { loadChapterContent(index) }
                currentIndex = index
                chapterTitle = result.first
                chapterMarkdown = result.second
                currentImages = result.third

                title = chapterTitle
                markwon.setMarkdown(binding.textContent, chapterMarkdown)
                applyFontScale()
                binding.scrollContent.post { binding.scrollContent.scrollTo(0, 0) }

                if (currentImages.isNotEmpty()) {
                    imageAdapter.updateItems(currentImages)
                    binding.recyclerImages.visibility = View.VISIBLE
                } else {
                    binding.recyclerImages.visibility = View.GONE
                }

                binding.progressBar.visibility = View.GONE
                updateActionButtonsState()

                bookId?.let { id -> store.updateProgress(id, currentIndex, totalChapters) }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ReaderActivity, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** (título, markdown, imágenes) de un capítulo/parte. Se ejecuta en background. */
    private fun loadChapterContent(index: Int): Triple<String, String, List<Bitmap>> {
        return if (fileType == "epub") {
            val parser = epubParser ?: throw IllegalStateException("EPUB no inicializado")
            val title = epubChapterTitles.getOrNull(index) ?: ""
            val markdown = parser.extractChapterMarkdown(index)
            val images = try {
                parser.imagesInChapter(index).mapNotNull { (_, bytes) ->
                    try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null }
                }
            } catch (e: Exception) {
                emptyList()
            }
            Triple(title, markdown, images)
        } else {
            val parser = pdfParser ?: throw IllegalStateException("PDF no inicializado")
            val chapter = pdfChapters.getOrNull(index) ?: throw IllegalStateException("parte inexistente")
            val markdown = parser.extractMarkdown(chapter.startPage, chapter.endPage)
            // En modo "libro completo" evitamos extraer imágenes de todas las páginas (podría ser muy pesado).
            val images = if (fullBookMode) emptyList() else try {
                parser.extractImages(chapter.startPage, chapter.endPage)
            } catch (e: Exception) {
                emptyList()
            }
            Triple(chapter.title, markdown, images)
        }
    }

    private fun showChapterListDialog() {
        val titles = if (fileType == "epub") epubChapterTitles else pdfChapters.map { it.title }
        if (titles.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.indice_capitulos)
            .setSingleChoiceItems(titles.toTypedArray(), currentIndex) { dialog, which ->
                dialog.dismiss()
                if (which != currentIndex) loadChapter(which)
            }
            .setNegativeButton(R.string.cerrar, null)
            .show()
    }

    private fun setLoadingState() {
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonCopyChapter.isEnabled = false
        binding.buttonPrevChapter.isEnabled = false
        binding.buttonNextChapter.isEnabled = false
    }

    private fun updateActionButtonsState() {
        binding.buttonCopyChapter.isEnabled = true
        binding.buttonPrevChapter.isEnabled = currentIndex > 0
        binding.buttonNextChapter.isEnabled = currentIndex < totalChapters - 1
    }

    private fun markdownToPlainText(markdown: String): String {
        var text = markdown
        text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")
        text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        text = text.replace(Regex("\\*(.+?)\\*"), "$1")
        text = text.replace(Regex("(?m)^>\\s?"), "")
        return text.trim()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.capitulo_copiado), Toast.LENGTH_SHORT).show()
    }

    // ---- Tamaño de letra ----

    private fun changeFontScale(delta: Float) {
        fontScale = (fontScale + delta).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(PREF_FONT_SCALE, fontScale).apply()
        applyFontScale()
    }

    private fun applyFontScale() {
        binding.textContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * fontScale)
    }

    // ---- Alineación de texto ----

    private fun setupAlignmentButtons() {
        binding.buttonAlignLeft.setOnClickListener { saveAndApplyAlignment(ALIGN_LEFT) }
        binding.buttonAlignCenter.setOnClickListener { saveAndApplyAlignment(ALIGN_CENTER) }
        binding.buttonAlignRight.setOnClickListener { saveAndApplyAlignment(ALIGN_RIGHT) }
        binding.buttonAlignJustify.setOnClickListener { saveAndApplyAlignment(ALIGN_JUSTIFY) }
    }

    private fun saveAndApplyAlignment(mode: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_ALIGNMENT, mode).apply()
        applyAlignment(mode)
    }

    private fun loadSavedAlignment(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_ALIGNMENT, ALIGN_JUSTIFY) ?: ALIGN_JUSTIFY
    }

    private fun applyAlignment(mode: String) {
        val textView = binding.textContent
        when (mode) {
            ALIGN_LEFT -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                textView.justificationMode = Layout.JUSTIFICATION_MODE_NONE
            }
            ALIGN_CENTER -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_CENTER
                textView.justificationMode = Layout.JUSTIFICATION_MODE_NONE
            }
            ALIGN_RIGHT -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                textView.justificationMode = Layout.JUSTIFICATION_MODE_NONE
            }
            ALIGN_JUSTIFY -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                textView.justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
                textView.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            }
        }
        highlightSelectedButton(mode)
    }

    private fun highlightSelectedButton(mode: String) {
        val buttons: Map<String, Button> = mapOf(
            ALIGN_LEFT to binding.buttonAlignLeft,
            ALIGN_CENTER to binding.buttonAlignCenter,
            ALIGN_RIGHT to binding.buttonAlignRight,
            ALIGN_JUSTIFY to binding.buttonAlignJustify
        )
        buttons.forEach { (key, button) ->
            val selected = key == mode
            button.alpha = if (selected) 1f else 0.45f
            button.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // ---- Guardar imágenes ----

    private fun onSaveImageClicked(bitmap: Bitmap, index: Int) {
        val safeName = chapterTitle.replace(Regex("[^A-Za-z0-9]+"), "_").take(30).ifBlank { "capitulo" }
        val displayName = "${safeName}_imagen_${index + 1}.jpg"
        saveImageToGallery(bitmap, displayName)
    }

    private fun saveImageToGallery(bitmap: Bitmap, displayName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveImage = bitmap to displayName
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        reallySaveImage(bitmap, displayName)
    }

    private fun reallySaveImage(bitmap: Bitmap, displayName: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LectorDeTexto")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                Toast.makeText(this, getString(R.string.imagen_guardada), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.error_guardar_imagen), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_guardar_imagen), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        epubParser?.close()
        pdfParser?.close()
    }
}
