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

    private lateinit var binding: ActivityChapterListBinding
    private lateinit var adapter: ChapterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChapterListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val fileType = intent.getStringExtra(EXTRA_FILE_TYPE)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: fileType.orEmpty()

        if (filePath == null || fileType == null) {
            Toast.makeText(this, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            finish()
            return
        }

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

        lifecycleScope.launch {
            try {
                val titles = withContext(Dispatchers.IO) { loadChapterTitles(filePath, fileType) }
                binding.progressBar.visibility = View.GONE
                adapter.updateItems(titles)
                if (titles.isEmpty()) {
                    Toast.makeText(this@ChapterListActivity, getString(R.string.sin_capitulos), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ChapterListActivity, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadChapterTitles(path: String, type: String): List<String> {
        return if (type == "epub") {
            val parser = EpubParser(File(path))
            val book = parser.parse()
            parser.close()
            book.chapters.map { it.title }
        } else {
            val parser = PdfParser(applicationContext, File(path))
            val book = parser.parse()
            parser.close()
            book.chapters.map { it.title }
        }
    }
}
