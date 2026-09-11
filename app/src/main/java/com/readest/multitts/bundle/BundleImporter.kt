package com.readest.multitts.bundle

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.readest.multitts.model.Book
import com.readest.multitts.model.BookRepository
import com.readest.multitts.model.BookmarkRepository
import com.readest.multitts.reader.DocumentManager
import com.readest.multitts.tts.SentenceSplitter
import com.readest.multitts.tts.TTSLocalAudioCache
import com.readest.multitts.vocab.VocabStore
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Restores a book, its narration, and what you did with it.
 *
 * The chapter tracks are sliced back into per-sentence clips under the cache
 * keys this device would have written itself, which is what makes the restored
 * book behave exactly like one narrated here: the scrubber, the cached-ahead
 * track and 0% CPU playback all work without knowing a bundle was involved.
 */
object BundleImporter {

    private const val TAG = "BundleImporter"

    interface Progress {
        fun onStage(message: String)
        fun onChapter(done: Int, total: Int)
        fun onDone(book: Book, clips: Int)
        fun onError(message: String)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** True when this looks like one of ours, so a plain audio file is refused. */
    fun looksLikeBundle(name: String?): Boolean =
        name?.endsWith(".${BookBundle.EXTENSION}", ignoreCase = true) == true

    /**
     * Looks inside when the name does not say.
     *
     * A cloud provider is free to hand over a document with no usable filename,
     * and an EPUB is a zip too — only the manifest tells the two apart.
     */
    fun containsManifest(open: () -> InputStream?): Boolean = try {
        open()?.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var scanned = 0
                var found = false
                while (scanned < 64) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == BookBundle.MANIFEST) {
                        found = true
                        break
                    }
                    zip.closeEntry()
                    scanned++
                }
                found
            }
        } == true
    } catch (e: Throwable) {
        false
    }

    fun import(
        context: Context,
        input: InputStream,
        bookRepository: BookRepository,
        bookmarks: BookmarkRepository,
        vocab: VocabStore,
        audioCache: TTSLocalAudioCache,
        onVoiceRestored: (bookId: String, voiceId: String) -> Unit,
        progress: Progress
    ) {
        cancelled = false
        val work = File(context.cacheDir, "bundle_import").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            progress.onStage("Unpacking…")
            var manifest: BookBundle.Manifest? = null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (cancelled) return
                    val name = entry.name
                    // A zip can name an entry anything it likes, including a
                    // path that walks out of the directory it is unpacked into.
                    val target = File(work, name).canonicalFile
                    if (!target.path.startsWith(work.canonicalFile.path + File.separator)) {
                        Log.w(TAG, "Refusing entry outside the work directory: $name")
                        zip.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        if (name == BookBundle.MANIFEST) {
                            manifest = runCatching {
                                Gson().fromJson(target.readText(), BookBundle.Manifest::class.java)
                            }.getOrNull()
                        }
                    }
                    zip.closeEntry()
                }
            }

            val found = manifest
            if (found == null) {
                progress.onError("That file isn't a Readest bundle")
                return
            }
            if (found.format > BookBundle.FORMAT) {
                progress.onError("That bundle was made by a newer version of the app")
                return
            }

            val bookFile = File(work, found.bookFile)
            if (!bookFile.exists()) {
                progress.onError("The bundle has no book in it")
                return
            }

            progress.onStage("Adding ${found.title}…")
            val persistent = bookRepository.getPersistentFileForBook(bookFile.name)
            bookFile.inputStream().use { source ->
                persistent.outputStream().use { source.copyTo(it) }
            }
            val (parsed, parsedChapters) = DocumentManager.loadBookCached(context, persistent)

            // Re-importing a bundle for a book already on the shelf should refill
            // that book's audio rather than adding a second copy of it.
            val existing = bookRepository.findDuplicate(parsed.title, parsed.format, persistent.length())
            val reuse = existing != null && existing.filePath != persistent.absolutePath
            if (reuse) persistent.delete()
            val imported = if (reuse) existing!! else parsed.also { bookRepository.saveBook(it) }

            // Cache keys are derived from the book's own text, so the chapters
            // have to come from the copy that stays on disk.
            val chapters = if (reuse) {
                DocumentManager.loadBookCached(context, File(imported.filePath)).second
            } else {
                parsedChapters
            }
            onVoiceRestored(imported.id, found.voiceId)

            var clips = 0
            found.chapters.forEachIndexed { position, chapterAudio ->
                if (cancelled) return
                progress.onChapter(position, found.chapters.size)
                val track = File(work, chapterAudio.file)
                if (!track.exists()) return@forEachIndexed
                val chapter = chapters.getOrNull(chapterAudio.index) ?: return@forEachIndexed
                val sentences = SentenceSplitter.split(chapter).associateBy { it.index }

                clips += ChapterTrack.decodeInto(
                    track = track,
                    boundaries = chapterAudio.sentences.map {
                        ChapterTrack.Boundary(it.index, it.startByte, it.endByte)
                    },
                    sampleRate = chapterAudio.sampleRate,
                    channels = chapterAudio.channels,
                    bitsPerSample = chapterAudio.bitsPerSample,
                    destination = { sentenceIndex ->
                        val text = sentences[sentenceIndex]?.text ?: return@decodeInto null
                        audioCache.getAudioFile(
                            bookId = imported.id,
                            chapterIndex = chapterAudio.index,
                            sentenceIndex = sentenceIndex,
                            voiceId = found.voiceId,
                            rate = 1.0f,
                            pitch = 1.0f,
                            text = text
                        )
                    },
                    isCancelled = { cancelled }
                )
            }
            audioCache.invalidateTotalCacheSize()

            progress.onStage("Restoring notes…")
            for (note in found.notes) {
                if (note.isHighlight) {
                    bookmarks.addHighlight(
                        bookId = imported.id,
                        chapterIndex = note.chapterIndex,
                        chapterTitle = note.chapterTitle,
                        sentenceIndex = note.sentenceIndex,
                        excerpt = note.excerpt,
                        note = note.note
                    )
                } else {
                    bookmarks.add(
                        bookId = imported.id,
                        chapterIndex = note.chapterIndex,
                        chapterTitle = note.chapterTitle,
                        sentenceIndex = note.sentenceIndex,
                        excerpt = note.excerpt
                    )
                }
            }
            for (word in found.vocabulary) {
                vocab.record(
                    word = word.word,
                    gloss = word.gloss,
                    source = word.source,
                    sentence = word.sentence,
                    bookId = imported.id,
                    bookTitle = found.title
                )
            }

            bookRepository.updateProgress(imported.id, found.chapterIndex, found.sentenceIndex)
            progress.onDone(
                imported.copy(
                    currentChapterIndex = found.chapterIndex,
                    currentSentenceIndex = found.sentenceIndex
                ),
                clips
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Bundle import failed", e)
            progress.onError(e.message ?: "Import failed")
        } finally {
            work.deleteRecursively()
        }
    }
}
