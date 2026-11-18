# Sloota

*Swedish "sluta": stop, quit.* An app timer that waits for a **natural stopping point**
(finishing a video, backing out of a post, a scroll settling) instead of cutting you off mid-thing,
then ends the session for good. More time sits behind deliberate friction.

## Stage 1: logger

Before writing per-app rules, this build only **records** what YouTube, Instagram, TikTok and Reddit
report through Android's accessibility and media-session APIs, plus your own "this is a good stop" marks.

### Setup on the phone
1. Install the APK from the latest [release](https://github.com/pridgey/Sloota/releases/latest). Sloota updates itself from releases after that.
2. Open Sloota → **Watch apps** → turn on *Sloota logger*.
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
