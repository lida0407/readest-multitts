package com.readest.multitts.dict

import android.util.Log
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale

/**
 * A MOBI/PRC dictionary opened for lookup.
 *
 * Indexing happens once, at import: the headword index is written to a small
 * sidecar file so opening the dictionary later costs a couple of reads rather
 * than a full decompression pass over a file that can be hundreds of megabytes.
 */
class MobiDictionary private constructor(
    private val palm: PalmFile,
    private val indexFile: File,
    private val charset: Charset,
    private val firstTextRecord: Int,
    private val decoder: (ByteArray) -> ByteArray,
    private val extraFlags: Int
) : Closeable {

    data class Definition(val headword: String, val html: String)

    private lateinit var recordOffsets: LongArray   // cumulative decompressed sizes
    private lateinit var entryPositions: IntArray   // byte position of each entry in the blob
    private var blobStart: Long = 0
    private lateinit var raf: RandomAccessFile

    val entryCount: Int get() = if (::entryPositions.isInitialized) entryPositions.size else 0

    // ------------------------------------------------------------------ lookup

    /**
     * Finds the best entry for [word], trying the word as typed, then lowercased,
     * then a few common English inflections, then — for CJK, which has no spaces —
     * the longest prefix that matches something.
     */
    fun lookup(word: String): Definition? {
        val cleaned = word.trim().trim('"', '“', '”', '\'', '(', ')', '.', ',', ';', ':', '!', '?')
        if (cleaned.isEmpty()) return null

        for (candidate in candidates(cleaned)) {
            find(candidate)?.let { return it }
        }

        // CJK text arrives as a run of characters with no word boundaries, so the
        // longest prefix that is actually in the dictionary is the best guess.
        if (cleaned.length > 1 && cleaned.any { it.code in 0x3000..0x9FFF || it.code in 0xF900..0xFAFF }) {
            for (end in cleaned.length - 1 downTo 1) {
                find(cleaned.substring(0, end))?.let { return it }
            }
        }
        return null
    }

    private fun candidates(word: String): List<String> {
        val lower = word.lowercase(Locale.ROOT)
        val out = linkedSetOf(word, lower)
        // Cheap English stemming. Dictionaries index the lemma, and MOBI's own
        // inflection index is not worth parsing for the handful of cases it adds.
        if (lower.length > 3) {
            when {
                lower.endsWith("ies") -> out.add(lower.dropLast(3) + "y")
                lower.endsWith("es") -> { out.add(lower.dropLast(2)); out.add(lower.dropLast(1)) }
                lower.endsWith("s") -> out.add(lower.dropLast(1))
            }
            when {
                lower.endsWith("ied") -> out.add(lower.dropLast(3) + "y")
                lower.endsWith("ed") -> { out.add(lower.dropLast(2)); out.add(lower.dropLast(1)) }
                lower.endsWith("ing") -> {
                    out.add(lower.dropLast(3))
                    out.add(lower.dropLast(3) + "e")
                }
            }
            if (lower.endsWith("er") || lower.endsWith("est")) {
                out.add(lower.removeSuffix("est").removeSuffix("er"))
            }
        }
        return out.toList()
    }

    private fun find(word: String): Definition? {
        if (!::entryPositions.isInitialized) return null
        val key = normalize(word)
        var low = 0
        var high = entryPositions.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val entry = readEntry(mid) ?: return null
            val cmp = normalize(entry.first).compareTo(key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return definitionAt(entry.first, entry.second, entry.third)
            }
        }
        return null
    }

    private fun readEntry(index: Int): Triple<String, Long, Int>? {
        return try {
            raf.seek(blobStart + entryPositions[index])
            val nameLength = raf.readUnsignedShort()
            val name = ByteArray(nameLength)
            raf.readFully(name)
            val offset = raf.readLong()
            val length = raf.readInt()
            Triple(String(name, Charsets.UTF_8), offset, length)
        } catch (e: Exception) {
            null
        }
    }

    private fun definitionAt(headword: String, offset: Long, length: Int): Definition? {
        if (length <= 0) return null
        val bytes = readText(offset, minOf(length, 64 * 1024)) ?: return null
        val html = String(bytes, charset)
        return Definition(headword, html)
    }

    /** Pulls [length] bytes out of the decompressed text stream. */
    private fun readText(offset: Long, length: Int): ByteArray? {
        if (!::recordOffsets.isInitialized) return null
        var record = recordOffsets.binarySearch(offset).let { if (it < 0) -it - 2 else it }
        if (record < 0) record = 0
        if (record >= recordOffsets.size - 1) return null

        val out = java.io.ByteArrayOutputStream(length)
        var cursor = recordOffsets[record]
        while (record < recordOffsets.size - 1 && out.size() < length) {
            val raw = palm.record(firstTextRecord + record) ?: break
            val text = try {
                decoder(trimTrailing(raw, extraFlags))
            } catch (e: Exception) {
                break
            }
            val from = if (offset > cursor) (offset - cursor).toInt() else 0
            if (from < text.size) {
                val take = minOf(text.size - from, length - out.size())
                out.write(text, from, take)
            }
            cursor += text.size
            record++
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    override fun close() {
        try {
            if (::raf.isInitialized) raf.close()
        } catch (_: Exception) {
        }
        palm.close()
    }

    // ----------------------------------------------------------------- loading

    private fun loadIndex(): Boolean {
        return try {
            raf = RandomAccessFile(indexFile, "r")
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "RDCT") return false
            if (raf.readInt() != INDEX_VERSION) return false

            val records = raf.readInt()
            recordOffsets = LongArray(records + 1) { raf.readLong() }
            val entries = raf.readInt()
            entryPositions = IntArray(entries) { raf.readInt() }
            blobStart = raf.filePointer
            entries > 0
        } catch (e: Exception) {
            Log.w(TAG, "Index unreadable", e)
            false
        }
    }

    companion object {
        private const val TAG = "MobiDictionary"
        private const val INDEX_VERSION = 2

        fun normalize(word: String): String =
            word.trim().lowercase(Locale.ROOT).replace("­", "")

        /** Opens an already-indexed dictionary. Returns null if it needs indexing. */
        fun open(bookFile: File, indexFile: File): MobiDictionary? {
            if (!indexFile.exists()) return null
            val prepared = prepare(bookFile, indexFile) ?: return null
            return if (prepared.loadIndex()) prepared else { prepared.close(); null }
        }

        /**
         * Reads the headword index out of the file and writes the sidecar.
         * Returns the number of entries, or throws with a reason the user can act on.
         */
        fun buildIndex(bookFile: File, indexFile: File, onProgress: (String) -> Unit): Int {
            val palm = PalmFile(bookFile)
            try {
                val header = palm.record(0) ?: throw DictionaryException("This file has no MOBI header.")
                if (header.size < 32 || String(header, 16, 4, Charsets.US_ASCII) != "MOBI") {
                    throw DictionaryException("This is not a MOBI/PRC file.")
                }
                val orthIndex = PalmFile.i32(header, 16 + 24)
                if (orthIndex <= 0 || orthIndex >= palm.recordCount) {
                    throw DictionaryException(
                        "No dictionary index in this file — it looks like a regular book rather than a dictionary."
                    )
                }

                onProgress("Reading headwords…")
                val entries = IndxParser.read(palm, orthIndex)
                if (entries.isEmpty()) {
                    throw DictionaryException("The dictionary index is empty or in a format this app can't read.")
                }

                val info = textInfo(palm, header)
                    ?: throw DictionaryException("This dictionary uses a compression this app can't read.")

                onProgress("Measuring ${entries.size} entries…")

                // One pass over the text records records where each one lands in the
                // decompressed stream, so a lookup later only expands what it needs.
                val offsets = LongArray(info.textRecordCount + 1)
                var running = 0L
                for (i in 0 until info.textRecordCount) {
                    offsets[i] = running
                    val raw = palm.record(info.firstTextRecord + i)
                    running += if (raw == null) 0 else try {
                        info.decoder(trimTrailing(raw, info.extraFlags)).size.toLong()
                    } catch (e: Exception) {
                        0L
                    }
                    if (i % 500 == 0) onProgress("Scanning text ${i * 100 / info.textRecordCount}%…")
                }
                offsets[info.textRecordCount] = running

                onProgress("Sorting…")
                data class Row(val word: String, val offset: Long, val length: Int)

                val rows = entries.mapNotNull { entry ->
                    val start = entry.tags[1]?.firstOrNull() ?: return@mapNotNull null
                    val length = entry.tags[2]?.firstOrNull()?.toInt() ?: 0
                    Row(entry.text, start, length)
                }.sortedBy { normalize(it.word) }

                if (rows.isEmpty()) {
                    throw DictionaryException("The index has no usable headword positions.")
                }

                onProgress("Writing index…")
                val positions = IntArray(rows.size)
                val blob = java.io.ByteArrayOutputStream()
                run {
                    val d = DataOutputStream(blob)
                    rows.forEachIndexed { i, row ->
                        positions[i] = blob.size()
                        val name = row.word.toByteArray(Charsets.UTF_8)
                        d.writeShort(name.size)
                        d.write(name)
                        d.writeLong(row.offset)
                        // A missing length runs to the next headword's position.
                        d.writeInt(if (row.length > 0) row.length else 2048)
                    }
                    d.flush()
                }

                DataOutputStream(BufferedOutputStream(indexFile.outputStream())).use { out ->
                    out.write("RDCT".toByteArray(Charsets.US_ASCII))
                    out.writeInt(INDEX_VERSION)
                    out.writeInt(info.textRecordCount)
                    offsets.forEach { out.writeLong(it) }
                    out.writeInt(positions.size)
                    positions.forEach { out.writeInt(it) }
                    out.write(blob.toByteArray())
                }
                return rows.size
            } finally {
                palm.close()
            }
        }

        private data class TextInfo(
            val firstTextRecord: Int,
            val textRecordCount: Int,
            val charset: Charset,
            val extraFlags: Int,
            val decoder: (ByteArray) -> ByteArray
        )

        private fun textInfo(palm: PalmFile, header: ByteArray): TextInfo? {
            val compression = PalmFile.u16(header, 0)
            val textRecordCount = PalmFile.u16(header, 8)
            val encoding = PalmFile.i32(header, 16 + 12)
            val charset = when (encoding) {
                1252 -> Charset.forName("windows-1252")
                else -> Charsets.UTF_8
            }
            val headerLength = PalmFile.i32(header, 16 + 4)
            // Only trust the extra-data flags when the header is long enough to
            // actually contain the field; older files simply have no trailers.
            val extraFlags =
                if (headerLength >= 0xE4 && header.size >= 16 + 0xE6) PalmFile.u16(header, 16 + 0xE2)
                else 0

            val decoder: (ByteArray) -> ByteArray = when (compression) {
                1 -> { data -> data }
                2 -> { data -> PalmDoc.decompress(data) }
                17480 -> {
                    val huffRecord = PalmFile.i32(header, 16 + 0x60)
                    val huffCount = PalmFile.i32(header, 16 + 0x64)
                    val codec = HuffCdic.load(palm, huffRecord, huffCount) ?: return null
                    ({ data -> codec.decompress(data) })
                }
                else -> return null
            }
            return TextInfo(1, textRecordCount, charset, extraFlags, decoder)
        }

        private fun prepare(bookFile: File, indexFile: File): MobiDictionary? {
            return try {
                val palm = PalmFile(bookFile)
                val header = palm.record(0)
                if (header == null || String(header, 16, 4, Charsets.US_ASCII) != "MOBI") {
                    palm.close(); return null
                }
                val info = textInfo(palm, header)
                if (info == null) { palm.close(); return null }
                MobiDictionary(
                    palm = palm,
                    indexFile = indexFile,
                    charset = info.charset,
                    firstTextRecord = info.firstTextRecord,
                    decoder = info.decoder,
                    extraFlags = info.extraFlags
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Text records can carry trailing bookkeeping bytes that are not text.
         * Feeding those to the decompressor corrupts the tail of every record.
         */
        fun trimTrailing(data: ByteArray, flags: Int): ByteArray {
            var end = data.size
            var remaining = flags shr 1
            while (remaining != 0) {
                if (remaining and 1 != 0) end -= trailingEntrySize(data, end)
                remaining = remaining shr 1
            }
            if (flags and 1 != 0 && end > 0) {
                end -= (data[end - 1].toInt() and 0x03) + 1
            }
            return if (end <= 0) ByteArray(0)
            else if (end >= data.size) data
            else data.copyOfRange(0, end)
        }

        private fun trailingEntrySize(data: ByteArray, size: Int): Int {
            var bitPos = 0
            var result = 0
            var pos = size
            if (pos <= 0) return 0
            while (true) {
                val v = data[pos - 1].toInt() and 0xFF
                result = result or ((v and 0x7F) shl bitPos)
                bitPos += 7
                pos--
                if (v and 0x80 != 0 || bitPos >= 28 || pos == 0) return result
            }
        }
    }
}

class DictionaryException(message: String) : Exception(message)
