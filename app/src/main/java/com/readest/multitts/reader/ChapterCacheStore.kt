package com.readest.multitts.reader

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.readest.multitts.model.Chapter
import java.io.File

/**
 * Remembers the parsed chapter list for a book.
 *
 * Opening a large EPUB meant unzipping and parsing every chapter document again —
 * hundreds of files for a long series — which is what made big books feel sluggish.
 * The parse result is written once and reused until the file itself changes.
 */
object ChapterCacheStore {

    private const val TAG = "ChapterCache"

    /** Beyond this the JSON costs more to read than a fresh parse. */
    private const val MAX_CACHE_BYTES = 40L * 1024 * 1024

    private val gson = Gson()

    /** Title and author come from the file's metadata, so they are cached too. */
    data class ParsedBook(
        val title: String,
        val author: String,
        val chapters: List<Chapter>
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "parsed_chapters").apply { if (!exists()) mkdirs() }

    /** File length is part of the name, so replacing the book invalidates the cache. */
    private fun cacheFile(context: Context, bookId: String, source: File): File =
        File(dir(context), "${bookId}_${source.length()}.json")

    fun load(context: Context, bookId: String, source: File): ParsedBook? {
        val file = cacheFile(context, bookId, source)
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<ParsedBook>() {}.type
            val parsed: ParsedBook? = gson.fromJson(file.readText(), type)
            parsed?.takeIf { it.chapters.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable chapter cache; reparsing", e)
            file.delete()
            null
        }
    }

    fun save(context: Context, bookId: String, source: File, parsed: ParsedBook) {
        if (parsed.chapters.isEmpty()) return
        try {
            val json = gson.toJson(parsed)
            if (json.length > MAX_CACHE_BYTES) {
                Log.i(TAG, "Chapter cache skipped for $bookId: ${json.length} bytes")
                return
            }
            // Drop older entries for this book (a re-import changes the length suffix)
            dir(context).listFiles()
                ?.filter { it.name.startsWith("${bookId}_") }
                ?.forEach { it.delete() }

            cacheFile(context, bookId, source).writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Could not write chapter cache", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Book too large to cache chapters")
        }
    }

    fun clear(context: Context, bookId: String) {
        dir(context).listFiles()
            ?.filter { it.name.startsWith("${bookId}_") }
            ?.forEach { it.delete() }
    }
}
