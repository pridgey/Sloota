package dev.pridgey.sloota.gate

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

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
    )

    private fun write(d: AppDay) = JSONObject()
        .put("limit", d.limitMs)
        .put("used", d.usedMs)
        .put("extra", d.extraMs)
        .put("phase", d.phase.name)
        .put("wind", d.windStartMs)
        .put("hits", d.hits)
        .put("ext", d.extensions)

    private companion object {
        const val KEY_LIMITS = "limits"
        const val KEY_DAYS = "days"
        const val KEY_CAPS = "caps"
    }
}
