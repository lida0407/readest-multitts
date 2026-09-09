package com.readest.multitts.reader

import com.readest.multitts.model.Book
import com.readest.multitts.model.BookFormat
import com.readest.multitts.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Search results are only useful if the sentence index they carry is the one
 * the reader will scroll to, and if the snippet shows the match rather than
 * the first sixty characters of a long paragraph.
 */
class BookSearchTest {

    private val book = Book(
        id = "b1",
        title = "Test Book",
        author = "",
        format = BookFormat.TXT,
        filePath = "/dev/null",
        totalChapters = 1
    )

    private fun chapter(vararg paragraphs: String) =
        Chapter(index = 0, title = "One", paragraphs = paragraphs.toList())

    private fun find(query: String, vararg paragraphs: String): List<BookSearch.Hit> {
        val hits = mutableListOf<BookSearch.Hit>()
        BookSearch.inChapters(book, listOf(chapter(*paragraphs)), query, AtomicBoolean(false)) {
            hits.add(it)
        }
        return hits
    }

    @Test
    fun `finds a phrase regardless of case`() {
        val hits = find("dungeon", "He was placed in a Dungeon that night.")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].sentenceIndex)
    }

    @Test
    fun `the sentence index matches the splitter the reader uses`() {
        val hits = find("third", "One. Two. The third one.")
        assertEquals(1, hits.size)
        // "One.", "Two.", "The third one." — the match is in the third.
        assertEquals(2, hits[0].sentenceIndex)
    }

    @Test
    fun `the snippet is centred on the match, not the start of the sentence`() {
        val long = "x".repeat(400) + " needle " + "y".repeat(400)
        val hits = find("needle", long)
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertTrue("snippet should be trimmed", hit.snippet.length < long.length)
        assertTrue("snippet contains the match", hit.snippet.contains("needle"))
        assertEquals(
            "match offset points at the match",
            "needle",
            hit.snippet.substring(hit.matchStart, hit.matchStart + hit.matchLength)
        )
    }

    @Test
    fun `an unclipped short sentence still reports a usable offset`() {
        val hits = find("cat", "The cat sat.")
        val hit = hits[0]
        assertEquals("cat", hit.snippet.substring(hit.matchStart, hit.matchStart + hit.matchLength))
    }

    @Test
    fun `a one character query is ignored rather than matching everything`() {
        assertTrue(find("a", "a a a a a").isEmpty())
    }

    @Test
    fun `cancelling stops the scan`() {
        val cancelled = AtomicBoolean(true)
        val hits = mutableListOf<BookSearch.Hit>()
        BookSearch.inChapters(book, listOf(chapter("needle needle")), "needle", cancelled) {
            hits.add(it)
        }
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `chinese text matches without needing spaces`() {
        val hits = find("荒野", "他走进了荒野。然后回来了。")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].sentenceIndex)
    }
}
