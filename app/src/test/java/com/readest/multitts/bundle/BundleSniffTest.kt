package com.readest.multitts.bundle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Telling a bundle from the other zips a reader might hand the app. An EPUB is
 * a zip too, and importing one as a bundle — or a bundle as a book — fails in a
 * confusing way rather than a loud one.
 */
class BundleSniffTest {

    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a bundle is recognised by its manifest`() {
        val bytes = zip("book/a.epub", "audio/c0.m4a", "manifest.json")
        assertTrue(BundleImporter.containsManifest { ByteArrayInputStream(bytes) })
    }

    @Test
    fun `an epub is not a bundle`() {
        val bytes = zip("mimetype", "META-INF/container.xml", "OEBPS/content.opf")
        assertFalse(BundleImporter.containsManifest { ByteArrayInputStream(bytes) })
    }

    @Test
    fun `a file that is not a zip is refused rather than throwing`() {
        val bytes = "this is a plain text book".toByteArray()
        assertFalse(BundleImporter.containsManifest { ByteArrayInputStream(bytes) })
    }

    @Test
    fun `an unreadable source is refused rather than throwing`() {
        assertFalse(BundleImporter.containsManifest { null })
        assertFalse(BundleImporter.containsManifest { error("gone") })
    }

    @Test
    fun `the name check accepts only our extension`() {
        assertTrue(BundleImporter.looksLikeBundle("Monte Cristo.readest"))
        assertTrue(BundleImporter.looksLikeBundle("MONTE.READEST"))
        assertFalse(BundleImporter.looksLikeBundle("book.epub"))
        assertFalse(BundleImporter.looksLikeBundle("chapter.m4a"))
        assertFalse(BundleImporter.looksLikeBundle(null))
    }
}
