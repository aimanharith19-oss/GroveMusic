# Grove Music

Android music player styled after the Windows Phone 8 Music app (Metro UI):
black background, big lowercase pivot headers (songs · artists · albums · genres · settings),
accent-coloured letter tiles, huge artist name on Now Playing, round outline transport buttons,
swipe album art left/right to skip, 20 WP8 accent colours.

## Features
- **FLAC** (incl. 24-bit hi-res), MP3, OGG, OPUS, M4A/AAC, WAV via Media3 ExoPlayer
- Background playback + lock-screen/notification controls (MediaSession)
- Open audio files from any file manager ("Open with Grove Music")
- **Lyrics** (tap album art or "lyrics"): synced LRC scrolls with the song, tap a line to jump there.
  Looks in this order:
  1. a .lrc you picked for that song
  2. `song name.lrc` next to the audio file
  3. a lyrics folder chosen in settings (matches `file name`, `artist - title` or `title`)
  4. lyrics embedded in FLAC tags (LYRICS / UNSYNCEDLYRICS)
  5. lrclib.net online (can be turned off; downloads are saved for offline use)

## Build
- **Android Studio**: open this folder → Run. Requires JDK 17, Android SDK 35.
- **GitHub**: push to a repo; the included Actions workflow builds the APK (download it from the run's Artifacts).
- CLI: `./gradlew assembleRelease` → `app/build/outputs/apk/release/`
