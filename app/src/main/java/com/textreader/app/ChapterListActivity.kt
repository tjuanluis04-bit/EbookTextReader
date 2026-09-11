package com.textreader.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.textreader.app.data.PdfMode
import com.textreader.app.data.RecentBooksStore
import com.textreader.app.data.customRangesFromJson
import com.textreader.app.databinding.ActivityChapterListBinding
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pantalla para elegir CÓMO obtener las partes de un PDF: por sus marcadores
 * reales, definiendo rangos de páginas a mano, o viendo el libro entero corrido.
 */
class ChapterListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_TYPE = "extra_file_type"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_BOOK_ID = "extra_book_id"
    }

    private lateinit var binding: ActivityChapterListBinding
    private lateinit var store: RecentBooksStore
    private lateinit var filePath: String
    private lateinit var fileName: String
    private lateinit var bookId: String

    private val rangePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(PdfRangePickerActivity.RESULT_RANGES_JSON)
            val ranges = customRangesFromJson(json)
            if (ranges.isNotEmpty()) {
                saveModeAndOpen(PdfMode.CUSTOM, ranges)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChapterListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RecentBooksStore(this)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        val id = intent.getStringExtra(EXTRA_BOOK_ID)
        if (path == null || id == null) {
            Toast.makeText(this, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        filePath = path
        bookId = id
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "PDF"
        title = fileName

        binding.buttonUseBookmarks.setOnClickListener { saveModeAndOpen(PdfMode.BOOKMARKS, emptyList()) }
        binding.buttonUseCustomRanges.setOnClickListener {
            val rangeIntent = Intent(this, PdfRangePickerActivity::class.java)
            rangeIntent.putExtra(PdfRangePickerActivity.EXTRA_FILE_PATH, filePath)
            rangePickerLauncher.launch(rangeIntent)
        }
        binding.buttonFullBook.setOnClickListener { saveModeAndOpen(PdfMode.FULL_BOOK, emptyList()) }

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val (hasText, hasBookmarks) = withContext(Dispatchers.IO) {
                val parser = PdfParser(applicationContext, File(filePath))
                val book = parser.parse()
                parser.close()
                book.hasExtractableText to book.hasBookmarks
            }
            binding.progressBar.visibility = View.GONE
            if (!hasText) {
                binding.groupChooser.visibility = View.GONE
                binding.textNoText.visibility = View.VISIBLE
                binding.textNoText.text = getString(R.string.pdf_sin_texto)
            } else {
                binding.groupChooser.visibility = View.VISIBLE
                binding.buttonUseBookmarks.isEnabled = hasBookmarks
                binding.textNoBookmarks.visibility = if (hasBookmarks) View.GONE else View.VISIBLE
            }
        }
    }

    private fun saveModeAndOpen(mode: String, ranges: List<com.textreader.app.data.CustomRange>) {
        val books = store.loadAll()
        val idx = books.indexOfFirst { it.id == bookId }
        if (idx >= 0) {
            books[idx].pdfMode = mode
            books[idx].customRanges = ranges
            books[idx].lastIndex = 0
            store.saveAll(books)
        }

        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_FILE_PATH, filePath)
            putExtra(ReaderActivity.EXTRA_FILE_TYPE, "pdf")
            putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
            putExtra(ReaderActivity.EXTRA_FILE_NAME, fileName)
            putExtra(ReaderActivity.EXTRA_PDF_MODE, mode)
            putExtra(ReaderActivity.EXTRA_CHAPTER_INDEX, 0)
            putExtra(ReaderActivity.EXTRA_FULL_BOOK, mode == PdfMode.FULL_BOOK)
            if (mode == PdfMode.CUSTOM) {
                putExtra(ReaderActivity.EXTRA_CUSTOM_RANGES_JSON, com.textreader.app.data.customRangesToJson(ranges))
            }
        }
        startActivity(intent)
        finish()
    }
}
