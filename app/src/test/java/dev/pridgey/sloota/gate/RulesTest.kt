package dev.pridgey.sloota.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

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
        assertFalse(Rules.pastHardCap(d, 3 * min))
        d = Rules.addUse(d, 3 * min)
        assertTrue(Rules.pastHardCap(d, 3 * min))
        assertFalse(Rules.pastHardCap(d, 5 * min))
    }

    @Test
    fun `no hard cap waits for a stopping point however long it takes`() {
        var d = Rules.addUse(AppDay(limitMs = 15 * min), 15 * min)
        d = Rules.addUse(d, 120 * min)
        assertEquals(Phase.WINDING_DOWN, d.phase)
        assertFalse(Rules.pastHardCap(d, 0))
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

    // --- Open hours ---

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `open hours, same day and overnight`() {
        val evening = DayOfWeek.entries.associateWith { DayHours.Open(h(18), h(21)) }
        assertTrue(Rules.isOpen(evening, fri(18)))
        assertTrue(Rules.isOpen(evening, fri(20, 59)))
        assertFalse(Rules.isOpen(evening, fri(21)))
        assertFalse(Rules.isOpen(evening, fri(9)))
        val late = DayOfWeek.entries.associateWith { DayHours.Open(h(22), h(1)) }
        assertTrue(Rules.isOpen(late, fri(23, 30)))
        assertTrue(Rules.isOpen(late, fri(0, 30)))
        assertFalse(Rules.isOpen(late, fri(1)))
        assertTrue(Rules.isOpen(emptyMap(), fri(3)))
    }

    // 2026-11-20 is a Friday.
    private fun fri(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, 20, hour, minute)

    @Test
    fun `overnight hours spill into a day with its own rule`() {
        val week = mapOf(
            DayOfWeek.FRIDAY to DayHours.Open(h(22), h(1)),
            DayOfWeek.SATURDAY to DayHours.Closed,
        )
        assertTrue(Rules.isOpen(week, fri(23)))
        assertTrue(Rules.isOpen(week, fri(23).plusHours(1).plusMinutes(30)))
        assertFalse(Rules.isOpen(week, fri(23).plusHours(2)))
        assertFalse(Rules.isOpen(week, fri(12).plusDays(1)))
    }

    @Test
    fun `next open skips closed days and close follows adjoining hours`() {
        val week = DayOfWeek.entries.associateWith<DayOfWeek, DayHours> { DayHours.Closed } +
            mapOf(
                DayOfWeek.FRIDAY to DayHours.Open(h(18), h(4)),
                DayOfWeek.SATURDAY to DayHours.Open(h(4), h(6)),
                DayOfWeek.MONDAY to DayHours.Open(h(9), h(10)),
            )
        assertEquals(fri(18), Rules.nextOpen(week, fri(9)))
        assertEquals(fri(6).plusDays(1), Rules.closesAt(week, fri(20)))
        assertEquals(fri(9).plusDays(3), Rules.nextOpen(week, fri(7).plusDays(1)))
        assertEquals(null, Rules.nextOpen(DayOfWeek.entries.associateWith { DayHours.Closed }, fri(9)))
    }

    @Test
    fun `hours stay between 4 AM and 4 AM the next day`() {
        assertTrue(Rules.fitsDay(h(4), h(9)))
        assertTrue(Rules.fitsDay(h(22), h(4)))
        assertTrue(Rules.fitsDay(h(4), h(3, 59)))
        assertFalse(Rules.fitsDay(h(3, 59), h(9)))
        assertFalse(Rules.fitsDay(h(22), h(4, 1)))
    }

    @Test
    fun `saved hours are trimmed to the 4 AM boundary`() {
        assertEquals(DayHours.Open(h(22), h(4)), Rules.fitDay(DayHours.Open(h(22), h(5))))
        assertEquals(DayHours.Open(h(4), h(5)), Rules.fitDay(DayHours.Open(h(2), h(5))))
        assertEquals(DayHours.Open(h(4), h(1)), Rules.fitDay(DayHours.Open(h(2), h(1))))
        assertEquals(DayHours.Closed, Rules.fitDay(DayHours.Open(h(1), h(3))))
        assertEquals(DayHours.Open(h(18), h(21)), Rules.fitDay(DayHours.Open(h(18), h(21))))
    }

    @Test
    fun `day groups read naturally`() {
        val week = (DayOfWeek.MONDAY..DayOfWeek.FRIDAY).associateWith { DayHours.Open(h(18), h(21)) }
        val groups = Rules.groups(week)
        assertEquals("Mon–Fri", Rules.daysLabel(groups[0].first, Locale.US))
        assertEquals("Sat, Sun", Rules.daysLabel(groups[1].first, Locale.US))
        assertEquals(DayHours.AnyTime, groups[1].second)
        assertEquals("Every day", Rules.daysLabel(DayOfWeek.entries, Locale.US))
    }

    private operator fun DayOfWeek.rangeTo(end: DayOfWeek) = DayOfWeek.entries.filter { it.value in value..end.value }

    @Test
    fun `hours ending winds down, then closes until they reopen`() {
        val d = Rules.closeWindow(AppDay(windowStart = h(18), windowEnd = h(21), usedMs = 40 * min))
        assertEquals(Phase.WINDING_DOWN, d.phase)
        val ended = Rules.end(d)
        assertEquals(Phase.OPEN, ended.phase)
        assertEquals(1, ended.hits)
    }

    @Test
    fun `with hours and a budget, a spent budget still closes the day`() {
        var d = AppDay(limitMs = 30 * min, windowStart = h(18), windowEnd = h(21))
        d = Rules.addUse(d, 30 * min)
        assertEquals(Phase.WINDING_DOWN, d.phase)
        assertEquals(Phase.DONE, Rules.end(d).phase)
    }

    @Test
    fun `hours changes apply right away`() {
        val set = Rules.applyWindow(AppDay(), h(18) to h(21))
        assertEquals(h(18), set.windowStart)
        assertEquals(h(6), Rules.applyWindow(set, h(6) to h(9)).windowStart)
        assertFalse(Rules.applyWindow(set, null).windowed)
        assertFalse(Rules.applyWindow(AppDay(), h(5) to h(5)).windowed)
    }

    @Test
    fun `days with hours count as under when never closed`() {
        val days = mapOf(
            "2026-11-18" to mapOf("youtube" to AppDay(windowStart = h(18), windowEnd = h(21))),
            "2026-11-19" to mapOf("youtube" to AppDay(windowStart = h(18), windowEnd = h(21), hits = 1)),
        )
        assertEquals(1, Rules.stats(days, "2026-11-20").underDays)
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
