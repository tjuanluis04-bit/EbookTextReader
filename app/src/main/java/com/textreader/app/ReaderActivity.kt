package com.textreader.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Layout
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.textreader.app.databinding.ActivityReaderBinding
import com.textreader.app.epub.EpubParser
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

        private const val PREFS_NAME = "reader_prefs"
        private const val PREF_ALIGNMENT = "text_alignment"
        private const val ALIGN_LEFT = "LEFT"
        private const val ALIGN_CENTER = "CENTER"
        private const val ALIGN_RIGHT = "RIGHT"
        private const val ALIGN_JUSTIFY = "JUSTIFY"
    }

    private data class ChapterLoadResult(val title: String, val markdown: String, val totalChapters: Int)

    private lateinit var binding: ActivityReaderBinding
    private lateinit var markwon: Markwon
    private lateinit var filePath: String
    private lateinit var fileType: String

    private var currentIndex: Int = 0
    private var totalChapters: Int = 0
    private var chapterMarkdown: String = ""
    private var chapterTitle: String = ""
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markwon = Markwon.create(this)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        val type = intent.getStringExtra(EXTRA_FILE_TYPE)
        val startIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)

        if (path == null || type == null) {
            finish()
            return
        }
        filePath = path
        fileType = type

        setupAlignmentButtons()
        applyAlignment(loadSavedAlignment())

        binding.buttonCopyChapter.setOnClickListener {
            copyToClipboard(chapterTitle, markdownToPlainText(chapterMarkdown))
        }
        binding.buttonPrevChapter.setOnClickListener {
            if (currentIndex > 0) loadChapter(currentIndex - 1)
        }
        binding.buttonNextChapter.setOnClickListener {
            if (currentIndex < totalChapters - 1) loadChapter(currentIndex + 1)
        }

        loadChapter(startIndex)
    }

    /** Carga un capítulo (por índice) dentro de esta misma pantalla, sin abrir otra Activity. */
    private fun loadChapter(index: Int) {
        loadJob?.cancel()
        setLoadingState()

        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { loadChapterData(filePath, fileType, index) }
                currentIndex = index
                totalChapters = result.totalChapters
                chapterTitle = result.title
                chapterMarkdown = result.markdown

                title = chapterTitle
                markwon.setMarkdown(binding.textContent, chapterMarkdown)
                binding.scrollContent.post { binding.scrollContent.scrollTo(0, 0) }
                binding.progressBar.visibility = View.GONE
                updateActionButtonsState()
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ReaderActivity, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadChapterData(path: String, type: String, index: Int): ChapterLoadResult {
        return if (type == "epub") {
            val parser = EpubParser(File(path))
            val book = parser.parse()
            val chapter = book.chapters[index]
            val markdown = parser.extractChapterMarkdown(index)
            parser.close()
            ChapterLoadResult(chapter.title, markdown, book.chapters.size)
        } else {
            val parser = PdfParser(applicationContext, File(path))
            val book = parser.parse()
            val chapter = book.chapters[index]
            val markdown = parser.extractMarkdown(chapter.startPage, chapter.endPage)
            parser.close()
            ChapterLoadResult(chapter.title, markdown, book.chapters.size)
        }
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

    /** Quita los símbolos de Markdown (#, **, >) para copiar texto limpio al portapapeles. */
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
}
