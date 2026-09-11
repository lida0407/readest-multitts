package com.readest.multitts.bundle

/**
 * What a .readest file holds.
 *
 * The point of the format is that importing one gives back a *book* with its
 * narration, reading position and notes — not a folder of audio that a music
 * player would happily play and a reader could do nothing with.
 */
object BookBundle {

    const val EXTENSION = "readest"
    const val MIME = "application/zip"

    /** Bumped when a field stops meaning what it used to. */
    const val FORMAT = 1

    const val MANIFEST = "manifest.json"
    const val BOOK_DIR = "book/"
    const val AUDIO_DIR = "audio/"

    data class SentenceSpan(
        val index: Int,
        val startByte: Long,
        val endByte: Long
    )

    data class ChapterAudio(
        val index: Int,
        val title: String,
        val file: String,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val sentences: List<SentenceSpan>
    )

    data class Note(
        val chapterIndex: Int,
        val chapterTitle: String,
        val sentenceIndex: Int,
        val excerpt: String,
        val isHighlight: Boolean,
        val note: String?,
        val createdAt: Long
    )

    data class Word(
        val word: String,
        val gloss: String,
        val source: String,
        val sentence: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val lookups: Int
    )

    data class Manifest(
        val format: Int,
        val exportedAt: Long,
        val appVersion: String,
        val title: String,
        val author: String,
        val bookFile: String,
        val totalChapters: Int,
        /**
         * The voice the audio was made with. Cache keys include it, so an import
         * has to restore it or every clip would be looked up under the wrong
         * name and re-synthesized.
         */
        val voiceId: String,
        val chapterIndex: Int,
        val sentenceIndex: Int,
        val chapters: List<ChapterAudio>,
        val notes: List<Note>,
        val vocabulary: List<Word>
    )
}
