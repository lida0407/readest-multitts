package com.readest.multitts.tts

import android.content.Context

/**
 * One TTS connection per process.
 *
 * MultiTTS stops answering when two clients bind at once — the reader and the
 * caching service each holding their own TextToSpeech left synthesis hanging
 * forever. Everything now shares this instance.
 */
object TtsEngine {

    @Volatile
    private var controller: TTSEngineController? = null

    /** True while the reader UI is alive and still needs the engine. */
    @Volatile
    var activityAttached: Boolean = false

    fun get(context: Context): TTSEngineController =
        controller ?: synchronized(this) {
            controller ?: TTSEngineController(context.applicationContext).also { controller = it }
        }

    /** Release only when nothing else is using it. */
    fun releaseIfIdle() {
        if (activityAttached || CacheService.state.running) return
        synchronized(this) {
            controller?.shutdown()
            controller = null
        }
    }
}
