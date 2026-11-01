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

- **uBlock Origin Hybrid AdBlock Engine**:
  - Fast in-memory network URL interception (<0.05ms) blocking ad trackers, telemetry, and popunders.
  - Pre-DOM cosmetic CSS element hiding (`##`) injecting collapsed stylesheets before HTML render.
  - Anti-adblock scriptlet defusers (`+js`) neutralizing anti-adblock detection walls.
  - Background Service Worker request interception via `ServiceWorkerControllerCompat`.

- **Pre-DOM Userscript Engine**:
  - True race-free execution via `WebViewCompat.addDocumentStartJavaScript()`.
  - Full Tampermonkey / Greasemonkey bridge (`GM_addStyle`, `GM_setValue`, `GM_getValue`, `GM_log`).
  - Built-in Userscripts Manager and In-App Monospace Script Editor with metadata headers (`@match`, `@run-at`).

- **Hardware and Performance**:
  - Hardware-accelerated Chromium runtime (`WebView` on Android SDK 35).
  - 4-finger swipe-down gesture listener for instant page reload with haptic feedback.
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

This project is licensed under the MIT License. See [About Scraffold] in-app or repository license files.
