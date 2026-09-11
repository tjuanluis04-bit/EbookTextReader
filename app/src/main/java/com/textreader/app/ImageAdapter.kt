package com.textreader.app

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.textreader.app.databinding.ItemGalleryImageBinding

class ImageAdapter(
    private var items: List<Bitmap>,
    private val onSave: (Bitmap, Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemGalleryImageBinding) : RecyclerView.ViewHolder(binding.root)

    fun updateItems(newItems: List<Bitmap>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.imageThumb.setImageBitmap(items[position])
        holder.binding.buttonSaveImage.setOnClickListener { onSave(items[position], position) }
    }

    override fun getItemCount() = items.size
}
