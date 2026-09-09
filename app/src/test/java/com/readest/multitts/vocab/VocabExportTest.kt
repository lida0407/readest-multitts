package com.readest.multitts.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export has to survive the punctuation that real dictionary entries and
 * real sentences are full of: commas, quotes and newlines.
 */
class VocabExportTest {

    private fun entry(
        word: String = "wilderness",
        gloss: String = "n. 荒野;沙漠",
        sentence: String = "He was placed in a dungeon.",
        book: String? = "Monte Cristo"
    ) = VocabStore.Entry(
        word = word,
        gloss = gloss,
        source = "Collins",
        sentence = sentence,
        bookId = "b1",
        bookTitle = book,
        firstSeen = 0L,
        lastSeen = 0L,
        lookups = 1
    )

    @Test
    fun `csv quotes every field so commas cannot split a row`() {
        val csv = VocabStore.render(listOf(entry(gloss = "one, two, three")), VocabStore.Format.CSV)
        val rows = csv.trim().lines()
        assertEquals("header plus one row", 2, rows.size)
        assertTrue("gloss stays in one field", rows[1].contains("\"one, two, three\""))
    }

    @Test
    fun `csv doubles embedded quotes`() {
        val csv = VocabStore.render(listOf(entry(gloss = "said \"no\"")), VocabStore.Format.CSV)
        assertTrue(csv.contains("\"said \"\"no\"\"\""))
    }

    @Test
    fun `a newline inside a definition cannot break the row count`() {
        val csv = VocabStore.render(
            listOf(entry(gloss = "line one\nline two")),
            VocabStore.Format.CSV
        )
        assertEquals("header plus one row", 2, csv.trim().lines().size)
    }

    @Test
    fun `anki rows are four tab separated fields`() {
        val anki = VocabStore.render(listOf(entry()), VocabStore.Format.ANKI)
        assertEquals(4, anki.split("\t").size)
        assertTrue(anki.startsWith("wilderness\t"))
    }

    @Test
    fun `a tab in a definition would otherwise invent a fifth anki field`() {
        val anki = VocabStore.render(listOf(entry(gloss = "a\tb")), VocabStore.Format.ANKI)
        assertEquals(4, anki.split("\t").size)
    }

    @Test
    fun `a book that was deleted leaves the column empty rather than null`() {
        val anki = VocabStore.render(listOf(entry(book = null)), VocabStore.Format.ANKI)
        assertTrue(anki.endsWith("\t"))
        val csv = VocabStore.render(listOf(entry(book = null)), VocabStore.Format.CSV)
        assertTrue(csv.contains(",\"\","))
    }

    @Test
    fun `a gloss that opens by repeating the headword loses that line`() {
        val trimmed = VocabStore.trimGloss("wilderness", "wilderness\n[D.J.'wildənis]\nn. 荒野")
        assertTrue("keeps the pronunciation", trimmed.startsWith("[D.J."))
        assertTrue("keeps the meaning", trimmed.contains("荒野"))
    }

    @Test
    fun `a gloss that does not repeat the headword is untouched`() {
        val trimmed = VocabStore.trimGloss("run", "v. 跑, 奔跑")
        assertEquals("v. 跑, 奔跑", trimmed)
    }

    @Test
    fun `a gloss that is only the headword is kept rather than emptied`() {
        assertEquals("run", VocabStore.trimGloss("run", "run"))
    }

    @Test
    fun `an empty notebook still writes a usable csv header`() {
        val csv = VocabStore.render(emptyList(), VocabStore.Format.CSV)
        assertTrue(csv.startsWith("word,definition,"))
        assertEquals("", VocabStore.render(emptyList(), VocabStore.Format.ANKI))
    }
}
