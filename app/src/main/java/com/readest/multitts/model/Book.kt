package com.readest.multitts.model

import java.io.Serializable

enum class BookFormat {
    TXT, EPUB, MOBI, PDF, UNKNOWN
}

data class Book(
    val id: String,
    val title: String,
    val author: String = "Unknown Author",
    val format: BookFormat,
    val filePath: String,
    var currentChapterIndex: Int = 0,
    var currentSentenceIndex: Int = 0,
    var totalChapters: Int = 1,
    var lastReadTimestamp: Long = System.currentTimeMillis(),
    var cachedAudioBytes: Long = 0L
) : Serializable
