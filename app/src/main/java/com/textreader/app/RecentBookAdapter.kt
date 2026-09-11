package com.textreader.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.textreader.app.data.RecentBook
import com.textreader.app.databinding.ItemRecentBookBinding
import java.io.File

class RecentBookAdapter(
    private var items: MutableList<RecentBook>,
    private val onClick: (RecentBook) -> Unit,
    private val onDelete: (RecentBook) -> Unit,
    private val onReordered: (List<RecentBook>) -> Unit
) : RecyclerView.Adapter<RecentBookAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemRecentBookBinding) : RecyclerView.ViewHolder(binding.root)

    fun updateItems(newItems: List<RecentBook>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val book = items[position]
        holder.binding.textBookTitle.text = book.title

        val cover = book.coverPath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) BitmapFactory.decodeFile(path) else null
            } catch (e: Exception) {
                null
            }
        }
        if (cover != null) {
            holder.binding.imageCover.setImageBitmap(cover)
        } else {
            holder.binding.imageCover.setImageResource(R.drawable.ic_book_placeholder)
        }

        val progress = if (book.totalUnits > 0) ((book.lastIndex + 1) * 100 / book.totalUnits).coerceIn(0, 100) else 0
        holder.binding.progressBook.progress = progress
        holder.binding.textProgress.text = holder.itemView.context.getString(R.string.progreso_formato, book.lastIndex + 1, book.totalUnits)

        holder.itemView.setOnClickListener { onClick(book) }
        holder.binding.buttonDeleteBook.setOnClickListener { onDelete(book) }
    }

    override fun getItemCount() = items.size

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun onDragFinished() {
        onReordered(items.toList())
    }

    fun attachSwipeAndDrag(recyclerView: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT
        ) {
            override fun onMove(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position in items.indices) {
                    val book = items[position]
                    items.removeAt(position)
                    notifyItemRemoved(position)
                    onDelete(book)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                onDragFinished()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }
}
