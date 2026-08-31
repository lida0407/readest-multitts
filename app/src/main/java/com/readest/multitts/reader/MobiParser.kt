package com.readest.multitts.reader

import com.readest.multitts.dict.HuffCdic
import com.readest.multitts.dict.MobiDictionary
import com.readest.multitts.dict.PalmDoc
import com.readest.multitts.dict.PalmFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * Real MOBI/PalmDOC reading: walks the Palm database record table, reads the MOBI
 * header for encoding and record count, and decompresses PalmDOC LZ77 text.
 *
 * The previous implementation scanned raw bytes for printable characters, which
 * produced garbage for compressed books and mangled every multi-byte (CJK) string.
 */
object MobiParser {

    data class MobiDoc(val title: String?, val text: String)

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_PALMDOC = 2
    private const val COMPRESSION_HUFFCDIC = 17480

    fun parse(file: File): MobiDoc? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 78) return null

            // --- Palm database header ---
            val recordCount = readShort(bytes, 76)
            if (recordCount <= 0) return null

            val offsets = IntArray(recordCount)
            for (i in 0 until recordCount) {
                val entry = 78 + i * 8
                if (entry + 4 > bytes.size) return null
                offsets[i] = readInt(bytes, entry)
            }

            fun record(index: Int): ByteArray? {
                if (index >= recordCount) return null
                val start = offsets[index]
                val end = if (index + 1 < recordCount) offsets[index + 1] else bytes.size
                if (start < 0 || end > bytes.size || start >= end) return null
                return bytes.copyOfRange(start, end)
            }

            // --- Record 0: PalmDOC + MOBI headers ---
            val header = record(0) ?: return null
            if (header.size < 16) return null
            val compression = readShort(header, 0)
            val textRecordCount = readShort(header, 8)

            var charset: Charset = Charsets.UTF_8
            var title: String? = null
            var extraFlags = 0
            var huffRecord = 0
            var huffCount = 0

            if (header.size > 32 && String(header, 16, 4, Charsets.US_ASCII) == "MOBI") {
                val encoding = readInt(header, 16 + 12)
                charset = when (encoding) {
                    1252 -> Charset.forName("windows-1252")
                    65001 -> Charsets.UTF_8
                    else -> Charsets.UTF_8
                }
                val fullNameOffset = readInt(header, 16 + 68)
                val fullNameLength = readInt(header, 16 + 72)
                if (fullNameOffset > 0 && fullNameLength in 1..1024 &&
                    fullNameOffset + fullNameLength <= header.size
                ) {
                    title = String(header, fullNameOffset, fullNameLength, charset).trim()
                }

                val mobiHeaderLength = readInt(header, 16 + 4)
                // Only present on newer headers; older files simply have no trailers.
                if (mobiHeaderLength >= 0xE4 && header.size >= 16 + 0xE6) {
                    extraFlags = readShort(header, 16 + 0xE2)
                }
                huffRecord = readInt(header, 16 + 0x60)
                huffCount = readInt(header, 16 + 0x64)
            }

            // HUFF/CDIC needs the whole table before any record can be read.
            val huffman = if (compression == COMPRESSION_HUFFCDIC) {
                PalmFile(file).use { palm -> HuffCdic.load(palm, huffRecord, huffCount) }
                    ?: return null
            } else null

            val out = ByteArrayOutputStream()
            for (i in 1..textRecordCount) {
                val raw = record(i) ?: continue
                // Text records can end in bookkeeping bytes that are not text;
                // decompressing them corrupts the tail of every single record.
                val data = MobiDictionary.trimTrailing(raw, extraFlags)
                when (compression) {
                    COMPRESSION_NONE -> out.write(data)
                    COMPRESSION_PALMDOC -> out.write(PalmDoc.decompress(data))
                    COMPRESSION_HUFFCDIC -> out.write(huffman!!.decompress(data))
                    else -> return null
                }
            }

            val raw = String(out.toByteArray(), charset)
            val text = HtmlText.unescape(HtmlText.stripTags(raw.replace("(?i)<br\\s*/?>|</p>|</div>".toRegex(), "\n\n")))
            if (text.isBlank()) null else MobiDoc(title?.ifBlank { null }, text)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun readShort(b: ByteArray, offset: Int): Int =
        if (offset + 2 > b.size) 0
        else ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

    private fun readInt(b: ByteArray, offset: Int): Int =
        if (offset + 4 > b.size) 0
        else ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
}
