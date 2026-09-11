package com.textreader.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.textreader.app.data.CustomRange
import com.textreader.app.data.customRangesToJson
import com.textreader.app.databinding.ActivityPdfRangePickerBinding
import com.textreader.app.databinding.ItemRangeRowBinding
import com.textreader.app.pdf.PdfParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Permite definir a mano cuántas "partes" tiene el PDF y qué rango de páginas
 * abarca cada una, para los PDF que no tienen marcadores de capítulo reales.
 */
class PdfRangePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val RESULT_RANGES_JSON = "result_ranges_json"
    }

    private data class RowViews(val root: View, val title: EditText, val start: EditText, val end: EditText)

    private lateinit var binding: ActivityPdfRangePickerBinding
    private val rows = mutableListOf<RowViews>()
    private var pageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfRangePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath == null) {
            finish()
            return
        }

        binding.textPageCount.text = getString(R.string.cargando)
        lifecycleScope.launch {
            pageCount = withContext(Dispatchers.IO) {
                val parser = PdfParser(applicationContext, File(filePath))
                val count = parser.pageCount()
                parser.close()
                count
            }
            binding.textPageCount.text = getString(R.string.pdf_tiene_paginas, pageCount)
        }

        binding.buttonAddRange.setOnClickListener { addRow() }
        binding.buttonConfirmRanges.setOnClickListener { confirmRanges() }

        // Arrancamos con una fila lista para completar.
        addRow()
    }

    private fun addRow() {
        val rowBinding = ItemRangeRowBinding.inflate(LayoutInflater.from(this), binding.containerRows, false)
        rowBinding.editTitle.hint = getString(R.string.parte_numero, rows.size + 1)
        val rowViews = RowViews(rowBinding.root, rowBinding.editTitle, rowBinding.editStart, rowBinding.editEnd)
        rowBinding.buttonRemoveRow.setOnClickListener {
            binding.containerRows.removeView(rowBinding.root)
            rows.remove(rowViews)
            renumberHints()
        }
        rows.add(rowViews)
        binding.containerRows.addView(rowBinding.root)
    }

    private fun renumberHints() {
        rows.forEachIndexed { index, row ->
            if (row.title.text.isNullOrBlank()) {
                row.title.hint = getString(R.string.parte_numero, index + 1)
            }
        }
    }

    private fun confirmRanges() {
        if (rows.isEmpty()) {
            Toast.makeText(this, getString(R.string.agrega_al_menos_una_parte), Toast.LENGTH_LONG).show()
            return
        }
        if (pageCount <= 0) {
            Toast.makeText(this, getString(R.string.error_procesar_libro), Toast.LENGTH_LONG).show()
            return
        }

        val ranges = mutableListOf<CustomRange>()
        for ((index, row) in rows.withIndex()) {
            val start = row.start.text.toString().trim().toIntOrNull()
            val end = row.end.text.toString().trim().toIntOrNull()
            if (start == null || end == null || start < 1 || end > pageCount || start > end) {
                Toast.makeText(this, getString(R.string.rango_invalido, index + 1, pageCount), Toast.LENGTH_LONG).show()
                return
            }
            val title = row.title.text.toString().trim().ifBlank { getString(R.string.parte_numero, index + 1) }
            ranges.add(CustomRange(title, start - 1, end - 1)) // a 0-based internamente
        }

        val resultIntent = android.content.Intent()
        resultIntent.putExtra(RESULT_RANGES_JSON, customRangesToJson(ranges))
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
