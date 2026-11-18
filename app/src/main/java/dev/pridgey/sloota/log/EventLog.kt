package dev.pridgey.sloota.log

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Append-only JSON-lines log, one file per day, in
 * /sdcard/Android/data/dev.pridgey.sloota/files/logs/.
 *
 * All disk work runs in order on one background thread, so callers never block.
 */
object EventLog {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sloota-log").apply { isDaemon = true } }

    // Only touched on the io thread (SimpleDateFormat isn't thread-safe).
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private lateinit var dir: File

    val directory: File get() = dir

    fun init(context: Context) {
        dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
    }

    /**
     * Logs one event. [fill] runs right away on the caller's thread, so it can safely read
     * accessibility nodes; only the formatting and file write are deferred.
     */
    fun write(type: String, pkg: String? = null, fill: JSONObject.() -> Unit = {}) {
        val t = System.currentTimeMillis()
        val body = JSONObject()
        try {
            body.fill()
        } catch (e: Exception) {
            body.put("fill_error", e.toString())
        }
        io.execute {
            val line = JSONObject()
                .put("t", t)
                .put("at", clockFormat.format(Date(t)))
                .put("type", type)
            if (pkg != null) line.put("pkg", pkg)
            body.keys().forEach { line.put(it, body.get(it)) }
            runCatching { File(dir, dayFormat.format(Date(t)) + ".jsonl").appendText(line.toString() + "\n") }
        }
    }

    class Stats(val todayEvents: Int, val todayBytes: Long, val totalBytes: Long, val days: Int)

    /** Blocks until pending writes finish; call off the main thread. */
    fun stats(): Stats = io.submit(Callable {
        val files = logFiles()
        val today = File(dir, dayFormat.format(Date()) + ".jsonl")
        val events = if (today.exists()) today.bufferedReader().useLines { it.count() } else 0
        Stats(events, today.length(), files.sumOf { it.length() }, files.size)
    }).get()

    /** Zips every day's log into the cache for sharing. Null if there's nothing yet. Call off the main thread. */
    fun exportZip(context: Context): File? = io.submit(Callable {
        val files = logFiles()
        if (files.isEmpty()) return@Callable null
        val out = File(context.cacheDir, "export").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val zip = File(out, "sloota-logs-${dayFormat.format(Date())}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            files.forEach { f ->
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        zip
    }).get()

    fun clear() {
        io.execute { logFiles().forEach { it.delete() } }
    }

    private fun logFiles(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()
}
