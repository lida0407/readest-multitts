package com.readest.multitts.model

import java.io.Serializable

/**
 * A place worth coming back to.
 *
 * Highlights and plain bookmarks share this shape because they are the same
 * thing to the reader — a sentence and a way back to it. [isHighlight] only
 * decides whether the page paints it. Both fields default for entries written
 * before they existed, which is what keeps old bookmark files readable.
 */
data class Bookmark(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val sentenceIndex: Int,
    val excerpt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isHighlight: Boolean = false,
    val note: String? = null
) : Serializable
