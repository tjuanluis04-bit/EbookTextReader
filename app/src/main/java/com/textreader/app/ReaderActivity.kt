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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.textreader.app.data.CustomRange
import com.textreader.app.data.EditsStore
import com.textreader.app.data.PdfMode
import com.textreader.app.data.RecentBooksStore
import com.textreader.app.data.customRangesFromJson
import com.textreader.app.databinding.ActivityReaderBinding
import com.textreader.app.epub.EpubParser
import com.textreader.app.pdf.PdfChapter
import com.textreader.app.pdf.PdfParser
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
        private const val UNDO_DEBOUNCE_MS = 700L
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var markwon: Markwon
    private lateinit var store: RecentBooksStore
    private lateinit var editsStore: EditsStore

    private lateinit var filePath: String
    private lateinit var fileType: String
    private var bookId: String? = null
    private var pdfMode: String? = null
    private var fullBookMode: Boolean = false
    private var fullBookTitle: String = ""

    private var epubParser: EpubParser? = null
    private var pdfParser: PdfParser? = null
    private var epubChapterTitles: List<String> = emptyList()
    private var pdfChapters: List<PdfChapter> = emptyList()
    private var pdfPageCount: Int = 0

    private var currentIndex: Int = 0
    private var totalChapters: Int = 1
    private var chapterMarkdown: String = ""
    private var chapterTitle: String = ""
    private var currentImagePaths: List<String> = emptyList()
    private var isCurrentChapterEdited: Boolean = false
    private var loadJob: Job? = null

    private var isEditing: Boolean = false
    private val undoStack = mutableListOf<String>()
    private var pendingUndoSnapshot: String? = null
    private val undoHandler = Handler(Looper.getMainLooper())
    private val snapshotRunnable = Runnable {
        pendingUndoSnapshot?.let { undoStack.add(it) }
        pendingUndoSnapshot = null
    }
    private val editTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (pendingUndoSnapshot == null) pendingUndoSnapshot = s?.toString()
            undoHandler.removeCallbacks(snapshotRunnable)
        }
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            undoHandler.postDelayed(snapshotRunnable, UNDO_DEBOUNCE_MS)
        }
    }

    private var fontScale: Float = 1.0f
    private var pendingBulkImageSave: Boolean = false

    private val requestStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val wantsBulkSave = pendingBulkImageSave
        pendingBulkImageSave = false
        if (granted) {
            if (wantsBulkSave) performBulkImageSave()
        } else {
            Toast.makeText(this, getString(R.string.permiso_denegado), Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLegacyStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markwon = Markwon.builder(this)
            .usePlugin(ImagesPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()
        store = RecentBooksStore(this)
        editsStore = EditsStore(this)

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

        setupAlignmentButtons()
        applyAlignment(loadSavedAlignment())
        fontScale = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat(PREF_FONT_SCALE, 1.0f)
        applyFontScale()

        binding.buttonFontSmaller.setOnClickListener { changeFontScale(-0.1f) }
        binding.buttonFontLarger.setOnClickListener { changeFontScale(0.1f) }
        binding.buttonChapterList.setOnClickListener { showChapterListDialog() }
        binding.buttonEditChapter.setOnClickListener { enterEditMode() }
        binding.buttonUndo.setOnClickListener { undoEdit() }
        binding.buttonSaveEdit.setOnClickListener { saveEdit() }
        binding.buttonCancelEdit.setOnClickListener { exitEditMode(reload = true) }
        binding.buttonSaveChapterImages.setOnClickListener { saveAllChapterImages() }

        binding.buttonCopyChapter.setOnClickListener {
            val textToCopy = if (isCurrentChapterEdited) chapterMarkdown else markdownToPlainText(chapterMarkdown)
            copyToClipboard(chapterTitle, textToCopy)
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
        // El texto seleccionable rompe el renderizado de imágenes incrustadas de Markwon,
        // así que solo lo habilitamos en "libro completo" (ahí nunca hay imágenes, por diseño).
        binding.textContent.setTextIsSelectable(fullBookMode)

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
                    else -> parser.parse().chapters
                }
                totalChapters = pdfChapters.size.coerceAtLeast(1)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadChapter(index: Int) {
        if (isEditing) exitEditMode(reload = false)
        loadJob?.cancel()
        setLoadingState()

        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { loadChapterContent(index) }
                currentIndex = index
                chapterTitle = result.title
                chapterMarkdown = result.markdown
                currentImagePaths = result.imagePaths
                isCurrentChapterEdited = result.isEdited

                title = (if (isCurrentChapterEdited) "✎ " else "") + chapterTitle

                if (isCurrentChapterEdited) {
                    binding.textContent.text = result.markdown
                } else {
                    markwon.setMarkdown(binding.textContent, chapterMarkdown)
                }
                applyFontScale()
                binding.scrollContent.post { binding.scrollContent.scrollTo(0, 0) }

                if (!isCurrentChapterEdited && currentImagePaths.isNotEmpty()) {
                    binding.buttonSaveChapterImages.visibility = View.VISIBLE
                    binding.buttonSaveChapterImages.text = getString(R.string.guardar_imagenes_capitulo, currentImagePaths.size)
                } else {
                    binding.buttonSaveChapterImages.visibility = View.GONE
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

    private data class ChapterContent(
        val title: String,
        val markdown: String,
        val imagePaths: List<String>,
        val isEdited: Boolean
    )

    private fun loadChapterContent(index: Int): ChapterContent {
        val id = bookId
        if (id != null) {
            val edit = editsStore.get(id, index)
            if (edit != null) {
                val title = if (fileType == "epub") epubChapterTitles.getOrNull(index) else pdfChapters.getOrNull(index)?.title
                return ChapterContent(title ?: "", edit.text, emptyList(), isEdited = true)
            }
        }

        return if (fileType == "epub") {
            val parser = epubParser ?: throw IllegalStateException("EPUB no inicializado")
            val title = epubChapterTitles.getOrNull(index) ?: ""
            val imageDir = chapterImageDir(index)
            val markdown = parser.extractChapterMarkdown(index, imageDir)
            val images = imageDir.listFiles()?.map { it.absolutePath } ?: emptyList()
            ChapterContent(title, markdown, images, isEdited = false)
        } else {
            val parser = pdfParser ?: throw IllegalStateException("PDF no inicializado")
            val chapter = pdfChapters.getOrNull(index) ?: throw IllegalStateException("parte inexistente")
            val imageDir = if (fullBookMode) null else chapterImageDir(index)
            val markdown = parser.extractMarkdown(chapter.startPage, chapter.endPage, imageDir)
            val images = imageDir?.listFiles()?.map { it.absolutePath } ?: emptyList()
            ChapterContent(chapter.title, markdown, images, isEdited = false)
        }
    }

    /** Carpeta fija (vaciada antes de cada extracción) donde se guardan las imágenes incrustadas de un capítulo. */
    private fun chapterImageDir(index: Int): File {
        val dir = File(cacheDir, "inline_images/${bookId ?: "tmp"}/$index")
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        return dir
    }

    private fun showChapterListDialog() {
        val baseTitles = if (fileType == "epub") epubChapterTitles else pdfChapters.map { it.title }
        if (baseTitles.isEmpty()) return
        val editedSet = bookId?.let { editsStore.editedIndexesForBook(it) } ?: emptySet()
        val displayTitles = baseTitles.mapIndexed { i, t -> if (i in editedSet) "✎ $t" else t }
        AlertDialog.Builder(this)
            .setTitle(R.string.indice_capitulos)
            .setSingleChoiceItems(displayTitles.toTypedArray(), currentIndex) { dialog, which ->
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
        text = text.replace(Regex("!\\[\\]\\(file://[^)]*\\)"), "")
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

    // ---- Edición manual del capítulo ----

    private fun enterEditMode() {
        if (isEditing) return
        isEditing = true
        undoStack.clear()
        pendingUndoSnapshot = null

        val startingText = if (isCurrentChapterEdited) chapterMarkdown else markdownToPlainText(chapterMarkdown)
        binding.editContent.removeTextChangedListener(editTextWatcher)
        binding.editContent.setText(startingText)
        binding.editContent.setSelection(startingText.length)
        binding.editContent.addTextChangedListener(editTextWatcher)

        binding.scrollContent.visibility = View.GONE
        binding.editContent.visibility = View.VISIBLE
        binding.buttonSaveChapterImages.visibility = View.GONE
        binding.rowActions.visibility = View.GONE
        binding.rowEditActions.visibility = View.VISIBLE
    }

    private fun exitEditMode(reload: Boolean) {
        if (!isEditing) return
        isEditing = false
        binding.editContent.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.rowEditActions.visibility = View.GONE
        binding.rowActions.visibility = View.VISIBLE
        if (reload) loadChapter(currentIndex)
    }

    private fun undoEdit() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, getString(R.string.nada_para_deshacer), Toast.LENGTH_SHORT).show()
            return
        }
        val previous = undoStack.removeAt(undoStack.size - 1)
        binding.editContent.removeTextChangedListener(editTextWatcher)
        binding.editContent.setText(previous)
        binding.editContent.setSelection(previous.length)
        binding.editContent.addTextChangedListener(editTextWatcher)
    }

    private fun saveEdit() {
        val id = bookId
        if (id == null) {
            Toast.makeText(this, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            return
        }
        val text = binding.editContent.text.toString()
        editsStore.save(id, currentIndex, text)
        isEditing = false
        binding.editContent.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.rowEditActions.visibility = View.GONE
        binding.rowActions.visibility = View.VISIBLE
        loadChapter(currentIndex)
    }

    // ---- Tamaño de letra ----

    private fun changeFontScale(delta: Float) {
        fontScale = (fontScale + delta).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(PREF_FONT_SCALE, fontScale).apply()
        applyFontScale()
    }

    private fun applyFontScale() {
        binding.textContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * fontScale)
        binding.editContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE_SP * fontScale)
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

    private fun saveAllChapterImages() {
        if (currentImagePaths.isEmpty()) return
        if (!hasLegacyStoragePermission()) {
            pendingBulkImageSave = true
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        performBulkImageSave()
    }

    private fun performBulkImageSave() {
        val paths = currentImagePaths
        if (paths.isEmpty()) return
        lifecycleScope.launch {
            var saved = 0
            withContext(Dispatchers.IO) {
                paths.forEachIndexed { i, path ->
                    try {
                        val bmp = BitmapFactory.decodeFile(path)
                        if (bmp != null) {
                            val safeName = chapterTitle.replace(Regex("[^A-Za-z0-9]+"), "_").take(30).ifBlank { "capitulo" }
                            insertImageIntoGallery(bmp, "${safeName}_imagen_${i + 1}.jpg")
                            saved++
                        }
                    } catch (e: Exception) {
                        // una imagen puntual falló: seguimos con el resto
                    }
                }
            }
            Toast.makeText(
                this@ReaderActivity,
                getString(R.string.imagenes_guardadas, saved, paths.size),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun insertImageIntoGallery(bitmap: Bitmap, displayName: String) {
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        undoHandler.removeCallbacks(snapshotRunnable)
        epubParser?.close()
        pdfParser?.close()
    }
}
