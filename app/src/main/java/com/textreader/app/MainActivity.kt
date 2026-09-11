package com.textreader.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.textreader.app.data.RecentBook
import com.textreader.app.data.RecentBooksStore
import com.textreader.app.databinding.ActivityMainBinding
import com.textreader.app.epub.EpubParser
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: RecentBooksStore
    private lateinit var adapter: RecentBookAdapter

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handlePickedFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RecentBooksStore(this)

        binding.buttonOpenFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/epub+zip", "application/pdf"))
        }

        adapter = RecentBookAdapter(
            items = mutableListOf(),
            onClick = { book -> resumeBook(book) },
            onDelete = { book -> deleteBook(book) },
            onReordered = { newOrder -> store.reorder(newOrder.map { it.id }) }
        )
        binding.recyclerRecentBooks.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecentBooks.adapter = adapter
        adapter.attachSwipeAndDrag(binding.recyclerRecentBooks)
    }

    override fun onResume() {
        super.onResume()
        refreshRecentList()
    }

    private fun refreshRecentList() {
        val books = store.loadAll()
        adapter.updateItems(books)
        binding.textEmptyRecent.visibility = if (books.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerRecentBooks.visibility = if (books.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun deleteBook(book: RecentBook) {
        store.remove(book.id)
        try { File(book.filePath).delete() } catch (e: Exception) { /* no pasa nada si falla */ }
        book.coverPath?.let { try { File(it).delete() } catch (e: Exception) { } }
        refreshRecentList()
    }

    private fun resumeBook(book: RecentBook) {
        if (book.fileType == "epub") {
            val intent = Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_FILE_PATH, book.filePath)
                putExtra(ReaderActivity.EXTRA_FILE_TYPE, "epub")
                putExtra(ReaderActivity.EXTRA_CHAPTER_INDEX, book.lastIndex)
                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
            }
            startActivity(intent)
        } else {
            if (book.pdfMode == null) {
                openPdfChooser(book.filePath, book.title, book.id)
            } else {
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_FILE_PATH, book.filePath)
                    putExtra(ReaderActivity.EXTRA_FILE_TYPE, "pdf")
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                    putExtra(ReaderActivity.EXTRA_PDF_MODE, book.pdfMode)
                    putExtra(ReaderActivity.EXTRA_FULL_BOOK, book.pdfMode == com.textreader.app.data.PdfMode.FULL_BOOK)
                    putExtra(ReaderActivity.EXTRA_FILE_NAME, book.title)
                    if (book.pdfMode == com.textreader.app.data.PdfMode.CUSTOM) {
                        putExtra(ReaderActivity.EXTRA_CUSTOM_RANGES_JSON, com.textreader.app.data.customRangesToJson(book.customRanges))
                        putExtra(ReaderActivity.EXTRA_CHAPTER_INDEX, book.lastIndex)
                    } else {
                        putExtra(ReaderActivity.EXTRA_CHAPTER_INDEX, book.lastIndex)
                    }
                }
                startActivity(intent)
            }
        }
    }

    private fun openPdfChooser(filePath: String, fileName: String, bookId: String) {
        val intent = Intent(this, ChapterListActivity::class.java).apply {
            putExtra(ChapterListActivity.EXTRA_FILE_PATH, filePath)
            putExtra(ChapterListActivity.EXTRA_FILE_TYPE, "pdf")
            putExtra(ChapterListActivity.EXTRA_FILE_NAME, fileName)
            putExtra(ChapterListActivity.EXTRA_BOOK_ID, bookId)
        }
        startActivity(intent)
    }

    private fun handlePickedFile(uri: Uri) {
        val fileName = queryFileName(uri) ?: "libro"
        val lowerName = fileName.lowercase()
        val isEpub = lowerName.endsWith(".epub")
        val isPdf = lowerName.endsWith(".pdf")

        if (!isEpub && !isPdf) {
            binding.textStatus.text = getString(R.string.formato_no_soportado)
            return
        }

        binding.textStatus.text = getString(R.string.cargando)
        val extension = if (isEpub) "epub" else "pdf"
        val bookId = UUID.randomUUID().toString()
        val booksDir = File(filesDir, "books").apply { mkdirs() }
        val destFile = File(booksDir, "$bookId.$extension")

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: throw java.io.IOException("No se pudo abrir el archivo")
        } catch (e: Exception) {
            binding.textStatus.text = getString(R.string.error_leer_archivo)
            return
        }

        binding.textStatus.text = ""
        val displayTitle = fileName.substringBeforeLast('.')

        lifecycleScope.launch {
            val coverPath = withContext(Dispatchers.IO) { generateCover(destFile, extension, bookId) }
            val book = RecentBook(
                id = bookId,
                title = displayTitle,
                fileType = extension,
                filePath = destFile.absolutePath,
                coverPath = coverPath
            )
            store.upsert(book)
            refreshRecentList()

            if (isEpub) {
                val intent = Intent(this@MainActivity, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_FILE_PATH, destFile.absolutePath)
                    putExtra(ReaderActivity.EXTRA_FILE_TYPE, "epub")
                    putExtra(ReaderActivity.EXTRA_CHAPTER_INDEX, 0)
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                }
                startActivity(intent)
            } else {
                openPdfChooser(destFile.absolutePath, displayTitle, bookId)
            }
        }
    }

    private fun generateCover(file: File, extension: String, bookId: String): String? {
        return try {
            val coversDir = File(filesDir, "covers").apply { mkdirs() }
            val coverFile = File(coversDir, "$bookId.jpg")
            val bitmap = if (extension == "epub") {
                val parser = EpubParser(file)
                parser.parse()
                val img = parser.coverImage()
                parser.close()
                img?.second?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else {
                val parser = PdfParser(applicationContext, file)
                val bmp = parser.renderCoverBitmap()
                parser.close()
                bmp
            }
            if (bitmap == null) return null
            FileOutputStream(coverFile).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            coverFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
