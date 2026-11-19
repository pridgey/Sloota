package dev.pridgey.sloota.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RulesTest {
    private val min = 60_000L
    private val zone = ZoneId.of("America/New_York")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `days roll over at 4 AM`() {
        assertEquals("2026-11-17", Rules.dayKey(at(2026, 11, 17, 23, 30), zone))
        assertEquals("2026-11-17", Rules.dayKey(at(2026, 11, 18, 1, 36), zone))
        assertEquals("2026-11-18", Rules.dayKey(at(2026, 11, 18, 4, 0), zone))
    }

    @Test
    fun `winds down when the budget runs out, then hard caps`() {
        var d = AppDay(limitMs = 30 * min)
        d = Rules.addUse(d, 29 * min)
        assertEquals(Phase.OPEN, d.phase)
        d = Rules.addUse(d, 1 * min)
        assertEquals(Phase.WINDING_DOWN, d.phase)
        assertFalse(Rules.pastHardCap(d))
        d = Rules.addUse(d, Rules.HARD_CAP_MS)
        assertTrue(Rules.pastHardCap(d))
    }

    @Test
    fun `no limit never winds down`() {
        val d = Rules.addUse(AppDay(), 500 * min)
        assertEquals(Phase.OPEN, d.phase)
    }

    @Test
    fun `ending counts a hit only when winding down`() {
        val open = AppDay(limitMs = 30 * min)
        assertEquals(open, Rules.end(open))
        val ended = Rules.end(Rules.addUse(open, 30 * min))
        assertEquals(Phase.DONE, ended.phase)
        assertEquals(1, ended.hits)
        assertEquals(ended, Rules.end(ended))
    }

    @Test
    fun `more time reopens a finished day`() {
        val done = Rules.end(Rules.addUse(AppDay(limitMs = 30 * min), 31 * min))
        val more = Rules.extend(done, 10 * min)
        assertEquals(Phase.OPEN, more.phase)
        assertEquals(1, more.extensions)
        assertEquals(9 * min, more.remainingMs)
        val again = Rules.addUse(more, 9 * min)
        assertEquals(Phase.WINDING_DOWN, again.phase)
    }

    @Test
    fun `stricter limits apply today, looser ones wait`() {
        assertEquals(15 * min, Rules.limitToday(30 * min, 15 * min))
        assertEquals(30 * min, Rules.limitToday(30 * min, 60 * min))
        assertEquals(30 * min, Rules.limitToday(30 * min, 0))
        assertEquals(20 * min, Rules.limitToday(0, 20 * min))
        val d = Rules.applyLimit(AppDay(limitMs = 60 * min, usedMs = 40 * min), 30 * min)
        assertEquals(Phase.WINDING_DOWN, d.phase)
    }

    @Test
    fun `stats count under days, hits and extensions`() {
        val days = mapOf(
            "2026-11-16" to mapOf("youtube" to AppDay(limitMs = 30 * min, usedMs = 10 * min), "lurk" to AppDay()),
            "2026-11-17" to mapOf("youtube" to AppDay(limitMs = 30 * min, hits = 2, extensions = 1)),
            "2026-11-18" to mapOf("youtube" to AppDay(limitMs = 30 * min)),
        )
        assertEquals(Rules.Stats(underDays = 1, hits = 2, extensions = 1), Rules.stats(days, "2026-11-18"))
        assertEquals(Rules.Stats(0, 0, 0), Rules.stats(days, "2026-11-18", "lurk"))
    }
}

class YouTubeRulesTest {
    private val height = 2244

    @Test
    fun `shorts swipes from the logs count as the next short`() {
        // Real settled scrolls: y0 -844 → 0, and a drag-then-fling with dy 1016.
        assertTrue(YouTubeRules.isNextShort("reel_player_underlay", 0, -844, 0, height))
        assertTrue(YouTubeRules.isNextShort("reel_player_underlay", 1016, -169, 0, height))
        // A small nudge, and a scroll in the comments, don't.
        assertFalse(YouTubeRules.isNextShort("reel_player_underlay", 53, 0, 0, height))
        assertFalse(YouTubeRules.isNextShort("results", 1731, 0, 0, height))
    }

    @Test
    fun `screens and back to feed`() {
        assertEquals(YouTubeRules.Screen.SHORTS, YouTubeRules.screen(true, 1f))
        assertEquals(YouTubeRules.Screen.WATCH, YouTubeRules.screen(false, 1f))
        assertEquals(YouTubeRules.Screen.FEED, YouTubeRules.screen(false, 0.45f))
        assertTrue(YouTubeRules.isBackToFeed(YouTubeRules.Screen.WATCH, YouTubeRules.Screen.FEED))
        assertFalse(YouTubeRules.isBackToFeed(null, YouTubeRules.Screen.FEED))
        assertFalse(YouTubeRules.isBackToFeed(YouTubeRules.Screen.FEED, YouTubeRules.Screen.FEED))
    }

    @Test
    fun `autoplay is a new title`() {
        assertTrue(YouTubeRules.isNewVideo("BIGTOP BURGER: OVEN", "BIGTOP BURGER: VENEZIA"))
        assertFalse(YouTubeRules.isNewVideo("BIGTOP BURGER: OVEN", "BIGTOP BURGER: OVEN"))
        assertFalse(YouTubeRules.isNewVideo(null, "First video"))
        assertFalse(YouTubeRules.isNewVideo("A", ""))
    }
}
