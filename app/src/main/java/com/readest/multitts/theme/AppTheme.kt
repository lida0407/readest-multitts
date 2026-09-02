package com.readest.multitts.theme

import android.content.Context
import com.readest.multitts.R

/**
 * The app's visual identity, chosen by the reader.
 *
 * A theme is more than a palette here: Pixel and Cozy rename things ("chapter"
 * becomes a dungeon floor or a stepping stone), so the vocabulary travels with
 * the style rather than being scattered through the layouts as fixed strings.
 */
enum class AppTheme(
    val id: String,
    val styleRes: Int,
    val label: String,
    val blurb: String,
    val words: Words
) {
    CLASSIC(
        id = "classic",
        styleRes = R.style.Theme_Readest_Classic,
        label = "Classic · 经典",
        blurb = "The plain reader: royal blue on white.",
        words = Words.CLASSIC
    ),
    PIXEL(
        id = "pixel",
        styleRes = R.style.Theme_Readest_Pixel,
        label = "Pixel · 像素",
        blurb = "Retro RPG. Books are quests, chapters are dungeon floors.",
        words = Words.PIXEL
    ),
    COZY(
        id = "cozy",
        styleRes = R.style.Theme_Readest_Cozy,
        label = "Cozy · 温暖",
        blurb = "A warm walk. Books are trails, chapters are stepping stones.",
        words = Words.COZY
    );

    companion object {
        private const val PREFS = "reader_settings"
        private const val KEY = "app_theme"

        fun current(context: Context): AppTheme {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, CLASSIC.id)
            return entries.firstOrNull { it.id == saved } ?: CLASSIC
        }

        fun save(context: Context, theme: AppTheme) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, theme.id).apply()
        }
    }
}
