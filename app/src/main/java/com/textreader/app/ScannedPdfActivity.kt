package com.textreader.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.textreader.app.databinding.ActivityScannedPdfBinding
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Para PDF sin texto real (escaneados): como no se puede extraer ni copiar
 * texto, se muestra cada página como imagen para poder guardarlas (una por
 * una, o todas de una vez).
 */
class ScannedPdfActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"
        private const val THUMBNAIL_SCALE = 0.55f
    }

    private lateinit var binding: ActivityScannedPdfBinding
    private lateinit var filePath: String
    private var parser: PdfParser? = null
    private lateinit var adapter: ImageAdapter
    private var pendingAction: (() -> Unit)? = null

    private val requestStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.permiso_denegado), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannedPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_FILE_PATH)
        if (path == null) {
            finish()
            return
        }
        filePath = path
        title = intent.getStringExtra(EXTRA_FILE_NAME) ?: getString(R.string.app_name)

        adapter = ImageAdapter(emptyList()) { bitmap, index -> saveOnePage(bitmap, index) }
        binding.recyclerPages.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerPages.adapter = adapter
        binding.buttonSaveAllPages.setOnClickListener { onSaveAllClicked() }

        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val thumbnails = withContext(Dispatchers.IO) {
                val p = PdfParser(applicationContext, File(filePath))
                parser = p
                val count = p.pageCount()
                (0 until count).mapNotNull { p.renderPageBitmap(it, THUMBNAIL_SCALE) }
            }
            binding.progressBar.visibility = View.GONE
            adapter.updateItems(thumbnails)
            binding.textPageCount.text = getString(R.string.paginas_detectadas, thumbnails.size)
            binding.buttonSaveAllPages.visibility = if (thumbnails.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun ensurePermission(action: () -> Unit) {
        val hasPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            action()
        } else {
            pendingAction = action
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun saveOnePage(bitmap: Bitmap, index: Int) {
        ensurePermission {
            try {
                insertImageIntoGallery(bitmap, "pagina_${index + 1}.jpg")
                Toast.makeText(this, getString(R.string.imagen_guardada), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.error_guardar_imagen), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onSaveAllClicked() {
        ensurePermission { saveAllPages() }
    }

    private fun saveAllPages() {
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonSaveAllPages.isEnabled = false
        lifecycleScope.launch {
            var saved = 0
            val total = adapter.itemCount
            withContext(Dispatchers.IO) {
                for (i in 0 until total) {
                    try {
                        val bitmap = currentBitmapAt(i) ?: continue
                        insertImageIntoGallery(bitmap, "pagina_${i + 1}.jpg")
                        saved++
                    } catch (e: Exception) {
                        // una página puntual falló: seguimos con el resto
                    }
                }
            }
            binding.progressBar.visibility = View.GONE
            binding.buttonSaveAllPages.isEnabled = true
            Toast.makeText(this@ScannedPdfActivity, getString(R.string.imagenes_guardadas, saved, total), Toast.LENGTH_LONG).show()
        }
    }

    private fun currentBitmapAt(index: Int): Bitmap? = adapter.bitmapAt(index)

    private fun insertImageIntoGallery(bitmap: Bitmap, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LectorDeTexto")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        parser?.close()
    }
}
