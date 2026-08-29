package com.readest.multitts.tts

import java.io.File
import java.io.RandomAccessFile

/** Minimal RIFF/WAVE reader: enough to concatenate TTS output into one track. */
object WavFile {

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataLength: Long
    ) {
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRate * channels * (bitsPerSample / 8)
                return if (bytesPerSecond > 0) dataLength * 1000 / bytesPerSecond else 0
            }
    }

    fun read(file: File): Info? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff) != "RIFF") return null
            raf.skipBytes(4) // chunk size
            val wave = ByteArray(4)
            raf.readFully(wave)
            if (String(wave) != "WAVE") return null

            var sampleRate = 0
            var channels = 0
            var bits = 0

            while (raf.filePointer < raf.length() - 8) {
                val id = ByteArray(4)
                raf.readFully(id)
                val size = readIntLE(raf)
                val chunkStart = raf.filePointer

                when (String(id)) {
                    "fmt " -> {
                        raf.skipBytes(2) // audio format
                        channels = readShortLE(raf)
                        sampleRate = readIntLE(raf)
                        raf.skipBytes(6) // byte rate + block align
                        bits = readShortLE(raf)
                    }
                    "data" -> {
                        if (sampleRate == 0 || channels == 0 || bits == 0) return null
                        val available = (raf.length() - chunkStart).coerceAtLeast(0)
                        val dataLength = if (size <= 0) available else size.toLong().coerceAtMost(available)
                        return Info(sampleRate, channels, bits, chunkStart, dataLength)
                    }
                }

                // Chunks are word-aligned
                val advance = size.toLong() + (size % 2)
                if (advance <= 0) return null
                raf.seek(chunkStart + advance)
            }
        }
        return null
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
}
