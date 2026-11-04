# Scraffold Changelog

What changed in each version of Scraffold.

---

## [Unreleased]

### Added
- **A guide for your first visit**: The first time you open Scraffold, a short guide shows you where everything is, with a demo app on the step about your apps if your shelf is still empty. You can watch it again from Settings, under How to use Scraffold.
- **Usage, with Screen time and Battery pages**: See how long you spent in each app and the browser, and how much battery went while each was open, in Settings. A ring shows each app's share with the total in the middle, and a dropdown lets you look at a day hour by hour, or the last week, 14 days, month or year. Tap a bar to see just that hour, day or month. Battery use is an estimate, since the screen and apps in the background count too, so the Battery page also opens your phone's own battery usage. Only the numbers are kept, on your phone: each day for a year, and hour by hour for a month.
- **A proper media notification**: When a video or song keeps playing after you leave an app, the notification shows what's playing, who made it and which app it's in, with its cover and a seek bar. Sites with playlists get previous and next buttons, and anything else gets buttons that jump 10 seconds back or on. Pages without a cover get one in your accent color, and your phone colors the player to match.
- **Refresh rate**: Some phones keep apps they don't know at 60 Hz. Settings now lets Scraffold ask for 60, 90 or 120 Hz, or Adaptive, which runs at the fastest rate while you touch or scroll and slows down once the screen is still. Rates your screen can't do are greyed out, and Phone default stays the battery-friendly choice. Settings shows the rate the screen runs at right now. Some phones, Xiaomi's among them, pick the rate themselves whatever an app asks; Scraffold checks for that once and leaves the choice to the phone there.
- **Scrollbars** run along the screen's edge instead of over the cards.
- **Cloudflare checks**: pages could tell that Scraffold had rewritten parts of the browser to keep media playing in the background, and had no `window.chrome` like Chrome does, which Cloudflare's "Verify you are human" check takes for a bot. Both now look the way they do in Chrome.
- **Cloudflare in desktop mode**: the check still never accepts desktop mode, because Android's WebView names the app in a header on every request and an app can't take it off. When it turns up, Scraffold now offers the site's mobile version instead, which the check accepts, still zoomed out to a desktop's width. Tick "Don't ask again" to have it switch by itself or never ask. Settings, Cloudflare checks in desktop mode, changes the answer or forgets the sites.
- **Clear cache and Clear data for each app**: Hold an app on the shelf to clear its cache, or its data, which signs it out and erases what its site saved, like a phone does for a real app. It stays on your shelf with its settings, and your other apps stay signed in.
- **Bring back what you hid**: The shield now shows how many things you hid on the site you're on, and one tap shows them again. You can also remove them one by one in Settings, under Ad blocking, My filters.
- **Back and forward buttons in the browser**, next to the address bar, like a regular browser.
- **The side ball goes either side**: Drag it to the left or right edge of the screen and it stays there.
- **Update notices**: When a new version is out, the home screen tells you what's new and can install it for you. You can also check from About.
- **Apps that look like the real thing**: When you add a site, Scraffold uses the site's own app name, icon and colors, and opens it on the right page. Links to other sites open in a browser on top, so the app stays put.
- **Pull to reload**: Pull down at the top of a page to reload it. Pages that scroll inside themselves, like feeds and chats, just scroll. You can turn this off in Settings.
- **See what was blocked**: The shield button on the side ball shows how many ads, trackers and pop-ups were stopped on the site you're on.
- **Hide anything yourself**: Tap Hide an element in the shield, then tap the ad or box you want gone. Scraffold remembers it for that site.
- **Turn blocking off for one site**: If a site breaks, switch blocking off just for that site from the shield.
- **Your own filters**: Ad blocking settings has a place for your own block rules, if you know how to write them.

