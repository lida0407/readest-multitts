package com.readest.multitts.reader

import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset

object EncodingDetector {
    fun detectCharset(file: File): Charset {
        val buffer = ByteArray(4096)
        val readBytes = FileInputStream(file).use { it.read(buffer) }
        if (readBytes <= 0) return Charsets.UTF_8

        // Check BOM
        if (readBytes >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() && buffer[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (readBytes >= 2 && buffer[0] == 0xFE.toByte() && buffer[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        if (readBytes >= 2 && buffer[0] == 0xFF.toByte() && buffer[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }

        // Test valid UTF-8
        if (isValidUtf8(buffer, readBytes)) {
            return Charsets.UTF_8
        }

        // Fallback to GBK / GB18030 for Chinese or ISO-8859-1 for Western
        return try {
            Charset.forName("GB18030")
        } catch (e: Exception) {
            try {
                Charset.forName("GBK")
            } catch (e: Exception) {
                Charsets.ISO_8859_1
            }
        }
    }

    private fun isValidUtf8(bytes: ByteArray, length: Int): Boolean {
        var i = 0
        var hasNonAscii = false
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (b <= 0x7F) {
                i++
            } else if ((b in 0xC2..0xDF)) {
                if (i + 1 >= length) break
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b2 !in 0x80..0xBF) return false
                hasNonAscii = true
                i += 2
            } else if ((b in 0xE0..0xEF)) {
                if (i + 2 >= length) break
                val b2 = bytes[i + 1].toInt() and 0xFF
                val b3 = bytes[i + 2].toInt() and 0xFF
                if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF) return false
                hasNonAscii = true
                i += 3
            } else if ((b in 0xF0..0xF4)) {
                if (i + 3 >= length) break
                val b2 = bytes[i + 1].toInt() and 0xFF
                val b3 = bytes[i + 2].toInt() and 0xFF
                val b4 = bytes[i + 3].toInt() and 0xFF
                if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF || b4 !in 0x80..0xBF) return false
                hasNonAscii = true
                i += 4
            } else {
                return false
            }
        }
        return hasNonAscii
    }
}
