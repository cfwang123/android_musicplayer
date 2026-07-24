# Music Player

An Android app for browsing local media folders and playing audio in the background.

[中文说明](README.zh.md) · [Changelog](CHANGELOG.md)

**Current version: 1.0.3**

## Screenshots

| Page 1 · Browse / Playlists | Page 2 · Player | Page 3 · Lyrics |
|:---:|:---:|:---:|
| <img src="screenshots/1.jpg" width="240" alt="Browse and playlist" /> | <img src="screenshots/2.jpg" width="240" alt="Player" /> | <img src="screenshots/3.jpg" width="240" alt="Lyrics" /> |

- **Page 1**: Folders / playlists, current-track highlight, bottom controls  
- **Page 2**: Cover, progress, favorite & add-to-playlist, speed / sleep timer, mini lyrics  
- **Page 3**: Full-screen lyrics (bilingual), karaoke highlight  

More images: [`screenshots/`](screenshots/).

## Overview

| Item | Value |
|------|--------|
| App name | Music Player |
| Package | `com.whj.music` |
| Version | 1.0.3 (`versionCode` 4) |
| Language | Kotlin |
| minSdk | 24 (Android 7.0+) |
| targetSdk / compileSdk | 34 |
| Build | Gradle 8.4 + AGP 8.3.2 |
| JDK | 17+ (21 recommended; set `JAVA_HOME` or local `org.gradle.java.home` — do not commit machine paths) |
| Paths | If the project path has non-ASCII characters, keep `android.overridePathCheck=true` (already set) |

## Features

| Area | Description |
|------|-------------|
| Three swipe pages | Folder browse / Player / Lyrics |
| Media scan | MediaStore audio + video (audio only) |
| Root folders | Configurable roots (system folder picker) |
| Playlists | User playlists; add / remove / drag reorder; stored locally |
| Favorites | Heart on list and player; “Favorites” under Playlists |
| Playback history | Playlists → **Playback history**: folders with play records (newest first); open jumps to folder; back returns to history |
| Multi-select | Long-press on page 1: batch add to playlist, batch delete; in a playlist: batch remove |
| Play modes | Repeat one / folder / next folder / **shuffle** (shuffle list once; next/prev follow that order until shuffle is re-entered) |
| Progress memory | Global last track; **per-folder** memory; launch locate; auto-locate on track change while on browse page |
| Folder resume | Optional auto-resume when **entering** a folder (not when going up); if already playing, only scroll + **green-dot** last track; long-press folder → “Resume last playback” |
| Browse UX | Going up restores parent scroll; last track green-dot; folders with history show a green-dot on the icon |
| Volume normalize | Optional shared loudness (RMS sample + cache); settings target RMS; Details menu shows gain |
| Lyrics | Same-name `.lrc` beside the file; karaoke + seek |
| Sleep timer / speed | Supported |
| Equalizer | Off by default; bar button |
| Idle auto-close | Settings: stop and exit after UI idle (default 2 h); continues in background / lock screen |
| Lock screen | MediaSession controls |
| Updates | Settings: check GitHub Releases (`android_musicplayer`) |
| Languages | Chinese / English / system default |
| Themes | Light multi-skin UI (extra accent packs) |
| File ops | Multi-select batch delete (system confirm); add to playlist |

### Playlists (brief)

1. **Browse root** → top entry “Playlists”; overflow menu can create a list  
2. **Long-press files** for multi-select → “Add to list” (existing or new) or batch delete  
3. **Inside a playlist**, long-press → “Remove from list”; drag handles reorder while multi-select is on  
4. **Player page (top-right)** add-to-list icon → pick a list or create one  
5. **Playback history** under Playlists → folders with per-folder play records  

Playlists store references and order only; deleting a playlist does not delete media files on the device.

### Playback notes

- Scan local audio/video via MediaStore; tap a track to play  
- Bottom bar: previous / play-pause / next, seek, modes, EQ, now-playing queue  
- Foreground service + notification / lock-screen controls  
- Permissions:  
  - Android 13+: `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `POST_NOTIFICATIONS`  
  - Older: `READ_EXTERNAL_STORAGE`  
- First launch needs media (and notification) permission grants  

## Quick start

### Local setup (do not commit secrets or machine paths)

1. Create `local.properties` (gitignored):

```properties
sdk.dir=path/to/Android/Sdk
```

2. JDK 17+: set `JAVA_HOME` / PATH, or set `org.gradle.java.home` in **your local** `gradle.properties` (do not push real paths).  
3. `adb` on PATH (or SDK `platform-tools`). USB debugging enabled on the device.

```powershell
java -version
adb devices
```

### Android Studio

This is a normal Android Gradle project (AGP 8.3.2, Gradle 8.4, Kotlin). You can open and build it fully in Android Studio; `build.js` / `gradlew` are optional CLI helpers using the same project.

1. **File → Open** the project root (the folder that contains `settings.gradle.kts`, not only `app/`).
2. Wait for **Gradle Sync**.
3. Pick a run configuration / debug variant → **Run** on a device or emulator, or use **Build → Make Project** / **Build APK(s)**.

Notes:

| Topic | Detail |
|-------|--------|
| Android Studio | Prefer **Hedgehog / Iguana or newer** (AGP 8.3 support) |
| Gradle JDK | **Settings → Build → Gradle → Gradle JDK** → JDK **17+** |
| `org.gradle.java.home` | If set in local `gradle.properties`, it overrides the IDE JDK; fix or remove a wrong path if Sync fails |
| `local.properties` | AS usually writes `sdk.dir` on first open |
| Non-ASCII path | `android.overridePathCheck=true` is already set for Chinese paths on Windows; if issues persist, try an ASCII-only path |
| Signing | With `keystore.properties` + `release.keystore`, debug/release can share a keystore so reinstall keeps app data |

### Using the script (recommended)

From the `music-player/` project root:

```powershell
# Build release APK → release/music{version}.apk
node build.js release

# Rebuild release only if sources are newer, then install & launch
node build.js install

# Always rebuild release, then install
node build.js install --force

# Build only (default is release)
node build.js build --release
node build.js build --debug

# Clean / rebuild
node build.js clean
node build.js rebuild --debug

# Install debug and start
node build.js run

# Multiple devices
node build.js run -s YOUR_SERIAL

# List devices
node build.js devices
```

### Using Gradle

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat installDebug
node build.js run
node build.js apk
```

Typical APK paths (release name includes versionName):

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/music*.apk
release/music1.0.3.apk
```

Release signing uses `keystore.properties` + `release.keystore` (shared with debug when configured so reinstall keeps data). Do not commit secrets; see `keystore.properties.example`.

### Day-to-day workflow

1. Edit `app/src/main/...`  
2. `node build.js build --debug` or `.\gradlew.bat assembleDebug`  
3. `node build.js run`  
4. `adb logcat` as needed  

Incremental builds usually do not need `clean`.

### Logcat / uninstall

```powershell
adb logcat | Select-String "AndroidRuntime"
adb uninstall com.whj.music
```

## Project layout

```
music-player/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/whj/music/
│       │   ├── MusicApp.kt
│       │   ├── MainActivity.kt
│       │   ├── data/            # browse, favorites, playlists, folder history, settings
│       │   ├── player/          # foreground service, equalizer, volume normalize
│       │   ├── ui/              # adapters
│       │   └── …
│       └── res/                 # layouts, themes, i18n
├── screenshots/                 # UI screenshots (shown above)
├── release/                     # packaged release APKs (e.g. music1.0.3.apk)
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties             # local SDK path, do not commit
├── gradlew.bat
├── build.js
├── CHANGELOG.md
├── keystore.properties.example
├── README.md                    # English (this file)
└── README.zh.md                 # Chinese
```

## Permissions

| Permission | Purpose |
|------------|---------|
| Read media audio/video | Browse & play |
| Foreground service / media playback | Background play & notification |
| Notifications | Playback notification / lock screen |
| Optional ignore battery optimizations | More reliable background play |

On Android 11+, batch file delete uses the system confirmation dialog.

## FAQ

### SDK not found

Check `sdk.dir` in `local.properties`.

### Wrong JDK for Gradle

Install JDK 17+ and set `JAVA_HOME`, or set `org.gradle.java.home` in **local** `gradle.properties` (do not commit machine-specific paths).

### adb shows no device

- Enable developer options and USB debugging  
- Try another cable/port; accept the debug prompt  
- `adb kill-server` then `adb devices`  

### Empty library list

No media on the device, or permission not granted. Tap “Grant permission” or refresh.

## License

This project’s original source code is released under the [MIT License](LICENSE).

Third-party libraries keep their own licenses; see the notice in `LICENSE`.
