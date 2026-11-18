package dev.pridgey.sloota.log

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flattens the on-screen view tree into a compact list for the log.
 *
 * Each entry: d=depth, c=class, id=view id, desc=content description, txt=text,
 * b=[left,top,right,bottom], f=flags (s scrollable, k clickable, S selected, c/C checkable/checked),
 * coll=rows x cols of a list, item=row index inside a list, state=state description.
 */
object NodeDump {
    private const val MAX_NODES = 400
    private const val SIGNATURE_DEPTH = 14

    class Snapshot(
        val nodes: JSONArray,
        val visited: Int,
        val truncated: Boolean,
        /** Hash of the screen's layout (classes and ids, not text): changes when the screen changes, not when a count ticks up. */
        val signature: Int,
        /** Hash of everything logged, to skip exact repeats. */
        val contentHash: Int,
    )

    fun clip(s: CharSequence, max: Int = 80): String {
        val t = s.toString().replace('\n', ' ').trim()
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }

    fun dump(root: AccessibilityNodeInfo): Snapshot {
        val nodes = JSONArray()
        var visited = 0
        var truncated = false
        var signature = 1

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= MAX_NODES) {
                truncated = true
                return
            }
            if (!node.isVisibleToUser) return
            visited++
            if (depth <= SIGNATURE_DEPTH) {
                signature = 31 * (31 * signature + depth) +
                    (node.className?.toString()?.hashCode() ?: 0) * 7 +
                    (node.viewIdResourceName?.hashCode() ?: 0)
            }
            if (isInteresting(node)) {
                nodes.put(JSONObject().put("d", depth).also { describeInto(it, node) })
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
                recycle(child)
            }
        }

        visit(root, 0)
        return Snapshot(nodes, visited, truncated, signature, nodes.toString().hashCode())
    }

    /** Writes one node's details into [o]. */
    fun describeInto(o: JSONObject, node: AccessibilityNodeInfo) {
        node.className?.let { o.put("c", it.toString().substringAfterLast('.')) }
        node.viewIdResourceName?.let { o.put("id", it.substringAfter(":id/")) }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { o.put("desc", clip(it)) }
        node.text?.takeIf { it.isNotBlank() }?.let { o.put("txt", clip(it)) }
        val r = Rect().also { node.getBoundsInScreen(it) }
        o.put("b", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
        val flags = buildString {
            if (node.isScrollable) append('s')
            if (node.isClickable) append('k')
            if (node.isSelected) append('S')
            if (node.isCheckable) append(if (node.isChecked) 'C' else 'c')
        }
        if (flags.isNotEmpty()) o.put("f", flags)
        node.collectionInfo?.let { o.put("coll", "${it.rowCount}x${it.columnCount}") }
        node.collectionItemInfo?.let { o.put("item", it.rowIndex) }
        if (Build.VERSION.SDK_INT >= 30) {
            node.stateDescription?.takeIf { it.isNotBlank() }?.let { o.put("state", clip(it)) }
        }
    }

    private fun isInteresting(node: AccessibilityNodeInfo): Boolean =
        node.viewIdResourceName != null ||
            !node.contentDescription.isNullOrBlank() ||
            !node.text.isNullOrBlank() ||
            node.isScrollable ||
            node.isClickable ||
            node.collectionInfo != null

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) node.recycle()
    }
}
