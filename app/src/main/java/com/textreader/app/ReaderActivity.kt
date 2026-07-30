package com.textreader.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.textreader.app.databinding.ActivityReaderBinding
import com.textreader.app.epub.EpubParser
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_TYPE = "extra_file_type"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"
    }

    private lateinit var binding: ActivityReaderBinding
    private var chapterText: String = ""
    private var chapterTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val fileType = intent.getStringExtra(EXTRA_FILE_TYPE)
        val chapterIndex = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)

        if (filePath == null || fileType == null) {
            finish()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.buttonCopyChapter.isEnabled = false
        binding.buttonCopyChapter.setOnClickListener { copyToClipboard(chapterTitle, chapterText) }

        lifecycleScope.launch {
            try {
                val (loadedTitle, loadedText) = withContext(Dispatchers.IO) {
                    loadChapterText(filePath, fileType, chapterIndex)
                }
                chapterTitle = loadedTitle
                chapterText = loadedText
                title = chapterTitle
                binding.textContent.text = chapterText
                binding.progressBar.visibility = View.GONE
                binding.buttonCopyChapter.isEnabled = true
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ReaderActivity, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadChapterText(path: String, type: String, index: Int): Pair<String, String> {
        return if (type == "epub") {
            val parser = EpubParser(File(path))
            val book = parser.parse()
            val chapter = book.chapters[index]
            val text = parser.extractChapterText(chapter.href)
            parser.close()
            chapter.title to text
        } else {
            val parser = PdfParser(applicationContext, File(path))
            val book = parser.parse()
            val chapter = book.chapters[index]
            val text = parser.extractText(chapter.startPage, chapter.endPage)
            parser.close()
            chapter.title to text
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.capitulo_copiado), Toast.LENGTH_SHORT).show()
    }
}
