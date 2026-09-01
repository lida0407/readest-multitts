package com.readest.multitts.tts.bundled

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Wraps sherpa-onnx so the rest of the app can ask for audio without knowing
 * anything about ONNX.
 *
 * One model is held at a time: each is tens of megabytes of weights, and a
 * reader switching between an English and a Chinese book does not need both
 * resident at once.
 */
object SherpaTts {

    private const val TAG = "SherpaTts"

    private var loadedId: String? = null
    private var tts: OfflineTts? = null

    val isAvailable: Boolean
        get() = try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "sherpa-onnx native library missing for this ABI", e)
            false
        }

    @Synchronized
    fun sampleRate(context: Context, voice: BundledVoices.Voice): Int =
        load(context, voice)?.sampleRate() ?: 22050

    /**
     * Synthesises [text] and hands back 16-bit PCM at the model's sample rate.
     * Returns null when the model cannot be loaded at all.
     */
    @Synchronized
    fun synthesize(
        context: Context,
        voice: BundledVoices.Voice,
        text: String,
        speed: Float
    ): Pair<ByteArray, Int>? {
        val engine = load(context, voice) ?: return null
        return try {
            val audio = engine.generate(text, voice.speakerId, speed.coerceIn(0.3f, 3.0f))
            audio.samples to audio.sampleRate
        } catch (e: Throwable) {
            Log.e(TAG, "Synthesis failed for ${voice.id}", e)
            null
        }?.let { (samples, rate) -> toPcm16(samples) to rate }
    }

    /** Floats in [-1, 1] to little-endian 16-bit PCM, which is what Android wants. */
    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = (samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt()
            out[i * 2] = (clamped and 0xFF).toByte()
            out[i * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Synchronized
    fun release() {
        tts?.release()
        tts = null
        loadedId = null
    }

    private fun load(context: Context, voice: BundledVoices.Voice): OfflineTts? {
        if (loadedId == voice.id && tts != null) return tts
        release()
        val dir = ModelAssets.ensure(context, voice) ?: return null
        fun at(name: String) = File(dir, name).absolutePath
        val espeak = voice.sharedDataDir
            ?.let { ModelAssets.dirFor(context, it).absolutePath }
            ?: ""
        return try {
            val vits = if (voice.kind == BundledVoices.Kind.VITS) {
                OfflineTtsVitsModelConfig(
                    model = at(voice.modelFile),
                    lexicon = voice.lexicon?.let { at(it) } ?: "",
                    tokens = at(voice.tokensFile),
                    dataDir = espeak,
                    dictDir = voice.dictDir?.let { at(it) } ?: "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f
                )
            } else {
                OfflineTtsVitsModelConfig("", "", "", "", "", 0.667f, 0.8f, 1.0f)
            }

            val matcha = if (voice.kind == BundledVoices.Kind.MATCHA) {
                OfflineTtsMatchaModelConfig(
                    acousticModel = at(voice.modelFile),
                    vocoder = voice.vocoderFile?.let { at(it) } ?: "",
                    lexicon = voice.lexicon?.let { at(it) } ?: "",
                    tokens = at(voice.tokensFile),
                    dataDir = espeak,
                    dictDir = voice.dictDir?.let { at(it) } ?: "",
                    noiseScale = 1.0f,
                    lengthScale = 1.0f
                )
            } else {
                OfflineTtsMatchaModelConfig("", "", "", "", "", "", 1.0f, 1.0f)
            }

            val model = OfflineTtsModelConfig(
                vits = vits,
                matcha = matcha,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val config = OfflineTtsConfig(
                model = model,
                ruleFsts = voice.ruleFsts.joinToString(",") { at(it) }
            )
            // File-backed, not asset-backed: see ModelAssets for why.
            OfflineTts(null, config).also {
                tts = it
                loadedId = voice.id
                Log.i(TAG, "Loaded ${voice.id} at ${it.sampleRate()} Hz, ${it.numSpeakers()} speakers")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load ${voice.id}", e)
            tts = null
            loadedId = null
            null
        }
    }
}
