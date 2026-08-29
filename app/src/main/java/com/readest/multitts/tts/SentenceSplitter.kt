package com.readest.multitts.tts

import com.readest.multitts.model.Chapter
import com.readest.multitts.model.SentenceItem

/**
 * Single definition of "what counts as a sentence".
 *
 * Cache keys are derived from sentence text, so the pre-synthesizer and the
 * exporter must split identically or exported chapters would miss audio.
 * Mirrors the regex used by reader.js.
 */
object SentenceSplitter {

    private val REGEX = "([^。！？.!?\\n\\r]+[。！？.!?\\n\\r]*|\\n+)".toRegex()

    fun split(chapter: Chapter): List<SentenceItem> {
        val items = mutableListOf<SentenceItem>()
        var index = 0
        for ((paragraphIndex, paragraph) in chapter.paragraphs.withIndex()) {
            for (match in REGEX.findAll(paragraph)) {
                val text = match.value.trim()
                if (text.isEmpty()) continue
                items.add(SentenceItem(index++, text, paragraphIndex))
            }
        }
        return items
    }
}
