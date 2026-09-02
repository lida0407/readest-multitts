package com.readest.multitts.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.readest.multitts.R
import com.readest.multitts.databinding.BottomSheetContentsBinding
import com.readest.multitts.databinding.ItemChapterRowBinding
import com.readest.multitts.model.Bookmark
import com.readest.multitts.model.Chapter

/**
 * Table of contents + saved bookmarks, so a reader can jump around the book by
 * content rather than by paging through it.
 */
class ContentsBottomSheet(
    private val chapters: List<Chapter>,
    private val currentChapterIndex: Int,
    private val bookmarks: List<Bookmark>,
    private val startOnBookmarks: Boolean = false,
    /** What this theme calls the table of contents. */
    private val title: String = "Contents · 目录",
    private val onChapterSelected: (Int) -> Unit,
    private val onBookmarkSelected: (Bookmark) -> Unit,
    private val onBookmarkDeleted: (Bookmark) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetContentsBinding? = null
    private val binding get() = _binding!!

    private var workingBookmarks = bookmarks.toMutableList()
    private var showingBookmarks = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetContentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // A contents list is a browsing surface — open it tall rather than peeking.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.85f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvContentsTitle.text = title

        binding.rvContents.layoutManager = LinearLayoutManager(requireContext())
        showingBookmarks = startOnBookmarks
        binding.toggleContents.check(
            if (showingBookmarks) binding.btnTabBookmarks.id else binding.btnTabChapters.id
        )
        render()
        ClickFeedback.applyToTree(view)

        binding.toggleContents.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showingBookmarks = checkedId == binding.btnTabBookmarks.id
            render()
        }
    }

    private fun render() {
        if (showingBookmarks) {
            binding.tvContentsEmpty.visibility = if (workingBookmarks.isEmpty()) View.VISIBLE else View.GONE
            binding.rvContents.adapter = BookmarkAdapter(
                workingBookmarks,
                onClick = { bm ->
                    onBookmarkSelected(bm)
                    dismiss()
                },
                onDelete = { bm ->
                    onBookmarkDeleted(bm)
                    workingBookmarks.remove(bm)
                    render()
                }
            )
        } else {
            binding.tvContentsEmpty.visibility = View.GONE
            binding.rvContents.adapter = ChapterAdapter(chapters, currentChapterIndex) { index ->
                onChapterSelected(index)
                dismiss()
            }
            binding.rvContents.post {
                (binding.rvContents.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(currentChapterIndex.coerceAtLeast(0), 120)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ChapterAdapter(
        private val chapters: List<Chapter>,
        private val currentIndex: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<RowHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(ItemChapterRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val chapter = chapters[position]
            val ctx = holder.itemView.context
            val isCurrent = position == currentIndex

            holder.binding.tvRowIndex.text = (position + 1).toString()
            holder.binding.tvRowTitle.text = chapter.title
            holder.binding.tvRowSubtitle.visibility = View.GONE
            holder.binding.btnRowDelete.visibility = View.GONE
            holder.binding.tvRowBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE
            holder.binding.tvRowBadge.text = "Reading"

            val titleColor = if (isCurrent) R.color.accent else R.color.text_primary
            holder.binding.tvRowTitle.setTextColor(ContextCompat.getColor(ctx, titleColor))
            holder.binding.tvRowTitle.setTypeface(null, if (isCurrent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            holder.itemView.setOnClickListener { onClick(position) }
        }

        override fun getItemCount(): Int = chapters.size
    }

    private class BookmarkAdapter(
        private val items: List<Bookmark>,
        private val onClick: (Bookmark) -> Unit,
        private val onDelete: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<RowHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(ItemChapterRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val bm = items[position]
            holder.binding.tvRowIndex.text = "🔖"
            holder.binding.tvRowTitle.text = bm.excerpt.ifBlank { bm.chapterTitle }
            holder.binding.tvRowSubtitle.visibility = View.VISIBLE
            holder.binding.tvRowSubtitle.text = "Ch ${bm.chapterIndex + 1} · ${bm.chapterTitle}"
            holder.binding.tvRowBadge.visibility = View.GONE
            holder.binding.btnRowDelete.visibility = View.VISIBLE

            holder.itemView.setOnClickListener { onClick(bm) }
            holder.binding.btnRowDelete.setOnClickListener { onDelete(bm) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class RowHolder(val binding: ItemChapterRowBinding) : RecyclerView.ViewHolder(binding.root)
}
