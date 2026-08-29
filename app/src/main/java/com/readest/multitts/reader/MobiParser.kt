package com.readest.multitts.reader

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
            }

            val out = ByteArrayOutputStream()
            for (i in 1..textRecordCount) {
                val data = record(i) ?: continue
                when (compression) {
                    COMPRESSION_NONE -> out.write(data)
                    COMPRESSION_PALMDOC -> out.write(decompressPalmDoc(data))
                    else -> return null // HUFF/CDIC (compression 17480) is not supported
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

    /**
     * PalmDOC LZ77 variant: literals, 2-byte back-references, and space+char pairs.
     * Writes into a plain growable array so overlapping back-references can be copied
     * byte by byte without re-snapshotting the output.
     */
    private fun decompressPalmDoc(data: ByteArray): ByteArray {
        var out = ByteArray(data.size * 4 + 16)
        var size = 0

        fun put(value: Int) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = value.toByte()
        }

        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            i++
            when {
                b == 0 -> put(b)
                b in 1..8 -> {
                    var n = 0
                    while (n < b && i < data.size) {
                        put(data[i].toInt())
                        i++; n++
                    }
                }
                b in 0x09..0x7F -> put(b)
                b in 0x80..0xBF -> {
                    if (i >= data.size) break
                    val b2 = data[i].toInt() and 0xFF
                    i++
                    val pair = ((b shl 8) or b2) and 0x3FFF
                    val distance = pair shr 3
                    val length = (pair and 0x07) + 3
                    val src = size - distance
                    if (distance <= 0 || src < 0) continue
                    for (n in 0 until length) {
                        if (src + n >= size) break
                        put(out[src + n].toInt())
                    }
                }
                else -> {
                    // 0xC0..0xFF: space followed by the low 7 bits
                    put(' '.code)
                    put(b xor 0x80)
                }
            }
        }
        return out.copyOf(size)
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
