package com.textreader.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.textreader.app.databinding.ItemStripImageBinding

/** Tira horizontal de imágenes de un capítulo (modo "imágenes al final"). */
class StripImageAdapter(
    private var paths: List<String>,
    private val onOpen: (String) -> Unit,
    private val onSave: (String) -> Unit
) : RecyclerView.Adapter<StripImageAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemStripImageBinding) : RecyclerView.ViewHolder(binding.root)

    fun update(newPaths: List<String>) {
        paths = newPaths
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemStripImageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = paths[position]
        val thumb = ImageUtils.decodeFileSampled(path, 240)
        if (thumb != null) holder.binding.imageThumb.setImageBitmap(thumb) else holder.binding.imageThumb.setImageDrawable(null)
        holder.binding.imageThumb.setOnClickListener { onOpen(path) }
        holder.binding.buttonSaveImage.setOnClickListener { onSave(path) }
    }

    override fun getItemCount() = paths.size
}
