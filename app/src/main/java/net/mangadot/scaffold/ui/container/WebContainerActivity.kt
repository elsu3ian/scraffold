package net.mangadot.scaffold.ui.container

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.app.ActivityManager
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mangadot.scaffold.data.settings.WebAppSettings
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.data.settings.effectiveSettings
import net.mangadot.scaffold.data.settings.withSettings
import net.mangadot.scaffold.databinding.ActivityWebContainerBinding
import net.mangadot.scaffold.engine.*
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.gesture.WebGestures
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import net.mangadot.scaffold.ui.browser.ExternalLinks
import net.mangadot.scaffold.ui.settings.SettingsActivity
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicTheme
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.showCosmic
import net.mangadot.scaffold.ui.userscript.UserscriptInstallPrompt

class WebContainerActivity : CosmicActivity() {

    private lateinit var binding: ActivityWebContainerBinding
    private lateinit var webAppRepository: WebAppRepository
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var webViewClient: DeckWebViewClient
    private lateinit var webChromeClient: DeckWebChromeClient

    private var currentApp: WebAppEntity? = null
    private lateinit var assistiveBall: AssistiveBall
    private lateinit var appLook: AppLook
    private var iconLookup: Job? = null
    private var iconLookups = 0
    private var manifestReads = 0
    private var isFullscreenEnabled = false
    private var showProgressBar = true
    private var userscriptsOn = true
    private var clearCacheOnExit = false
    private var pendingLocation: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingMedia: Pair<PermissionRequest, List<String>>? = null
    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var fullscreenVideo: View? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var softwareNav = false
    private lateinit var cookies: CookieManager
    private lateinit var pageZoom: PageZoom
    private var restoredState: Bundle? = null
    private var rendererGone = false
    private var appliedTheme: CosmicTheme? = null
    private var appliedSettings: WebAppSettings? = null
    private var appliedScripts: List<Pair<String, Long>> = emptyList()

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        pendingLocation?.let { (origin, callback) -> callback.invoke(origin, results.values.any { it }, false) }
        pendingLocation = null
    }

    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingMedia?.let { (request, allowed) -> grantHeld(request, allowed) }
        pendingMedia = null
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFiles = null
    }

    companion object {
        private const val MAX_ICON_LOOKUPS = 6
        private const val RECENTS_ICON_PX = 96
        const val EXTRA_NAVIGATE_URL = "extra_navigate_url"
        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_APP_URL = "extra_app_url"
        const val EXTRA_APP_NAME = "extra_app_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // True Edge-to-Edge Display Cutout Fullscreen (zero letterbox notch padding)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        binding = ActivityWebContainerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.containerRootLayout, dimmed = theme.dimInReader)
            appliedTheme = theme
        }
        restoredState = savedInstanceState

        val db = AppDatabase.getInstance(this)
        webAppRepository = WebAppRepository(db.webAppDao())
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        adBlockEngine = AdBlockEngine.getInstance(this)
        scriptEngine = ScriptEngine(this, scriptRepository, lifecycleScope)

        val appId = AppTasks.appId(intent)
        val appUrl = intent.getStringExtra(EXTRA_APP_URL)
            ?: intent.getStringExtra(EXTRA_NAVIGATE_URL)
            ?: "https://mangadot.net"

        // A WebView's profile can only be chosen before it does anything, so each app gets its own activity
        AppProfiles.attach(binding.deckWebview, appId)
        BackgroundMedia.attach(binding.deckWebview, intent.getStringExtra(EXTRA_APP_NAME).orEmpty(), AppTasks.intent(this, appId))
        binding.deckWebview.presentAsBrowser()
        cookies = AppProfiles.cookies(this, appId)
        pageZoom = PageZoom(binding.deckWebview)
        appLook = AppLook(this)

        setupWindowInsets()
        setupWebView(appId, appUrl)
        setupAssistiveEdgeBall()
        setupBackNavigation()
        loadAppData(appId, appUrl)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.containerRootLayout) { view, insets ->
            val bars = if (isFullscreenEnabled) {
                if (softwareNav) Insets.of(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom) else Insets.NONE
            } else {
                insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            }
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            appLook.onStatusBarHeight(bars.top)
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, imeBottom))
            // Handled here, so the page must not also see them as safe-area-inset-* and pad itself
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupWebView(appId: String, initialUrl: String) {
        val dummyApp = WebAppEntity(id = appId, name = "", url = initialUrl)

        webViewClient = DeckWebViewClient(
            context = this,
            adBlockEngine = adBlockEngine,
            scriptEngine = scriptEngine,
            webApp = dummyApp,
            onPageFinishedCallback = {
                // Keeps a fresh login safe even if the app is killed before it next pauses
                cookies.flush()
                rememberPage()
                upgradeIcon()
                appLook.hideSplash()
                readManifestFromPage()
            },
            onUserscriptLink = { url -> UserscriptInstallPrompt.fromUrl(this, scriptRepository, url) },
            onRendererGone = {
                Toast.makeText(this, "The page stopped working, reloading it", Toast.LENGTH_SHORT).show()
                rendererGone = true
                recreate()
            }
        )

        webChromeClient = DeckWebChromeClient(
            webApp = dummyApp,
            onProgressUpdate = { progress ->
                appLook.onProgress(progress)
                binding.containerPull.onProgress(progress)
                if (progress < 100 && showProgressBar) {
                    binding.containerProgressBar.visibility = View.VISIBLE
                    binding.containerProgressBar.progress = progress
                } else {
                    binding.containerProgressBar.visibility = View.GONE
                }
            },
            onExternalLinkRequested = { uri -> openWindowLink(uri) },
            onLocationRequested = { origin, callback -> requestLocation(origin, callback) },
            onFaviconReceived = { icon -> storeFavicon(icon) },
            onMediaRequested = { request, allowed -> requestMedia(request, allowed) },
            onFileChooser = { callback, params -> chooseFiles(callback, params) },
            onShowFullscreenVideo = { view, callback -> showFullscreenVideo(view, callback) },
            onHideFullscreenVideo = { hideFullscreenVideo() },
            onPopupHeld = { uri -> offerPopup(uri) }
        )

        binding.deckWebview.webViewClient = webViewClient
        binding.deckWebview.webChromeClient = webChromeClient
        scriptEngine.attach(binding.deckWebview, appId)
        binding.deckWebview.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            WebDownloads.start(this, url, cookies, null, contentDisposition, mimeType, userAgent)
        }

        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        binding.deckWebview.setOnTouchListener(
            WebGestures(
                context = this,
                onTwoFingerDown = if (prefs.getBoolean(SettingsActivity.KEY_GESTURE_RELOAD, true)) {
                    { binding.deckWebview.reload() }
                } else null,
                onTwoFingerHorizontal = if (prefs.getBoolean(SettingsActivity.KEY_GESTURE_NAV, true)) {
                    { forward -> navigateHistory(forward) }
                } else null,
                onThreeFingerHorizontal = if (prefs.getBoolean(SettingsActivity.KEY_GESTURE_APP_SWITCH, false)) {
                    { next -> switchToNeighbour(next) }
                } else null
            )
        )
        binding.containerPull.page = { binding.deckWebview }
    }

    private fun setupAssistiveEdgeBall() {
        assistiveBall = AssistiveBall(
            ball = binding.edgeDockBall,
            icon = binding.ivBallIcon,
            menu = binding.edgeQuickMenu,
            buttons = listOf(
                binding.btnBallReload,
                binding.btnBallHome,
                binding.btnBallShield,
                binding.btnBallScripts,
                binding.btnBallTextSize,
                binding.btnBallDesktop,
                binding.btnBallCopyLink
            ),
            onOpen = {
                val currentDesktop = currentApp?.effectiveSettings(this)?.desktop == true
                binding.btnBallDesktop.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (currentDesktop) R.color.accent_emerald else R.color.text_primary)
                )
            },
            onLongPress = { openAppSwitcher() }
        )

        // Quick Menu Actions
        binding.btnBallReload.setOnClickListener {
            binding.deckWebview.reload()
            hideQuickMenu()
        }

        binding.btnBallHome.setOnClickListener {
            hideQuickMenu()
            goToShelf()
        }

        binding.btnBallShield.setOnClickListener {
            hideQuickMenu()
            ShieldMeter.show(this, binding.deckWebview, appliedSettings?.adblock ?: true)
        }
        binding.btnBallShield.setOnLongClickListener {
            hideQuickMenu()
            ElementPicker.start(this, binding.deckWebview)
            true
        }

        binding.btnBallScripts.setOnClickListener {
            hideQuickMenu()
            showScriptMenu()
        }

        binding.btnBallTextSize.setOnClickListener {
            hideQuickMenu()
            showTextSizeDialog()
        }

        binding.btnBallDesktop.setOnClickListener {
            hideQuickMenu()
            toggleDesktopSite()
        }

        binding.btnBallCopyLink.setOnClickListener {
            hideQuickMenu()
            copyCurrentLink()
        }

    }

    private fun hideQuickMenu() = assistiveBall.close()

    /** Leaves this app running behind the shelf, so opening it again continues where it was. */
    private fun goToShelf() = AppTasks.goToShelf(this)

    /** Where to continue if Android ends this app before it is reopened. */
    private fun rememberPage() {
        val app = currentApp ?: return
        if (isFinishing) return
        val url = binding.deckWebview.url ?: return
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return
        val appHost = Uri.parse(app.url).host?.lowercase()?.removePrefix("www.") ?: return
        // A sign-in or security check elsewhere isn't a page to come back to
        if (host != appHost && !host.endsWith(".$appHost")) return
        if (url.contains("__cf_chl")) return
        AppTasks.saveLastPage(this, app.id, url)
    }

    /** Settings can change on the shelf while this app waits behind it. */
    private fun refreshIfChanged() {
        val shown = currentApp ?: return
        lifecycleScope.launch {
            val app = webAppRepository.getWebAppById(shown.id).first()
            if (app == null) {
                if (shown.name.isNotEmpty()) finishAndRemoveTask()
                return@launch
            }
            currentApp = app
            val theme = CosmicThemeStore.load(this@WebContainerActivity)
            val scripts = if (app.userscriptsEnabled) scriptRepository.getEnabledScriptsList() else emptyList()
            // Starting over keeps the page and its history, and applies everything the same way as a first open
            if (app.effectiveSettings(this@WebContainerActivity) != appliedSettings ||
                app.userscriptsEnabled != userscriptsOn ||
                scripts.map { it.id to it.updatedAt } != appliedScripts ||
                theme.accent != appliedTheme?.accent
            ) {
                recreate()
            } else if (theme != appliedTheme) {
                theme.applyBackground(binding.containerRootLayout, dimmed = theme.dimInReader)
                appliedTheme = theme
            }
        }
    }

    private fun showTaskInRecents(app: WebAppEntity) {
        if (app.name.isEmpty()) return
        lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) {
                app.iconUrl?.let(BitmapFactory::decodeFile)?.let { Bitmap.createScaledBitmap(it, RECENTS_ICON_PX, RECENTS_ICON_PX, true) }
            }
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(app.name, icon))
        }
    }

    private fun showTextSizeDialog() {
        PageTools.showSizeDialog(this, currentApp?.id.orEmpty(), binding.deckWebview.settings, pageZoom)
    }

    /** Remembered for this app, like its other Web App Settings. */
    private fun toggleDesktopSite() {
        val app = currentApp ?: return
        lifecycleScope.launch {
            val settings = app.effectiveSettings(this@WebContainerActivity)
            val updatedSettings = settings.copy(desktop = !settings.desktop)
            val updated = app.withSettings(updatedSettings).copy(overrideGlobal = true)
            webAppRepository.updateWebApp(updated)
            currentApp = updated
            appliedSettings = updatedSettings
            binding.deckWebview.applySettings(updatedSettings, cookies)
            binding.deckWebview.reload()
            Toast.makeText(
                this@WebContainerActivity,
                if (updatedSettings.desktop) "Desktop site on" else "Desktop site off",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyCurrentLink() {
        val url = binding.deckWebview.url ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Link", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun openAppSwitcher() {
        hideQuickMenu()
        lifecycleScope.launch {
            val apps = webAppRepository.allWebApps.first()
            if (apps.isEmpty()) {
                Toast.makeText(this@WebContainerActivity, "No other apps on shelf", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sheet = AppSwitcherBottomSheetDialog(
                apps = apps,
                onAppSelected = { app -> switchTo(app) }
            )
            sheet.show(supportFragmentManager, "app_switcher")
        }
    }

    private fun switchTo(app: WebAppEntity) {
        if (app.id == currentApp?.id) return
        AppTasks.open(this, app)
    }

    private fun switchToNeighbour(next: Boolean) {
        lifecycleScope.launch {
            // Launch order reshuffles the shelf, so step through the order apps were added
            val apps = webAppRepository.allWebApps.first().sortedBy { it.createdAt }
            val index = apps.indexOfFirst { it.id == currentApp?.id }
            if (apps.size > 1 && index >= 0) {
                switchTo(apps[(index + (if (next) 1 else -1) + apps.size) % apps.size])
            }
        }
    }

    private fun navigateHistory(forward: Boolean) {
        if (forward && binding.deckWebview.canGoForward()) binding.deckWebview.goForward()
        if (!forward && binding.deckWebview.canGoBack()) binding.deckWebview.goBack()
    }

    /** A sign-in that left for the in-app browser comes back here with where to continue. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_NAVIGATE_URL)?.let { binding.deckWebview.loadUrl(it) }
    }

    private fun openExternalUrl(uri: Uri) {
        ExternalLinks.open(this, uri, webViewClient.linkOwner())
    }

    /** Where a new window from the page goes: this page when it's the app's own, the in-app browser otherwise. */
    private fun openWindowLink(uri: Uri) {
        if (webViewClient.staysInApp(uri)) binding.deckWebview.loadUrl(uri.toString()) else openExternalUrl(uri)
    }

    /** A window the page opened on another site without a link being tapped. It only opens if asked. */
    private fun offerPopup(uri: Uri) {
        Snackbar.make(binding.containerRootLayout, "Blocked a popup to ${uri.host ?: uri.scheme}", Snackbar.LENGTH_LONG)
            .setAction("Open") { openWindowLink(uri) }
            .show()
    }

    private fun requestMedia(request: PermissionRequest, allowed: List<String>) {
        val missing = allowed.mapNotNull(::androidPermissionFor).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            grantHeld(request, allowed)
        } else {
            pendingMedia = request to allowed
            mediaPermission.launch(missing.toTypedArray())
        }
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    /** Grants what Android also allows; DRM needs no Android permission. */
    private fun grantHeld(request: PermissionRequest, allowed: List<String>) {
        val held = allowed.filter { resource ->
            val permission = androidPermissionFor(resource)
            permission == null || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (held.isEmpty()) request.deny() else request.grant(held.toTypedArray())
    }

    private fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        pendingFiles?.onReceiveValue(null)
        pendingFiles = callback
        return try {
            filePicker.launch(params.createIntent())
            true
        } catch (e: Exception) {
            pendingFiles = null
            Toast.makeText(this, "No app can choose files on this device", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun showFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        fullscreenVideo = view
        fullscreenVideoCallback = callback
        (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideFullscreenVideo() {
        fullscreenVideo?.let { (window.decorView as ViewGroup).removeView(it) }
        fullscreenVideo = null
        fullscreenVideoCallback = null
        configureFullscreen(isFullscreenEnabled)
    }

    private fun showScriptMenu() {
        PageTools.showScriptMenu(this, binding.deckWebview.url.orEmpty(), scriptRepository, scriptEngine, userscriptsOn)
    }

    /** Sites that refuse the shelf's own icon fetch still hand their favicon to the page they load. */
    private fun storeFavicon(icon: Bitmap) {
        val app = currentApp?.takeIf { it.iconUrl == null && it.name.isNotEmpty() } ?: return
        currentApp = app.copy(iconUrl = "")
        lifecycleScope.launch {
            webAppRepository.updateIconUrl(app.id, AppIcons.save(this@WebContainerActivity, app.id, icon))
        }
    }

    private fun upgradeIcon() {
        val app = currentApp?.takeIf { it.name.isNotEmpty() } ?: return
        if (iconLookups >= MAX_ICON_LOOKUPS) return
        iconLookups++
        // A newer page replaces the last lookup, which may have been on a security check that navigated away
        iconLookup?.cancel()
        iconLookup = lifecycleScope.launch {
            val icon = AppIcons.betterIconFromPage(this@WebContainerActivity, binding.deckWebview, app)
            if (icon != null) {
                val path = AppIcons.save(this@WebContainerActivity, app.id, icon)
                webAppRepository.updateIconUrl(app.id, path)
                currentApp = currentApp?.copy(iconUrl = path)
            }
        }
    }

    /** For an app whose site adds its manifest with script, so it couldn't be read when the app was added. */
    private fun readManifestFromPage() {
        val app = currentApp?.takeIf { it.name.isNotEmpty() && ManifestInstall.isPending(this, it.id) } ?: return
        if (manifestReads++ >= MAX_ICON_LOOKUPS) return
        lifecycleScope.launch {
            val updated = ManifestInstall.fromPage(this@WebContainerActivity, binding.deckWebview, app) ?: return@launch
            ManifestInstall.clear(this@WebContainerActivity, app.id)
            val latest = webAppRepository.getWebAppById(app.id).first() ?: return@launch
            val merged = latest.copy(
                name = updated.name, startUrl = updated.startUrl, scope = updated.scope, display = updated.display,
                themeColor = updated.themeColor, backgroundColor = updated.backgroundColor
            )
            webAppRepository.updateWebApp(merged)
            currentApp = merged
            appliedSettings?.let { webViewClient.updateWebApp(merged, it) }
            appLook.apply(merged, showSplash = false)
        }
    }

    private fun requestLocation(origin: String, callback: GeolocationPermissions.Callback) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            callback.invoke(origin, true, false)
        } else {
            pendingLocation = origin to callback
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun loadAppData(appId: String, initialUrl: String) {
        lifecycleScope.launch {
            val app = webAppRepository.getWebAppById(appId).first() ?: WebAppEntity(id = appId, name = "", url = initialUrl)
            currentApp = app
            showTaskInRecents(app)
            // Android brought back the page as it was, so there's nothing to wait for behind a splash
            appLook.apply(app, showSplash = restoredState == null)
            val settings = app.effectiveSettings(this@WebContainerActivity)
            appliedSettings = settings
            binding.deckWebview.applySettings(settings, cookies)
            binding.deckWebview.settings.textZoom =
                PageTools.savedTextZoom(this@WebContainerActivity, appId)
            pageZoom.set(PageTools.savedPageZoom(this@WebContainerActivity, appId))
            webViewClient.updateWebApp(app, settings)
            webChromeClient.updateWebApp(app, settings)
            scriptEngine.bridge.linkOwner = webViewClient.linkOwner()

            val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
            softwareNav = prefs.getBoolean(SettingsActivity.KEY_SOFTWARE_NAV, false)

            // Configure True Fullscreen
            configureFullscreen(settings.fullscreen)

            showProgressBar = prefs.getBoolean(SettingsActivity.KEY_PROGRESS_BAR, true)
            clearCacheOnExit = prefs.getBoolean(SettingsActivity.KEY_CLEAR_CACHE_EXIT, false)

            // Load scripts and attach pre-DOM engine
            userscriptsOn = app.userscriptsEnabled
            val scripts = if (userscriptsOn) scriptRepository.getEnabledScriptsList() else emptyList()
            appliedScripts = scripts.map { it.id to it.updatedAt }
            scriptEngine.register(webView = binding.deckWebview, scripts = scripts)

            // Show HUD pill if matching userscripts are active
            val matching = scripts.count { it.id !in scriptEngine.failedScripts && UserscriptMeta.parse(it.code).appliesTo(app.url) }
            if (matching > 0) {
                showScriptHud(matching)
            }

            // Android ended this page while it was in the background: bring back its history too
            if (restoredState?.let(binding.deckWebview::restoreState) != null) return@launch

            // Start navigation
            val keepHttp = settings.allowHttp || app.url.startsWith("http://", ignoreCase = true)
            val target = intent.getStringExtra(EXTRA_NAVIGATE_URL) ?: AppTasks.lastPage(this@WebContainerActivity, appId)
                ?: app.startUrl ?: app.url
            val startUrl = if (keepHttp) target else target.replaceFirst("http://", "https://")
            binding.deckWebview.loadUrl(startUrl)
        }
    }

    private fun configureFullscreen(enabled: Boolean) {
        isFullscreenEnabled = enabled
        ViewCompat.requestApplyInsets(binding.containerRootLayout)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            // The setting keeps Android's back, home and recents buttons on screen even in fullscreen
            if (softwareNav) {
                windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
            } else {
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showScriptHud(count: Int) {
        binding.tvScriptHud.visibility = View.VISIBLE
        binding.tvScriptHud.text = "[Scraffold] $count Userscript(s) Active"

        lifecycleScope.launch {
            delay(2500)
            binding.tvScriptHud.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    binding.tvScriptHud.visibility = View.GONE
                    binding.tvScriptHud.alpha = 1f
                }
                .start()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenVideo != null) {
                    fullscreenVideoCallback?.onCustomViewHidden()
                    hideFullscreenVideo()
                } else if (assistiveBall.isOpen) {
                    hideQuickMenu()
                } else if (binding.deckWebview.canGoBack()) {
                    binding.deckWebview.goBack()
                } else {
                    // Backing all the way out is the one way to leave for good, so the next open starts fresh
                    currentApp?.let { AppTasks.forgetLastPage(this@WebContainerActivity, it.id) }
                    goToShelf()
                    finishAndRemoveTask()
                }
            }
        })
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onPause() {
        adBlockEngine.flushStats()
        // WebView only writes cookies to disk every so often, so a kill could otherwise lose a fresh login
        cookies.flush()
        rememberPage()
        binding.deckWebview.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.deckWebview.onResume()
    }

    override fun onRestart() {
        super.onRestart()
        refreshIfChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!rendererGone) binding.deckWebview.saveState(outState)
    }

    override fun onDestroy() {
        if (clearCacheOnExit) binding.deckWebview.clearCache(true)
        binding.deckWebview.destroy()
        super.onDestroy()
    }
}
