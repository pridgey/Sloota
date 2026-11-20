# Sloota

*Swedish "sluta": stop, quit.* An app timer that waits for a **natural stopping point**
(finishing a video, backing out of a post, a scroll settling) instead of cutting you off mid-thing,
then ends the session for good. More time sits behind deliberate friction.

## How it works

Set a daily limit for **YouTube** or **Lurk** in Sloota. When it runs out, nothing interrupts you: Sloota waits for the next
natural stopping point, then shows a "you did it" screen, pauses playback and sends the app home for the rest of the day.
Days start at 4 AM.

| App | Stopping points |
|---|---|
| YouTube | swiping to the next short, autoplay moving to a new video, backing out of a video or Shorts to a feed |
| Lurk | reported by Lurk itself (see below) |
| any | leaving the app, turning the screen off |

**Hard cap** (per app, off by default): end anyway if no stopping point comes within 3, 5, 10 or 15 minutes.
**Limits.** Lowering a limit takes effect right away; raising one (or turning it off) starts tomorrow.
**More time** only lives in Sloota: open the app's page, pick an amount, press and hold.
The home screen counts days under the limit, times you hit your Sloota, and times you went back for more.

**Lurk integration.** Lurk sends its stopping points as a broadcast:
```kotlin
sendBroadcast(Intent("dev.pridgey.sloota.STOPPING_POINT")
    .setPackage("dev.pridgey.sloota")
    .putExtra("pkg", packageName)
    .putExtra("reason", "left_post"))
```
It's only acted on while that app is in front and past its limit, so it needs no permission.

The logger from stage 1 still runs for YouTube, to keep tuning the rules.

### Setup on the phone
1. Install the APK from the latest [release](https://github.com/pridgey/Sloota/releases/latest). Sloota updates itself from releases after that.
2. Open Sloota → **Watch apps** → turn on *Sloota*.
   If the switch is greyed out (sideloaded app, Android 13+): App info → ⋮ → *Allow restricted settings*.
3. **Video playback** → allow notification access for Sloota.
4. Add the **Mark stop** Quick Settings tile. Tap it whenever stopping would feel natural.

### Getting the logs
*Share logs* in the app zips them, or: `adb pull /sdcard/Android/data/dev.pridgey.sloota/files/logs`

One JSON object per line, one file per day. Event types:

| type | meaning |
|---|---|
| `foreground` | app in front changed (`from` = previous) |
| `window` | screen / dialog / pane changed in a watched app |
| `scroll` | one settled scroll gesture; `first0`→`first1` is first visible item before/after (pager swipe = +1), `b` = scroller bounds |
| `click` | a tap, with the tapped node's class/id/desc/text |
| `snapshot` | visible view tree (`reason`: window, scroll, or layout change) |
| `media_session` / `media_state` / `media_meta` | playback state, seeks, new video (title, `dur` ms) |
| `mark` | you tapped *Mark stop* |
| `screen_on` / `screen_off` / `unlocked` | |

Logs contain on-screen text (clipped to 80 chars) from these apps, so they stay on the phone unless shared.

## Build & release
```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```
Every push to `main` builds, lints and publishes a signed GitHub release (`v1.0.<run>`), which installed copies
offer as an update. Releases must be signed with the same key: run `scripts/setup-signing.sh` once
(key kept in `~/keystores/sloota/`, back it up). Debug builds install separately as `dev.pridgey.sloota.debug`.
