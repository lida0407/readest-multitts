package com.readest.multitts.reader

import java.util.Locale

object LanguageDetector {

    fun detectLanguage(sampleText: String): Locale {
        if (sampleText.isBlank()) return Locale.SIMPLIFIED_CHINESE

        var cjkCount = 0
        var latinCount = 0
        var japaneseKanaCount = 0
        var cyrillicCount = 0

        for (ch in sampleText) {
            val ub = Character.UnicodeBlock.of(ch)
            when {
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A -> {
                    cjkCount++
                }
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA -> {
                    japaneseKanaCount++
                }
                ub == Character.UnicodeBlock.CYRILLIC ||
                ub == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> {
                    cyrillicCount++
                }
                ub == Character.UnicodeBlock.BASIC_LATIN ||
                ub == Character.UnicodeBlock.LATIN_1_SUPPLEMENT -> {
                    if (ch.isLetter()) latinCount++
                }
            }
        }

        return when {
            japaneseKanaCount > 5 -> Locale.JAPANESE
            cjkCount > 10 || cjkCount > latinCount -> Locale.SIMPLIFIED_CHINESE
            cyrillicCount > 10 -> Locale("ru", "RU")
            else -> Locale.US
        }
    }
}
