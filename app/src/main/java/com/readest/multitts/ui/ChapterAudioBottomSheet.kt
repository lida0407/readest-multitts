package com.readest.multitts.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.readest.multitts.databinding.BottomSheetChapterAudioBinding
import com.readest.multitts.databinding.ItemChapterAudioBinding
import com.readest.multitts.model.Book
import com.readest.multitts.model.Chapter
import com.readest.multitts.reader.DocumentManager
import com.readest.multitts.tts.SentenceSplitter
import com.readest.multitts.tts.TTSLocalAudioCache
import java.io.File

/**
 * A book's narration, chapter by chapter.
 *
 * "This chapter" and "the whole book" were the only two sizes caching came in,
 * and once a whole book was cached it was one undivided lump of storage. This
 * lists every chapter with what is cached of it, so a reader can narrate the
 * next few ahead of a commute and throw away the ones already listened to.
 */
class ChapterAudioBottomSheet(
    private val book: Book,
    private val audioCache: TTSLocalAudioCache,
    /** The voice clips are looked up under; the one playback would use. */
    private val voiceId: String,
    private val chapterWord: (Int) -> String,
    private val onCache: (List<Int>, String) -> Unit,
    private val onChanged: () -> Unit = {}
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetChapterAudioBinding? = null
    private val binding get() = _binding!!

    private data class Row(
        val chapter: Chapter,
        val sentences: List<Pair<Int, String>>,
        var status: TTSLocalAudioCache.ChapterStatus
    )

    private var rows: List<Row> = emptyList()
    private val selected = sortedSetOf<Int>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetChapterAudioBinding.inflate(inflater, container, false)
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
        binding.tvChapterAudioTitle.text = book.title
        binding.rvChapterAudio.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChapterAudio.adapter = Adapter()

        binding.chipSelectRead.setOnClickListener {
            // Everything before where you are now: listened to, or skipped past.
            select { it.chapter.index < book.currentChapterIndex && it.status.diskBytes > 0 }
        }
        binding.chipSelectCached.setOnClickListener { select { it.status.diskBytes > 0 } }
        binding.chipSelectMissing.setOnClickListener { select { !it.status.isComplete } }
        binding.chipSelectAhead.setOnClickListener {
            val from = book.currentChapterIndex
            select { it.chapter.index in from until from + 10 && !it.status.isComplete }
        }
        binding.chipSelectAll.setOnClickListener { select { true } }
        binding.chipSelectNone.setOnClickListener { select { false } }

        binding.btnChapterCache.setOnClickListener { cacheSelected() }
        binding.btnChapterDelete.setOnClickListener { confirmDelete() }

        refreshActions()
        load()
        ClickFeedback.applyToTree(view)
    }

    /**
     * Status for every chapter, off the main thread.
     *
     * Each chapter is one directory listing and a hash per sentence — quick,
     * but a 600-chapter book still wants a background thread.
     */
    private fun load() {
        binding.tvChapterAudioSummary.text = "Counting…"
        val context = requireContext().applicationContext
        Thread {
            val chapters = try {
                DocumentManager.loadBookCached(context, File(book.filePath)).second
            } catch (e: Throwable) {
                post { binding.tvChapterAudioSummary.text = "Couldn't read this book" }
                return@Thread
            }
            val legacy = audioCache.legacyNames(book.id)
            // Hashing every sentence to look it up is the expensive part, and
            // a chapter with no folder has nothing to find. On a 629-chapter
            // book with one chapter cached, that is the difference between
            // hashing 300,000 sentences and hashing 500.
            val withAudio = audioCache.chaptersWithAudio(book.id)
            val loaded = chapters.map { chapter ->
                val sentences = SentenceSplitter.split(chapter).map { it.index to it.text }
                val status = if (withAudio != null && chapter.index !in withAudio) {
                    TTSLocalAudioCache.ChapterStatus(0, sentences.size, 0L)
                } else {
                    audioCache.chapterStatus(book.id, chapter.index, voiceId, sentences, legacy)
                }
                Row(chapter, sentences, status)
            }
            post {
                rows = loaded
                binding.rvChapterAudio.adapter?.notifyDataSetChanged()
                (binding.rvChapterAudio.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset((book.currentChapterIndex - 2).coerceAtLeast(0), 0)
                refreshSummary()
                refreshActions()
            }
        }.start()
    }

    private fun select(predicate: (Row) -> Boolean) {
        selected.clear()
        rows.filter(predicate).forEach { selected.add(it.chapter.index) }
        binding.rvChapterAudio.adapter?.notifyDataSetChanged()
        refreshActions()
    }

    private fun refreshSummary() {
        if (rows.isEmpty()) return
        val complete = rows.count { it.status.isComplete }
        val partial = rows.count { !it.status.isEmpty && !it.status.isComplete }
        val bytes = rows.sumOf { it.status.diskBytes }
        binding.tvChapterAudioSummary.text = buildString {
            append("$complete of ${rows.size} cached")
            if (partial > 0) append(" · $partial partly")
            append(" · ${audioCache.formatBytes(bytes)}")
        }
    }

    /** The two buttons say exactly what they will do to how much. */
    private fun refreshActions() {
        val picked = rows.filter { it.chapter.index in selected }
        val toCache = picked.count { !it.status.isComplete }
        val toDelete = picked.filter { it.status.diskBytes > 0 }
        val freed = toDelete.sumOf { it.status.diskBytes }

        binding.btnChapterCache.text =
            if (toCache == 0) "Cache" else "Cache $toCache"
        binding.btnChapterCache.isEnabled = toCache > 0
        binding.btnChapterCache.alpha = if (toCache > 0) 1f else 0.4f

        binding.btnChapterDelete.text =
            if (toDelete.isEmpty()) "Delete audio"
            else "Delete ${toDelete.size} · ${audioCache.formatBytes(freed)}"
        binding.btnChapterDelete.isEnabled = toDelete.isNotEmpty()
        binding.btnChapterDelete.alpha = if (toDelete.isNotEmpty()) 1f else 0.4f
    }

    private fun cacheSelected() {
        val wanted = rows.filter { it.chapter.index in selected && !it.status.isComplete }
            .map { it.chapter.index }
        if (wanted.isEmpty()) return
        onCache(wanted, voiceId)
        dismiss()
    }

    private fun confirmDelete() {
        val doomed = rows.filter { it.chapter.index in selected && it.status.diskBytes > 0 }
        if (doomed.isEmpty()) return
        val freed = doomed.sumOf { it.status.diskBytes }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete audio for ${doomed.size} chapter${if (doomed.size == 1) "" else "s"}?")
            .setMessage(
                "Frees ${audioCache.formatBytes(freed)}. The chapters stay in the book; " +
                    "they will simply be narrated live, or can be cached again."
            )
            .setPositiveButton("Delete") { _, _ -> delete(doomed) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(doomed: List<Row>) {
        binding.tvChapterAudioSummary.text = "Deleting…"
        Thread {
            for (row in doomed) {
                audioCache.clearChapter(book.id, row.chapter.index, voiceId, row.sentences)
                row.status = TTSLocalAudioCache.ChapterStatus(0, row.sentences.size, 0L)
            }
            post {
                selected.clear()
                binding.rvChapterAudio.adapter?.notifyDataSetChanged()
                refreshSummary()
                refreshActions()
                onChanged()
            }
        }.start()
    }

    private fun post(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val binding: ItemChapterAudioBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemChapterAudioBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val b = holder.binding
            val index = row.chapter.index
            val isCurrent = index == book.currentChapterIndex

            b.cbChapter.isChecked = index in selected
            b.tvChapterName.text = buildString {
                append(chapterWord(index + 1))
                if (row.chapter.title.isNotBlank()) append(" · ").append(row.chapter.title)
            }
            b.tvChapterMeta.text = buildString {
                if (isCurrent) append("Reading now · ")
                else if (index < book.currentChapterIndex) append("Read · ")
                append("${row.sentences.size} sentences")
                if (!row.status.isEmpty) append(" · ").append(audioCache.formatBytes(row.status.bytes))
                if (row.status.hasOrphans) {
                    append(" · ")
                    append(audioCache.formatBytes(row.status.diskBytes - row.status.bytes))
                    append(" in another voice")
                }
            }
            b.tvChapterBadge.text = when {
                row.status.isComplete -> "✓ cached"
                row.status.isEmpty -> "—"
                else -> "${row.status.percent}%"
            }

            holder.itemView.setOnClickListener {
                if (!selected.add(index)) selected.remove(index)
                notifyItemChanged(position)
                refreshActions()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