### Changed
- **Ad blocking is off until you turn it on**: Turn it on in Settings, under Ad blocking, after reading what it does. Once on, it blocks far more than before, including ads inside videos on many sites, and it catches ads as the page opens instead of after. The ad blocking window shows how many block lists and rules are in use.
- **Sign in once**: All your apps and the browser now share your sign-ins, so signing in to Google once works everywhere. If you want a second account in one app, turn on Separate login in that app's settings.
- **Fewer pop-ups**: Windows a site opens on its own are held back with a notice, so you choose whether to open them. Sign-in windows still open straight away.
- **Sites treat Scraffold like Chrome**: Fewer sites refuse to work or ask you to switch browsers.
- **A black background to start**: The Cosmic background is now a style you turn on in the theme settings.
- **A tidier home screen**: Your apps scroll under a frosted top bar, and the Icons, Grid and List tabs scroll away with them.
- **Clearer words**: Settings, About and the other screens now say what things do in plain language.

### Fixed
- The home screen folder no longer shows its apps on top of its title when they don't all fit.
- Settings, app settings and About now scroll all the way to the bottom.
- Sites asking "Are you human?" in desktop mode no longer ask again and again. Desktop mode now names your phone's own processor, which Cloudflare's check compares with what the page sees.
- Pop-up windows, like the one when importing a backup, now match your theme.
- After importing a backup, its theme and Cosmic background show straight away.
- Removing a filter under My filters takes effect when you reload the page, and tapping outside the window no longer throws away what you typed.
- Turning ad blocking on or off now reaches a browser that was already open.
- Security check pages no longer fill up your browser history.

---

## [1.0.3-beta] - 2026-11-02

### Added
- **Edge to edge**: Apps fill the whole screen, around the camera cutout too.
- **Links open where they should**: Links to other apps, like Discord and Telegram, open in those apps.
- **The side ball**: A small ball at the edge of the screen gives you reload, home and ad blocking in one tap. Hold it to switch between open apps, and drag it anywhere.
- **New sheets for adding apps, themes and default settings.**
- **Home screen folder**: A widget with three of your apps up front and a folder with all of them.
- **Quick Browser**: Browse any site, with tabs and history, and add any page to your shelf.
- **Background play**: Music and videos keep playing after you leave an app, with controls on the lock screen.
- **Ad blocking**: Uses the same public block lists as popular ad blockers, kept up to date by themselves.
- **Sorting**: Sort your apps, or drag them into your own order.
- **Page and text size**: Set for each app, or for the browser.

### Changed
- Each app opens where you left it, and shows up on its own in Recent apps.
- About shows that this is a beta, and the update check finds beta versions.
- Scraffold is now free for personal, noncommercial use.

### Fixed
- Apps left open when the phone restarted could come back as the wrong site.

---

## [1.0.2] - 2026-11-02

### Added
- **A fuller script editor**, with menus, line numbers and a settings tab for each userscript.
- **More in About**: What's inside Scraffold, and the licenses of what it's built on.
- **More settings**: Default settings for new apps, and switches for gestures, the loading bar and clearing temporary files.
- **Home screen polish**: A clearer tab underline, a theme button, and a bar showing how much was blocked.

---

## [1.0.1] - 2026-11-02

### Added
- The status bar no longer covers the top of any screen.
- **Userscripts**: A screen to add, turn on and off, and manage userscripts.
- **New script template** to start a userscript from.
- **Ad blocking panel** in Settings, with how much was blocked.
- **App options**: Hold an app, or tap its menu, to open it, go fullscreen, manage its scripts or remove it.
- **Empty shelf message** that tells you how to add your first app.
- **Icons, Grid and List views**, with a colorful placeholder icon for each app.
- A small notice when an app runs your userscripts.

### Changed
- New looks for the Icons, Grid and List views.
- More settings, including gestures, storage, and backing up and restoring your apps.
- A neater + button.

### Removed
- Scraffold no longer adds sample apps. Your shelf starts empty.

---

## [1.0.0] - 2026-11-02

### First release
- Turn any website into an app on your phone.
- Your apps, scripts and settings are saved on the phone.
- Built-in ad blocking.
- Userscripts that run as a page loads.
- Swipe with four fingers to reload, and temporary files clear themselves at 500 MB.
- A dark look.
