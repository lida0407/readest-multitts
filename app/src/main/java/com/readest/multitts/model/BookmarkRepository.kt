package com.readest.multitts.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

class BookmarkRepository(context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "bookmarks.json")

    fun getAll(): MutableList<Bookmark> {
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Bookmark>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun getForBook(bookId: String): List<Bookmark> =
        getAll().filter { it.bookId == bookId }
            .sortedWith(compareBy({ it.chapterIndex }, { it.sentenceIndex }))

    /**
     * The plain bookmark on a sentence, if any.
     *
     * Highlights are excluded so that toggling the bookmark icon on a sentence
     * you highlighted does not silently delete the highlight.
     */
    fun find(bookId: String, chapterIndex: Int, sentenceIndex: Int): Bookmark? =
        getAll().firstOrNull {
            it.bookId == bookId && it.chapterIndex == chapterIndex &&
                it.sentenceIndex == sentenceIndex && !it.isHighlight
        }

    fun findHighlight(bookId: String, chapterIndex: Int, sentenceIndex: Int): Bookmark? =
        getAll().firstOrNull {
            it.bookId == bookId && it.chapterIndex == chapterIndex &&
                it.sentenceIndex == sentenceIndex && it.isHighlight
        }

    /** Sentence indices the reader should paint in this chapter. */
    fun highlightIndices(bookId: String, chapterIndex: Int): List<Int> =
        getAll().filter { it.bookId == bookId && it.chapterIndex == chapterIndex && it.isHighlight }
            .map { it.sentenceIndex }

    fun addHighlight(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        sentenceIndex: Int,
        excerpt: String,
        note: String? = null
    ): Bookmark {
        val existing = findHighlight(bookId, chapterIndex, sentenceIndex)
        if (existing != null) {
            if (note == null) return existing
            return setNote(existing.id, note) ?: existing
        }
        val highlight = Bookmark(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            sentenceIndex = sentenceIndex,
            excerpt = excerpt.take(300),
            isHighlight = true,
            note = note
        )
        val all = getAll()
        all.add(highlight)
        save(all)
        return highlight
    }

    fun setNote(bookmarkId: String, note: String?): Bookmark? {
        val all = getAll()
        val index = all.indexOfFirst { it.id == bookmarkId }
        if (index < 0) return null
        val updated = all[index].copy(note = note?.takeIf { it.isNotBlank() })
        all[index] = updated
        save(all)
        return updated
    }

    fun add(
        bookId: String,
        chapterIndex: Int,
        chapterTitle: String,
        sentenceIndex: Int,
        excerpt: String
    ): Bookmark {
        val bookmark = Bookmark(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            sentenceIndex = sentenceIndex,
            excerpt = excerpt.take(160)
        )
        val all = getAll()
        all.add(bookmark)
        save(all)
        return bookmark
    }

    fun remove(bookmarkId: String) {
        val all = getAll()
        all.removeAll { it.id == bookmarkId }
        save(all)
    }

    fun removeForBook(bookId: String) {
        val all = getAll()
        all.removeAll { it.bookId == bookId }
        save(all)
    }

    private fun save(list: List<Bookmark>) {
        try {
            file.writeText(gson.toJson(list))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
