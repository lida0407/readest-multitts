package com.readest.multitts.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.readest.multitts.databinding.BottomSheetCacheBinding
import com.readest.multitts.databinding.ItemCacheBookBinding
import com.readest.multitts.model.Book
import com.readest.multitts.model.BookRepository
import com.readest.multitts.reader.DocumentManager
import com.readest.multitts.tts.AudioExporter
import com.readest.multitts.tts.CacheResolver
import com.readest.multitts.tts.TTSLocalAudioCache
import java.io.File

/**
 * Lets the reader see what offline audio is taking up space, delete it per book,
 * and export it as ordinary .m4a files that any music player can open.
 */
class CacheManagerBottomSheet(
    private val audioCache: TTSLocalAudioCache,
    private val bookRepository: BookRepository,
    private val voiceCandidates: List<String>
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCacheBinding? = null
    private val binding get() = _binding!!

    private data class Row(val bookId: String, val title: String, val bytes: Long, val clips: Int, val book: Book?)

    private var rows: List<Row> = emptyList()
    private var exportThread: Thread? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetCacheBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        view?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.8f).toInt()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvCacheBooks.layoutManager = LinearLayoutManager(requireContext())

        binding.btnClearAllCache.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete all offline audio?")
                .setMessage("Removes ${audioCache.getFormattedCacheSize()} of cached narration for every book. Exported files in Music are not affected.")
                .setPositiveButton("Delete all") { _, _ ->
                    audioCache.clearAllCache()
                    reload()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnCancelExport.setOnClickListener {
            AudioExporter.cancel()
            binding.tvCacheStatus.text = "Cancelling…"
        }

        reload()
        ClickFeedback.applyToTree(view)
    }

    private fun reload() {
        val books = bookRepository.getAllBooks()
        rows = audioCache.listBookCaches().map { entry ->
            val book = books.firstOrNull { it.id == entry.bookId }
            Row(
                bookId = entry.bookId,
                title = book?.title ?: "Removed book · 已删除的书",
                bytes = entry.bytes,
                clips = entry.fileCount,
                book = book
            )
        }

        binding.tvCacheSummary.text =
            "${audioCache.getFormattedCacheSize()} across ${rows.size} book(s) · exports land in Music/Readest++"
        binding.tvCacheEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.rvCacheBooks.adapter = CacheAdapter(rows)
    }

    private fun confirmDelete(row: Row) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete offline audio?")
            .setMessage("Frees ${audioCache.formatBytes(row.bytes)} for “${row.title}”. The book itself stays in your library.")
            .setPositiveButton("Delete") { _, _ ->
                audioCache.clearBookCache(row.bookId)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startExport(row: Row) {
        val book = row.book
        if (book == null) {
            Toast.makeText(context, "This book is no longer in your library, so its audio can't be matched to chapters. Delete it to free the space.", Toast.LENGTH_LONG).show()
            return
        }
        if (exportThread?.isAlive == true) {
            Toast.makeText(context, "An export is already running", Toast.LENGTH_SHORT).show()
            return
        }

        showExporting(true, "Reading chapters…")

        exportThread = Thread {
            try {
                val file = File(book.filePath)
                if (!file.exists()) {
                    post { showExporting(false, null); toast("Book file is missing on this device") }
                    return@Thread
                }

                val (_, chapters) = DocumentManager.loadBook(file)
                val voice = CacheResolver.detectVoice(audioCache, book, chapters, voiceCandidates)
                if (voice == null) {
                    post {
                        showExporting(false, null)
                        toast("Couldn't match the cached audio to this book's text — it may have been cached with a voice that is no longer installed.")
                    }
                    return@Thread
                }

                val chapterAudio = CacheResolver.bookAudio(audioCache, book, chapters, voice)
                val jobs = chapterAudio
                    .filter { it.files.isNotEmpty() }
                    .map {
                        AudioExporter.ChapterJob(
                            displayIndex = it.chapterIndex + 1,
                            title = it.title,
                            wavFiles = it.files
                        )
                    }

                AudioExporter.exportChapters(
                    requireContext().applicationContext,
                    book.title,
                    jobs,
                    object : AudioExporter.Progress {
                        override fun onFile(current: Int, total: Int, name: String) {
                            post {
                                if (_binding == null) return@post
                                binding.pbCacheExport.progress = (current * 100 / total.coerceAtLeast(1))
                                binding.tvCacheStatus.text = "Exporting $current / $total · $name"
                            }
                        }

                        override fun onDone(files: Int, bytes: Long, location: String) {
                            post {
                                showExporting(false, null)
                                toast("Exported $files chapter file(s), ${audioCache.formatBytes(bytes)} → $location")
                            }
                        }

                        override fun onError(message: String) {
                            post {
                                showExporting(false, null)
                                toast(message)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                post { showExporting(false, null); toast("Export failed: ${e.message}") }
            }
        }.also { it.start() }
    }

    private fun showExporting(active: Boolean, status: String?) {
        if (_binding == null) return
        binding.pbCacheExport.visibility = if (active) View.VISIBLE else View.GONE
        binding.tvCacheStatus.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnCancelExport.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnClearAllCache.visibility = if (active) View.GONE else View.VISIBLE
        if (status != null) binding.tvCacheStatus.text = status
        if (active) binding.pbCacheExport.progress = 0
    }

    private fun post(block: () -> Unit) {
        activity?.runOnUiThread { if (_binding != null) block() }
    }

    private fun toast(message: String) {
        Toast.makeText(context ?: return, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AudioExporter.cancel()
        _binding = null
    }

    private inner class CacheAdapter(private val items: List<Row>) :
        RecyclerView.Adapter<CacheAdapter.Holder>() {

        inner class Holder(val binding: ItemCacheBookBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemCacheBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            holder.binding.tvCacheBookTitle.text = row.title
            holder.binding.tvCacheBookMeta.text =
                "${audioCache.formatBytes(row.bytes)} · ${row.clips} clips"
            holder.binding.btnExportBook.isEnabled = row.book != null
            holder.binding.btnExportBook.setOnClickListener { startExport(row) }
            holder.binding.btnDeleteBookCache.setOnClickListener { confirmDelete(row) }
        }

        override fun getItemCount(): Int = items.size
    }
}
