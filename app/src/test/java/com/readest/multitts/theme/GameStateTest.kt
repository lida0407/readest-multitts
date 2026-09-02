package com.readest.multitts.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level curve. The state itself needs a Context, but the arithmetic that
 * decides what the header shows does not, and that is where the mistakes live.
 */
class GameStateTest {

    @Test
    fun `a new reader is level zero`() {
        assertEquals(0, GameState.levelFor(0))
        assertEquals(0, GameState.levelFor(49))
    }

    @Test
    fun `fifty experience is exactly level one`() {
        assertEquals(1, GameState.levelFor(50))
        assertEquals(1, GameState.levelFor(199))
        assertEquals(2, GameState.levelFor(200))
    }

    @Test
    fun `levelFor and xpForLevel agree at every boundary`() {
        for (level in 0..60) {
            val threshold = GameState.xpForLevel(level)
            assertEquals("at the threshold for $level", level, GameState.levelFor(threshold))
            if (level > 0) {
                assertEquals(
                    "one short of $level",
                    level - 1,
                    GameState.levelFor(threshold - 1)
                )
            }
        }
    }

    @Test
    fun `levels get further apart as they rise`() {
        val early = GameState.xpForLevel(2) - GameState.xpForLevel(1)
        val later = GameState.xpForLevel(20) - GameState.xpForLevel(19)
        assertTrue("later levels should cost more: $early vs $later", later > early)
    }

    @Test
    fun `a level is never negative for a plausible total`() {
        // Ten thousand hours of narration, roughly.
        assertTrue(GameState.levelFor(50_000_000L) > 0)
    }
}
