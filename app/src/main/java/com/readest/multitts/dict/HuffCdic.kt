package com.readest.multitts.dict

import java.io.ByteArrayOutputStream

/**
 * HUFF/CDIC decompression (MOBI compression type 17480).
 *
 * Almost every commercial MOBI dictionary uses this rather than PalmDOC LZ77,
 * so without it the dictionary feature would only work on a small minority of files.
 *
 * The scheme is a Huffman code whose symbols are dictionary phrases: a code
 * resolves to a slice, and a slice may itself still be compressed, in which case
 * it is expanded once and cached in place.
 */
class HuffCdic private constructor() {

    // dict1: one entry per possible leading byte, giving a code length and,
    // for short codes, the answer outright without consulting the range table.
    private val codeLength = IntArray(256)
    private val terminal = BooleanArray(256)
    private val entryMaxCode = LongArray(256)

    // dict2: per code length, the range of code values that use that length.
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)

    private val slices = ArrayList<ByteArray>()
    private val sliceIsLiteral = ArrayList<Boolean>()

    private fun loadHuff(huff: ByteArray) {
        require(huff.size >= 16 && String(huff, 0, 4, Charsets.US_ASCII) == "HUFF") {
            "Not a HUFF record"
        }
        val off1 = PalmFile.i32(huff, 8)
        val off2 = PalmFile.i32(huff, 12)

        for (i in 0 until 256) {
            val v = PalmFile.u32(huff, off1 + i * 4)
            val len = (v and 0x1FL).toInt()
            require(len != 0) { "Corrupt HUFF table" }
            codeLength[i] = len
            terminal[i] = (v and 0x80L) != 0L
            // Widened to a full 32-bit code so it can be compared against the
            // bit window directly. Long, not Int: this overflows a signed 32-bit.
            entryMaxCode[i] = (((v ushr 8) + 1) shl (32 - len)) - 1
        }

        for (len in 0 until 32) {
            minCode[len] = PalmFile.u32(huff, off2 + len * 8) shl (32 - len)
            maxCode[len] = ((PalmFile.u32(huff, off2 + len * 8 + 4) + 1) shl (32 - len)) - 1
        }
    }

    private fun loadCdic(cdic: ByteArray, totalPhrases: Int) {
        require(cdic.size >= 16 && String(cdic, 0, 4, Charsets.US_ASCII) == "CDIC") {
            "Not a CDIC record"
        }
        val bits = PalmFile.i32(cdic, 12)
        val remaining = totalPhrases - slices.size
        val n = minOf(1 shl bits, remaining)
        for (i in 0 until n) {
            val off = PalmFile.u16(cdic, 16 + i * 2)
            val blen = PalmFile.u16(cdic, 16 + off)
            val length = blen and 0x7FFF
            val start = 18 + off
            if (start + length > cdic.size) {
                slices.add(ByteArray(0))
                sliceIsLiteral.add(true)
                continue
            }
            slices.add(cdic.copyOfRange(start, start + length))
            sliceIsLiteral.add((blen and 0x8000) != 0)
        }
    }

    fun decompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 4)
        expand(BitReader(data), out, 0)
        return out.toByteArray()
    }

    private fun expand(bits: BitReader, out: ByteArrayOutputStream, depth: Int) {
        if (depth > 32) throw IllegalStateException("Dictionary phrases nest too deeply")
        while (bits.left() > 0) {
            val window = bits.value()
            val lead = (window ushr 24).toInt()
            var len = codeLength[lead]
            var max = entryMaxCode[lead]
            if (!terminal[lead]) {
                while (len < 32 && window < minCode[len]) len++
                max = maxCode[len]
            }
            if (!bits.eat(len)) break

            val index = ((max - window) ushr (32 - len)).toInt()
            if (index < 0 || index >= slices.size) break

            if (!sliceIsLiteral[index]) {
                // A phrase that is itself compressed: expand once, then keep the
                // plain bytes so the next hit on this code is a straight copy.
                val nested = ByteArrayOutputStream()
                expand(BitReader(slices[index]), nested, depth + 1)
                slices[index] = nested.toByteArray()
                sliceIsLiteral[index] = true
            }
            out.write(slices[index])
        }
    }

    /** Reads the 32-bit window MSB-first, which is the order the codes were written in. */
    private class BitReader(data: ByteArray) {
        private val buffer = data + ByteArray(8)
        private val totalBits = data.size * 8
        private var pos = 0

        fun left(): Int = totalBits - pos

        fun value(): Long {
            var p = pos
            var remaining = 32
            var result = 0L
            while (remaining > 0) {
                val byte = buffer[p shr 3].toInt() and 0xFF
                val take = minOf(8 - (p and 7), remaining)
                val shifted = (byte shr (8 - (p and 7) - take)) and ((1 shl take) - 1)
                result = (result shl take) or shifted.toLong()
                p += take
                remaining -= take
            }
            return result
        }

        fun eat(n: Int): Boolean {
            pos += n
            return pos <= totalBits
        }
    }

    companion object {
        /**
         * @param huffRecord index of the HUFF record; the CDIC records follow it.
         */
        fun load(palm: PalmFile, huffRecord: Int, huffCount: Int): HuffCdic? {
            if (huffRecord <= 0 || huffCount <= 1) return null
            return try {
                val codec = HuffCdic()
                val huff = palm.record(huffRecord) ?: return null
                codec.loadHuff(huff)

                // Phrase count lives in the first CDIC and covers all of them.
                val firstCdic = palm.record(huffRecord + 1) ?: return null
                val totalPhrases = PalmFile.i32(firstCdic, 8)

                for (i in 1 until huffCount) {
                    val cdic = palm.record(huffRecord + i) ?: break
                    codec.loadCdic(cdic, totalPhrases)
                }
                if (codec.slices.isEmpty()) null else codec
            } catch (e: Exception) {
                null
            }
        }
    }
}
