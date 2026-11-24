package com.textreader.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.textreader.app.data.CustomRange
import com.textreader.app.data.EditsStore
import com.textreader.app.data.PdfMode
import com.textreader.app.data.RecentBooksStore
import com.textreader.app.data.customRangesFromJson
import com.textreader.app.data.customRangesToJson
import com.textreader.app.databinding.ActivityReaderBinding
import com.textreader.app.databinding.ItemChapterIndexBinding
import com.textreader.app.epub.EpubParser
import com.textreader.app.pdf.PdfChapter
import com.textreader.app.pdf.PdfParser
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : BaseSaveActivity() {

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
        private const val PREF_IMAGE_MODE = "image_mode"
        private const val IMAGE_INLINE = "inline"
        private const val IMAGE_STRIP = "strip"
        private const val ALIGN_LEFT = "LEFT"
        private const val ALIGN_CENTER = "CENTER"
        private const val ALIGN_RIGHT = "RIGHT"
        private const val ALIGN_JUSTIFY = "JUSTIFY"
        private const val BASE_TEXT_SIZE_SP = 16f
        private const val MIN_FONT_SCALE = 0.7f
        private const val MAX_FONT_SCALE = 2.0f
        private const val UNDO_DEBOUNCE_MS = 700L
        private val IMAGE_REF_REGEX = Regex("!\\[\\]\\(file://([^)]*)\\)")
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var markwon: Markwon
    private lateinit var store: RecentBooksStore
    private lateinit var editsStore: EditsStore
    private lateinit var stripAdapter: StripImageAdapter

    private lateinit var filePath: String
    private lateinit var fileType: String
    private var bookId: String? = null
    private var pdfMode: String? = null
    private var fullBookMode: Boolean = false
    private var fullBookTitle: String = ""
    private var currentCustomRanges: List<CustomRange> = emptyList()
    private var editScope: String = "epub"
    private var imageMode: String = IMAGE_INLINE

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
    private var editStartText: String = ""
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

    private val rangePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val ranges = customRangesFromJson(result.data?.getStringExtra(PdfRangePickerActivity.RESULT_RANGES_JSON))
                if (ranges.isNotEmpty()) applyMode(PdfMode.CUSTOM, ranges)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markwon = Markwon.builder(this)
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
        currentCustomRanges = customRangesFromJson(intent.getStringExtra(EXTRA_CUSTOM_RANGES_JSON))

        if (path == null || type == null) {
            finish()
            return
        }
        filePath = path
        fileType = type
        editScope = computeEditScope()
        imageMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_IMAGE_MODE, IMAGE_INLINE) ?: IMAGE_INLINE

        stripAdapter = StripImageAdapter(
            emptyList(),
            onOpen = { imagePath -> openImageLarge(imagePath) },
            onSave = { imagePath -> ensureStoragePermission { saveSingleImage(imagePath) } }
        )
        binding.recyclerImages.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerImages.adapter = stripAdapter

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
        binding.buttonEditPrev.setOnClickListener { navigateWhileEditing(-1) }
        binding.buttonEditNext.setOnClickListener { navigateWhileEditing(1) }
        binding.buttonSaveChapterImages.setOnClickListener { ensureStoragePermission { saveAllChapterImages() } }

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

    /** Identifica la "configuración" de partes actual, para que las ediciones no se mezclen entre configuraciones. */
    private fun computeEditScope(): String {
        return when {
            fileType == "epub" -> "epub"
            fullBookMode -> "full"
            pdfMode == PdfMode.CUSTOM ->
                "custom-" + currentCustomRanges.joinToString("|") { "${it.startPage}-${it.endPage}" }.hashCode().toString(16)
            else -> "bookmarks"
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
                    pdfMode == PdfMode.CUSTOM ->
                        currentCustomRanges.map { PdfChapter(it.title, it.startPage, it.endPage) }
                    else -> parser.parse().chapters
                }
                totalChapters = pdfChapters.size.coerceAtLeast(1)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- Carga y dibujado de un capítulo ----

    private fun loadChapter(index: Int, thenEdit: Boolean = false) {
        if (isEditing) exitEditMode(reload = false)
        loadJob?.cancel()
        setLoadingState()

        loadJob = lifecycleScope.launch {
            try {
                val targetWidth = imageTargetWidth()
                val result = withContext(Dispatchers.IO) { loadChapterContent(index, targetWidth) }
                currentIndex = index
                chapterTitle = result.title
                chapterMarkdown = result.markdown
                currentImagePaths = result.imagePaths
                isCurrentChapterEdited = result.isEdited

                title = chapterTitle
                updateEditedBadge()

                if (isCurrentChapterEdited) {
                    binding.textContent.text = result.markdown
                } else {
                    renderMarkdown(result)
                }
                applyFontScale()
                binding.scrollContent.post { binding.scrollContent.scrollTo(0, 0) }

                val hasImages = !isCurrentChapterEdited && currentImagePaths.isNotEmpty()
                if (hasImages && imageMode == IMAGE_STRIP) {
                    stripAdapter.update(currentImagePaths)
                    binding.recyclerImages.visibility = View.VISIBLE
                } else {
                    binding.recyclerImages.visibility = View.GONE
                }
                if (hasImages) {
                    binding.buttonSaveChapterImages.visibility = View.VISIBLE
                    binding.buttonSaveChapterImages.text = getString(R.string.guardar_imagenes_capitulo, currentImagePaths.size)
                } else {
                    binding.buttonSaveChapterImages.visibility = View.GONE
                }

                binding.progressBar.visibility = View.GONE
                updateActionButtonsState()
                bookId?.let { id -> store.updateProgress(id, currentIndex, totalChapters) }

                if (thenEdit) enterEditMode()
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
        val isEdited: Boolean,
        val inlineBitmaps: Map<Int, Bitmap>
    )

    private fun loadChapterContent(index: Int, targetWidth: Int): ChapterContent {
        val id = bookId
        if (id != null) {
            val edit = editsStore.get(id, editScope, index)
            if (edit != null) {
                val title = if (fileType == "epub") epubChapterTitles.getOrNull(index) else pdfChapters.getOrNull(index)?.title
                return ChapterContent(title ?: "", edit.text, emptyList(), true, emptyMap())
            }
        }

        val title: String
        val markdown: String
        if (fileType == "epub") {
            val parser = epubParser ?: throw IllegalStateException("EPUB no inicializado")
            title = epubChapterTitles.getOrNull(index) ?: ""
            markdown = parser.extractChapterMarkdown(index, chapterImageDir(index))
        } else {
            val parser = pdfParser ?: throw IllegalStateException("PDF no inicializado")
            val chapter = pdfChapters.getOrNull(index) ?: throw IllegalStateException("parte inexistente")
            title = chapter.title
            val imageDir = if (fullBookMode) null else chapterImageDir(index)
            markdown = parser.extractMarkdown(chapter.startPage, chapter.endPage, imageDir)
        }

        val paths = IMAGE_REF_REGEX.findAll(markdown).map { it.groupValues[1] }.toList()
        val bitmaps = mutableMapOf<Int, Bitmap>()
        if (imageMode == IMAGE_INLINE) {
            paths.forEachIndexed { i, p ->
                ImageUtils.decodeFileToWidth(p, targetWidth)?.let { bitmaps[i] = it }
            }
        }
        return ChapterContent(title, markdown, paths, false, bitmaps)
    }

    /**
     * Dibuja el capítulo: el Markdown lo resuelve Markwon, y las imágenes se colocan nosotros mismos
     * en su lugar exacto (como ImageSpan), sin depender de la carga asíncrona de imágenes de Markwon.
     */
    private fun renderMarkdown(content: ChapterContent) {
        var counter = 0
        val tokenized = IMAGE_REF_REGEX.replace(content.markdown) {
            val token = "\n\nLCIMG${counter}LC\n\n"
            counter++
            token
        }
        val spanned = markwon.toMarkdown(tokenized)
        val builder = SpannableStringBuilder(spanned)

        for (i in 0 until counter) {
            val token = "LCIMG${i}LC"
            val start = builder.indexOf(token)
            if (start < 0) continue
            val end = start + token.length
            val bitmap = content.inlineBitmaps[i]
            if (imageMode == IMAGE_INLINE && bitmap != null) {
                val drawable = BitmapDrawable(resources, bitmap)
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                builder.replace(start, end, "\uFFFC")
                builder.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                builder.delete(start, end)
            }
        }
        markwon.setParsedMarkdown(binding.textContent, builder)
    }

    private fun imageTargetWidth(): Int {
        val w = binding.textContent.width
        if (w > 120) return w
        val dm = resources.displayMetrics
        return dm.widthPixels - (40 * dm.density).toInt()
    }

    /** Carpeta (vaciada antes de cada extracción) donde se guardan las imágenes de un capítulo. */
    private fun chapterImageDir(index: Int): File {
        val dir = File(cacheDir, "inline_images/${bookId ?: "tmp"}/$index")
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        return dir
    }

    // ---- Índice de capítulos ----

    private fun showChapterListDialog() {
        val baseTitles = if (fileType == "epub") epubChapterTitles else pdfChapters.map { it.title }
        if (baseTitles.isEmpty()) return
        val editedSet = bookId?.let { editsStore.editedIndexesForBook(it, editScope) } ?: emptySet()
        val adapter = ChapterIndexAdapter(baseTitles, editedSet, currentIndex)
        AlertDialog.Builder(this)
            .setTitle(R.string.indice_capitulos)
            .setAdapter(adapter) { dialog, which ->
                dialog.dismiss()
                if (which != currentIndex) loadChapter(which)
            }
            .setNegativeButton(R.string.cerrar, null)
            .show()
    }

    private inner class ChapterIndexAdapter(
        private val titles: List<String>,
        private val edited: Set<Int>,
        private val current: Int
    ) : BaseAdapter() {
        override fun getCount() = titles.size
        override fun getItem(position: Int) = titles[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val rowBinding = if (convertView != null) {
                ItemChapterIndexBinding.bind(convertView)
            } else {
                ItemChapterIndexBinding.inflate(LayoutInflater.from(this@ReaderActivity), parent, false)
            }
            rowBinding.textChapterTitle.text = titles[position]
            rowBinding.textChapterTitle.setTypeface(null, if (position == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            rowBinding.imageEditedBadge.visibility = if (position in edited) View.VISIBLE else View.GONE
            return rowBinding.root
        }
    }

    /** Muestra el sello propio de "editado" en la barra superior cuando la parte actual fue editada. */
    private fun updateEditedBadge() {
        val bar = supportActionBar ?: return
        if (isCurrentChapterEdited) {
            bar.setDisplayShowHomeEnabled(true)
            bar.setDisplayUseLogoEnabled(true)
            bar.setLogo(R.drawable.ic_edited_seal)
        } else {
            bar.setDisplayUseLogoEnabled(false)
            bar.setDisplayShowHomeEnabled(false)
        }
    }

    // ---- Menú: imágenes y forma de apertura ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_image_mode)?.title = getString(
            if (imageMode == IMAGE_INLINE) R.string.imagenes_al_final else R.string.imagenes_en_texto
        )
        menu.findItem(R.id.action_change_mode)?.isVisible = fileType == "pdf"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_image_mode -> {
                imageMode = if (imageMode == IMAGE_INLINE) IMAGE_STRIP else IMAGE_INLINE
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_IMAGE_MODE, imageMode).apply()
                invalidateOptionsMenu()
                if (!isEditing) loadChapter(currentIndex)
                true
            }
            R.id.action_change_mode -> {
                showChangeModeDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showChangeModeDialog() {
        val options = arrayOf(
            getString(R.string.modo_marcadores),
            getString(R.string.modo_rangos),
            getString(R.string.modo_completo)
        )
        val currentChoice = when {
            fullBookMode -> 2
            pdfMode == PdfMode.CUSTOM -> 1
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cambiar_forma_apertura)
            .setSingleChoiceItems(options, currentChoice) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> chooseBookmarksMode()
                    1 -> openRangePicker()
                    2 -> applyMode(PdfMode.FULL_BOOK, emptyList())
                }
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun chooseBookmarksMode() {
        val parser = pdfParser ?: return
        lifecycleScope.launch {
            val parts = withContext(Dispatchers.IO) {
                try { parser.parse().chapters.size } catch (e: Exception) { 0 }
            }
            if (parts <= 0) {
                Toast.makeText(this@ReaderActivity, getString(R.string.sin_marcadores_aviso), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ReaderActivity, getString(R.string.marcadores_detectados, parts), Toast.LENGTH_SHORT).show()
                applyMode(PdfMode.BOOKMARKS, emptyList())
            }
        }
    }

    private fun openRangePicker() {
        val intent = Intent(this, PdfRangePickerActivity::class.java).apply {
            putExtra(PdfRangePickerActivity.EXTRA_FILE_PATH, filePath)
            if (pdfMode == PdfMode.CUSTOM && currentCustomRanges.isNotEmpty()) {
                putExtra(PdfRangePickerActivity.EXTRA_EXISTING_RANGES_JSON, customRangesToJson(currentCustomRanges))
            }
        }
        rangePickerLauncher.launch(intent)
    }

    /** Guarda la nueva forma de apertura y vuelve a abrir el libro con ella. */
    private fun applyMode(mode: String, ranges: List<CustomRange>) {
        bookId?.let { id ->
            store.update(id) { book ->
                book.pdfMode = mode
                book.customRanges = ranges
                book.lastIndex = 0
            }
        }
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_FILE_TYPE, "pdf")
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_FILE_NAME, fullBookTitle)
            putExtra(EXTRA_PDF_MODE, mode)
            putExtra(EXTRA_CHAPTER_INDEX, 0)
            putExtra(EXTRA_FULL_BOOK, mode == PdfMode.FULL_BOOK)
            if (mode == PdfMode.CUSTOM) putExtra(EXTRA_CUSTOM_RANGES_JSON, customRangesToJson(ranges))
        }
        startActivity(intent)
        finish()
    }

    // ---- Estado de botones y copiado ----

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
        text = text.replace(IMAGE_REF_REGEX, "")
        text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")
        text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        text = text.replace(Regex("\\*(.+?)\\*"), "$1")
        text = text.replace(Regex("(?m)^>\\s?"), "")
        text = text.replace(Regex("\n{3,}"), "\n\n")
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
        editStartText = startingText
        binding.editContent.removeTextChangedListener(editTextWatcher)
        binding.editContent.setText(startingText)
        binding.editContent.setSelection(0)
        binding.editContent.addTextChangedListener(editTextWatcher)

        binding.scrollContent.visibility = View.GONE
        binding.scrollEdit.visibility = View.VISIBLE
        binding.scrollEdit.post { binding.scrollEdit.scrollTo(0, 0) }
        binding.buttonSaveChapterImages.visibility = View.GONE
        binding.recyclerImages.visibility = View.GONE
        binding.rowActions.visibility = View.GONE
        binding.rowEditActions.visibility = View.VISIBLE
        binding.buttonEditPrev.isEnabled = currentIndex > 0
        binding.buttonEditNext.isEnabled = currentIndex < totalChapters - 1
    }

    private fun leaveEditUi() {
        isEditing = false
        binding.scrollEdit.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.rowEditActions.visibility = View.GONE
        binding.rowActions.visibility = View.VISIBLE
    }

    private fun exitEditMode(reload: Boolean) {
        if (!isEditing) return
        leaveEditUi()
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

    /** Guarda lo escrito solo si cambió respecto al texto con el que se abrió la edición. */
    private fun persistEditIfChanged() {
        val id = bookId ?: return
        val text = binding.editContent.text.toString()
        if (text != editStartText) {
            editsStore.save(id, editScope, currentIndex, text)
        }
    }

    private fun saveEdit() {
        val id = bookId
        if (id == null) {
            Toast.makeText(this, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            return
        }
        editsStore.save(id, editScope, currentIndex, binding.editContent.text.toString())
        leaveEditUi()
        loadChapter(currentIndex)
    }

    /** Pasa a la parte anterior/siguiente sin salir de la edición (guarda automáticamente si hubo cambios). */
    private fun navigateWhileEditing(delta: Int) {
        val target = currentIndex + delta
        if (target < 0 || target >= totalChapters) return
        persistEditIfChanged()
        loadChapter(target, thenEdit = true)
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

    // ---- Imágenes: ver en grande y guardar (calidad original) ----

    private fun openImageLarge(imagePath: String) {
        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra(ImageViewerActivity.EXTRA_TITLE, chapterTitle)
            putExtra(ImageViewerActivity.EXTRA_IMAGE_PATH, imagePath)
        }
        startActivity(intent)
    }

    private fun imageDisplayName(index: Int, path: String): String {
        val safeName = chapterTitle.replace(Regex("[^A-Za-z0-9]+"), "_").take(30).ifBlank { "capitulo" }
        val ext = path.substringAfterLast('.', "jpg").lowercase().filter { it.isLetterOrDigit() }.ifBlank { "jpg" }
        return "${safeName}_imagen_${index + 1}.$ext"
    }

    private fun saveSingleImage(path: String) {
        val index = currentImagePaths.indexOf(path).coerceAtLeast(0)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { GallerySaver.saveFile(this@ReaderActivity, path, imageDisplayName(index, path)) }
            val msg = if (ok) R.string.imagen_guardada else R.string.error_guardar_imagen
            Toast.makeText(this@ReaderActivity, getString(msg), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAllChapterImages() {
        val paths = currentImagePaths
        if (paths.isEmpty()) return
        lifecycleScope.launch {
            var saved = 0
            withContext(Dispatchers.IO) {
                paths.forEachIndexed { i, path ->
                    if (GallerySaver.saveFile(this@ReaderActivity, path, imageDisplayName(i, path))) saved++
                }
            }
            Toast.makeText(
                this@ReaderActivity,
                getString(R.string.imagenes_guardadas, saved, paths.size),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        undoHandler.removeCallbacks(snapshotRunnable)
        epubParser?.close()
        pdfParser?.close()
    }
}
