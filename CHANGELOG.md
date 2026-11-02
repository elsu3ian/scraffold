# Scraffold Changelog

All notable changes to this project are documented in this file.

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
