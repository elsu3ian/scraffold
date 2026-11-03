# Scraffold Changelog

All notable changes to this project are documented in this file.

---

## [Unreleased]

### Added
- **Shield meter**: The side bubble's shield button shows how many ads, trackers and pop-ups were blocked on the site on screen, updating live.

### Changed
- The shield now runs on Brave's adblock-rust (MPL-2.0), built from `native/adblock` for arm64-v8a, armeabi-v7a and x86_64. It reads the same lists, keeps a compiled copy so it loads in moments, and hides ads from the first moment a page loads, in every frame, using site and generic element-hiding rules. Brave's scriptlet resources are downloaded with the lists.
- Pop-up rules (`$popup`) still apply, through a small separate engine checked only for new windows.
- `+js` rules, which YouTube's ad rules rely on, can now use uBlock Origin's scriptlets. Like the filter lists, they are downloaded from uBlock Origin at runtime and stay under its own GPLv3 license; none of its code is in the app or this repository. The phone converts them for the engine and keeps the last good copy, and Brave's scriptlets still apply if the download fails. The source is one address in `FilterLists`, so it can be pointed elsewhere.
- Pages see Chrome rather than an app: the user agent drops WebView's `wv` and `Version/4.0` marks, the `X-Requested-With` header no longer names the app, script brand checks report Google Chrome, and the userscript bridge is only added when a userscript runs.
- The shield now applies `$popup` and `$document` filter rules, stops pages from redirecting by themselves to ad hosts, and stops pages from opening the Play Store or other apps without a tap.
- A window a page opens on another site, when the tap wasn't on a link to it, is held behind a "Blocked a popup" notice with an Open button. Sign-in windows still open directly.

---

## [1.0.3-beta] - 2026-11-02

### Added
- **True Display Cutout Fullscreen**: Eliminated black notification notch letterboxing by enabling `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` and `WindowCompat.setDecorFitsSystemWindows(false)`.
- **Universal External Links**: Implemented `onCreateWindow` and multi-window popup routing for external URLs, donate links, Discord, Telegram, and deep links.
- **Assistive Edge Dock Ball**: Draggable floating ball that auto-hides half-way into screen edge, with tap quick actions (Reload, Home, Shield) and long-press Running Apps Switcher bottom sheet.
- **Obsidian Modals Parity**: Built custom Add WebApp bottom sheet (`modal-add`), Cosmic Theme palette customizer (`modal-cosmic`), and Default Web App Settings sheet (`modal-app-settings`).
- **Pre-Release Distribution**: Configured automated compilation and publishing directly as GitHub Pre-releases.
- **Home Screen Folder Widget**: A 2x2 folder with three big icons that open apps directly and small icons that open a folder with every app, in the shelf's sort order.
- **Quick Browser**: Tabs, history with a clear button, a side bubble, its own login profile, and adding any page to the shelf.
- **Background Playback**: Audio and video keep running after leaving the app, with notification and lock screen controls.
- **Filter-List Ad Blocker**: EasyList, EasyPrivacy, uBlock Origin's lists, Peter Lowe and AdGuard Mobile, downloaded from their publishers and refreshed every few days.
- **Shelf Customization**: Sorting including a custom drag order, a Customize sheet, and a theme reset.
- **Page and Font Size**: Set per web app, and across the browser.

### Changed
- Web apps resume where they were left, each in its own task and Recents entry.
- The update check now finds pre-releases, and About shows the Beta stage.
- The license is now PolyForm Noncommercial 1.0.0.

### Fixed
- Apps left open across a phone restart could reopen as the wrong site.

---

## [1.0.2] - 2026-11-02

### Added
- **Desktop-Grade Script Editor**: Full split architecture matching prototype with `[ Editor ]` / `[ Settings ]` sub-tabs, menu bar (`File`, `Edit`, `Selection`, `Find`, `GoTo`), line numbers gutter, and bottom status bar (`Lines: X • UTF-8 | JavaScript • document-start`).
- **About Engine Specifications**: Restored Engine Specifications panel (Core Runtime, Adblock Engine, Cosmetic Filtering, Script Execution, Cache Auto-Purge) and expandable Open Source Licenses accordions (Scraffold MIT, adblock-rust MPL-2.0, AndroidX WebKit & Room Apache-2.0).
- **Settings Prototype Parity**: Added Default Web App Settings row and exact 6 prototype gesture/behavior toggles (two-finger reload, two-finger navigation, three-finger app switcher, software navigation buttons, clear cache on exit, progress bar).
- **Shelf Tab Underline & Status Bar**: Fixed active tab indicator line rendering (`bg_tab_active.xml`), added Cosmic Theme palette button in header, and added fixed bottom status bar displaying shield counter and option hints.

---

## [1.0.1] - 2026-11-02

### Added
- **Universal Window Insets**: Fixed status bar collision across MainActivity, SettingsActivity, AboutActivity, ScriptEditorActivity, and WebContainerActivity for seamless Android 14/15 and Xiaomi HyperOS edge-to-edge support.
- **Userscripts Manager Activity**: Dedicated screen to list, toggle, create, and manage Tampermonkey and Greasemonkey userscripts.
- **Script Editor Templates**: Pre-populated Greasemonkey metadata header (`@name`, `@match`, `@run-at document-start`, `@grant none`).
- **AdBlock Dashboard**: Dedicated panel in Global Settings displaying uBlock Origin engine status, live blocked request counts, and network rules.
- **Obsidian App Action Bottom Sheet**: Modal bottom sheet accessible via 3-dots button or long-press with launch, fullscreen toggle, script manager, and delete actions.
- **Empty Shelf State**: Clean obsidian card informing the user how to add their first WebApp.
- **Active Tab Underline**: View switcher tabs with emerald underline indicator (`#10b981`, 2dp) matching the prototype.
- **Branded Gradient Cards**: 5 procedural gradient squircles (Emerald, Violet, Orange, Cyan, Rose) based on domain hash.
- **In-App Userscript HUD**: 2-second floating status pill notifying the user when custom scripts execute on a WebApp.

### Changed
- Rebuilt Icons view mode with 3 columns (`grid-cols-3`) and clean domain subtitles.
- Rebuilt Grid view mode with 36dp squircle, 3-dots action button, script count, and live blocked counter.
- Rebuilt List view mode with 32dp squircle, domain, blocked count, and action button.
- Overhauled Global Settings to include protection engine rows, gesture switches, cache metrics, and JSON backup/restore.
- Redesigned Floating Action Button to a sleek 44dp squircle with crisp white plus vector.

### Removed
- Removed hardcoded default app seeding (`seedDefaultApps()`). Scraffold now launches as a pure, clean, sovereign container.

---

## [1.0.0] - 2026-11-02

### Initial Release
- Native Android multi-PWA container targeting SDK 35.
- Room SQLite persistence for WebApps, Userscripts, and key-value script storage.
- uBlock Origin filter rule engine with network blocking and cosmetic CSS hiding.
- Pre-DOM userscript injection via `WebViewCompat.addDocumentStartJavaScript`.
- 4-finger swipe reload gesture and 500 MB cache auto-purge.
- Obsidian dark theme palette (`#09090b`).
