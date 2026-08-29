package com.readest.multitts.tts

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class TTSLocalAudioCache(private val context: Context) {

    private val cacheDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "tts_audio_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    fun getCacheKey(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voiceId: String,
        rate: Float,
        pitch: Float,
        text: String
    ): String {
        val raw = "${bookId}_c${chapterIndex}_s${sentenceIndex}_${voiceId}_${rate}_${pitch}_$text"
        return sha256(raw)
    }

    private fun bookDir(bookId: String) = File(cacheDir, bookId)

    /**
     * Clips live in a per-chapter folder. Everything used to go into one flat folder
     * per book, which meant tens of thousands of entries in a single directory —
     * every listing or stat over it got slower as the cache grew.
     */
    private fun chapterDir(bookId: String, chapterIndex: Int) = File(bookDir(bookId), "c$chapterIndex")

    /** Path to write a new clip to (creates the directory once per session). */
    fun getAudioFile(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voiceId: String,
        rate: Float,
        pitch: Float,
        text: String
    ): File {
        val key = getCacheKey(bookId, chapterIndex, sentenceIndex, voiceId, rate, pitch, text)
        val dir = chapterDir(bookId, chapterIndex)
        // Checked every time on purpose: the cache can be cleared from the UI or by
        // Android at any moment, and writing into a vanished directory fails silently.
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$key.wav")
    }

    /**
     * An existing clip for this sentence, or null. Falls back to the old flat layout
     * so audio cached by earlier builds keeps playing instead of re-synthesizing.
     */
    fun resolveForRead(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voiceId: String,
        rate: Float,
        pitch: Float,
        text: String
    ): File? {
        val key = getCacheKey(bookId, chapterIndex, sentenceIndex, voiceId, rate, pitch, text)
        val sharded = File(chapterDir(bookId, chapterIndex), "$key.wav")
        if (isUsable(sharded)) return sharded
        val legacy = File(bookDir(bookId), "$key.wav")
        if (isUsable(legacy)) return legacy
        return null
    }

    private fun isUsable(file: File): Boolean = file.exists() && file.length() > 200

    fun isCached(
        bookId: String,
        chapterIndex: Int,
        sentenceIndex: Int,
        voiceId: String,
        rate: Float,
        pitch: Float,
        text: String
    ): Boolean = resolveForRead(bookId, chapterIndex, sentenceIndex, voiceId, rate, pitch, text) != null

    data class BookCache(val bookId: String, val bytes: Long, val fileCount: Int)

    /** One entry per book folder that currently holds cached audio. */
    fun listBookCaches(): List<BookCache> {
        val dirs = cacheDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            var bytes = 0L
            var count = 0
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    count++
                }
            }
            if (count == 0) null else BookCache(dir.name, bytes, count)
        }.sortedByDescending { it.bytes }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * Size of one chapter's clips. Only the chapter's own folder is scanned — this
     * used to walk the entire book folder, which made every chapter boundary during
     * a long caching run slower than the last.
     */
    fun getChapterCachedBytes(bookId: String, chapterIndex: Int): Long {
        val dir = chapterDir(bookId, chapterIndex)
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { f -> total += f.length() }
        return total
    }

    fun getTotalCacheSizeBytes(): Long {
        var total = 0L
        cacheDir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    fun getFormattedCacheSize(): String = formatBytes(getTotalCacheSizeBytes())

    fun clearBookCache(bookId: String) {
        val dir = bookDir(bookId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    fun clearAllCache() {
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
