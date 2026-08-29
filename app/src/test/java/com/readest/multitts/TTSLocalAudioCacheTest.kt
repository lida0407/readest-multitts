package com.readest.multitts

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TTSLocalAudioCacheTest {

    @Test
    fun testCacheKeyConsistency() {
        val raw = "book123_c0_s5_voice_zh_1.0_1.0_Hello world"
        val hash1 = sha256(raw)
        val hash2 = sha256(raw)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
