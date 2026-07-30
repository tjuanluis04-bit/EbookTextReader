package com.textreader.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.textreader.app.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handlePickedFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonOpenFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/epub+zip", "application/pdf"))
        }
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
        val destFile = File(cacheDir, "libro_actual.$extension")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: throw java.io.IOException("No se pudo abrir el archivo")
        } catch (e: Exception) {
            binding.textStatus.text = getString(R.string.error_leer_archivo)
            return
        }

        binding.textStatus.text = ""

        val intent = Intent(this, ChapterListActivity::class.java).apply {
            putExtra(ChapterListActivity.EXTRA_FILE_PATH, destFile.absolutePath)
            putExtra(ChapterListActivity.EXTRA_FILE_TYPE, extension)
            putExtra(ChapterListActivity.EXTRA_FILE_NAME, fileName)
        }
        startActivity(intent)
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
