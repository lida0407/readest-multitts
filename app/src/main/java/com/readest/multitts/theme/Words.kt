package com.readest.multitts.theme

/**
 * The words a theme uses for the app's own concepts.
 *
 * Chinese labels are deliberately identical across themes — the game framing is
 * an English-language conceit, and inventing Chinese equivalents for "dungeon
 * floor" would read as a translation error rather than as flavour.
 */
data class Words(
    /** Library screen title. */
    val shelf: String,
    /** Import CTA. */
    val importBook: String,
    /** Settings hub title. */
    val settings: String,
    /** Chip that opens settings. */
    val settingsChip: String,
    /** Chapter, formatted with its number and the total. */
    val chapter: (Int, Int) -> String,
    /** Short form for a chapter number alone. */
    val chapterShort: (Int) -> String,
    /** Voice & playback row. */
    val voice: String,
    /** Offline audio row and sheet. */
    val offlineAudio: String,
    /** Contents sheet title. */
    val contents: String,
    /** Eyebrow above a word that was just looked up. */
    val wordFound: String,
    /** The narrated part of the scrubber. */
    val narratedLegend: String,
    /** The cached part of the scrubber. */
    val cachedLegend: String,
    /** Sentences narrated this session, formatted with the count. */
    val combo: (Int) -> String,
    /** Streak line under the app name, formatted with the day count. */
    val streak: (Int) -> String,
    /** Per-book experience, formatted with the amount. */
    val bookXp: (Int) -> String,
    /** Caching is running, formatted with the chapter number. */
    val caching: (Int) -> String,
    /** Shelf order row. */
    val shelfOrder: String,
    /** Display settings row. */
    val display: String
) {
    companion object {
        val CLASSIC = Words(
            shelf = "MY SHELF 我的书架",
            importBook = "＋ Import a book · 导入书籍",
            settings = "Settings · 设置",
            settingsChip = "⚙ Settings · 设置",
            chapter = { n, total -> "Chapter $n / $total" },
            chapterShort = { n -> "Chapter $n" },
            voice = "Voice & playback",
            offlineAudio = "Offline audio",
            contents = "Contents · 目录",
            wordFound = "LOOKED UP",
            narratedLegend = "read",
            cachedLegend = "cached",
            combo = { n -> "$n read" },
            streak = { d -> "$d day streak · 连读 $d 天" },
            bookXp = { xp -> "$xp XP" },
            caching = { n -> "Caching chapter $n…" },
            shelfOrder = "Shelf order",
            display = "Display & themes"
        )

        val PIXEL = Words(
            shelf = "QUEST LOG 我的书架",
            importBook = "＋ NEW QUEST · 导入书籍",
            settings = "STATUS 设置",
            settingsChip = "⚙ STATUS 设置",
            chapter = { n, total -> "FLOOR $n / $total" },
            chapterShort = { n -> "FLOOR $n" },
            voice = "Voice & playback · 声音",
            offlineAudio = "Offline audio · 离线音频",
            contents = "DUNGEON MAP · 目录",
            wordFound = "ITEM FOUND · +5 GOLD",
            narratedLegend = "NARRATED",
            cachedLegend = "CACHED",
            combo = { n -> "COMBO\n×$n" },
            streak = { d -> "$d DAY STREAK 连读" },
            bookXp = { xp -> "★ $xp XP" },
            caching = { n -> "Forging floor $n…" },
            shelfOrder = "Quest log order · 书架排序",
            display = "Display & skins · 显示"
        )

        val COZY = Words(
            shelf = "MY TRAILS 我的书架",
            importBook = "＋ Start a new journey · 导入书籍",
            settings = "Camp · 设置",
            settingsChip = "⚙ Camp · 设置",
            chapter = { n, total -> "Stone $n of $total" },
            chapterShort = { n -> "Stone $n" },
            voice = "Your companion · 声音",
            offlineAudio = "The Grove · 离线音频",
            contents = "The trail · 目录",
            wordFound = "NEW WORD COLLECTED · +5 SEEDS",
            narratedLegend = "walked",
            cachedLegend = "path paved",
            combo = { n -> "♥ +$n\nthis walk" },
            streak = { d -> "Day $d of your journey · 连续第 $d 天" },
            bookXp = { xp -> "♥ $xp" },
            caching = { n -> "Growing stone $n…" },
            shelfOrder = "Trail order · 书架排序",
            display = "Display & skins · 显示"
        )
    }
}
