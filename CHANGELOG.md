# Scraffold Changelog

All notable changes to this project are documented in this file.

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
