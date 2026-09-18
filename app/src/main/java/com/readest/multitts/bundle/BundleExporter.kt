package com.readest.multitts.bundle

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.readest.multitts.model.Book
import com.readest.multitts.model.BookmarkRepository
import com.readest.multitts.model.Chapter
import com.readest.multitts.tts.CacheResolver
import com.readest.multitts.tts.SentenceSplitter
import com.readest.multitts.tts.TTSLocalAudioCache
import com.readest.multitts.tts.WavFile
import com.readest.multitts.vocab.VocabStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a book, its narration and everything you did with it into one file.
 *
 * Chapters are encoded one at a time and streamed straight into the zip, so a
 * long book never needs more than one chapter of working space — the whole
 * point being a file small enough to put in Drive.
 */
object BundleExporter {

    private const val TAG = "BundleExporter"

    interface Progress {
        fun onChapter(done: Int, total: Int, title: String)
        fun onDone(bytes: Long)
        fun onError(message: String)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** A filename that will still say what it is a year from now. */
    fun fileNameFor(book: Book): String {
        val safe = book.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60)
        return "${if (safe.isBlank()) "book" else safe}.${BookBundle.EXTENSION}"
    }

    fun export(
        context: Context,
        book: Book,
        chapters: List<Chapter>,
        voiceId: String,
        audioCache: TTSLocalAudioCache,
        bookmarks: BookmarkRepository,
        vocab: VocabStore,
        appVersion: String,
        output: OutputStream,
        progress: Progress
    ) {
        cancelled = false
        val source = File(book.filePath)
        if (!source.exists()) {
            progress.onError("The book file is missing on this device")
            return
        }

        val work = File(context.cacheDir, "bundle_work").apply { mkdirs() }
        val track = File(work, "chapter.m4a")
        var written = 0L

        try {
            ZipOutputStream(output.buffered()).use { zip ->
                val entries = mutableListOf<BookBundle.ChapterAudio>()

                zip.putNextEntry(ZipEntry(BookBundle.BOOK_DIR + source.name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                // Only chapters with audio are worth encoding, and a folder
                // listing says which those are without a stat per sentence.
                val withAudio = audioCache.chaptersWithAudio(book.id)
                val work = if (withAudio == null) chapters else chapters.filter { it.index in withAudio }

                work.forEachIndexed { position, chapter ->
                    if (cancelled) return@forEachIndexed
                    progress.onChapter(position, work.size, chapter.title)

                    val clips = clipsFor(audioCache, book, chapter, voiceId)
                    if (clips.isEmpty()) return@forEachIndexed

                    track.delete()
                    val encoded = ChapterTrack.encode(clips, track) { cancelled } ?: return@forEachIndexed

                    val name = "${BookBundle.AUDIO_DIR}c${chapter.index}.m4a"
                    zip.putNextEntry(ZipEntry(name))
                    track.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    written += track.length()

                    entries.add(
                        BookBundle.ChapterAudio(
                            index = chapter.index,
                            title = chapter.title,
                            file = name,
                            sampleRate = encoded.sampleRate,
                            channels = encoded.channels,
                            bitsPerSample = encoded.bitsPerSample,
                            sentences = encoded.boundaries.map {
                                BookBundle.SentenceSpan(it.sentenceIndex, it.startByte, it.endByte)
                            }
                        )
                    )
                }

                if (cancelled) {
                    progress.onError("Export stopped")
                    return
                }

                val manifest = BookBundle.Manifest(
                    format = BookBundle.FORMAT,
                    exportedAt = System.currentTimeMillis(),
                    appVersion = appVersion,
                    title = book.title,
                    author = book.author,
                    bookFile = BookBundle.BOOK_DIR + source.name,
                    totalChapters = chapters.size,
                    voiceId = voiceId,
                    chapterIndex = book.currentChapterIndex,
                    sentenceIndex = book.currentSentenceIndex,
                    chapters = entries,
                    notes = bookmarks.getForBook(book.id).map {
                        BookBundle.Note(
                            it.chapterIndex, it.chapterTitle, it.sentenceIndex,
                            it.excerpt, it.isHighlight, it.note, it.createdAt
                        )
                    },
                    vocabulary = vocab.all().filter { it.bookId == book.id }.map {
                        BookBundle.Word(
                            it.word, it.gloss, it.source, it.sentence,
                            it.firstSeen, it.lastSeen, it.lookups
                        )
                    }
                )

                zip.putNextEntry(ZipEntry(BookBundle.MANIFEST))
                zip.write(Gson().toJson(manifest).toByteArray())
                zip.closeEntry()
            }
            progress.onDone(written)
        } catch (e: Throwable) {
            Log.w(TAG, "Bundle export failed", e)
            progress.onError(e.message ?: "Export failed")
        } finally {
            work.deleteRecursively()
        }
    }

    /** The cached clips for a chapter, paired with the sentence each belongs to. */
    private fun clipsFor(
        cache: TTSLocalAudioCache,
        book: Book,
        chapter: Chapter,
        voiceId: String
    ): List<Pair<Int, File>> = SentenceSplitter.split(chapter).mapNotNull { item ->
        cache.resolveForRead(
            bookId = book.id,
            chapterIndex = chapter.index,
            sentenceIndex = item.index,
            voiceId = voiceId,
            rate = 1.0f,
            pitch = 1.0f,
            text = item.text
        )?.let { item.index to it }
    }

    /**
     * Rough size of the bundle, so the reader can decide before waiting for it.
     *
     * Measured from folder sizes rather than by resolving each sentence: on a
     * 600-chapter book the per-sentence way took the better part of a minute
     * with nothing on screen, to produce a number that is an estimate anyway.
     */
    fun estimateBytes(
        cache: TTSLocalAudioCache,
        book: Book,
        chapters: List<Chapter>,
        voiceId: String
    ): Long {
        val withAudio = cache.chaptersWithAudio(book.id)
        val audio = if (withAudio == null) {
            CacheResolver.bookAudio(cache, book, chapters, voiceId).sumOf { it.bytes }
        } else {
            withAudio.sumOf { cache.chapterFolderBytes(book.id, it) }
        }
        val bookBytes = File(book.filePath).let { if (it.exists()) it.length() else 0L }
        return bookBytes + (audio * compressionRatio(cache, book, withAudio)).toLong()
    }

    /**
     * AAC at 64 kbps against the clips' own PCM rate.
     *
     * Read from a real clip because the ratio depends on the voice: 24 kHz mono
     * compresses about six times, 16 kHz about four. Assuming a fixed twentieth
     * had a 3.5 GB book announcing itself as 175 MB and arriving at 600.
     */
    private fun compressionRatio(cache: TTSLocalAudioCache, book: Book, chapters: Set<Int>?): Double {
        val sample = chapters?.firstOrNull()?.let { cache.anyClip(book.id, it) }
        val info = sample?.let { WavFile.read(it) }
        val pcmBytesPerSecond = if (info != null) {
            info.sampleRate * info.channels * (info.bitsPerSample / 8)
        } else {
            24_000 * 2
        }
        return (64_000 / 8.0) / pcmBytesPerSecond
    }
}
