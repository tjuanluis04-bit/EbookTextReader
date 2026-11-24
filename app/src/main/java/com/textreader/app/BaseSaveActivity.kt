package com.textreader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Base para las pantallas que guardan imágenes en la galería. En Android 9 o menor pide el
 * permiso de almacenamiento antes de ejecutar la acción; en versiones nuevas no hace falta.
 */
abstract class BaseSaveActivity : AppCompatActivity() {

    private var pendingAction: (() -> Unit)? = null

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAction
            pendingAction = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(this, getString(R.string.permiso_denegado), Toast.LENGTH_LONG).show()
            }
        }

    protected fun ensureStoragePermission(action: () -> Unit) {
        val hasPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            action()
        } else {
            pendingAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
