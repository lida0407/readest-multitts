package com.readest.multitts.tts.bundled

import java.util.Locale

/**
 * The voices shipped inside the APK.
 *
 * These are open-licensed models, deliberately: the point of bundling is that
 * the app can be handed to someone else and simply work, which rules out the
 * proprietary voice packs that engines like MultiTTS side-load.
 */
object BundledVoices {

    /** Which sherpa-onnx model family a voice belongs to; they configure differently. */
    enum class Kind { VITS, MATCHA }

    data class Voice(
        val id: String,
        val displayName: String,
        val locale: Locale,
        val kind: Kind,
        /** Folder under assets/ holding the model files. */
        val assetDir: String,
        val modelFile: String,
        val tokensFile: String,
        /** Matcha splits synthesis in two: an acoustic model and a vocoder. */
        val vocoderFile: String? = null,
        /** Piper voices need espeak-ng data; the Chinese models need a lexicon. */
        val dataDir: String? = null,
        val lexicon: String? = null,
        val dictDir: String? = null,
        val ruleFsts: List<String> = emptyList(),
        val speakerId: Int = 0,
        val licence: String
    )

    val ENGLISH = Voice(
        id = "bundled_en_us_ryan",
        displayName = "English · Ryan (bundled)",
        locale = Locale.US,
        kind = Kind.VITS,
        assetDir = "tts/vits-piper-en_US-ryan-medium",
        modelFile = "en_US-ryan-medium.onnx",
        tokensFile = "tokens.txt",
        dataDir = "espeak-ng-data",
        licence = "Piper en_US-ryan (MIT)"
    )

    // Matcha at 22.05 kHz rather than the AIShell-3 VITS model, which synthesises
    // at 8 kHz — fine for a prompt, far too thin for hours of listening.
    val CHINESE = Voice(
        id = "bundled_zh_cn_baker",
        displayName = "中文 · Baker (bundled)",
        locale = Locale.SIMPLIFIED_CHINESE,
        kind = Kind.MATCHA,
        assetDir = "tts/matcha-icefall-zh-baker",
        modelFile = "model-steps-3.onnx",
        tokensFile = "tokens.txt",
        vocoderFile = "hifigan_v2.onnx",
        lexicon = "lexicon.txt",
        dictDir = "dict",
        ruleFsts = listOf("phone.fst", "date.fst", "number.fst"),
        licence = "icefall Matcha Baker + HiFiGAN (Apache-2.0)"
    )

    val ALL = listOf(ENGLISH, CHINESE)

    fun byId(id: String?): Voice? = ALL.firstOrNull { it.id == id }

    /** The voice that best covers [locale], falling back to English. */
    fun forLocale(locale: Locale): Voice {
        val language = locale.language.lowercase(Locale.ROOT)
        return when (language) {
            "zh", "yue" -> CHINESE
            else -> ENGLISH
        }
    }
}
