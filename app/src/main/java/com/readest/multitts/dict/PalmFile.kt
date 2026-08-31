package com.readest.multitts.dict

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Random access to a Palm database — the container MOBI, PRC and AZW files use.
 *
 * Book parsing reads the whole file into memory, which is fine for a novel but not
 * for a dictionary: those run to hundreds of megabytes, and only a couple of
 * records are needed to answer any one lookup.
 */
class PalmFile(file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    private val fileLength = raf.length()
    private val offsets: IntArray

    val recordCount: Int

    init {
        val head = ByteArray(78)
        raf.seek(0)
        raf.readFully(head)
        recordCount = u16(head, 76)
        require(recordCount > 0) { "Not a Palm database" }

        val table = ByteArray(recordCount * 8)
        raf.seek(78)
        raf.readFully(table)
        offsets = IntArray(recordCount) { i32(table, it * 8) }
    }

    fun record(index: Int): ByteArray? {
        if (index < 0 || index >= recordCount) return null
        val start = offsets[index].toLong()
        val end = if (index + 1 < recordCount) offsets[index + 1].toLong() else fileLength
        if (start < 0 || end > fileLength || start >= end) return null
        val buffer = ByteArray((end - start).toInt())
        raf.seek(start)
        raf.readFully(buffer)
        return buffer
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun u16(b: ByteArray, off: Int): Int =
            if (off + 2 > b.size) 0
            else ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        fun i32(b: ByteArray, off: Int): Int =
            if (off + 4 > b.size) 0
            else ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        fun u32(b: ByteArray, off: Int): Long = i32(b, off).toLong() and 0xFFFFFFFFL
    }
}
