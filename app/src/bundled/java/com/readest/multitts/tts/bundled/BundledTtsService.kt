package com.readest.multitts.tts.bundled

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import java.util.Locale

/**
 * Exposes the bundled voices as a normal Android TTS engine.
 *
 * Registering as an engine rather than synthesising inline means every existing
 * part of the app — pre-caching, the playback service, export — keeps working
 * untouched, because they all speak to `android.speech.tts` already.
 */
class BundledTtsService : TextToSpeechService() {

    @Volatile
    private var stopRequested = false

    @Volatile
    private var currentVoice: BundledVoices.Voice = BundledVoices.ENGLISH

    override fun onCreate() {
        super.onCreate()
        // Announce the engine's starting language before any request arrives.
        onLoadLanguage(
            currentVoice.locale.isO3Language,
            currentVoice.locale.country,
            ""
        )
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val voice = match(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (voice.locale.country.equals(country, ignoreCase = true)) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> = arrayOf(
        currentVoice.locale.isO3Language,
        currentVoice.locale.isO3Country,
        ""
    )

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val voice = match(lang, country) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        currentVoice = voice
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val voice = match(request.language, request.country) ?: currentVoice
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            callback.start(22050, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        // Android sends speech rate as a percentage where 100 is normal, but
        // the model takes a length scale, where *larger* means slower.
        val speed = (request.speechRate.coerceIn(20, 400)) / 100f

        val result = try {
            SherpaTts.synthesize(applicationContext, voice, text, speed)
        } catch (e: Throwable) {
            Log.e(TAG, "Synthesis threw", e)
            null
        }

        if (result == null) {
            callback.error()
            return
        }

        val (pcm, sampleRate) = result
        if (callback.start(sampleRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            return
        }

        // The buffer the framework accepts per call is capped, so the audio goes
        // out in slices; this is also the only place a stop can take effect.
        val max = callback.maxBufferSize.coerceAtLeast(1024)
        var offset = 0
        while (offset < pcm.size) {
            if (stopRequested) {
                callback.done()
                return
            }
            val length = minOf(max, pcm.size - offset)
            if (callback.audioAvailable(pcm, offset, length) != TextToSpeech.SUCCESS) {
                return
            }
            offset += length
        }
        callback.done()
    }

    private fun match(lang: String?, country: String?): BundledVoices.Voice? {
        if (lang.isNullOrBlank()) return BundledVoices.ENGLISH
        val normalized = lang.lowercase(Locale.ROOT)
        return BundledVoices.ALL.firstOrNull { voice ->
            val l = voice.locale
            normalized == l.isO3Language || normalized == l.language ||
                (normalized.startsWith("zh") && l.language == "zh") ||
                (normalized.startsWith("en") && l.language == "en")
        } ?: run {
            // An unknown language still gets a voice rather than silence, since
            // most books are Latin script even when the tag says otherwise.
            if (country.isNullOrBlank()) BundledVoices.ENGLISH else BundledVoices.ENGLISH
        }
    }

    companion object {
        private const val TAG = "BundledTtsService"
    }
}
