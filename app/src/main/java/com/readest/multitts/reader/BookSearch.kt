package com.readest.multitts.reader

import android.content.Context
import com.readest.multitts.model.Book
import com.readest.multitts.model.Chapter
import com.readest.multitts.tts.SentenceSplitter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds a phrase in a book's text.
 *
 * Results carry a sentence index rather than a character offset because that is
 * the unit the reader can actually navigate to — the same index narration and
 * bookmarks use.
 */
object BookSearch {

    data class Hit(
        val bookId: String,
        val bookTitle: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val sentenceIndex: Int,
        /** The sentence, trimmed around the match so a long one still reads. */
        val snippet: String,
        /** Where the match starts inside [snippet], for highlighting it. */
        val matchStart: Int,
        val matchLength: Int
    )

    /** Beyond this a query stops being a search and starts being a scroll. */
    private const val MAX_HITS = 300
    private const val SNIPPET_BEFORE = 60
    private const val SNIPPET_AFTER = 90

    /**
     * Scans one book's already-parsed chapters. Cheap enough to run on every
     * keystroke for a book that is open.
     */
    fun inChapters(
        book: Book,
        chapters: List<Chapter>,
        query: String,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onHit: (Hit) -> Unit
    ): Int {
        val needle = query.trim()
        if (needle.length < 2) return 0
        var found = 0
        for (chapter in chapters) {
            if (cancelled.get() || found >= MAX_HITS) break
            // Sentence indices have to match what the reader assigned, so the
            // same splitter runs here rather than a quicker approximation.
            for (sentence in SentenceSplitter.split(chapter)) {
                val at = sentence.text.indexOf(needle, ignoreCase = true)
                if (at < 0) continue
                onHit(
                    hitFor(book, chapter, sentence.index, sentence.text, at, needle.length)
                )
                if (++found >= MAX_HITS) break
            }
        }
        return found
    }

    /**
     * Scans every book on the shelf.
     *
     * Books opened before are read from the parse cache and are quick; one that
     * has never been opened has to be parsed first, which is why this reports
     * progress per book and can be cancelled.
     */
    fun acrossLibrary(
        context: Context,
        books: List<Book>,
        query: String,
        cancelled: AtomicBoolean,
        onProgress: (done: Int, total: Int, title: String) -> Unit,
        onHit: (Hit) -> Unit
    ) {
        val needle = query.trim()
        if (needle.length < 2) return
        books.forEachIndexed { index, book ->
            if (cancelled.get()) return
            onProgress(index, books.size, book.title)
            val file = File(book.filePath)
            if (!file.exists()) return@forEachIndexed
            val chapters = try {
                DocumentManager.loadBookCached(context, file).second
            } catch (e: Throwable) {
                // A book that will not parse should not stop the search.
                return@forEachIndexed
            }
            inChapters(book, chapters, needle, cancelled, onHit)
        }
        onProgress(books.size, books.size, "")
    }

    private fun hitFor(
        book: Book,
        chapter: Chapter,
        sentenceIndex: Int,
        text: String,
        at: Int,
        length: Int
    ): Hit {
        val from = (at - SNIPPET_BEFORE).coerceAtLeast(0)
        val to = (at + length + SNIPPET_AFTER).coerceAtMost(text.length)
        val prefix = if (from > 0) "…" else ""
        val snippet = prefix + text.substring(from, to) + if (to < text.length) "…" else ""
        return Hit(
            bookId = book.id,
            bookTitle = book.title,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            sentenceIndex = sentenceIndex,
            snippet = snippet,
            matchStart = at - from + prefix.length,
            matchLength = length
        )
    }
}
