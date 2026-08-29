package com.readest.multitts.model

import java.util.Locale

data class TTSVoiceInfo(
    val id: String,
    val name: String,
    val locale: Locale,
    val enginePackage: String,
    val isMultiTts: Boolean = false,
    /** Works with no network — the only voices that can synthesize in airplane mode. */
    val isOffline: Boolean = false
)

data class TTSEngineInfo(
    val packageName: String,
    val label: String,
    val isMultiTts: Boolean = false,
    val isDefault: Boolean = false
)
