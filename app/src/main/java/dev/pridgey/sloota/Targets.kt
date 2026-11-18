package dev.pridgey.sloota

import android.app.Application
import dev.pridgey.sloota.log.EventLog

class SlootaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}

/** The apps Sloota watches closely. Everything else only shows up as "foreground" changes. */
object Targets {
    class App(val name: String, val packages: List<String>)

    val apps = listOf(
        App("YouTube", listOf("com.google.android.youtube")),
        App("Instagram", listOf("com.instagram.android")),
        App("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        App("Reddit", listOf("com.reddit.frontpage")),
    )

    private val byPackage = apps.flatMap { app -> app.packages.map { it to app } }.toMap()

    fun isTarget(pkg: String?): Boolean = pkg != null && pkg in byPackage

    fun nameOf(pkg: String): String? = byPackage[pkg]?.name
}

/** Shared between the accessibility service and the Quick Settings tile (same process). */
object State {
    /** The app the person is actually using; the notification shade doesn't count. */
    @Volatile
    var foreground: String? = null
}
