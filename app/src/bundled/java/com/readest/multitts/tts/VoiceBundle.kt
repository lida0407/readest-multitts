package com.readest.multitts.tts

import android.content.Context

/**
 * The bundled build carries its own voices, registered as a TTS engine under
 * this app's own package name.
 */
object VoiceBundle {
    const val HAS_BUNDLED_VOICES = true

    fun enginePackage(context: Context): String? = context.packageName
}
