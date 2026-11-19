package dev.pridgey.sloota.gate

import android.content.Context
import android.os.SystemClock
import dev.pridgey.sloota.Targets
import dev.pridgey.sloota.log.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs the limits: counts time while a gated app is in front, starts the wind-down when the budget
 * runs out, and ends the session at the next stopping point (or the hard cap).
 *
 * Everything here runs on the main thread (accessibility events, broadcasts, the UI).
 */
object Gate {
    /** What the accessibility service does for the gate: it owns the overlay and the home button. */
    interface Host {
        /** True when the app is in the middle of something (a video, a post), so ending should wait. */
        fun midContent(app: Targets.App): Boolean
        fun endSession(app: Targets.App, why: EndKind)
    }

    enum class EndKind {
        /** Reached a natural stopping point after the limit. */
        STOPPING_POINT,
        /** Waited the full hard cap without one. */
        HARD_CAP,
        /** Opened again after it was done for the day. */
        BLOCKED,
    }

    private lateinit var store: GateStore
    private var days = mutableMapOf<String, MutableMap<String, AppDay>>()
    private var limits = mapOf<String, Int>()

    private val _changes = MutableStateFlow(0)
    /** Bumps whenever something the UI shows changes. */
    val changes: StateFlow<Int> = _changes

    var host: Host? = null

    private var current: Targets.App? = null
    private var screenOn = true
    private var lastTick = 0L
    private var lastSave = 0L
    private var dirty = false

    fun init(context: Context) {
        store = GateStore(context)
        limits = store.limits()
        days = store.days()
    }

    // --- Reading, for the UI ---

    fun today(): String = Rules.dayKey(System.currentTimeMillis())

    fun limitMinutes(app: Targets.App): Int = limits[app.id] ?: 0

    fun day(app: Targets.App): AppDay = todayRecords().getValue(app.id)

    fun stats(app: Targets.App? = null): Rules.Stats = Rules.stats(days, today(), app?.id)

    fun isWindingDown(pkg: String): Boolean {
        val app = Targets.byPackage(pkg) ?: return false
        return app == current && day(app).phase == Phase.WINDING_DOWN
    }

    // --- Changes from the UI ---

    fun setLimit(app: Targets.App, minutes: Int) {
        accrue()
        store.setLimit(app.id, minutes)
        limits = store.limits()
        val today = todayRecords()
        today[app.id] = Rules.applyLimit(today.getValue(app.id), minutes * 60_000L)
        EventLog.write("gate_limit", app.packages.first()) { put("min", minutes) }
        save(force = true)
    }

    fun addTime(app: Targets.App, minutes: Int) {
        accrue()
        val today = todayRecords()
        today[app.id] = Rules.extend(today.getValue(app.id), minutes * 60_000L)
        EventLog.write("gate_more", app.packages.first()) { put("min", minutes) }
        save(force = true)
    }

    // --- Events from the watchers ---

    fun onForeground(pkg: String?) {
        accrue()
        val app = Targets.byPackage(pkg)
        val previous = current
        current = app
        lastTick = SystemClock.elapsedRealtime()

        // Leaving the app while it winds down is the most natural stop of all.
        if (previous != null && previous != app) endIfWinding(previous, "left_app", show = false)

        if (app != null && day(app).phase == Phase.DONE) {
            EventLog.write("gate_block", pkg)
            host?.endSession(app, EndKind.BLOCKED)
        }
    }

    /** The service is going away (Android restarts it often). Not a stopping point: just stop counting. */
    fun detach() {
        accrue()
        current = null
        save(force = true)
    }

    /** [on] is true once the phone is unlocked (lock-screen time doesn't count), false when the screen turns off. */
    fun onScreen(on: Boolean) {
        accrue()
        screenOn = on
        lastTick = SystemClock.elapsedRealtime()
        if (!on) current?.let { endIfWinding(it, "screen_off", show = false) }
        save(force = !on)
    }

    /** A natural stopping point in [pkg]. Only acts if that app is in front and winding down. */
    fun stoppingPoint(pkg: String, reason: String) {
        val app = Targets.byPackage(pkg) ?: return
        if (app != current) return
        accrue()
        endIfWinding(app, reason, show = true)
    }

    /** Called about once a second by the accessibility service. */
    fun tick() {
        val before = current?.let { day(it).phase }
        accrue()
        val app = current ?: return save(force = false)
        val now = day(app)
        if (before == Phase.OPEN && now.phase == Phase.WINDING_DOWN) {
            EventLog.write("gate_wind", app.packages.first()) { put("used", now.usedMs) }
            // Nothing in progress (a feed, a menu): this is already a fine place to stop.
            if (host?.midContent(app) == false) endIfWinding(app, "not_mid_content", show = true)
            changed()
        } else if (Rules.pastHardCap(now)) {
            end(app, "hard_cap", EndKind.HARD_CAP)
        }
        save(force = false)
    }

    private fun endIfWinding(app: Targets.App, reason: String, show: Boolean) {
        if (day(app).phase != Phase.WINDING_DOWN) return
        if (show) end(app, reason, EndKind.STOPPING_POINT) else end(app, reason, null)
    }

    private fun end(app: Targets.App, reason: String, kind: EndKind?) {
        val today = todayRecords()
        today[app.id] = Rules.end(today.getValue(app.id))
        EventLog.write("gate_end", app.packages.first()) { put("reason", reason) }
        save(force = true)
        if (kind != null) host?.endSession(app, kind)
    }

    /** Adds the time since the last call to the app in front. */
    private fun accrue() {
        val now = SystemClock.elapsedRealtime()
        val app = current
        if (app != null && screenOn && lastTick > 0) {
            // Big gaps mean we missed events (service restarted, phone asleep); don't count them.
            val delta = (now - lastTick).takeIf { it in 1..MAX_GAP_MS } ?: 0L
            if (delta > 0) {
                val today = todayRecords()
                today[app.id] = Rules.addUse(today.getValue(app.id), delta)
                dirty = true
            }
        }
        lastTick = now
    }

    /** Today's records, creating any that are missing with the configured limits. */
    private fun todayRecords(): MutableMap<String, AppDay> {
        val key = today()
        val today = days.getOrPut(key) { mutableMapOf() }
        Targets.gated.forEach { app ->
            if (app.id !in today) {
                today[app.id] = AppDay(limitMs = (limits[app.id] ?: 0) * 60_000L)
                dirty = true
            }
        }
        return today
    }

    private fun save(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!dirty && !force) return
        if (!force && now - lastSave < SAVE_EVERY_MS) return
        store.saveDays(days)
        lastSave = now
        dirty = false
        changed()
    }

    private fun changed() {
        _changes.value++
    }

    private const val MAX_GAP_MS = 10_000L
    private const val SAVE_EVERY_MS = 10_000L
}
