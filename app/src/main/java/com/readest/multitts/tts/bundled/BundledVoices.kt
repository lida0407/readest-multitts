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
        /**
         * espeak-ng pronunciation data, shared by every Piper voice. Kept out of
         * the per-voice folders so it is stored and extracted once, not per voice.
         */
        val sharedDataDir: String? = null,
        val lexicon: String? = null,
        val dictDir: String? = null,
        val ruleFsts: List<String> = emptyList(),
        val speakerId: Int = 0,
        val licence: String
    )

    const val ESPEAK_DIR = "tts/espeak-ng-data"

    val ENGLISH = Voice(
        id = "bundled_en_us_ryan",
        displayName = "English · Ryan (bundled)",
        locale = Locale.US,
        kind = Kind.VITS,
        assetDir = "tts/en_US-ryan",
        modelFile = "model.onnx",
        tokensFile = "tokens.txt",
        sharedDataDir = ESPEAK_DIR,
        licence = "Piper en_US-ryan (MIT)"
    )

    // Piper rather than Matcha: same 22.05 kHz, a fifth of the size, and it
    // phonemises through the shared espeak data instead of its own 14MB
    // segmentation dictionary.
    val CHINESE = Voice(
        id = "bundled_zh_cn_xiao_ya",
        displayName = "中文 · 小雅 (bundled)",
        locale = Locale.SIMPLIFIED_CHINESE,
        kind = Kind.VITS,
        assetDir = "tts/zh_CN-xiao_ya",
        modelFile = "model.onnx",
        tokensFile = "tokens.txt",
        sharedDataDir = ESPEAK_DIR,
        lexicon = "lexicon.txt",
        ruleFsts = listOf("phone.fst", "date.fst", "number.fst"),
        licence = "Piper zh_CN-xiao_ya (MIT)"
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
