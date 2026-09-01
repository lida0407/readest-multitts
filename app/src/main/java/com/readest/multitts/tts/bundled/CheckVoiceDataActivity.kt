package com.readest.multitts.tts.bundled

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Android asks every TTS engine which languages it has data for before it will
 * use it. The voices ship inside the APK, so the answer never involves a
 * download and is always the same list.
 */
class CheckVoiceDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()
        if (SherpaTts.isAvailable) {
            BundledVoices.ALL.forEach { voice ->
                available.add("${voice.locale.isO3Language}-${voice.locale.isO3Country}")
            }
        } else {
            // A device this app was not built for (32-bit ABI): say so honestly
            // rather than claiming voices that cannot load.
            BundledVoices.ALL.forEach { voice ->
                unavailable.add("${voice.locale.isO3Language}-${voice.locale.isO3Country}")
            }
        }

        val result = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)
        }
        setResult(
            if (available.isEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
            else TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            result
        )
        finish()
    }
}
