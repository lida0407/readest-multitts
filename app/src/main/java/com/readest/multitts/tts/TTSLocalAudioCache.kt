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

    @Volatile
    private var totalBytes: Long = -1L

    /**
     * Total size on disk, remembered until something invalidates it.
     *
     * The walk is over tens of thousands of files — 42k on the author's phone —
     * so it must never run on the main thread, and it should not run twice for
     * one screen. [peekTotalCacheSizeBytes] lets a caller show the last known
     * figure immediately and refresh in the background.
     */
    fun getTotalCacheSizeBytes(): Long {
        var total = 0L
        cacheDir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        totalBytes = total
        return total
    }

    /** The last measured total, or null if it has never been measured. */
    fun peekTotalCacheSizeBytes(): Long? = totalBytes.takeIf { it >= 0 }

    /** Call after anything that adds or removes cached audio. */
    fun invalidateTotalCacheSize() {
        totalBytes = -1L
    }

    fun getFormattedCacheSize(): String = formatBytes(getTotalCacheSizeBytes())

    /** How much of one chapter is cached, and how big it is. */
    data class ChapterStatus(
        val cached: Int,
        val total: Int,
        val bytes: Long,
        /**
         * Everything in the chapter's folder, whatever voice made it. Clips in
         * another voice are invisible to playback but still take space, and the
         * only way to reclaim it is to be able to see it.
         */
        val diskBytes: Long = bytes
    ) {
        val isComplete: Boolean get() = total > 0 && cached >= total
        val isEmpty: Boolean get() = cached == 0
        val hasOrphans: Boolean get() = diskBytes > bytes
        val percent: Int get() = if (total == 0) 0 else (cached * 100 / total)
    }

    /**
     * Cached sentences in one chapter, checked against a directory listing
     * rather than a stat per clip.
     *
     * A long book is tens of thousands of clips; asking the filesystem about
     * each one is what made the chapter list slow to open. One listing per
     * chapter and a hash lookup per sentence is the same answer, quickly.
     * Clips written under a different voice are not counted, because playback
     * would not find them either.
     */
    fun chapterStatus(
        bookId: String,
        chapterIndex: Int,
        voiceId: String,
        sentences: List<Pair<Int, String>>,
        legacyNames: Set<String> = emptySet()
    ): ChapterStatus {
        val dir = chapterDir(bookId, chapterIndex)
        val files = dir.listFiles() ?: emptyArray()
        val names = files.mapTo(HashSet(files.size)) { it.name }
        val folderBytes = files.sumOf { it.length() }

        var cached = 0
        var bytes = 0L
        var legacyBytes = 0L
        for ((sentenceIndex, text) in sentences) {
            val name = getCacheKey(bookId, chapterIndex, sentenceIndex, voiceId, 1.0f, 1.0f, text) + ".wav"
            when {
                name in names -> {
                    cached++
                    bytes += File(dir, name).length()
                }
                name in legacyNames -> {
                    // Loose in the book folder, from before chapters had folders.
                    val size = File(bookDir(bookId), name).length()
                    cached++
                    bytes += size
                    legacyBytes += size
                }
            }
        }
        return ChapterStatus(cached, sentences.size, bytes, folderBytes + legacyBytes)
    }

    /**
     * Chapters that have any audio at all, from one listing of the book folder.
     *
     * Null means "can't tell": clips from before chapters had folders sit loose
     * in the book folder with nothing in their name saying which chapter they
     * belong to, so a caller has to fall back to checking every chapter.
     */
    fun chaptersWithAudio(bookId: String): Set<Int>? {
        val entries = bookDir(bookId).listFiles() ?: return emptySet()
        if (entries.any { it.isFile && it.name.endsWith(".wav") }) return null
        return entries.mapNotNullTo(HashSet()) { f ->
            if (f.isDirectory && f.name.startsWith("c")) f.name.drop(1).toIntOrNull() else null
        }
    }

    /** Any one clip from a chapter, to read the format audio was made in. */
    fun anyClip(bookId: String, chapterIndex: Int): File? =
        chapterDir(bookId, chapterIndex).listFiles()?.firstOrNull { it.isFile && it.length() > 44 }

    /** Bytes in one chapter's folder, whatever voice made them. */
    fun chapterFolderBytes(bookId: String, chapterIndex: Int): Long =
        chapterDir(bookId, chapterIndex).listFiles()?.sumOf { it.length() } ?: 0L

    /** Clips from before the per-chapter layout, which sit loose in the book folder. */
    fun legacyNames(bookId: String): Set<String> =
        bookDir(bookId).listFiles()?.filter { it.isFile }?.map { it.name }?.toHashSet() ?: emptySet()

    /**
     * Deletes one chapter's audio and reports what it freed.
     *
     * The whole chapter folder goes, whatever voice made it — the reader asked
     * for the chapter to be gone, not for one voice's copy of it. Loose clips
     * from the old flat layout can only be matched by key, so those need the
     * sentences.
     */
    fun clearChapter(
        bookId: String,
        chapterIndex: Int,
        voiceId: String,
        sentences: List<Pair<Int, String>>
    ): Long {
        var freed = 0L
        val dir = chapterDir(bookId, chapterIndex)
        dir.listFiles()?.forEach { freed += it.length() }
        dir.deleteRecursively()

        val book = bookDir(bookId)
        for ((sentenceIndex, text) in sentences) {
            val legacy = File(book, getCacheKey(bookId, chapterIndex, sentenceIndex, voiceId, 1.0f, 1.0f, text) + ".wav")
            if (legacy.exists()) {
                freed += legacy.length()
                legacy.delete()
            }
        }
        invalidateTotalCacheSize()
        return freed
    }

    fun clearBookCache(bookId: String) {
        invalidateTotalCacheSize()
        val dir = bookDir(bookId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    fun clearAllCache() {
        invalidateTotalCacheSize()
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
