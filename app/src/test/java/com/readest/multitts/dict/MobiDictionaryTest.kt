package com.readest.multitts.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the dictionary reader against synthetic MOBI files that cover the parts
 * most likely to be wrong: HUFF/CDIC decoding, the ORTH index, and reads that
 * straddle a text record boundary.
 */
class MobiDictionaryTest {

    private fun fixture(name: String): File {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("missing fixture $name")
        val out = File.createTempFile(name, ".mobi")
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun openIndexed(name: String): MobiDictionary {
        val book = fixture(name)
        val index = File.createTempFile(name, ".idx").also { it.delete() }
        val count = MobiDictionary.buildIndex(book, index) {}
        assertEquals(10, count)
        return MobiDictionary.open(book, index) ?: error("could not open $name")
    }

    @Test
    fun `huff cdic dictionary resolves headwords`() {
        openIndexed("test-dict-huff.mobi").use { dict ->
            assertEquals(10, dict.entryCount)
            val apple = dict.lookup("apple")
            assertNotNull(apple)
            assertTrue(apple!!.html.contains("a round fruit"))

            // Later entries live past the 4096-byte record boundary, which is
            // where an off-by-one in the offset table would show up.
            val jungle = dict.lookup("jungle")
            assertNotNull(jungle)
            assertTrue(jungle!!.html.contains("dense forest"))
        }
    }

    @Test
    fun `uncompressed dictionary resolves headwords`() {
        openIndexed("test-dict-none.mobi").use { dict ->
            assertTrue(dict.lookup("banana")!!.html.contains("curved yellow"))
            assertTrue(dict.lookup("island")!!.html.contains("surrounded by water"))
        }
    }

    @Test
    fun `lookup is case insensitive and strips punctuation`() {
        openIndexed("test-dict-huff.mobi").use { dict ->
            assertNotNull(dict.lookup("Apple"))
            assertNotNull(dict.lookup("APPLE"))
            assertNotNull(dict.lookup("apple,"))
            assertNotNull(dict.lookup("  apple  "))
        }
    }

    @Test
    fun `plural and past forms fall back to the lemma`() {
        openIndexed("test-dict-huff.mobi").use { dict ->
            assertNotNull(dict.lookup("apples"))
            assertNotNull(dict.lookup("dogs"))
        }
    }

    @Test
    fun `missing words return nothing rather than a wrong entry`() {
        openIndexed("test-dict-huff.mobi").use { dict ->
            assertNull(dict.lookup("zebra"))
            assertNull(dict.lookup("aardvark"))
        }
    }

    @Test
    fun `a book without an orth index is rejected with a readable reason`() {
        val book = fixture("test-dict-huff.mobi")
        // Blank the ORTH index pointer, which is what a normal novel looks like.
        val bytes = book.readBytes()
        val recordStart = ((bytes[78].toInt() and 0xFF) shl 24) or
            ((bytes[79].toInt() and 0xFF) shl 16) or
            ((bytes[80].toInt() and 0xFF) shl 8) or (bytes[81].toInt() and 0xFF)
        for (i in 0 until 4) bytes[recordStart + 40 + i] = 0xFF.toByte()
        book.writeBytes(bytes)

        val error = runCatching {
            MobiDictionary.buildIndex(book, File.createTempFile("noindex", ".idx")) {}
        }.exceptionOrNull()
        assertTrue("was: $error", error is DictionaryException)
        assertTrue(error!!.message!!.contains("regular book"))
    }

    @Test
    fun `ordinal-encoded headwords decode through the ORDT table`() {
        // Real Kindle dictionaries store headwords as 2-byte ordinals into an
        // ORDT table (text encoding 65002) rather than as text. Read as bytes
        // they come out as mangled letters interleaved with NULs, the index
        // still looks well-formed, and every single lookup misses.
        openIndexed("test-dict-ordt.mobi").use { dict ->
            assertEquals(10, dict.entryCount)
            val apple = dict.lookup("apple")
            assertNotNull("ORDT headwords did not decode", apple)
            assertEquals("apple", apple!!.headword)
            assertTrue(apple.html.contains("a round fruit"))
            assertNotNull(dict.lookup("jungle"))
            assertNull(dict.lookup("zebra"))
        }
    }

    @Test
    fun `trailing record bytes are stripped before decompression`() {
        // flags bit 0 means the last byte's low 2 bits count trailing filler.
        val data = byteArrayOf(1, 2, 3, 4, 5, 0x02)
        assertEquals(3, MobiDictionary.trimTrailing(data, 1).size)
        // No flags means nothing is removed.
        assertEquals(6, MobiDictionary.trimTrailing(data, 0).size)
    }
}

class HuffCdicUnitTest {

    @Test
    fun `bit reader and phrase expansion round trip`() {
        val book = File.createTempFile("huff", ".mobi")
        javaClass.classLoader!!.getResourceAsStream("test-dict-huff.mobi")!!.use { input ->
            book.outputStream().use { input.copyTo(it) }
        }
        PalmFile(book).use { palm ->
            val codec = HuffCdic.load(palm, 3, 2)
            assertNotNull(codec)
            // The fixture's toy table maps every 8-bit code to its own byte, so a
            // record must decode to exactly the bytes it was built from.
            val record = palm.record(1)!!
            val decoded = codec!!.decompress(record)
            assertEquals(String(record), String(decoded))
        }
    }
}
