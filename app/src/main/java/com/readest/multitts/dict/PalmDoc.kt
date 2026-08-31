package com.readest.multitts.dict

/**
 * PalmDOC LZ77: literals, 2-byte back-references, and space+character pairs.
 *
 * Shared by book reading and dictionary lookup so both agree on the details.
 */
object PalmDoc {

    fun decompress(data: ByteArray): ByteArray {
        // A plain growable array, so overlapping back-references can be copied
        // byte by byte without re-snapshotting the output.
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
                    // 0xC0..0xFF: a space followed by the low 7 bits
                    put(' '.code)
                    put(b xor 0x80)
                }
            }
        }
        return out.copyOf(size)
    }
}
