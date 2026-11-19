package dev.pridgey.sloota.gate

import kotlin.math.abs

/**
 * Stopping points in YouTube, from the Nov 17–18 2026 logs.
 *
 * - Shorts don't report playback. A swipe to the next short is a settled scroll on `reel_player_underlay`
 *   that moved most of a screen (the pager index isn't exposed, so it's measured in pixels).
 * - Long videos report playback; autoplay shows up as a new title.
 * - Going from a video or Shorts back to a feed is a stop.
 */
object YouTubeRules {
    const val PKG = "com.google.android.youtube"

    enum class Screen { FEED, WATCH, SHORTS }

    /** Settled scroll on the Shorts pager that moved at least a quarter of the screen. */
    fun isNextShort(viewId: String?, dy: Int, y0: Int, y1: Int, screenHeight: Int): Boolean {
        if (viewId != "reel_player_underlay" && viewId != "reel_recycler") return false
        val moved = maxOf(abs(dy), abs(y1 - y0))
        return moved >= screenHeight / 4
    }

    /**
     * Which screen is showing. [hasReel] = the Shorts pager is on screen; [playerWidthFraction] is the
     * main player's width as a fraction of the screen (a minimized player is narrow), or 0 if there's none.
     */
    fun screen(hasReel: Boolean, playerWidthFraction: Float): Screen = when {
        hasReel -> Screen.SHORTS
        playerWidthFraction >= 0.9f -> Screen.WATCH
        else -> Screen.FEED
    }

    fun isBackToFeed(from: Screen?, to: Screen): Boolean = from != null && from != Screen.FEED && to == Screen.FEED

    /** A new, different video title while playing means the video ended or autoplay moved on. */
    fun isNewVideo(previousTitle: String?, title: String?): Boolean =
        !previousTitle.isNullOrBlank() && !title.isNullOrBlank() && title != previousTitle
}
