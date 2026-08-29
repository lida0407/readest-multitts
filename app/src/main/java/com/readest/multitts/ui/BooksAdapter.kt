package com.readest.multitts.ui

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.readest.multitts.databinding.ItemBookCardBinding
import com.readest.multitts.model.Book
import com.readest.multitts.model.BookFormat

class BooksAdapter(
    private var books: List<Book>,
    private val onBookClicked: (Book) -> Unit,
    private val onBookDelete: (Book) -> Unit
) : RecyclerView.Adapter<BooksAdapter.BookViewHolder>() {

    class BookViewHolder(val binding: ItemBookCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = books[position]
        holder.binding.tvFormatBadge.text = book.format.name
        holder.binding.tvFormatBadge.backgroundTintList = ColorStateList.valueOf(
            when (book.format) {
                BookFormat.EPUB -> 0xFF4F46E5.toInt()
                BookFormat.PDF -> 0xFFDC2626.toInt()
                BookFormat.MOBI -> 0xFFD97706.toInt()
                BookFormat.TXT -> 0xFF0D9488.toInt()
                else -> 0xFF64748B.toInt()
            }
        )
        holder.binding.tvCardTitle.text = book.title

        val lastRead = DateUtils.getRelativeTimeSpanString(
            book.lastReadTimestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        holder.binding.tvCardProgress.text =
            "Chapter ${book.currentChapterIndex + 1} / ${book.totalChapters} · $lastRead"

        val percent = if (book.totalChapters > 0) {
            ((book.currentChapterIndex + 1) * 100 / book.totalChapters).coerceIn(0, 100)
        } else 0
        holder.binding.pbBookProgress.progress = percent

        holder.itemView.setOnClickListener {
            onBookClicked(book)
        }

        holder.binding.btnDeleteBook.setOnClickListener {
            onBookDelete(book)
        }
    }

    override fun getItemCount(): Int = books.size

    fun updateData(newBooks: List<Book>) {
        books = newBooks
        notifyDataSetChanged()
    }
}
