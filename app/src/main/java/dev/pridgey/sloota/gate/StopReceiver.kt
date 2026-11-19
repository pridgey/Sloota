package dev.pridgey.sloota.gate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Apps we make (Lurk) report their own stopping points here, instead of Sloota guessing from the screen:
 *
 *     sendBroadcast(Intent("dev.pridgey.sloota.STOPPING_POINT")
 *         .setPackage("dev.pridgey.sloota")
 *         .putExtra("pkg", packageName)
 *         .putExtra("reason", "left_post"))
 *
 * No permission guards this. A faked stopping point can only end a session that's already past its
 * limit, and only for the app that's in front, so there's nothing to gain by sending one.
 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val reason = intent.getStringExtra(EXTRA_REASON) ?: "reported"
        Gate.stoppingPoint(pkg, "app:$reason")
    }

    companion object {
        const val ACTION = "dev.pridgey.sloota.STOPPING_POINT"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_REASON = "reason"
    }
}
