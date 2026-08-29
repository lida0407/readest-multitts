package com.readest.multitts.reader

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

/**
 * Real PDF text extraction via PDFBox.
 *
 * The previous approach only matched uncompressed `Tj` operators, so most real-world
 * PDFs (Flate-compressed streams) produced empty or garbled text.
 */
object PdfParser {

    data class PdfDoc(val title: String?, val pages: List<String>)

    @Volatile
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (!initialised) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialised = true
            }
        }
    }

    fun parse(file: File): PdfDoc? {
        return try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) return null
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    paragraphStart = "\n"
                }

                val pages = mutableListOf<String>()
                for (page in 1..doc.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(doc).trim()
                    if (text.isNotEmpty()) pages.add(text)
                }
                if (pages.isEmpty()) return null

                val title = doc.documentInformation?.title?.trim()?.ifBlank { null }
                PdfDoc(title, pages)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
