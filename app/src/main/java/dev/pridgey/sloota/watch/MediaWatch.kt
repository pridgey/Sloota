package dev.pridgey.sloota.watch

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import dev.pridgey.sloota.Targets
import dev.pridgey.sloota.log.EventLog
import dev.pridgey.sloota.log.NodeDump
import org.json.JSONObject
import kotlin.math.abs

/**
 * Notification access is the only way to read other apps' media sessions. This logs, for watched apps:
 *
 * - media_session / media_session_end: a player appeared or went away
 * - media_state: play, pause, buffering, stop, seeks (normal playback progress is skipped)
 * - media_meta:  a new video (title, channel, length), which is how autoplay shows up
 */
class MediaWatch : NotificationListenerService() {

    private var sessions: MediaSessionManager? = null
    private val watched = HashMap<MediaSession.Token, Watcher>()
    private val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { sync(it ?: emptyList()) }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        sessions = manager
        val me = ComponentName(this, MediaWatch::class.java)
        EventLog.write("media_on")
        runCatching {
            manager.addOnActiveSessionsChangedListener(onSessionsChanged, me)
            sync(manager.getActiveSessions(me))
        }.onFailure { e -> EventLog.write("media_error") { put("msg", e.toString()) } }
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(onSessionsChanged)
        watched.values.forEach { it.stop() }
        watched.clear()
        EventLog.write("media_off")
    }

    private fun sync(controllers: List<MediaController>) {
        val live = controllers.filter { Targets.isTarget(it.packageName) }.associateBy { it.sessionToken }
        (watched.keys - live.keys).forEach { token -> watched.remove(token)?.stop() }
        live.forEach { (token, controller) ->
            if (token !in watched) watched[token] = Watcher(controller).also { it.start() }
        }
    }

    private class Watcher(private val controller: MediaController) : MediaController.Callback() {
        private val pkg = controller.packageName
        private var last: PlaybackState? = null

        fun start() {
            controller.registerCallback(this)
            last = controller.playbackState
            EventLog.write("media_session", pkg) {
                controller.playbackState?.let { putState(this, it) }
                putMeta(this, controller.metadata)
            }
        }

        fun stop() {
            controller.unregisterCallback(this)
            EventLog.write("media_session_end", pkg)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state ?: return
            if (!worthLogging(state)) return
            last = state
            EventLog.write("media_state", pkg) {
                putState(this, state)
                duration(controller.metadata)?.let { put("dur", it) }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            EventLog.write("media_meta", pkg) { putMeta(this, metadata) }
        }

        override fun onSessionDestroyed() {
            EventLog.write("media_session_destroyed", pkg)
        }

        /** Skip updates that are just playback moving forward as expected. */
        private fun worthLogging(state: PlaybackState): Boolean {
            val prev = last ?: return true
            if (prev.state != state.state || prev.playbackSpeed != state.playbackSpeed) return true
            val elapsed = state.lastPositionUpdateTime - prev.lastPositionUpdateTime
            val expected = prev.position +
                if (prev.state == PlaybackState.STATE_PLAYING) (elapsed * prev.playbackSpeed).toLong() else 0L
            return abs(state.position - expected) > 2000
        }
    }

    private companion object {
        fun putState(o: JSONObject, s: PlaybackState) {
            o.put("state", stateName(s.state))
            o.put("pos", s.position)
            o.put("speed", s.playbackSpeed.toDouble())
        }

        fun putMeta(o: JSONObject, m: MediaMetadata?) {
            m ?: return
            (m.getText(MediaMetadata.METADATA_KEY_TITLE) ?: m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
                ?.let { o.put("title", NodeDump.clip(it)) }
            (m.getText(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
                ?.let { o.put("artist", NodeDump.clip(it, 50)) }
            duration(m)?.let { o.put("dur", it) }
        }

        fun duration(m: MediaMetadata?): Long? =
            m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }

        fun stateName(state: Int) = when (state) {
            PlaybackState.STATE_NONE -> "none"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_FAST_FORWARDING -> "ff"
            PlaybackState.STATE_REWINDING -> "rewind"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_ERROR -> "error"
            PlaybackState.STATE_CONNECTING -> "connecting"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "skip_prev"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "skip_next"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "skip_queue"
            else -> "state_$state"
        }
    }
}
