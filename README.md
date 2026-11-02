# Scraffold

High-Density Native Android Multi-PWA Container and Tampermonkey/Greasemonkey Userscript Engine.

Built for speed, user privacy, and zero telemetry.

---

## Key Features

- **Multi-PWA Shelf Launcher**:
  - Three customizable layouts: 3-column Icons, 2-column Grid with live telemetry, and List mode.
  - Branded dynamic gradient cards procedurally assigned to each WebApp.
  - Quick options sheet for per-app fullscreen toggle, adblocker settings, and userscripts.
  - Pure sovereign container: zero hardcoded pre-installed apps.

- **Quick Browser**:
  - Tabs, history, and logins kept in its own profile on the device, apart from every web app.
  - One tap to add the current page to the shelf.

- **Background Playback**:
  - Audio and video keep playing after leaving the app, with a media notification and lock screen controls.

- **Shield Ad Blocker**:
  - Blocks ads and trackers with public filter lists (EasyList, EasyPrivacy, uBlock Origin's lists, Peter Lowe, AdGuard Mobile), downloaded from their publishers and refreshed every few days. It isn't uBlock Origin, which can't run in a WebView.
  - Element hiding from the lists' per-site rules, and answers to a few common anti-adblock checks.
  - Background Service Worker request interception via `ServiceWorkerControllerCompat`.

- **Pre-DOM Userscript Engine**:
  - True race-free execution via `WebViewCompat.addDocumentStartJavaScript()`.
  - Full Tampermonkey / Greasemonkey bridge (`GM_addStyle`, `GM_setValue`, `GM_getValue`, `GM_log`).
  - Built-in Userscripts Manager and In-App Monospace Script Editor with metadata headers (`@match`, `@run-at`).

- **Hardware and Performance**:
  - Hardware-accelerated Chromium runtime (`WebView` on Android SDK 35).
  - Optional multi-finger gestures for moving through pages and switching apps.
  - Automatic 500 MB cache monitor and 1-tap cache purge daemon.

---

## Architecture Specifications

- **OS Target**: Android 15 (API 35)
- **Minimum SDK**: Android 8.0 Oreo (API 26)
- **Primary Language**: Kotlin 2.0.21
- **Database**: Room SQLite 2.6.1 (`scraffold.db`)
- **UI Engine**: AndroidX Material3 Obsidian Dark Palette (`#09090b`)

---

## Building From Source

```bash
# Clone the repository
git clone https://github.com/elsu3ian/scraffold.git
cd scraffold

# Build the release APK
./gradlew assembleRelease
```

Compiled APK artifacts are located at:
`app/build/outputs/apk/release/app-release.apk`

---

## License

Copyright (c) 2026 elsu3ian. Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

You may use and change Scraffold for personal and other noncommercial purposes. You may not sell it or use it commercially. This is a summary; the [LICENSE](LICENSE) file is what applies.
