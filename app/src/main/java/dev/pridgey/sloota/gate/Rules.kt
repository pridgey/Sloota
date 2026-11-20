package dev.pridgey.sloota.gate

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The pure rules behind gating, kept free of Android so they can be unit tested.
 *
 * A day for one app goes OPEN → WINDING_DOWN (limit reached, waiting for a natural stopping point)
 * → DONE (closed for the rest of the day). Added time puts it back to OPEN.
 *
 * An app can also have open hours (a timebox). Outside them it's closed. When they end mid-session
 * it winds down the same way, but ends back in OPEN, since the hours (not the budget) are what closed it.
 */
enum class Phase { OPEN, WINDING_DOWN, DONE }

/** One app on one day. Times are milliseconds of the app being in front with the screen on. */
data class AppDay(
    /** This day's limit; 0 means no limit. */
    val limitMs: Long = 0,
    val usedMs: Long = 0,
    /** Time added from Sloota today. */
    val extraMs: Long = 0,
    val phase: Phase = Phase.OPEN,
    /** [usedMs] when the wind-down started, for the hard cap. */
    val windStartMs: Long = 0,
    /** Times a session ended at the limit (a stopping point, the hard cap, or leaving the app). */
    val hits: Int = 0,
    /** Times more time was added. */
    val extensions: Int = 0,
    /** Today's open hours as minutes after midnight; -1 for none. End may be before start (overnight). */
    val windowStart: Int = -1,
    val windowEnd: Int = -1,
) {
    val limited: Boolean get() = limitMs > 0
    val windowed: Boolean get() = Rules.validWindow(windowStart, windowEnd)
    /** The budget, not the hours, is what's used up. */
    val budgetSpent: Boolean get() = limited && usedMs >= budgetMs
    val budgetMs: Long get() = limitMs + extraMs
    val remainingMs: Long get() = (budgetMs - usedMs).coerceAtLeast(0)
}

object Rules {

    /** Days roll over at 4 AM, so a late-night session counts toward the evening it started in. */
    const val DAY_START_HOUR = 4L

    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).minusHours(DAY_START_HOUR).toLocalDate().toString()

    /** Adds foreground time. Starts the wind-down when the budget runs out. */
    fun addUse(day: AppDay, ms: Long): AppDay {
        if (ms <= 0) return day
        val used = day.usedMs + ms
        return if (day.phase == Phase.OPEN && day.limited && used >= day.budgetMs) {
            day.copy(usedMs = used, phase = Phase.WINDING_DOWN, windStartMs = used)
        } else {
            day.copy(usedMs = used)
        }
    }

    /**
     * True once a wind-down has waited [capMs] for a stopping point. A cap of 0 (the default) means no
     * cap: the wind-down waits for a real stopping point however long it takes.
     */
    fun pastHardCap(day: AppDay, capMs: Long): Boolean =
        capMs > 0 && day.phase == Phase.WINDING_DOWN && day.usedMs - day.windStartMs >= capMs

    /**
     * The session ends at a stopping point (or the cap). Only counts as a hit if it was winding down.
     * A spent budget closes the app for the day; open hours ending leave it OPEN (the hours keep it shut).
     */
    fun end(day: AppDay): AppDay = when {
        day.phase != Phase.WINDING_DOWN -> day
        day.budgetSpent -> day.copy(phase = Phase.DONE, hits = day.hits + 1)
        else -> day.copy(phase = Phase.OPEN, windStartMs = 0, hits = day.hits + 1)
    }

    // --- Open hours ---

    fun validWindow(start: Int, end: Int): Boolean = start in 0 until DAY_MIN && end in 0 until DAY_MIN && start != end

    /** Whether [minute] (after midnight) is inside today's open hours. Always true without hours. */
    fun inWindow(day: AppDay, minute: Int): Boolean {
        if (!day.windowed) return true
        val (s, e) = day.windowStart to day.windowEnd
        return if (s < e) minute in s until e else minute >= s || minute < e
    }

    /** The open hours just ended while in the app: wind down to the next stopping point. */
    fun closeWindow(day: AppDay): AppDay =
        if (day.phase == Phase.OPEN) day.copy(phase = Phase.WINDING_DOWN, windStartMs = day.usedMs) else day

    /** Hours changes apply right away; pass null (or an invalid range) to remove them. */
    fun applyWindow(day: AppDay, window: Pair<Int, Int>?): AppDay =
        if (window != null && validWindow(window.first, window.second)) {
            day.copy(windowStart = window.first, windowEnd = window.second)
        } else {
            day.copy(windowStart = -1, windowEnd = -1)
        }

    /** "6:00 PM" (or "18:00", per locale) for minutes after midnight. */
    fun clock(minute: Int): String =
        LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    const val DAY_MIN = 24 * 60

    fun extend(day: AppDay, ms: Long): AppDay {
        val more = day.copy(extraMs = day.extraMs + ms, extensions = day.extensions + 1)
        return if (more.usedMs < more.budgetMs) more.copy(phase = Phase.OPEN, windStartMs = 0) else more
    }

    /**
     * Today's limit after the configured limit changes. A stricter limit applies now; a looser one
     * (including turning it off) waits for tomorrow, so changing the limit isn't a back door to more time.
     */
    fun limitToday(currentMs: Long, newMs: Long): Long = when {
        newMs <= 0 -> currentMs
        currentMs <= 0 -> newMs
        else -> minOf(currentMs, newMs)
    }

    /** Applies a new limit to today's record, starting the wind-down if it's already used up. */
    fun applyLimit(day: AppDay, newMs: Long): AppDay {
        val limit = limitToday(day.limitMs, newMs)
        if (limit == day.limitMs) return day
        val changed = day.copy(limitMs = limit)
        return if (changed.phase == Phase.OPEN && changed.usedMs >= changed.budgetMs) {
            changed.copy(phase = Phase.WINDING_DOWN, windStartMs = changed.usedMs)
        } else {
            changed
        }
    }

    data class Stats(
        /** Finished days with a limit or open hours that never had to close the app, and no time was added. */
        val underDays: Int,
        /** Sessions Sloota ended at the limit. */
        val hits: Int,
        /** Times more time was added. */
        val extensions: Int,
    )

    /** [days] is day key → app id → record. Pass [appId] for one app, or null for all of them. */
    fun stats(days: Map<String, Map<String, AppDay>>, today: String, appId: String? = null): Stats {
        var under = 0
        var hits = 0
        var ext = 0
        days.forEach { (key, apps) ->
            apps.forEach { (id, day) ->
                if (appId != null && id != appId) return@forEach
                hits += day.hits
                ext += day.extensions
                if (key < today && (day.limited || day.windowed) && day.hits == 0 && day.extensions == 0) under++
            }
        }
        return Stats(under, hits, ext)
    }
}
