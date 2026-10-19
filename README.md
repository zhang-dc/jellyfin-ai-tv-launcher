# Jellyfin AI TV Launcher

**English** | [简体中文](README.zh-CN.md)

A **minimal, ad-free launcher** for Android TV and mainland-China Xiaomi TVs that brings your home media server and your own AI agent onto the TV:

- **No ads, no "recommended" rows** — black background, a clock, and your stuff
- **Continue watching / Recently added** — read straight from your Jellyfin server, with progress bars; press OK and it starts playing right away
- **Built-in player with subtitles done right** — ExoPlayer straight from Jellyfin (falls back to server transcoding), resume + progress sync, auto next episode. Subtitles are rendered by the launcher itself: **adjust timing live with the remote** (0.1 s steps, remember per season, or ask your agent to fix the file for good), outline / translucent / black-box styles, white / yellow, three sizes, overlapping lines merged, and the video's own subtitle tracks (incl. PGS) selectable
- **Ask** — talk to your own AI agent: hold OK and speak into the TV, press the remote's voice button (transcribed by the built-in Xiaomi assistant), or type
- **Find / Downloads** — search for content, start a download, watch progress (implemented by your own backend)
- **Debloat script** — disable preinstalled ad, analytics and push packages over ADB, fully reversible

![Home](docs/screenshots/home.png)

> Inspired by [tv-vibehack](https://github.com/Gathin23/tv-vibehack), where a developer used Claude Code to strip the ads off their parents' Xiaomi TV and replace the launcher. This project applies the same idea to the **mainland-China** Xiaomi TV firmware, which is far more locked down, and wires it up to a home media server and an AI assistant.

## What's in this repo

Only the **TV side**: the launcher APK source and an ADB maintenance script. Backends (AI agent, speech recognition, content search and download) differ for everyone, so this repo ships **only the interface contract** — see [docs/protocol.md](docs/protocol.md). It's small on purpose: hand it to your AI coding assistant and have it implement the endpoints on your own server.

```
launcher/          Launcher APK source (plain Java + platform widgets; no AndroidX, no Gradle)
  deps.mjs         Fetches Media3 (ExoPlayer) + transitive deps from Maven, no Gradle
  build.mjs        One command → signed APK
  assets/*.example Config templates (Jellyfin, backend gateway)
tv.mjs             ADB tooling: backup, debloat, restore, screenshot, enable home-key takeover
disable-list.txt   Packages to disable (disable only, never uninstall)
docs/              (in Chinese — any LLM will translate)
  protocol.md      Endpoints your backend implements (chat / speech-to-text / search / downloads)
  jellyfin.md      Jellyfin setup
  voice.md         Voice assistant setup (local Whisper)
  xiaomi-notes.md  Mainland Xiaomi TV pitfalls and workarounds (home key, mic, voice button, ad packages)
```

## Everything is optional

| You have | You get |
|---|---|
| Just the TV | Minimal launcher + debloat (works with no config at all; shows apps only) |
| + Jellyfin | "Continue watching" and "Recently added" rows |
| + your backend gateway | "Ask", "Find" and "Downloads" pages, plus a "Downloading" row on the home screen |
| + a speech-to-text service | Talking to the TV |

Anything not configured is hidden automatically.

## Quick start

### 1. Prerequisites

> The build and ADB scripts are currently written for **Windows** (paths to `7z.exe`, `adb.exe`, and a default JDK location; override with `JAVA_HOME`). On macOS/Linux, adjust those few paths in `launcher/build.mjs` and `tv.mjs` — or ask your AI assistant to.

- TV: Settings → About → press "Model" 7 times to enable developer mode → turn on **ADB debugging**
- PC: JDK 17, Node.js 18+, 7-Zip. Android SDK needs only `build-tools;34.0.0` and `platforms;android-34`, placed in `tools/android-sdk`
- adb: put `platform-tools` in `tools/platform-tools`

```bash
# Android command-line tools — just the two packages, no Android Studio
sdkmanager --sdk_root=tools/android-sdk "build-tools;34.0.0" "platforms;android-34"
```

### 2. Connect and back up first

```bash
export TV_ADDR=<tv-ip>:5555
node tv.mjs connect          # accept "Allow USB debugging" on the TV (tick "always allow")
node tv.mjs backup           # packages / settings / default launcher → backup/; restore depends on this
```

### 3. Debloat (optional)

Review `disable-list.txt` first, then:

```bash
node tv.mjs disable          # pm disable-user only, nothing is uninstalled
node tv.mjs restore          # changed your mind? restores everything from the backup
```

### 4. Configure and build

```bash
cd launcher
cp assets/jellyfin.properties.example assets/jellyfin.properties   # optional, see docs/jellyfin.md
cp assets/gateway.properties.example  assets/gateway.properties    # optional, see docs/protocol.md
node deps.mjs                # once: downloads the player engine (Media3) and its deps into libs/, no Gradle
node build.mjs               # → out/tvhome.apk
```

### 5. Install on the TV

```bash
adb -s $TV_ADDR install -r launcher/out/tvhome.apk
node tv.mjs enable-home-service    # home-key takeover + grants mic, log-reading and usage-stats permissions
```

On a regular Android TV you can simply set it as the default launcher. **Mainland Xiaomi TVs refuse third-party default launchers**, so the home key is taken over by an accessibility service instead — see [docs/xiaomi-notes.md](docs/xiaomi-notes.md) for how and why.

## Customizing the app row

The favorite apps live in `FAVORITES` at the top of `MainActivity.java` (package, display name, optional activity). Apps that aren't installed are skipped.

## Screenshots

| Home | Ask |
|---|---|
| ![](docs/screenshots/home.png) | ![](docs/screenshots/agent.png) |

## Highlights of the Xiaomi workarounds

- **Home key**: `set-home-activity` silently does nothing, and disabling the stock launcher leaves the TV stuck on a blank `FallbackHome`. An accessibility service intercepts the remote's home key, and watches `UsageStats` because the stock launcher's window is hidden from accessibility.
- **Microphone**: the built-in far-field mic only delivers audio at **48 kHz** via `VOICE_COMMUNICATION` / `VOICE_RECOGNITION`; 16 kHz stalls. The launcher records at 48 kHz and downsamples.
- **Remote voice button**: it never reaches Android's key pipeline. While the Ask/Find page is open, the launcher reads the Xiaomi assistant's recognized text from logcat and dismisses its popup.

## License

MIT
