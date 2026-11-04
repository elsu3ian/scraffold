<div align="center">

# Scraffold

**Turn any website into its own Android app, with ad blocking and userscripts built in.**

[![Download APK](https://img.shields.io/badge/Download%20APK-10b981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/elsu3ian/scraffold/releases)

[![Latest pre-release](https://img.shields.io/github/v/release/elsu3ian/scraffold?include_prereleases&label=pre-release&color=10b981)](https://github.com/elsu3ian/scraffold/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/elsu3ian/scraffold/build-apk.yml?branch=main&label=build)](https://github.com/elsu3ian/scraffold/actions/workflows/build-apk.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
[![License: PolyForm Noncommercial](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue)](LICENSE)

[Download](#installation) · [Features](#features) · [Userscripts](#userscripts) · [Privacy](#privacy) · [Build from source](#building-from-source) · [License](#license)

</div>

---

## Overview

Scraffold is a native Android container for web apps. Add a site once and it gets its own place on your shelf and its own task in Recents. It opens without browser bars and comes back exactly where you left it.

An ad blocker, a Tampermonkey-style userscript engine, background playback with proper media controls, and a tabbed browser are all included. There are no accounts and no analytics.

> [!NOTE]
> Scraffold is in **beta**. Expect rough edges, and please [report what you find](https://github.com/elsu3ian/scraffold/issues).

## Features

### Web apps

- **Its own app:** each site gets a shelf icon, an entry in Recents, and a full-screen window without browser bars.
- **Picks up where you left off:** leave an app and it returns to the page you were on. Pressing back all the way out starts it fresh next time.
- **Shared or separate logins:** apps share sign-ins by default, so signing in once works everywhere. Turn on **Separate login** for an app to keep a second account in it.
- **Per-app settings:** JavaScript, cookies, ad blocking, fullscreen, desktop mode, zoom, data saver, location, camera, microphone, and protected media.
- **Clear cache or clear data** for one app from its hold menu, without touching the others.
- **Page and font size:** set for each app.
- **Side bubble:** a floating button for reload, home, ad blocking, scripts, text size, desktop mode, and switching between apps.

### Background playback

- Audio and video keep playing after you leave the app.
- The notification and lock screen show the title, the channel or artist, and the cover, with a seek bar.
- Previous and next skip tracks on sites with playlists, and jump 10 seconds back or forward everywhere else.

### Desktop mode

- Asks sites for their desktop version, the way Chrome on a phone does.
- Cloudflare's "Verify you are human" check never accepts desktop mode from an app, because Android's WebView names the app in every request. When the check turns up, Scraffold offers that site's mobile version instead, still laid out at desktop width.

### Usage

- **Screen time:** how long each app was open, by hour, day, week, 14 days, month, or year.
- **Battery:** an estimate of how much battery each app used while it was open. For exact numbers, Android's own battery screen is one tap away.

### Home screen and shelf

- **Folder widget:** a 2x2 home screen folder. Three big icons open apps directly, and the small icons open a panel with every app.
- **Three layouts:** icons, grid, or list.
- **Your order:** sort by recent use, name, or date added, or drag apps into a custom order. The widget follows the same order.
- **Themes:** change the accent colour and background.
- **Refresh rate:** ask for 60, 90 or 120 Hz, or Adaptive, on phones that let apps choose. Rates a phone can't do are greyed out.
- **A short guide** on first run, and again any time from Settings.

### Ad blocker

- Off until you turn it on, with a plain explanation of what it does first.
- Uses EasyList, EasyPrivacy, uBlock Origin's lists, Peter Lowe's list, and AdGuard Mobile, downloaded from their publishers and refreshed every few days.
- Blocks network requests, including those from service workers, and hides leftover page elements using each list's per-site rules.
- **Hide an element** on any page, and bring back what you hid with one tap. Your own rules live in **My filters**.
- Can be turned on or off for each app.

The filter engine is Brave's [adblock-rust](https://github.com/brave/adblock-rust), built into the app. Scraffold is not uBlock Origin, which can't run inside an Android WebView.

### Browser

- Tabs that restore after the app closes.
- History with a clear button.
- Add the current page to your shelf in one tap.

### Userscripts

- Scripts run at document start, before the page's own code.
- Includes a script manager and an in-app editor.
- Tampermonkey and Greasemonkey metadata and APIs are supported. See [Userscripts](#userscripts).

### Everything else

- Export and import your settings and web apps as a backup file. Logins aren't included, for your safety.
- Optional multi-finger gestures for reloading, moving through pages, and switching apps.
- Automatic cache clearing at 500 MB that keeps your logins and cookies.
- A pop-up when a new version is out, and an update check in the About screen.

## Installation

Requires **Android 8.0 (API 26)** or newer.

1. Download `Scraffold-app-release-beta.apk` from the newest release on the [releases page](https://github.com/elsu3ian/scraffold/releases).
2. Open the file and allow installs from that source if Android asks.
3. Tap **+** on the shelf and add your first site.

> [!IMPORTANT]
> **Coming from 1.0.3-beta or older?** Those builds were signed with a temporary key, so the new version can't install over them. Export a backup in **Settings**, uninstall, install the new version, and import the backup. You'll need to sign in to your sites again. This happens only once: from 1.1.0-beta on, every update installs over the last.

### Adding the folder widget

Hold an empty spot on your home screen, then choose **Widgets → Scraffold → App folder**. The steps vary slightly between launchers.

## Userscripts

Install a script from a `.user.js` link or write one in the built-in editor. Scripts run on any web app or page their metadata matches.

| Type | Supported |
| --- | --- |
| Metadata | `@match`, `@include`, `@exclude`, `@run-at`, `@require`, `@resource`, `@connect` |
| Storage | `GM_getValue`, `GM_setValue`, `GM_deleteValue`, `GM_listValues`, `GM_addValueChangeListener`, `GM_removeValueChangeListener` |
| Page | `GM_addStyle`, `GM_addElement`, `GM_setClipboard`, `GM_notification`, `GM_openInTab`, `GM_download` |
| Network | `GM_xmlhttpRequest`, `GM.xmlHttpRequest` |
| Resources | `GM_getResourceText`, `GM_getResourceURL` |
| Menu and info | `GM_registerMenuCommand`, `GM_unregisterMenuCommand`, `GM_info`, `GM_log` |

Only install scripts you trust. A userscript can read and change everything on the pages it runs on.

## Privacy

Scraffold has no accounts, analytics, or crash reporting. Your apps, history, logins, and usage numbers stay on your device.

Apart from the sites you open, the app itself only connects to:

| Destination | Why |
| --- | --- |
| Filter list publishers | Downloading ad blocker lists every few days |
| GitHub | Checking for updates, and the ad blocker's helper scripts from Brave's adblock-resources |
| jsDelivr | The ad blocker's helper scripts from uBlock Origin |
| The sites you add | Fetching their icons |
| The cover a page names | Showing it in the media notification |

Searches typed into the browser's address bar go to Google. Sites you visit can still see your traffic, as they would in any browser.

## Building from source

You'll need **JDK 17**, the **Android SDK** with API 35 and the NDK, and **Rust** with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) for the ad blocker's engine.

```bash
git clone https://github.com/elsu3ian/scraffold.git
cd scraffold

# The ad blocker's engine, built into the app's native libraries
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cd native/adblock
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 -o ../../app/src/main/jniLibs build --release
cd ../..

./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

A release build (`./gradlew assembleRelease`) is signed with your own key if you point `SCRAFFOLD_SIGNING_PROPERTIES` at a `keystore.properties` file, and with the debug key otherwise. Either way it can't update the official releases, which are signed with a key only the maintainer holds.

### Built with

| | |
| --- | --- |
| Language | Kotlin 2.0 and Java 17, with Rust for the ad blocker's engine |
| Target | Android 15 (API 35), minimum Android 8.0 (API 26) |
| Web engine | Android WebView with AndroidX WebKit |
| Ad blocking | Brave's adblock-rust |
| Storage | Room (SQLite) |
| Networking | OkHttp |
| UI | Android Views, Material 3 |

## Contributing

Bug reports and suggestions are welcome in [Issues](https://github.com/elsu3ian/scraffold/issues). For a bug, please include your Android version, your device, and the site involved. See the [changelog](CHANGELOG.md) for what has changed in each version.

## Acknowledgements

Ad blocking is possible thanks to Brave's [adblock-rust](https://github.com/brave/adblock-rust) and [adblock-resources](https://github.com/brave/adblock-resources), and to the people who maintain [EasyList](https://easylist.to), [uBlock Origin's filter lists](https://github.com/uBlockOrigin/uAssets), [Peter Lowe's list](https://pgl.yoyo.org/adservers/), and [AdGuard's filters](https://github.com/AdguardTeam/AdguardFilters).

## License

Copyright (c) 2026 elsu3ian. Scraffold's own code is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

In short: you may use, study, change, and share Scraffold for personal and other noncommercial purposes, as long as you keep the license and its copyright notice with it. You may not sell it or use it commercially. This is a summary; the [LICENSE](LICENSE) file is what applies.

### Third-party code

Scraffold includes code from other projects, which stays under its own license:

| Component | License |
| --- | --- |
| [adblock-rust](https://github.com/brave/adblock-rust) (Brave) | Mozilla Public License 2.0 |
| [jni](https://github.com/jni-rs/jni-rs), [serde_json](https://github.com/serde-rs/json) | MIT or Apache License 2.0 |
| AndroidX, Material Components, Room | Apache License 2.0 |
| [OkHttp](https://github.com/square/okhttp) | Apache License 2.0 |
| Kotlin and kotlinx.coroutines | Apache License 2.0 |

The app lists these in **Settings → About Scraffold & Licenses**. Filter lists and the ad blocker's helper scripts are downloaded from their publishers at run time and are not part of Scraffold; each comes under its publisher's own license.
