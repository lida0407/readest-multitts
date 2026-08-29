package com.readest.multitts.model

import java.io.Serializable

data class Chapter(
    val index: Int,
    val title: String,
    val paragraphs: List<String>,
    var cachedSentenceCount: Int = 0,
    var totalSentenceCount: Int = 0
) : Serializable

data class SentenceItem(
    val index: Int,
    val text: String,
    val paragraphIndex: Int,
    var isCached: Boolean = false,
    var cachedAudioPath: String? = null
) : Serializable
