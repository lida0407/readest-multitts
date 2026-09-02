package com.readest.multitts.theme

import android.content.Context
import java.util.Calendar
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Reading progress expressed as a game: experience, level, streak, per-book
 * totals.
 *
 * Both game themes put this in their header, so it has to exist for them to
 * look like anything. It is local and unauthenticated by design — the app has
 * no accounts and no sync, and a reading streak is not worth breaking that for.
 *
 * Writes happen on the playback thread as sentences finish, so every mutation
 * is synchronized and cheap: one small prefs file, no cross-process access.
 */
class GameState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("game_state", Context.MODE_PRIVATE)

    companion object {
        const val XP_PER_SENTENCE = 10
        const val XP_PER_LOOKUP = 5

        /** Levels get further apart as they rise: Lv = floor(sqrt(xp / 50)). */
        fun levelFor(xp: Long): Int = floor(sqrt(xp / 50.0)).toInt()

        /** Total XP needed to reach a level, the inverse of [levelFor]. */
        fun xpForLevel(level: Int): Long = level.toLong() * level.toLong() * 50L

        private fun today(): Int {
            val c = Calendar.getInstance()
            return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        }
    }

    val totalXp: Long get() = prefs.getLong("total_xp", 0L)
    val level: Int get() = levelFor(totalXp)
    val sentencesRead: Int get() = prefs.getInt("sentences", 0)
    val wordsLookedUp: Int get() = prefs.getInt("lookups", 0)

    /** XP into the current level, and how much that level spans. */
    val levelProgress: Pair<Long, Long>
        get() {
            val lv = level
            val floor = xpForLevel(lv)
            val ceiling = xpForLevel(lv + 1)
            return (totalXp - floor) to (ceiling - floor)
        }

    /**
     * Consecutive days with at least one narrated sentence.
     *
     * Read lazily rather than reset by a timer: a streak that is two days stale
     * is simply over, and noticing that on the next read is enough.
     */
    val streakDays: Int
        get() {
            val last = prefs.getInt("last_active", 0)
            if (last == 0) return 0
            return if (today() - last <= 1) prefs.getInt("streak", 0) else 0
        }

    fun bookXp(bookId: String): Int = prefs.getInt("book_$bookId", 0)

    /** Badges earned, for the settings stat strip. */
    fun badges(cachedBytes: Long): List<String> = buildList {
        val gb = cachedBytes / 1_073_741_824.0
        if (gb >= 10) add("Offline archivist")
        if (gb >= 25) add("Librarian of silence")
        if (wordsLookedUp >= 100) add("Word collector")
        if (wordsLookedUp >= 1000) add("Lexicographer")
        if (streakDays >= 7) add("Seven days")
        if (streakDays >= 30) add("Thirty days")
        if (sentencesRead >= 1000) add("A thousand sentences")
        if (level >= 10) add("Level ten")
        if (level >= 25) add("Level twenty-five")
    }

    @Synchronized
    fun onSentenceNarrated(bookId: String?) {
        award(XP_PER_SENTENCE, bookId)
        prefs.edit()
            .putInt("sentences", sentencesRead + 1)
            .apply()
        touchStreak()
    }

    @Synchronized
    fun onWordLookedUp(bookId: String?) {
        award(XP_PER_LOOKUP, bookId)
        prefs.edit().putInt("lookups", wordsLookedUp + 1).apply()
    }

    private fun award(xp: Int, bookId: String?) {
        val editor = prefs.edit().putLong("total_xp", totalXp + xp)
        if (bookId != null) editor.putInt("book_$bookId", bookXp(bookId) + xp)
        editor.apply()
    }

    /** Extends the streak on a new day, restarts it after a gap. */
    private fun touchStreak() {
        val today = today()
        val last = prefs.getInt("last_active", 0)
        if (last == today) return
        val next = if (last != 0 && today - last == 1) prefs.getInt("streak", 0) + 1 else 1
        prefs.edit().putInt("streak", next).putInt("last_active", today).apply()
    }

    /** Sentences narrated since the app was opened — the mini-player's combo. */
    @Volatile
    var sessionSentences: Int = 0
        private set

    @Synchronized
    fun countSessionSentence() {
        sessionSentences++
    }

    fun forgetBook(bookId: String) {
        prefs.edit().remove("book_$bookId").apply()
    }
}
