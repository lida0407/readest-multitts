package com.readest.multitts.dict

import java.nio.charset.Charset

/**
 * Reads a MOBI INDX index — the structure that maps a dictionary's headwords to
 * positions in its text.
 *
 * An index is a header record (holding a TAGX table that describes the shape of
 * every entry) followed by data records, each of which ends in an IDXT block of
 * offsets pointing at its own entries.
 */
object IndxParser {

    data class Entry(val text: String, val tags: Map<Int, List<Long>>)

    private data class Tagx(val tag: Int, val valuesPerEntry: Int, val mask: Int, val endFlag: Int)

    fun read(palm: PalmFile, indxRecord: Int): List<Entry> {
        val header = palm.record(indxRecord) ?: return emptyList()
        if (header.size < 56 || String(header, 0, 4, Charsets.US_ASCII) != "INDX") return emptyList()

        val headerLength = PalmFile.i32(header, 4)
        val dataRecordCount = PalmFile.i32(header, 24)
        val encoding = PalmFile.i32(header, 28)
        val charset: Charset = when (encoding) {
            1252 -> Charset.forName("windows-1252")
            else -> Charsets.UTF_8
        }

        val tagx = readTagx(header, headerLength) ?: return emptyList()
        val controlByteCount = tagx.second
        val tags = tagx.first

        val entries = ArrayList<Entry>()
        for (i in 0 until dataRecordCount) {
            val record = palm.record(indxRecord + 1 + i) ?: continue
            readDataRecord(record, tags, controlByteCount, charset, entries)
        }
        return entries
    }

    private fun readTagx(header: ByteArray, headerLength: Int): Pair<List<Tagx>, Int>? {
        if (headerLength <= 0 || headerLength + 12 > header.size) return null
        if (String(header, headerLength, 4, Charsets.US_ASCII) != "TAGX") return null
        val tagxLength = PalmFile.i32(header, headerLength + 4)
        val controlByteCount = PalmFile.i32(header, headerLength + 8)
        if (tagxLength < 12 || headerLength + tagxLength > header.size) return null

        val list = ArrayList<Tagx>()
        var i = headerLength + 12
        while (i + 4 <= headerLength + tagxLength) {
            list.add(
                Tagx(
                    tag = header[i].toInt() and 0xFF,
                    valuesPerEntry = header[i + 1].toInt() and 0xFF,
                    mask = header[i + 2].toInt() and 0xFF,
                    endFlag = header[i + 3].toInt() and 0xFF
                )
            )
            i += 4
        }
        return list to controlByteCount
    }

    private fun readDataRecord(
        record: ByteArray,
        tagx: List<Tagx>,
        controlByteCount: Int,
        charset: Charset,
        into: MutableList<Entry>
    ) {
        if (record.size < 28 || String(record, 0, 4, Charsets.US_ASCII) != "INDX") return
        val idxtStart = PalmFile.i32(record, 20)
        val count = PalmFile.i32(record, 24)
        if (idxtStart <= 0 || count <= 0) return

        for (i in 0 until count) {
            val pointer = idxtStart + 4 + i * 2
            if (pointer + 2 > record.size) break
            val offset = PalmFile.u16(record, pointer)
            // Where this entry stops: the next entry, or the IDXT block itself.
            val next =
                if (i + 1 < count && pointer + 4 <= record.size) PalmFile.u16(record, pointer + 2)
                else idxtStart
            if (offset <= 0 || offset >= record.size) continue

            val textLength = record[offset].toInt() and 0xFF
            val textStart = offset + 1
            if (textStart + textLength > record.size) continue
            val text = String(record, textStart, textLength, charset)

            val tagStart = textStart + textLength
            val tagEnd = minOf(if (next > tagStart) next else record.size, record.size)
            val tags = readTagValues(record, tagStart, tagEnd, tagx, controlByteCount)
            into.add(Entry(text, tags))
        }
    }

    private fun readTagValues(
        data: ByteArray,
        start: Int,
        end: Int,
        tagx: List<Tagx>,
        controlByteCount: Int
    ): Map<Int, List<Long>> {
        if (start + controlByteCount > data.size) return emptyMap()

        // Pass one: the control bytes say which tags are present and how many
        // values each carries. Pass two reads the values themselves, which sit
        // in a single varint run after the control bytes.
        data class Pending(val tag: Int, val valueCount: Int?, val valueBytes: Int?, val perEntry: Int)

        val pending = ArrayList<Pending>()
        var controlIndex = 0
        val cursor = intArrayOf(start + controlByteCount)

        for (t in tagx) {
            if (t.endFlag and 0x01 == 0x01) {
                controlIndex++
                continue
            }
            if (start + controlIndex >= data.size) break
            val control = data[start + controlIndex].toInt() and 0xFF
            var value = control and t.mask
            if (value == 0) continue

            if (value == t.mask) {
                if (Integer.bitCount(t.mask) > 1) {
                    // A full mask with several bits means the byte count itself
                    // is a varint sitting with the values.
                    val v = varint(data, cursor, end) ?: continue
                    pending.add(Pending(t.tag, null, v.toInt(), t.valuesPerEntry))
                } else {
                    pending.add(Pending(t.tag, 1, null, t.valuesPerEntry))
                }
            } else {
                var mask = t.mask
                while (mask and 0x01 == 0) {
                    mask = mask shr 1
                    value = value shr 1
                }
                pending.add(Pending(t.tag, value, null, t.valuesPerEntry))
            }
        }

        val result = HashMap<Int, List<Long>>()
        for (p in pending) {
            val values = ArrayList<Long>()
            if (p.valueCount != null) {
                repeat(p.valueCount * p.perEntry) {
                    varint(data, cursor, end)?.let { values.add(it) }
                }
            } else {
                val target = cursor[0] + (p.valueBytes ?: 0)
                while (cursor[0] < target) {
                    varint(data, cursor, end)?.let { values.add(it) } ?: break
                }
            }
            result[p.tag] = values
        }
        return result
    }

    /** Big-endian base-128, high bit marking the final byte. */
    private fun varint(data: ByteArray, cursor: IntArray, end: Int): Long? {
        var value = 0L
        var moved = false
        while (cursor[0] < end && cursor[0] < data.size) {
            val b = data[cursor[0]].toInt() and 0xFF
            cursor[0]++
            moved = true
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 != 0) return value
        }
        return if (moved) value else null
    }
}
