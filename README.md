# Phoenicx TV

A very small IPTV player for **Fire TV**, **Android TV** and Android phones. No ads, no account, no bundled video engines — just your playlist and a player.

## What it does

1. **Add a playlist** — an M3U / M3U8 link, or an Xtream Codes login (server, username, password).
2. **Home** shows the **groups** in that playlist (for example *Malayalam*, *Tamil*, *News*).
3. Open a group to see its **channels with logos**.
4. Click a channel and it **plays full screen**. Press **Back** to return to the list.
   While playing, **Up / Down** switches to the previous / next channel in the group.

The playlist is cached on the device, so the app opens instantly; use **Refresh** to re-download it and **Change playlist** to use a different one.

## Install

**Fire TV:** open the *Downloader* app and enter

```
https://github.com/hansonds/Phoenicx-TV/releases/latest/download/PhoenicxTV.apk
```

**Android TV / phone:** download `PhoenicxTV.apk` from [Releases](https://github.com/hansonds/Phoenicx-TV/releases/latest) and install it.

## Why it is small

Phoenicx TV uses the Android framework UI (no Compose, no Material library), Google's ExoPlayer (Media3) for playback, and a 100-line logo loader. There is no VLC. The only native code is a small **FFmpeg audio decoder** (LGPL build, ARM only) that handles AC-3, E-AC-3, DTS and similar audio when the device has no hardware decoder for it.

## Build

The APK is built by GitHub Actions on every push to `main` and published to Releases, signed with the keystore in `signing/`.

The FFmpeg decoder (`app/libs/media3-decoder-ffmpeg-lgpl.aar`) is the Media3 FFmpeg extension built without GPL components; FFmpeg is licensed under the LGPL 2.1+ and the exact FFmpeg commit is recorded next to the `.aar`. It is the LGPL build made by the [Ultra TV](https://github.com/vantaflow/ultra-tv) project (`android-native/tools/ffmpeg-lgpl/build.sh`).

Phoenicx TV provides **no content**. Only use playlists you are entitled to use.

MIT licence · [phoenicx.org](https://phoenicx.org)
