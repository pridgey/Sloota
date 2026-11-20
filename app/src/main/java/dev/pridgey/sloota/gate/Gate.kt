package dev.pridgey.sloota.gate

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dev.pridgey.sloota.Targets
import dev.pridgey.sloota.log.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime

/**
 * Runs the limits: counts time while a gated app is in front, starts the wind-down when the budget
 * runs out, and ends the session at the next stopping point (or the hard cap, if one is set).
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
        /** Opened outside its open hours. */
        CLOSED_HOURS,
    }

    private lateinit var store: GateStore
    private var days = mutableMapOf<String, MutableMap<String, AppDay>>()
    private var limits = mapOf<String, Int>()
    private var caps = mapOf<String, Int>()
    private var windows = mapOf<String, Pair<Int, Int>>()

    private val _changes = MutableStateFlow(0)
    /** Bumps whenever something the UI shows changes. */
    val changes: StateFlow<Int> = _changes

    var host: Host? = null

    private var current: Targets.App? = null
    /** The app in front was opened inside its open hours, so their ending is a wind-down, not a block. */
    private var openedInWindow = false
    private var power: PowerManager? = null
    private var keyguard: KeyguardManager? = null
    private var lastTick = 0L
    private var lastSave = 0L
    private var dirty = false

    fun init(context: Context) {
        store = GateStore(context)
        power = context.getSystemService(PowerManager::class.java)
        keyguard = context.getSystemService(KeyguardManager::class.java)
        limits = store.limits()
        caps = store.caps()
        windows = store.windows()
        days = store.days()
    }

    // --- Reading, for the UI ---

    fun today(): String = Rules.dayKey(System.currentTimeMillis())

    fun limitMinutes(app: Targets.App): Int = limits[app.id] ?: 0

    /** Hard cap in minutes; 0 = off (wait for a real stopping point). */
    fun capMinutes(app: Targets.App): Int = caps[app.id] ?: 0

    /** Configured open hours (start, end) in minutes after midnight, or null for any time. */
    fun windowConfigured(app: Targets.App): Pair<Int, Int>? = windows[app.id]

    /** Whether the app is inside today's open hours right now (always true without hours). */
    fun inHours(app: Targets.App): Boolean = Rules.inWindow(day(app), minuteNow())

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

    /** Applies right away, including to a wind-down in progress. */
    fun setCap(app: Targets.App, minutes: Int) {
        store.setCap(app.id, minutes)
        caps = store.caps()
        windows = store.windows()
        EventLog.write("gate_cap", app.packages.first()) { put("min", minutes) }
        changed()
    }

    /** Applies right away, today included. */
    fun setWindow(app: Targets.App, window: Pair<Int, Int>?) {
        accrue()
        store.setWindow(app.id, window?.takeIf { Rules.validWindow(it.first, it.second) })
        windows = store.windows()
        val today = todayRecords()
        today[app.id] = Rules.applyWindow(today.getValue(app.id), window)
        EventLog.write("gate_hours", app.packages.first()) {
            put("start", window?.first ?: -1)
            put("end", window?.second ?: -1)
        }
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

        openedInWindow = false
        if (app != null && !blockIfClosed(app)) openedInWindow = Rules.inWindow(day(app), minuteNow())
    }

    /** Shows the stop screen if [app] is done for today or outside its hours. True if it did. */
    private fun blockIfClosed(app: Targets.App): Boolean {
        val d = day(app)
        val kind = when {
            d.phase == Phase.DONE -> EndKind.BLOCKED
            d.phase == Phase.OPEN && !Rules.inWindow(d, minuteNow()) -> EndKind.CLOSED_HOURS
            else -> return false
        }
        EventLog.write("gate_block", app.packages.first()) { put("why", if (kind == EndKind.BLOCKED) "done" else "hours") }
        showEnd(app, kind)
        return true
    }

    /** The stop screen covers the app until it's dismissed; stop counting it as in use meanwhile. */
    private fun showEnd(app: Targets.App, kind: EndKind) {
        host?.endSession(app, kind)
        accrue()
        current = null
    }

    /** The service is going away (Android restarts it often). Not a stopping point: just stop counting. */
    fun detach() {
        accrue()
        current = null
        save(force = true)
    }

    /**
     * The screen turned on, off, or the phone was unlocked. Whether time counts is read from the
     * system on every tick (see [inUse]), so this only settles the time so far and ends a wind-down.
     */
    fun onScreen(on: Boolean) {
        accrue()
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
        var now = day(app)
        var why = "budget"
        if (openedInWindow && now.phase == Phase.OPEN && !Rules.inWindow(now, minuteNow())) {
            // The open hours ended mid-session.
            now = Rules.closeWindow(now)
            todayRecords()[app.id] = now
            openedInWindow = false
            why = "hours"
            dirty = true
        }
        // Still in front of a closed app, e.g. back from a screen-off that ended the session.
        if (inUse() && blockIfClosed(app)) return save(force = true)
        if (before == Phase.OPEN && now.phase == Phase.WINDING_DOWN) {
            EventLog.write("gate_wind", app.packages.first()) {
                put("used", now.usedMs)
                put("why", why)
            }
            // Nothing in progress (a feed, a menu): this is already a fine place to stop.
            if (host?.midContent(app) == false) endIfWinding(app, "not_mid_content", show = true)
            changed()
        } else if (Rules.pastHardCap(now, capMinutes(app) * 60_000L)) {
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
        if (kind != null) showEnd(app, kind)
    }

    /** Adds the time since the last call to the app in front. */
    private fun accrue() {
        val now = SystemClock.elapsedRealtime()
        val app = current
        if (app != null && inUse() && lastTick > 0) {
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

    /**
     * True while the screen is on and unlocked. Asked of the system each time rather than tracked from
     * broadcasts: some phones never deliver ACTION_USER_PRESENT, which left counting stuck off.
     */
    private fun inUse(): Boolean =
        power?.isInteractive != false && keyguard?.isKeyguardLocked != true

    /** Today's records, creating any that are missing with the configured limits. */
    private fun todayRecords(): MutableMap<String, AppDay> {
        val key = today()
        val today = days.getOrPut(key) { mutableMapOf() }
        Targets.gated.forEach { app ->
            if (app.id !in today) {
                val w = windows[app.id]
                today[app.id] = AppDay(
                    limitMs = (limits[app.id] ?: 0) * 60_000L,
                    windowStart = w?.first ?: -1,
                    windowEnd = w?.second ?: -1,
                )
                dirty = true
            }
        }
        return today
    }

    private fun minuteNow(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

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
