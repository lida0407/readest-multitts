package com.readest.multitts.tts

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Where a caching run got to. A whole-book job can take hours, so it has to
 * survive the app being closed, the job being paused, or synthesis failing —
 * and pick up at the next uncached sentence rather than at the beginning.
 */
data class CacheCheckpoint(
    val bookId: String,
    val bookTitle: String,
    val wholeBook: Boolean,
    val chapterIndex: Int,
    val sentenceIndex: Int,
    val processed: Int,
    val total: Int,
    val voiceId: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val percent: Int
        get() = if (total > 0) ((processed.toFloat() / total) * 100).toInt().coerceIn(0, 100) else 0
}

class CacheCheckpointStore(context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "cache_checkpoints.json")

    private fun all(): MutableList<CacheCheckpoint> {
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<CacheCheckpoint>>() {}.type
            gson.fromJson(file.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun get(bookId: String): CacheCheckpoint? = all().firstOrNull { it.bookId == bookId }

    fun save(checkpoint: CacheCheckpoint) {
        val list = all()
        list.removeAll { it.bookId == checkpoint.bookId }
        list.add(checkpoint)
        write(list)
    }

    fun clear(bookId: String) {
        val list = all()
        if (list.removeAll { it.bookId == bookId }) write(list)
    }

    private fun write(list: List<CacheCheckpoint>) {
        try {
            file.writeText(gson.toJson(list))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
