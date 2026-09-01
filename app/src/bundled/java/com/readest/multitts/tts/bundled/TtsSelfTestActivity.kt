package com.readest.multitts.tts.bundled

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Synthesises a fixed phrase in every bundled voice and reports what came back.
 *
 * Useful when something is wrong with audio on a particular device: it separates
 * "the model cannot load" from "the model produced no samples" from "playback is
 * broken", which are otherwise indistinguishable from silence.
 */
class TtsSelfTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread {
            val report = StringBuilder()
            report.append("native=${SherpaTts.isAvailable}\n")

            for (voice in BundledVoices.ALL) {
                val phrase = if (voice.locale.language == "zh") {
                    "这是一个测试。今天天气很好。"
                } else {
                    "This is a test of the bundled voice."
                }
                val started = System.currentTimeMillis()
                val result = try {
                    SherpaTts.synthesize(applicationContext, voice, phrase, 1.0f)
                } catch (e: Throwable) {
                    Log.e(TAG, "${voice.id} threw", e)
                    null
                }
                val elapsed = System.currentTimeMillis() - started

                if (result == null) {
                    report.append("${voice.id}: FAILED\n")
                } else {
                    val (pcm, rate) = result
                    val seconds = pcm.size / 2f / rate
                    report.append(
                        "${voice.id}: OK ${pcm.size}B @${rate}Hz " +
                            "= %.2fs audio in ${elapsed}ms\n".format(seconds)
                    )
                }
            }

            // The direct calls above prove the model works. This proves the
            // engine works through android.speech.tts, which is the path the
            // reader's caching and playback actually take.
            report.append(frameworkRoundTrip())

            val text = report.toString().trim()
            Log.i(TAG, "SELFTEST\n$text")
            runOnUiThread {
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    /** Drives our own TextToSpeechService the way the rest of the app does. */
    private fun frameworkRoundTrip(): String {
        val ready = java.util.concurrent.CountDownLatch(1)
        var initOk = false
        lateinit var tts: android.speech.tts.TextToSpeech
        tts = android.speech.tts.TextToSpeech(this, { status ->
            initOk = status == android.speech.tts.TextToSpeech.SUCCESS
            ready.countDown()
        }, packageName)

        if (!ready.await(20, java.util.concurrent.TimeUnit.SECONDS) || !initOk) {
            return "framework: engine did not initialise\n"
        }

        val out = java.io.File(cacheDir, "selftest.wav")
        out.delete()
        val done = java.util.concurrent.CountDownLatch(1)
        var failed = false
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = done.countDown()
            @Deprecated("required override")
            override fun onError(utteranceId: String?) {
                failed = true; done.countDown()
            }
        })

        tts.language = java.util.Locale.US
        val queued = tts.synthesizeToFile(
            "Testing the bundled engine through the Android speech framework.",
            android.os.Bundle(),
            out,
            "selftest"
        )
        val finished = done.await(30, java.util.concurrent.TimeUnit.SECONDS)
        tts.shutdown()

        return when {
            queued != android.speech.tts.TextToSpeech.SUCCESS -> "framework: request rejected\n"
            !finished -> "framework: timed out\n"
            failed -> "framework: engine reported an error\n"
            !out.exists() || out.length() < 1000 -> "framework: wrote ${out.length()} bytes (too small)\n"
            else -> "framework: OK wav ${out.length()} bytes\n"
        }
    }

    companion object {
        private const val TAG = "TtsSelfTest"
    }
}
