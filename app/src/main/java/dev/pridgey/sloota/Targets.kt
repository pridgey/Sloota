package dev.pridgey.sloota

import android.app.Application
import dev.pridgey.sloota.gate.Gate
import dev.pridgey.sloota.log.EventLog

class SlootaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        Gate.init(this)
    }
}

/** The apps Sloota can limit. */
object Targets {
    class App(
        /** Stable key for stored limits and stats. */
        val id: String,
        val name: String,
        val packages: List<String>,
        /** Log screens, scrolls and taps in detail (for working out stopping points from what's on screen). */
        val logDetail: Boolean,
    )

    val youtube = App("youtube", "YouTube", listOf("com.google.android.youtube"), logDetail = true)

    /** Our own app: it reports its stopping points to Sloota directly (see gate/StopReceiver). */
    val lurk = App("lurk", "Lurk", listOf("app.lurk"), logDetail = false)

    val gated = listOf(youtube, lurk)

    private val byPkg = gated.flatMap { app -> app.packages.map { it to app } }.toMap()

    fun byPackage(pkg: String?): App? = pkg?.let { byPkg[it] }

    fun byId(id: String?): App? = gated.firstOrNull { it.id == id }

    /** Apps whose screens are logged in detail. */
    fun isLogged(pkg: String?): Boolean = byPackage(pkg)?.logDetail == true

    fun nameOf(pkg: String): String? = byPkg[pkg]?.name
}

/** Shared between the accessibility service and the Quick Settings tile (same process). */
object State {
    /** The app the person is actually using; the notification shade doesn't count. */
    @Volatile
    var foreground: String? = null
}
