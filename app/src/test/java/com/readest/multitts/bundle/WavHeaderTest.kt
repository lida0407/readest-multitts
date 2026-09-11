package com.readest.multitts.bundle

import com.readest.multitts.tts.WavFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Every restored clip is written with this header. If it is wrong the audio is
 * silent, mis-pitched, or unreadable — and the failure only shows up on a phone
 * after a long import, which is exactly the wrong place to find it.
 */
class WavHeaderTest {

    @Test
    fun `the header a slice is written with reads back as the same format`() {
        val data = ByteArray(8_000) { (it % 251).toByte() }
        val file = File.createTempFile("slice", ".wav")
        file.outputStream().use { out ->
            out.write(WavFile.header(22_050, 1, 16, data.size))
            out.write(data)
        }

        val info = WavFile.read(file)
        assertNotNull("a header we wrote must be one we can read", info)
        assertEquals(22_050, info!!.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(data.size.toLong(), info.dataLength)
        assertEquals("samples start right after the 44-byte header", 44L, info.dataOffset)
        file.delete()
    }

    @Test
    fun `stereo at a different rate survives the round trip`() {
        val data = ByteArray(4_096)
        val file = File.createTempFile("slice", ".wav")
        file.outputStream().use { out ->
            out.write(WavFile.header(44_100, 2, 16, data.size))
            out.write(data)
        }
        val info = WavFile.read(file)!!
        assertEquals(44_100, info.sampleRate)
        assertEquals(2, info.channels)
        file.delete()
    }

    @Test
    fun `duration is derived from the declared format, not the file length`() {
        val oneSecond = 22_050 * 2
        val file = File.createTempFile("slice", ".wav")
        file.outputStream().use { out ->
            out.write(WavFile.header(22_050, 1, 16, oneSecond))
            out.write(ByteArray(oneSecond))
        }
        assertEquals(1000L, WavFile.read(file)!!.durationMs)
        file.delete()
    }

    @Test
    fun `the header is exactly the 44 bytes the writer reserves`() {
        assertEquals(44, WavFile.header(22_050, 1, 16, 0).size)
    }
}
