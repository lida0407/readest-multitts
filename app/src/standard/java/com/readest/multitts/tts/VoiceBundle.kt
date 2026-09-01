package com.readest.multitts.tts

import android.content.Context

/**
 * The standard build ships no voices of its own; narration comes from MultiTTS
 * or whatever engine the phone already has.
 */
object VoiceBundle {
    const val HAS_BUNDLED_VOICES = false

    fun enginePackage(context: Context): String? = null
}
