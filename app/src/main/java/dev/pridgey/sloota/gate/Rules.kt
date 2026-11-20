package dev.pridgey.sloota.gate

import java.time.Instant
import java.time.ZoneId

/**
 * The pure rules behind gating, kept free of Android so they can be unit tested.
 *
 * A day for one app goes OPEN → WINDING_DOWN (limit reached, waiting for a natural stopping point)
 * → DONE (closed for the rest of the day). Added time puts it back to OPEN.
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
) {
    val limited: Boolean get() = limitMs > 0
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

    /** The session ends at a stopping point (or the cap). Only counts as a hit if it was winding down. */
    fun end(day: AppDay): AppDay =
        if (day.phase == Phase.WINDING_DOWN) day.copy(phase = Phase.DONE, hits = day.hits + 1) else day

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
        /** Finished days with a limit where the limit was never reached and no time was added. */
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
                if (key < today && day.limited && day.hits == 0 && day.extensions == 0) under++
            }
        }
        return Stats(under, hits, ext)
    }
}
