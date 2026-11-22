package dev.pridgey.sloota.watch

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import dev.pridgey.sloota.State
import dev.pridgey.sloota.Targets
import dev.pridgey.sloota.gate.EndOverlay
import dev.pridgey.sloota.gate.Gate
import dev.pridgey.sloota.gate.Phase
import dev.pridgey.sloota.gate.Rules
import dev.pridgey.sloota.gate.YouTubeRules
import dev.pridgey.sloota.log.EventLog
import dev.pridgey.sloota.log.NodeDump
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the gate (see [Gate]): tracks which app is in front, spots YouTube's stopping points, and shows
 * the end screen. It also logs what happens in the watched apps, so stopping-point rules can be written from real data:
 *
 * - foreground: which app is in front (all apps, so sessions have clear starts and ends)
 * - window:     a screen, dialog or pane changed in a watched app
 * - scroll:     one finished scroll gesture, with item indexes (a pager swipe moves first-visible by 1)
 * - click:      a tap, with what was tapped
 * - snapshot:   the visible view tree, after windows change, scrolls settle, or the layout changes
 * - screen_on / screen_off / unlocked
 */
class WatchService : AccessibilityService(), Gate.Host {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: EndOverlay

    private val ticker = object : Runnable {
        override fun run() {
            Gate.tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    // --- YouTube screen, for "back to the feed" ---
    private var ytScreen: YouTubeRules.Screen? = null
    private val classifyRunnable = Runnable { classifyYouTube() }

    // --- Snapshots ---
    private var pendingReason: String? = null
    private var lastSnapAt = 0L
    private var lastSignature = 0
    private var lastContentHash = 0
    private val snapRunnable = Runnable {
        val reason = pendingReason ?: return@Runnable
        pendingReason = null
        takeSnapshot(reason)
    }

    // --- Scrolls, merged per scrolling view until it goes quiet ---
    private val scrolls = LinkedHashMap<Int, ScrollRun>()
    private val settleRunnable = Runnable { flushScrolls(snapshot = true) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    flushScrolls(snapshot = false)
                    EventLog.write("screen_off", State.foreground)
                    Gate.onScreen(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    EventLog.write("screen_on")
                    Gate.onScreen(true)
                }
                Intent.ACTION_USER_PRESENT -> {
                    EventLog.write("unlocked")
                    Gate.onScreen(true)
                }
            }
        }
    }

