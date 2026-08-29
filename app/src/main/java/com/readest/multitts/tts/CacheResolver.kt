package com.readest.multitts.tts

import com.readest.multitts.model.Book
import com.readest.multitts.model.Chapter
import java.io.File

/**
 * Cache files are named by a hash of (book, chapter, sentence, voice, text), so
 * the folder alone can't tell you what's inside it. This recomputes those keys
 * from the parsed book to find what is cached — including working out which
 * voice a book was cached with, since that is part of the key.
 */
object CacheResolver {

    data class ChapterAudio(
        val chapterIndex: Int,
        val title: String,
        val files: List<File>,
        val cachedCount: Int,
        val totalSentences: Int
    ) {
        val isComplete: Boolean get() = totalSentences > 0 && cachedCount == totalSentences
        val bytes: Long get() = files.sumOf { it.length() }
    }

    /**
     * Pick the voice that actually matches the files on disk. Users change voices,
     * and audio cached with the old one is still perfectly playable.
     */
    fun detectVoice(
        cache: TTSLocalAudioCache,
        book: Book,
        chapters: List<Chapter>,
        candidateVoices: List<String>
    ): String? {
        // Probe a handful of sentences spread across the book
        val probes = mutableListOf<Triple<Int, Int, String>>()
        for (chapter in chapters) {
            for (item in SentenceSplitter.split(chapter).take(4)) {
                probes.add(Triple(chapter.index, item.index, item.text))
            }
            if (probes.size >= 16) break
        }
        if (probes.isEmpty()) return null

        var best: String? = null
        var bestHits = 0
        for (voice in candidateVoices.distinct()) {
            val hits = probes.count { (chapterIndex, sentenceIndex, text) ->
                cache.isCached(book.id, chapterIndex, sentenceIndex, voice, 1.0f, 1.0f, text)
            }
            if (hits > bestHits) {
                bestHits = hits
                best = voice
            }
        }
        return if (bestHits > 0) best else null
    }

    fun chapterAudio(
        cache: TTSLocalAudioCache,
        book: Book,
        chapter: Chapter,
        voiceId: String
    ): ChapterAudio {
        val sentences = SentenceSplitter.split(chapter)
        val files = mutableListOf<File>()
        for (item in sentences) {
            cache.resolveForRead(
                bookId = book.id,
                chapterIndex = chapter.index,
                sentenceIndex = item.index,
                voiceId = voiceId,
                rate = 1.0f,
                pitch = 1.0f,
                text = item.text
            )?.let { files.add(it) }
        }
        return ChapterAudio(chapter.index, chapter.title, files, files.size, sentences.size)
    }

    fun bookAudio(
        cache: TTSLocalAudioCache,
        book: Book,
        chapters: List<Chapter>,
        voiceId: String
    ): List<ChapterAudio> = chapters.map { chapterAudio(cache, book, it, voiceId) }
}
