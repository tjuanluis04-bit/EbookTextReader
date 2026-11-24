package com.textreader.app

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.textreader.app.databinding.ItemGalleryImageBinding
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cuadrícula de páginas/imágenes con miniaturas que se cargan bajo demanda (solo las que se ven),
 * para que un libro escaneado de cientos de páginas no llene la memoria.
 */
class PageGridAdapter(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val count: Int,
    private val loadThumb: (Int) -> Bitmap?,
    private val onOpen: (Int) -> Unit,
    private val onSave: (Int) -> Unit
) : RecyclerView.Adapter<PageGridAdapter.ViewHolder>() {

    private val cache = object : LruCache<Int, Bitmap>(24 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    inner class ViewHolder(val binding: ItemGalleryImageBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
        var boundIndex: Int = -1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.job?.cancel()
        holder.boundIndex = position

        val cached = cache.get(position)
        if (cached != null) {
            holder.binding.imageThumb.setImageBitmap(cached)
        } else {
            holder.binding.imageThumb.setImageDrawable(null)
            holder.job = scope.launch {
                val bmp = withContext(dispatcher) { loadThumb(position) }
                if (bmp != null) {
                    cache.put(position, bmp)
                    if (holder.boundIndex == position) holder.binding.imageThumb.setImageBitmap(bmp)
                }
            }
        }

        holder.binding.imageThumb.setOnClickListener { onOpen(position) }
        holder.binding.buttonSaveImage.setOnClickListener { onSave(position) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.job?.cancel()
        holder.job = null
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = count
}
