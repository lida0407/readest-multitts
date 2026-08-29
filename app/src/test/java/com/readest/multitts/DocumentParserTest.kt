package com.readest.multitts

import com.readest.multitts.model.BookFormat
import com.readest.multitts.reader.DocumentManager
import com.readest.multitts.reader.EncodingDetector
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParserTest {

    @Test
    fun testDetermineFormat() {
        assertEquals(BookFormat.TXT, DocumentManager.determineFormat(File("sample.txt")))
        assertEquals(BookFormat.EPUB, DocumentManager.determineFormat(File("sample.epub")))
        assertEquals(BookFormat.MOBI, DocumentManager.determineFormat(File("sample.mobi")))
        assertEquals(BookFormat.MOBI, DocumentManager.determineFormat(File("sample.azw3")))
        assertEquals(BookFormat.PDF, DocumentManager.determineFormat(File("sample.pdf")))
    }

    @Test
    fun testTxtChapterSplitting() {
        val tempFile = File.createTempFile("test_book", ".txt")
        tempFile.writeText(
            """
            第一章 启程
            这是第一章的第一句话。这是第二句话。
            
            第二章 冒险
            这是第二章的内容。探险者踏上了新的征程。
            """.trimIndent()
        )

        val (book, chapters) = DocumentManager.loadBook(tempFile)
        assertEquals(BookFormat.TXT, book.format)
        assertTrue(chapters.size >= 2)
        assertEquals("第一章 启程", chapters[0].title)
        assertEquals("第二章 冒险", chapters[1].title)
        tempFile.delete()
    }

    @Test
    fun testEpubMockParsing() {
        val tempEpub = File.createTempFile("test_ebook", ".epub")
        val zos = ZipOutputStream(FileOutputStream(tempEpub))
        
        // Add mimetype
        zos.putNextEntry(ZipEntry("mimetype"))
        zos.write("application/epub+zip".toByteArray())
        zos.closeEntry()

        // Add sample chapter XHTML
        zos.putNextEntry(ZipEntry("OEBPS/chapter1.html"))
        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head><title>Chapter 1: The Beginning</title></head>
            <body>
                <h1>Chapter 1: The Beginning</h1>
                <p>Welcome to the world of offline multi-tts reading.</p>
                <p>Enjoy low energy consumption and battery savings.</p>
            </body>
            </html>
        """.trimIndent()
        zos.write(htmlContent.toByteArray())
        zos.closeEntry()
        zos.close()

        val (book, chapters) = DocumentManager.loadBook(tempEpub)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1: The Beginning", chapters[0].title)
        assertTrue(chapters[0].paragraphs.size >= 2)
        tempEpub.delete()
    }
}
