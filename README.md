<div align="center">

# Scraffold

**Turn any website into its own Android app, with ad blocking and userscripts built in.**

[![Latest pre-release](https://img.shields.io/github/v/release/elsu3ian/scraffold?include_prereleases&label=pre-release&color=10b981)](https://github.com/elsu3ian/scraffold/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/elsu3ian/scraffold/build-apk.yml?branch=main&label=build)](https://github.com/elsu3ian/scraffold/actions/workflows/build-apk.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
[![License: PolyForm Noncommercial](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue)](LICENSE)

[Download](#installation) · [Features](#features) · [Userscripts](#userscripts) · [Privacy](#privacy) · [Build from source](#building-from-source)

</div>

---

## Overview

Scraffold is a native Android container for web apps. Add a site once and it gets its own place on your shelf, its own task in Recents, and its own separate login. It opens without browser bars and comes back exactly where you left it.

A filter-list ad blocker, a Tampermonkey-style userscript engine, background playback, and a tabbed browser are all included. There are no accounts and no analytics.

> [!NOTE]
> Scraffold is in **beta**. Expect rough edges, and please [report what you find](https://github.com/elsu3ian/scraffold/issues).

## Features

### Web apps

- **Separate by default:** each app has its own login profile and its own entry in Recents.
- **Picks up where you left off:** leave an app and it returns to the page you were on. Pressing back all the way out starts it fresh next time.
- **Per-app settings:** JavaScript, cookies, ad blocking, fullscreen, desktop mode, zoom, data saver, location, camera, microphone, and protected media.
- **Page and font size:** set for each app.
- **Background playback:** audio and video keep running after you leave the app, with notification and lock screen controls.
- **Side bubble:** a floating button for reload, home, ad blocking, scripts, text size, desktop mode, and switching between apps.

### Home screen and shelf

- **Folder widget:** a 2x2 home screen folder. Three big icons open apps directly, and the small icons open a panel with every app.
- **Three layouts:** icons, grid, or list.
- **Your order:** sort by recent use, name, or date added, or drag apps into a custom order. The widget follows the same order.
- **Themes:** change the accent colour and background.

### Ad blocker

- Uses EasyList, EasyPrivacy, uBlock Origin's lists, Peter Lowe's list, and AdGuard Mobile, downloaded from their publishers and refreshed every few days.
- Blocks network requests, including those from service workers, and hides leftover page elements using each list's per-site rules.
- Can be turned on or off for each app.

Scraffold uses these public filter lists with its own engine. It is not uBlock Origin, which can't run inside an Android WebView.

### Browser

- Tabs that restore after the app closes.
- History with a clear button.
- Logins kept in the browser's own profile, apart from your web apps.
- Add the current page to your shelf in one tap.

### Userscripts

- Scripts run at document start, before the page's own code.
- Includes a script manager and an in-app editor.
- Tampermonkey and Greasemonkey metadata and APIs are supported. See [Userscripts](#userscripts).

### Everything else

- Export and import your settings and web apps as a backup file.
- Optional multi-finger gestures for reloading, moving through pages, and switching apps.
- Automatic cache clearing at 500 MB that keeps your logins and cookies.
- Update check in the About screen.

## Installation

Requires **Android 8.0 (API 26)** or newer.

1. Download `Scraffold-app-release-beta.apk` from the [latest release](https://github.com/elsu3ian/scraffold/releases).
2. Open the file and allow installs from that source if Android asks.
3. Tap **+** on the shelf and add your first site.

> [!IMPORTANT]
> Beta builds aren't signed with a permanent key yet, so a new version may refuse to install over an old one. If it does, export a backup in **Settings**, uninstall, install the new version, and import the backup. Logins aren't part of the backup, so you'll need to sign in again.

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

Scraffold has no accounts, analytics, or crash reporting, and your apps, history, and logins stay on your device.

Apart from the sites you open, the app itself only connects to:

| Destination | Why |
| --- | --- |
| Filter list publishers | Downloading ad blocker lists |
| GitHub | Checking for updates |
| The sites you add | Fetching their icons |

Searches typed into the browser's address bar go to Google. Sites you visit can still see your traffic, as they would in any browser.

## Building from source

You'll need **JDK 17** and the **Android SDK** with API 35.

```bash
git clone https://github.com/elsu3ian/scraffold.git
cd scraffold
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

### Built with

| | |
| --- | --- |
| Language | Kotlin 2.0, Java 17 |
| Target | Android 15 (API 35), minimum Android 8.0 (API 26) |
| Web engine | Android WebView with AndroidX WebKit |
| Storage | Room (SQLite) |
| Networking | OkHttp |
| UI | Android Views, Material 3 |

## Contributing

Bug reports and suggestions are welcome in [Issues](https://github.com/elsu3ian/scraffold/issues). For a bug, please include your Android version, your device, and the site involved. See the [changelog](CHANGELOG.md) for what has changed in each version.

## Acknowledgements

Ad blocking is possible thanks to the people who maintain [EasyList](https://easylist.to), [uBlock Origin's filter lists](https://github.com/uBlockOrigin/uAssets), [Peter Lowe's list](https://pgl.yoyo.org/adservers/), and [AdGuard's filters](https://github.com/AdguardTeam/AdguardFilters).

## License

Copyright (c) 2026 elsu3ian. Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

You may use and change Scraffold for personal and other noncommercial purposes, but you may not sell it or use it commercially. This is a summary; the [LICENSE](LICENSE) file is what applies.
