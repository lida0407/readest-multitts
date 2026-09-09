package com.readest.multitts.vocab

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every word looked up while reading, with what the dictionary said and the
 * sentence it came from.
 *
 * The app already counted look-ups towards experience and then discarded the
 * word itself; keeping it is what turns a dictionary into a notebook. Entries
 * are keyed by the word alone, so looking the same word up again refreshes the
 * gloss and bumps the count rather than filling the list with duplicates.
 */
class VocabStore(context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "vocabulary.json")

    data class Entry(
        val word: String,
        /** Plain text, not the dictionary's HTML — the notebook is for reading. */
        val gloss: String,
        /** Which dictionary answered, or "Google Translate". */
        val source: String,
        /** The sentence the word was read in. This is what makes it memorable. */
        val sentence: String,
        val bookId: String?,
        val bookTitle: String?,
        val firstSeen: Long,
        var lastSeen: Long,
        var lookups: Int
    )

    @Synchronized
    fun all(): MutableList<Entry> {
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Entry>>() {}.type
            gson.fromJson<MutableList<Entry>>(file.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable vocabulary file", e)
            mutableListOf()
        }
    }

    /** Newest first: the notebook is a record of what you just read. */
    fun recent(): List<Entry> = all().sortedByDescending { it.lastSeen }

    fun count(): Int = all().size

    @Synchronized
    fun record(
        word: String,
        gloss: String,
        source: String,
        sentence: String,
        bookId: String?,
        bookTitle: String?
    ) {
        val trimmed = word.trim()
        if (trimmed.isEmpty() || gloss.isBlank()) return
        val short = trimGloss(trimmed, gloss)
        val now = System.currentTimeMillis()
        val list = all()
        val existing = list.indexOfFirst { it.word.equals(trimmed, ignoreCase = true) }
        if (existing >= 0) {
            val old = list[existing]
            list[existing] = old.copy(
                // A later look-up may be from a better dictionary, or a
                // different book — keep the newer context.
                gloss = short,
                source = source,
                sentence = sentence.ifBlank { old.sentence },
                bookId = bookId ?: old.bookId,
                bookTitle = bookTitle ?: old.bookTitle,
                lastSeen = now,
                lookups = old.lookups + 1
            )
        } else {
            list.add(
                Entry(
                    word = trimmed,
                    gloss = short,
                    source = source,
                    sentence = sentence,
                    bookId = bookId,
                    bookTitle = bookTitle,
                    firstSeen = now,
                    lastSeen = now,
                    lookups = 1
                )
            )
        }
        save(list)
    }

    @Synchronized
    fun remove(word: String) {
        val list = all()
        if (list.removeAll { it.word.equals(word, ignoreCase = true) }) save(list)
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    fun search(query: String): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return recent()
        return recent().filter {
            it.word.contains(q, ignoreCase = true) ||
                it.gloss.contains(q, ignoreCase = true) ||
                (it.bookTitle?.contains(q, ignoreCase = true) == true)
        }
    }

    /**
     * Anki's importer wants tab-separated fields and no header; a spreadsheet
     * wants commas and a header. Both are one file, so the notebook exports the
     * shape the destination asks for rather than a lowest common denominator.
     */
    enum class Format(val extension: String, val mime: String) {
        CSV("csv", "text/csv"),
        ANKI("txt", "text/tab-separated-values")
    }

    fun export(format: Format): String = render(recent(), format)

    private fun save(list: List<Entry>) {
        try {
            file.writeText(gson.toJson(list))
        } catch (e: Exception) {
            Log.w(TAG, "Could not save vocabulary", e)
        }
    }

    companion object {

        private const val TAG = "VocabStore"

        /**
         * Dictionary entries open by repeating the headword, which in a list
         * that already shows the word costs the most valuable line — the one
         * before the reader stops reading.
         */
        fun trimGloss(word: String, gloss: String): String {
            val lines = gloss.lines()
            val first = lines.firstOrNull()?.trim().orEmpty()
            val rest = if (first.equals(word, ignoreCase = true)) lines.drop(1) else lines
            return rest.joinToString("\n") { it.trim() }
                .trim()
                .ifBlank { gloss.trim() }
        }

        /** Pure, so the shape of an export can be tested without a filesystem. */
        fun render(rows: List<Entry>, format: Format): String {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return when (format) {
                // Front, back, then the sentence as a third field to hang a
                // cloze or an example on.
                Format.ANKI -> rows.joinToString("\n") { e ->
                    listOf(e.word, e.gloss, e.sentence, e.bookTitle ?: "")
                        .joinToString("\t") { it.replace("\t", " ").replace("\n", " ") }
                }

                Format.CSV -> buildString {
                    append("word,definition,sentence,book,source,lookups,first seen,last seen\n")
                    for (e in rows) {
                        append(
                            listOf(
                                e.word, e.gloss, e.sentence, e.bookTitle ?: "", e.source,
                                e.lookups.toString(),
                                date.format(Date(e.firstSeen)), date.format(Date(e.lastSeen))
                            ).joinToString(",") { csv(it) }
                        )
                        append("\n")
                    }
                }
            }
        }

        private fun csv(value: String): String =
            "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
    }
}
