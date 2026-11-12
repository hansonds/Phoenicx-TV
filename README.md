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

Phoenicx TV uses the Android framework UI (no Compose, no Material library), Google's ExoPlayer (Media3) for playback, and a 100-line logo loader. There is no VLC, no FFmpeg and no native code, so a single APK works on every processor.

## Build

The APK is built by GitHub Actions on every push to `main` and published to Releases. Add the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` secrets to sign it with your own key; without them a temporary key is used.

Phoenicx TV provides **no content**. Only use playlists you are entitled to use.

MIT licence · [phoenicx.org](https://phoenicx.org)