    override fun onServiceConnected() {
        val dm = resources.displayMetrics
        EventLog.write("service_on") {
            put("screen", JSONArray(listOf(dm.widthPixels, dm.heightPixels)))
            put("sdk", Build.VERSION.SDK_INT)
            put("device", Build.MODEL)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        overlay = EndOverlay(this)
        Gate.host = this
        // After a restart, pick up where things were.
        val active = rootInActiveWindow?.packageName?.toString()
        State.foreground = active
        Gate.onForeground(active)
        main.post(ticker)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (Gate.host === this) Gate.host = null
        Gate.detach()
        if (::overlay.isInitialized) overlay.dismiss()
        flushScrolls(snapshot = false)
        main.removeCallbacksAndMessages(null)
        EventLog.write("service_off")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowState(event, pkg)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (Targets.isLogged(pkg)) onScroll(event, pkg)
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> if (Targets.isLogged(pkg)) onClick(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (Targets.isLogged(pkg) && pkg == State.foreground) requestSnapshot(REASON_CONTENT, 800)
        }
    }

    private fun onWindowState(event: AccessibilityEvent, pkg: String) {
        val active = rootInActiveWindow?.packageName?.toString() ?: pkg
        // Our own stop screen isn't an app change.
        if (active == packageName && ::overlay.isInitialized && overlay.isShowing) return
        if (active !in IGNORED_FOREGROUND && active != State.foreground) {
            // Navigating away (home, another app) dismisses the stop screen.
            if (::overlay.isInitialized) overlay.dismiss()
            flushScrolls(snapshot = false)
            val from = State.foreground
            EventLog.write("foreground", active) { put("from", from ?: JSONObject.NULL) }
            State.foreground = active
            lastSignature = 0
            lastContentHash = 0
            ytScreen = null
            Gate.onForeground(active)
        }
        if (pkg == YouTubeRules.PKG) {
            main.removeCallbacks(classifyRunnable)
            main.postDelayed(classifyRunnable, CLASSIFY_DELAY_MS)
        }
        if (Targets.isLogged(pkg)) {
            EventLog.write("window", pkg) {
                put("cls", event.className?.toString())
                event.text.joinToString(" | ").takeIf { it.isNotBlank() }?.let { put("txt", NodeDump.clip(it)) }
                put("changes", event.contentChangeTypes)
            }
            requestSnapshot(REASON_WINDOW, 350)
        }
    }

    private fun onClick(event: AccessibilityEvent, pkg: String) {
        val source = event.source
        EventLog.write("click", pkg) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) put("long", true)
            if (source != null) {
                NodeDump.describeInto(this, source)
            } else {
                put("c", event.className?.toString()?.substringAfterLast('.'))
                event.text.joinToString(" | ").takeIf { it.isNotBlank() }?.let { put("txt", NodeDump.clip(it)) }
            }
        }
    }

    private fun onScroll(event: AccessibilityEvent, pkg: String) {
        val source = event.source
        val key = source?.hashCode() ?: (pkg + event.className).hashCode()
        scrolls.getOrPut(key) { ScrollRun(pkg, event, source) }.add(event)
        main.removeCallbacks(settleRunnable)
        main.postDelayed(settleRunnable, SCROLL_SETTLE_MS)
    }

    private fun flushScrolls(snapshot: Boolean) {
        main.removeCallbacks(settleRunnable)
        if (scrolls.isEmpty()) return
        scrolls.values.forEach { run ->
            EventLog.write("scroll", run.pkg) { run.writeTo(this) }
            if (run.pkg == YouTubeRules.PKG &&
                YouTubeRules.isNextShort(run.id, run.dy, run.startScrollY, run.scrollY, resources.displayMetrics.heightPixels)
            ) {
                Gate.stoppingPoint(run.pkg, "next_short")
            }
        }
        scrolls.clear()
        if (snapshot) requestSnapshot(REASON_SCROLL, 0)
    }

    private fun requestSnapshot(reason: String, delayMs: Long) {
        val pending = pendingReason
        // Content changes stream constantly (video progress, counters); don't let them postpone forever.
        if (pending != null && reason == REASON_CONTENT) return
        if (pending == null || rank(reason) >= rank(pending)) pendingReason = reason
        main.removeCallbacks(snapRunnable)
        main.postDelayed(snapRunnable, delayMs)
    }

    private fun takeSnapshot(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (reason == REASON_CONTENT && now - lastSnapAt < CONTENT_MIN_GAP_MS) {
            requestSnapshot(REASON_CONTENT, CONTENT_MIN_GAP_MS - (now - lastSnapAt))
            return
        }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (!Targets.isLogged(pkg)) return

        val snap = NodeDump.dump(root)
        // Content changes only matter when the screen's layout changed (e.g. opened a post).
        if (reason == REASON_CONTENT && snap.signature == lastSignature) return
        if (snap.contentHash == lastContentHash) return
        lastSignature = snap.signature
        lastContentHash = snap.contentHash
        lastSnapAt = now

        EventLog.write("snapshot", pkg) {
            put("reason", reason)
            put("n", snap.visited)
            if (snap.truncated) put("truncated", true)
            put("nodes", snap.nodes)
        }
    }

    /** One scroll gesture on one view, from first event until it goes quiet. */
    private class ScrollRun(val pkg: String, first: AccessibilityEvent, source: AccessibilityNodeInfo?) {
        private val startedAt = SystemClock.uptimeMillis()
        private var lastAt = startedAt
        private val cls = (source?.className ?: first.className)?.toString()?.substringAfterLast('.')
        val id = source?.viewIdResourceName?.substringAfter(":id/")
        private val bounds = Rect().also { source?.getBoundsInScreen(it) }
        private val startFirst = first.fromIndex
        val startScrollY = first.scrollY
        private var firstVisible = first.fromIndex
        private var lastVisible = first.toIndex
        private var items = first.itemCount
        var scrollY = first.scrollY
            private set
        private var maxScrollY = first.maxScrollY
        private var dx = 0
        var dy = 0
            private set
        private var events = 0

        fun add(e: AccessibilityEvent) {
            events++
            lastAt = SystemClock.uptimeMillis()
            firstVisible = e.fromIndex
            lastVisible = e.toIndex
            items = e.itemCount
            scrollY = e.scrollY
            maxScrollY = e.maxScrollY
            // -1 means "not reported" for these.
            if (e.scrollDeltaX != -1) dx += e.scrollDeltaX
            if (e.scrollDeltaY != -1) dy += e.scrollDeltaY
        }

        fun writeTo(o: JSONObject) {
            o.put("c", cls)
            o.put("id", id)
            o.put("b", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            o.put("ms", lastAt - startedAt)
            o.put("events", events)
            // first0 -> first1: first visible item before and after. In a full-screen pager, +1 is "next video".
            o.put("first0", startFirst)
            o.put("first1", firstVisible)
            o.put("last1", lastVisible)
            o.put("items", items)
            o.put("dx", dx)
            o.put("dy", dy)
            o.put("y0", startScrollY)
            o.put("y1", scrollY)
            o.put("maxY", maxScrollY)
        }
    }

    // --- Gate.Host ---

    override fun midContent(app: Targets.App): Boolean = when (app) {
        Targets.youtube -> {
            classifyYouTube()
            ytScreen != YouTubeRules.Screen.FEED || MediaWatch.isPlaying(YouTubeRules.PKG)
        }
        // Lurk tells us its own stopping points; until one comes, assume it's mid-post.
        else -> true
    }

    override fun endSession(app: Targets.App, why: Gate.EndKind) {
        app.packages.forEach { MediaWatch.pause(it) }
        val day = Gate.day(app)
        // Ended by its open hours rather than its budget: say when it opens again.
        val opens = Gate.opensLabel(app)
        val closed = if (day.phase == Phase.DONE || opens == null) "closed for the rest of today" else "closed until $opens"
        val (title, body) = when (why) {
            Gate.EndKind.STOPPING_POINT -> "Done." to "You reached a natural stopping point, so ${app.name} is $closed."
            Gate.EndKind.HARD_CAP -> "Time." to "No good stopping point came along, so ${app.name} is $closed."
            Gate.EndKind.BLOCKED -> "Closed." to "${app.name} is done for today. See you tomorrow."
            Gate.EndKind.CLOSED_HOURS ->
                "Closed." to (opens?.let { "${app.name} opens again $it." } ?: "${app.name} has no open hours set.")
        }
        // The app stays underneath until Okay (which goes home) or the user navigates away.
        overlay.show(title, body) { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    /** Works out which YouTube screen is showing; a video or Shorts giving way to a feed is a stopping point. */
    private fun classifyYouTube() {
        main.removeCallbacks(classifyRunnable)
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != YouTubeRules.PKG) return
        val hasReel = root.findAccessibilityNodeInfosByViewId("${YouTubeRules.PKG}:id/reel_recycler").isNotEmpty()
        val player = root.findAccessibilityNodeInfosByViewId("${YouTubeRules.PKG}:id/watch_player").firstOrNull()
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val fraction = player?.let { Rect().also(it::getBoundsInScreen).width().toFloat() / width } ?: 0f
        val screen = YouTubeRules.screen(hasReel, fraction)
        val from = ytScreen
        ytScreen = screen
        if (from != screen) EventLog.write("yt_screen", YouTubeRules.PKG) { put("screen", screen.name) }
        if (YouTubeRules.isBackToFeed(from, screen)) Gate.stoppingPoint(YouTubeRules.PKG, "back_to_feed")
    }

    private companion object {
        const val TICK_MS = 1000L
        const val CLASSIFY_DELAY_MS = 400L
        const val REASON_WINDOW = "window"
        const val REASON_SCROLL = "scroll"
        const val REASON_CONTENT = "content"
        const val SCROLL_SETTLE_MS = 600L
        const val CONTENT_MIN_GAP_MS = 2000L

        /** Windows that cover an app without the person leaving it. */
        val IGNORED_FOREGROUND = setOf("com.android.systemui")

        fun rank(reason: String) = when (reason) {
            REASON_WINDOW -> 3
            REASON_SCROLL -> 2
            else -> 1
        }
    }
}
