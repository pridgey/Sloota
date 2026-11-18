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
import dev.pridgey.sloota.log.EventLog
import dev.pridgey.sloota.log.NodeDump
import org.json.JSONArray
import org.json.JSONObject

/**
 * Logs what happens in the watched apps, so stopping-point rules can be written from real data:
 *
 * - foreground: which app is in front (all apps, so sessions have clear starts and ends)
 * - window:     a screen, dialog or pane changed in a watched app
 * - scroll:     one finished scroll gesture, with item indexes (a pager swipe moves first-visible by 1)
 * - click:      a tap, with what was tapped
 * - snapshot:   the visible view tree, after windows change, scrolls settle, or the layout changes
 * - screen_on / screen_off / unlocked
 */
class WatchService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

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
                }
                Intent.ACTION_SCREEN_ON -> EventLog.write("screen_on")
                Intent.ACTION_USER_PRESENT -> EventLog.write("unlocked")
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
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
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
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (Targets.isTarget(pkg)) onScroll(event, pkg)
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> if (Targets.isTarget(pkg)) onClick(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (Targets.isTarget(pkg) && pkg == State.foreground) requestSnapshot(REASON_CONTENT, 800)
        }
    }

    private fun onWindowState(event: AccessibilityEvent, pkg: String) {
        val active = rootInActiveWindow?.packageName?.toString() ?: pkg
        if (active !in IGNORED_FOREGROUND && active != State.foreground) {
            flushScrolls(snapshot = false)
            val from = State.foreground
            EventLog.write("foreground", active) { put("from", from ?: JSONObject.NULL) }
            State.foreground = active
            lastSignature = 0
            lastContentHash = 0
        }
        if (Targets.isTarget(pkg)) {
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
        scrolls.values.forEach { run -> EventLog.write("scroll", run.pkg) { run.writeTo(this) } }
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
        if (!Targets.isTarget(pkg)) return

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
        private val id = source?.viewIdResourceName?.substringAfter(":id/")
        private val bounds = Rect().also { source?.getBoundsInScreen(it) }
        private val startFirst = first.fromIndex
        private val startScrollY = first.scrollY
        private var firstVisible = first.fromIndex
        private var lastVisible = first.toIndex
        private var items = first.itemCount
        private var scrollY = first.scrollY
        private var maxScrollY = first.maxScrollY
        private var dx = 0
        private var dy = 0
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

    private companion object {
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
