package com.textreader.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.textreader.app.databinding.ActivityChapterListBinding
import com.textreader.app.epub.EpubParser
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChapterListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_TYPE = "extra_file_type"
        const val EXTRA_FILE_NAME = "extra_file_name"
    }

    private sealed class LoadResult {
        data class Chapters(val titles: List<String>, val infoMessage: String? = null) : LoadResult()
        object NoExtractableText : LoadResult()
    }

    private lateinit var binding: ActivityChapterListBinding
    private lateinit var adapter: ChapterAdapter
    private lateinit var filePath: String
    private lateinit var fileType: String
    private lateinit var fileName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChapterListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        val type = intent.getStringExtra(EXTRA_FILE_TYPE)

        if (path == null || type == null) {
            Toast.makeText(this, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        filePath = path
        fileType = type
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: type

        title = fileName
        binding.recyclerChapters.layoutManager = LinearLayoutManager(this)
        adapter = ChapterAdapter(emptyList()) { index ->
            val readerIntent = Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_FILE_PATH, filePath)
                putExtra(ReaderActivity.EXTRA_FILE_TYPE, fileType)
                putExtra(ReaderActivity.EXTRA_CHAPTER_INDEX, index)
            }
            startActivity(readerIntent)
        }
        binding.recyclerChapters.adapter = adapter
        binding.progressBar.visibility = View.VISIBLE

        binding.buttonFullBook.setOnClickListener {
            val readerIntent = Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_FILE_PATH, filePath)
                putExtra(ReaderActivity.EXTRA_FILE_TYPE, fileType)
                putExtra(ReaderActivity.EXTRA_FULL_BOOK, true)
                putExtra(ReaderActivity.EXTRA_FILE_NAME, fileName)
            }
            startActivity(readerIntent)
        }

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { loadBook(filePath, fileType) }
                binding.progressBar.visibility = View.GONE
                when (result) {
                    is LoadResult.Chapters -> {
                        adapter.updateItems(result.titles)
                        if (result.titles.isEmpty()) {
                            Toast.makeText(this@ChapterListActivity, getString(R.string.sin_capitulos), Toast.LENGTH_LONG).show()
                        } else if (result.infoMessage != null) {
                            Toast.makeText(this@ChapterListActivity, result.infoMessage, Toast.LENGTH_LONG).show()
                        }
                        // La opción "ver todo el libro corrido" solo tiene sentido para PDF.
                        binding.buttonFullBook.visibility = if (fileType == "pdf" && result.titles.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                    LoadResult.NoExtractableText -> {
                        binding.recyclerChapters.visibility = View.GONE
                        binding.buttonFullBook.visibility = View.GONE
                        binding.textNoText.visibility = View.VISIBLE
                        binding.textNoText.text = getString(R.string.pdf_sin_texto)
                    }
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ChapterListActivity, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadBook(path: String, type: String): LoadResult {
        return if (type == "epub") {
            val parser = EpubParser(File(path))
            val book = parser.parse()
            parser.close()
            LoadResult.Chapters(book.chapters.map { it.title })
        } else {
            val parser = PdfParser(applicationContext, File(path))
            val book = parser.parse()
            parser.close()
            if (!book.hasExtractableText) {
                LoadResult.NoExtractableText
            } else {
                val info = if (!book.chaptersFromBookmarks) getString(R.string.pdf_sin_marcadores) else null
                LoadResult.Chapters(book.chapters.map { it.title }, info)
            }
        }
    }
}
