package com.readest.multitts.model

import java.io.Serializable

data class Bookmark(
    val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val sentenceIndex: Int,
    val excerpt: String,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable
