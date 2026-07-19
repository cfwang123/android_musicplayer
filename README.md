# Music Player

An Android app for browsing local media folders and playing audio in the background.

[中文说明](README.zh.md) · Detailed notes: [DEVELOPMENT.md](DEVELOPMENT.md)

## Screenshots

| Page 1 · Browse / Playlists | Page 2 · Player | Page 3 · Lyrics |
|:---:|:---:|:---:|
| <img src="screenshots/1.jpg" width="240" alt="Browse and playlist" /> | <img src="screenshots/2.jpg" width="240" alt="Player" /> | <img src="screenshots/3.jpg" width="240" alt="Lyrics" /> |

- **Page 1**: Folders / playlists, current track highlight, bottom controls  
- **Page 2**: Cover, progress, favorite & add-to-playlist, speed / sleep timer, mini lyrics  
- **Page 3**: Full-screen lyrics (bilingual), karaoke highlight  

More images: [`screenshots/`](screenshots/).

## Overview

- **Package**: `com.whj.music`
- **Language**: Kotlin
- **minSdk 24** / **targetSdk 34**
- **Version**: 1.0.1 (`versionCode` 2)
- **Build**: Gradle 8.4 + AGP 8.3.2, JDK 17

## Features

| Area | Description |
|------|-------------|
| Three swipe pages | Folder browse / Player / Lyrics |
| Media scan | MediaStore audio + video (audio only) |
| Root folders | Configurable roots (system folder picker) |
| Playlists | User playlists; add / remove / drag reorder; stored locally |
| Multi-select | Long-press on page 1: batch add to playlist, batch delete files; in a playlist: batch remove |
| Play modes | Repeat one / folder / next folder / shuffle |
| Progress memory | Last track globally; per-folder memory; launch locate |
| Lyrics | Same-name `.lrc` beside the file; karaoke + seek |
| Sleep timer / speed | Supported |
| Favorites | Heart on list and player pages |
| Equalizer | Off by default; bar button |
| Lock screen | MediaSession controls |
| Languages | Chinese / English / system default |
| Themes | Light multi-skin UI |
| File ops | Multi-select batch delete (system confirm); add to playlist |

### Playlists (brief)

1. **Browse root** → top entry “Playlists”; overflow menu can create a list  
2. **Long-press files** for multi-select → “Add to list” (existing or new) or batch delete  
3. **Inside a playlist**, long-press → “Remove from list”; drag handles reorder while multi-select is on  
4. **Player page (top-right)** add-to-list icon → pick a list or create one  

Playlists store references and order only; deleting a playlist does not delete media files on the device.

## Quick start

### Requirements

- Android SDK (API 34)
- JDK 17
- `adb` on PATH
- Device with USB debugging (allow USB install when prompted)

Create `local.properties` (ignored by git):

```properties
sdk.dir=path/to/Android/Sdk
```

### Using the script (recommended)

From the `music-player/` project root:

```powershell
# Rebuild release only if sources are newer, then install & launch
node build.js install

# Always rebuild release, then install
node build.js install --force

# Build only
node build.js build --release
node build.js build --debug

# Install debug and start
node build.js run

# List devices
node build.js devices
```

### Using Gradle

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\music1.0.1.apk
```

Release signing uses `keystore.properties` + `release.keystore` (shared with debug when configured so reinstall keeps data). Do not commit secrets; see `keystore.properties.example`.

## Layout (brief)

```
music-player/
├── app/src/main/
│   ├── java/com/whj/music/
│   │   ├── data/            # browse, favorites, playlists, settings
│   │   ├── player/          # foreground service, equalizer
│   │   ├── ui/              # adapters
│   │   └── MainActivity.kt
│   └── res/                 # layouts, themes, i18n
├── screenshots/             # UI screenshots (shown in README)
├── build.js                 # build / install helper
├── DEVELOPMENT.md           # longer dev notes
├── keystore.properties.example
├── README.md                # Chinese
└── README_en.md             # this file
```

## Permissions

| Permission | Purpose |
|------------|---------|
| Read media audio/video | Browse & play |
| Foreground service / media playback | Background play & notification |
| Notifications | Playback notification / lock screen |
| Optional ignore battery optimizations | More reliable background play |

On Android 11+, batch file delete uses the system confirmation dialog.

## License

Use as determined by the repository owner.
