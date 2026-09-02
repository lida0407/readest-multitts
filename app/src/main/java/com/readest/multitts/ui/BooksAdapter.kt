package com.readest.multitts.ui

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.content.Context
import android.util.TypedValue
import com.readest.multitts.R
import com.readest.multitts.databinding.ItemBookCardBinding
import com.readest.multitts.model.Book
import com.readest.multitts.model.BookFormat

class BooksAdapter(
    private var books: List<Book>,
    /** How the current theme names a chapter — "Chapter 3 / 12", "FLOOR 3 / 12". */
    private val chapterLabel: (Int, Int) -> String,
    /** Per-book score, or null in a theme that has no scores. */
    private val bookXp: (String) -> String?,
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
        val context = holder.itemView.context
        holder.binding.tvFormatBadge.text = book.format.name
        // Built rather than tinted: a tint would repaint the keyline the pixel
        // and cozy badges are drawn with, not just their fill.
        holder.binding.tvFormatBadge.background = badgeFor(
            context,
            themeColor(
                context,
                when (book.format) {
                    BookFormat.EPUB -> R.attr.rdBadgeEpub
                    BookFormat.PDF -> R.attr.rdBadgePdf
                    BookFormat.MOBI -> R.attr.rdBadgeMobi
                    BookFormat.TXT -> R.attr.rdBadgeTxt
                    else -> R.attr.rdTextSecondary
                }
            )
        )
        holder.binding.tvCardTitle.text = book.title

        val lastRead = DateUtils.getRelativeTimeSpanString(
            book.lastReadTimestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        holder.binding.tvCardProgress.text =
            "${chapterLabel(book.currentChapterIndex + 1, book.totalChapters)} · $lastRead"

        val xp = bookXp(book.id)
        holder.binding.tvCardXp.visibility = if (xp == null) android.view.View.GONE else android.view.View.VISIBLE
        holder.binding.tvCardXp.text = xp ?: ""

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

    /** The theme's badge shape, refilled with this format's colour. */
    private fun badgeFor(context: Context, fill: Int): android.graphics.drawable.Drawable {
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.rdBadgeBgShape, value, true)
        val shape = androidx.core.content.ContextCompat
            .getDrawable(context, value.resourceId)!!.mutate()
        (shape as? android.graphics.drawable.GradientDrawable)?.setColor(fill)
        return shape
    }

    private fun themeColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
