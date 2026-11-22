package dev.pridgey.sloota.gate

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.DayOfWeek

/**
 * Limits and per-day records, kept in SharedPreferences so they survive the accessibility service
 * being restarted (which Android does often, e.g. whenever an app is installed).
 */
class GateStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("gate", Context.MODE_PRIVATE)

    /** App id → configured daily limit in minutes (0 = none). */
    fun limits(): Map<String, Int> {
        val o = JSONObject(prefs.getString(KEY_LIMITS, "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    fun setLimit(appId: String, minutes: Int) {
        val o = JSONObject(prefs.getString(KEY_LIMITS, "{}") ?: "{}")
        o.put(appId, minutes)
        prefs.edit().putString(KEY_LIMITS, o.toString()).apply()
    }

    /** App id → hard cap in minutes (0 = off, the default). */
    fun caps(): Map<String, Int> {
        val o = JSONObject(prefs.getString(KEY_CAPS, "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.optInt(it) }
    }

    fun setCap(appId: String, minutes: Int) {
        val o = JSONObject(prefs.getString(KEY_CAPS, "{}") ?: "{}")
        o.put(appId, minutes)
        prefs.edit().putString(KEY_CAPS, o.toString()).apply()
    }

    /** App id → weekly open hours. Hours saved before weekdays existed apply to every day. */
    fun hours(): Map<String, WeekHours> {
        val saved = prefs.getString(KEY_HOURS, null) ?: return legacyHours()
        val o = JSONObject(saved)
        return o.keys().asSequence().associateWith { id ->
            val week = o.getJSONObject(id)
            week.keys().asSequence().mapNotNull { name ->
                val day = runCatching { DayOfWeek.valueOf(name) }.getOrNull() ?: return@mapNotNull null
                val rule = week.getJSONObject(name)
                val hours = if (rule.optBoolean("closed")) {
                    DayHours.Closed
                } else {
                    val (s, e) = rule.optInt("s", -1) to rule.optInt("e", -1)
                    if (!Rules.validWindow(s, e)) return@mapNotNull null
                    DayHours.Open(s, e)
                }
                day to Rules.fitDay(hours)
            }.toMap()
        }
    }

    private fun legacyHours(): Map<String, WeekHours> {
        val o = JSONObject(prefs.getString(KEY_WINDOWS, "{}") ?: "{}")
        return o.keys().asSequence().mapNotNull { id ->
            val w = o.optJSONObject(id) ?: return@mapNotNull null
            val (s, e) = w.optInt("s", -1) to w.optInt("e", -1)
            if (!Rules.validWindow(s, e)) return@mapNotNull null
            id to DayOfWeek.entries.associateWith { Rules.fitDay(DayHours.Open(s, e)) }
        }.toMap()
    }

    /** Replaces an app's week. Any-time days aren't stored; an empty week removes the hours. */
    fun setHours(appId: String, week: WeekHours) {
        val all = hours() + (appId to week)
        val root = JSONObject()
        all.forEach { (id, days) ->
            val o = JSONObject()
            days.forEach { (day, rule) ->
                when (rule) {
                    DayHours.AnyTime -> Unit
                    DayHours.Closed -> o.put(day.name, JSONObject().put("closed", true))
                    is DayHours.Open -> o.put(day.name, JSONObject().put("s", rule.start).put("e", rule.end))
                }
            }
            if (o.length() > 0) root.put(id, o)
        }
        prefs.edit().putString(KEY_HOURS, root.toString()).apply()
    }

    /** Day key → app id → record. */
    fun days(): MutableMap<String, MutableMap<String, AppDay>> {
        val root = JSONObject(prefs.getString(KEY_DAYS, "{}") ?: "{}")
        val out = sortedMapOf<String, MutableMap<String, AppDay>>()
        root.keys().forEach { day ->
            val apps = root.getJSONObject(day)
            out[day] = apps.keys().asSequence().associateWith { read(apps.getJSONObject(it)) }.toMutableMap()
        }
        return out
    }

    fun saveDays(days: Map<String, Map<String, AppDay>>) {
        val root = JSONObject()
        days.forEach { (day, apps) ->
            val o = JSONObject()
            apps.forEach { (id, d) -> o.put(id, write(d)) }
            root.put(day, o)
        }
        prefs.edit().putString(KEY_DAYS, root.toString()).apply()
    }

    private fun read(o: JSONObject) = AppDay(
        limitMs = o.optLong("limit"),
        usedMs = o.optLong("used"),
        extraMs = o.optLong("extra"),
        phase = runCatching { Phase.valueOf(o.optString("phase")) }.getOrDefault(Phase.OPEN),
        windStartMs = o.optLong("wind"),
        hits = o.optInt("hits"),
        extensions = o.optInt("ext"),
        windowStart = o.optInt("ws", -1),
        windowEnd = o.optInt("we", -1),
    )

    private fun write(d: AppDay) = JSONObject()
        .put("limit", d.limitMs)
        .put("used", d.usedMs)
        .put("extra", d.extraMs)
        .put("phase", d.phase.name)
        .put("wind", d.windStartMs)
        .put("hits", d.hits)
        .put("ext", d.extensions)
        .put("ws", d.windowStart)
        .put("we", d.windowEnd)

    private companion object {
        const val KEY_LIMITS = "limits"
        const val KEY_DAYS = "days"
        const val KEY_CAPS = "caps"
        const val KEY_WINDOWS = "windows"
        const val KEY_HOURS = "hours"
    }
}
