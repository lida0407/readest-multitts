package com.readest.multitts.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.readest.multitts.R
import com.readest.multitts.databinding.BottomSheetSearchBinding
import com.readest.multitts.databinding.ItemSearchResultBinding
import com.readest.multitts.model.Book
import com.readest.multitts.model.Chapter
import com.readest.multitts.reader.BookSearch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Find a phrase, in the open book or on the whole shelf.
 *
 * Results stream in as they are found rather than appearing all at once: a
 * whole-shelf search has to parse books it has never opened, and watching the
 * first hits arrive is the difference between waiting and being stuck.
 */
class SearchBottomSheet(
    private val currentBook: Book?,
    private val currentChapters: List<Chapter>,
    private val libraryBooks: List<Book>,
    private val chapterWord: (Int, Int) -> String,
    private val onOpenHit: (BookSearch.Hit) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetSearchBinding? = null
    private val binding get() = _binding!!

    private val hits = mutableListOf<BookSearch.Hit>()
    private var worker: Thread? = null
    private var cancelled = AtomicBoolean(false)
    private var wholeLibrary = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvSearch.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearch.adapter = Adapter()

        if (currentBook == null) {
            // Opened from the shelf: there is no "this book" to search.
            binding.tgSearchScope.check(binding.btnScopeLibrary.id)
            binding.btnScopeBook.isEnabled = false
            wholeLibrary = true
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = start(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        binding.tgSearchScope.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            wholeLibrary = checkedId == binding.btnScopeLibrary.id
            start(binding.etSearch.text.toString())
        }

        binding.btnSearchCancel.setOnClickListener { stop() }
        binding.etSearch.requestFocus()
        ClickFeedback.applyToTree(view)
    }

    // ----------------------------------------------------------------- search

    private fun start(query: String) {
        stop()
        hits.clear()
        binding.rvSearch.adapter?.notifyDataSetChanged()

        val needle = query.trim()
        if (needle.length < 2) {
            binding.tvSearchStatus.text =
                if (needle.isEmpty()) "" else "Keep typing…"
            return
        }

        val context = requireContext().applicationContext
        val book = currentBook
        val flag = AtomicBoolean(false)
        cancelled = flag
        binding.btnSearchCancel.visibility = View.VISIBLE
        binding.tvSearchStatus.text = "Searching…"

        val thread = Thread {
            val batch = mutableListOf<BookSearch.Hit>()
            val flush = {
                if (batch.isNotEmpty()) {
                    val copy = batch.toList()
                    batch.clear()
                    post { append(copy) }
                }
            }
            if (wholeLibrary) {
                BookSearch.acrossLibrary(
                    context = context,
                    books = libraryBooks,
                    query = needle,
                    cancelled = flag,
                    onProgress = { done, total, title ->
                        flush()
                        post {
                            binding.tvSearchStatus.text =
                                if (done >= total) summary(needle)
                                else "Searching $done / $total · $title"
                        }
                    },
                    onHit = { batch.add(it) }
                )
            } else if (book != null) {
                BookSearch.inChapters(book, currentChapters, needle, flag) { batch.add(it) }
            }
            flush()
            post {
                binding.btnSearchCancel.visibility = View.GONE
                binding.tvSearchStatus.text = summary(needle)
            }
        }
        worker = thread
        thread.start()
    }

    private fun stop() {
        cancelled.set(true)
        worker = null
        _binding?.btnSearchCancel?.visibility = View.GONE
    }

    private fun summary(needle: String): String = when {
        hits.isEmpty() -> "Nothing found for “$needle”"
        hits.size == 1 -> "1 result"
        else -> "${hits.size} results"
    }

    private fun append(found: List<BookSearch.Hit>) {
        val from = hits.size
        hits.addAll(found)
        binding.rvSearch.adapter?.notifyItemRangeInserted(from, found.size)
        binding.tvSearchStatus.text = "${hits.size} so far…"
    }

    private fun post(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    // ------------------------------------------------------------------- list

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = hits.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val hit = hits[position]
            val where = buildString {
                if (wholeLibrary) append(hit.bookTitle).append(" · ")
                append(chapterWord(hit.chapterIndex + 1, 0))
                if (hit.chapterTitle.isNotBlank()) append(" · ").append(hit.chapterTitle)
            }
            holder.binding.tvHitWhere.text = where
            holder.binding.tvHitSnippet.text = highlighted(holder.itemView.context, hit)
            holder.itemView.setOnClickListener {
                dismiss()
                onOpenHit(hit)
            }
        }
    }

    /** Marks the matched span so the eye lands on it without re-reading. */
    private fun highlighted(context: Context, hit: BookSearch.Hit): CharSequence {
        val text = SpannableString(hit.snippet)
        val end = (hit.matchStart + hit.matchLength).coerceAtMost(text.length)
        if (hit.matchStart in 0 until end) {
            val value = TypedValue()
            context.theme.resolveAttribute(R.attr.rdSentenceHl, value, true)
            text.setSpan(
                BackgroundColorSpan(value.data),
                hit.matchStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                hit.matchStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return text
    }

    override fun onDestroyView() {
        stop()
        super.onDestroyView()
        _binding = null
    }
}
