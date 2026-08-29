package com.readest.multitts.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class BookRepository(private val context: Context) {

    private val gson = Gson()
    private val booksDir = File(context.filesDir, "books").apply { if (!exists()) mkdirs() }
    private val manifestFile = File(context.filesDir, "books_manifest.json")

    /**
     * Reading progress arrives once per spoken sentence, so the manifest is held in
     * memory and flushed on a timer instead of being rewritten every time.
     *
     * The cache is process-wide on purpose: the reader and the caching service each
     * construct a repository, and two independent caches could overwrite each other's
     * books with a stale list.
     */
    companion object {
        private val lock = Any()
        private var cache: MutableList<Book>? = null
        private var lastWriteAt = 0L
        private var dirty = false
    }

    fun getAllBooks(): MutableList<Book> = synchronized(lock) {
        cache?.let { return it }
        val loaded: MutableList<Book> = if (!manifestFile.exists()) {
            mutableListOf()
        } else {
            try {
                val type = object : TypeToken<MutableList<Book>>() {}.type
                gson.fromJson(manifestFile.readText(), type) ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        }
        cache = loaded
        loaded
    }

    /** Write anything still pending — call when the reader pauses or closes. */
    fun flush() = synchronized(lock) {
        if (dirty) cache?.let { writeNow(it) }
    }

    fun saveBook(book: Book) = synchronized(lock) {
        val books = getAllBooks()
        val index = books.indexOfFirst { it.id == book.id }
        if (index >= 0) {
            books[index] = book
        } else {
            books.add(0, book)
        }
        saveManifest(books)
    }

    fun updateProgress(bookId: String, chapterIndex: Int, sentenceIndex: Int) = synchronized(lock) {
        val books = getAllBooks()
        val book = books.find { it.id == bookId } ?: return
        val chapterChanged = book.currentChapterIndex != chapterIndex
        book.currentChapterIndex = chapterIndex
        book.currentSentenceIndex = sentenceIndex
        book.lastReadTimestamp = System.currentTimeMillis()
        dirty = true
        // Persist chapter turns immediately; sentence ticks at most every 5s
        if (chapterChanged || System.currentTimeMillis() - lastWriteAt > 5_000) {
            writeNow(books)
        }
    }

    /** Same file already in the library? Avoids a second shelf entry for a re-import. */
    fun findDuplicate(title: String, format: BookFormat, fileLength: Long): Book? =
        getAllBooks().firstOrNull { existing ->
            existing.title == title && existing.format == format &&
                File(existing.filePath).let { it.exists() && it.length() == fileLength }
        }

    fun deleteBook(bookId: String) = synchronized(lock) {
        val books = getAllBooks()
        val book = books.find { it.id == bookId }
        if (book != null) {
            val file = File(book.filePath)
            if (file.exists() && file.parentFile == booksDir) {
                file.delete()
            }
            books.remove(book)
            saveManifest(books)
        }
    }

    fun getLastReadBook(): Book? {
        val books = getAllBooks()
        return books.maxByOrNull { it.lastReadTimestamp }
    }

    fun getPersistentFileForBook(originalName: String): File {
        val sanitized = originalName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return File(booksDir, "${System.currentTimeMillis()}_$sanitized")
    }

    private fun saveManifest(books: List<Book>) = writeNow(books)

    private fun writeNow(books: List<Book>) {
        try {
            // Write to a temp file first so a kill mid-write can't truncate the library
            val tmp = File(manifestFile.parentFile, "books_manifest.json.tmp")
            tmp.writeText(gson.toJson(books))
            if (tmp.renameTo(manifestFile)) {
                lastWriteAt = System.currentTimeMillis()
                dirty = false
                return
            }
            manifestFile.writeText(gson.toJson(books))
            lastWriteAt = System.currentTimeMillis()
            dirty = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
