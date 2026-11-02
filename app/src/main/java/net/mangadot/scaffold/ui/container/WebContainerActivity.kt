package net.mangadot.scaffold.ui.container

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.GeolocationPermissions
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.data.settings.effectiveSettings
import net.mangadot.scaffold.databinding.ActivityWebContainerBinding
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.engine.*
import net.mangadot.scaffold.engine.gesture.FourFingerReloadGesture
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.userscript.UserscriptMeta
import net.mangadot.scaffold.ui.browser.ExternalLinks
import net.mangadot.scaffold.ui.settings.SettingsActivity
import net.mangadot.scaffold.ui.userscript.UserscriptInstallPrompt
import net.mangadot.scaffold.ui.userscript.UserscriptsActivity
import net.mangadot.scaffold.ui.theme.showCosmic

class WebContainerActivity : CosmicActivity() {

    private lateinit var binding: ActivityWebContainerBinding
    private lateinit var webAppRepository: WebAppRepository
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var webViewClient: DeckWebViewClient
    private lateinit var webChromeClient: DeckWebChromeClient

    private var currentApp: WebAppEntity? = null
    private var isQuickMenuOpen = false
    private var isFullscreenEnabled = false
    private var showProgressBar = true
    private var userscriptsOn = true
    private var clearCacheOnExit = false
    private var pendingLocation: Pair<String, GeolocationPermissions.Callback>? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        pendingLocation?.let { (origin, callback) -> callback.invoke(origin, results.values.any { it }, false) }
        pendingLocation = null
    }

    companion object {
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
        }

        val db = AppDatabase.getInstance(this)
        webAppRepository = WebAppRepository(db.webAppDao())
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        adBlockEngine = AdBlockEngine.getInstance(this)
        scriptEngine = ScriptEngine(this, scriptRepository, lifecycleScope)

        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        val appUrl = intent.getStringExtra(EXTRA_APP_URL) ?: "https://mangadot.net"

        setupWindowInsets()
        setupWebView(appId, appUrl)
        setupAssistiveEdgeBall()
        setupBackNavigation()
        loadAppData(appId, appUrl)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.containerRootLayout) { view, insets ->
            val bars = if (isFullscreenEnabled) {
                Insets.NONE
            } else {
                insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            }
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, imeBottom))
            insets
        }
    }

    private fun setupWebView(appId: String, initialUrl: String) {
        val dummyApp = WebAppEntity(id = appId, name = "", url = initialUrl)

        webViewClient = DeckWebViewClient(
            context = this,
            adBlockEngine = adBlockEngine,
            scriptEngine = scriptEngine,
            webApp = dummyApp,
            onUserscriptLink = { url -> UserscriptInstallPrompt.fromUrl(this, scriptRepository, url) }
        )

        webChromeClient = DeckWebChromeClient(
            webApp = dummyApp,
            onProgressUpdate = { progress ->
                if (progress < 100 && showProgressBar) {
                    binding.containerProgressBar.visibility = View.VISIBLE
                    binding.containerProgressBar.progress = progress
                } else {
                    binding.containerProgressBar.visibility = View.GONE
                }
            },
            onExternalLinkRequested = { uri ->
                if (webViewClient.staysInApp(uri)) binding.deckWebview.loadUrl(uri.toString()) else openExternalUrl(uri)
            },
            onLocationRequested = { origin, callback -> requestLocation(origin, callback) },
            onFaviconReceived = { icon -> storeFavicon(icon) }
        )

        binding.deckWebview.webViewClient = webViewClient
        binding.deckWebview.webChromeClient = webChromeClient
        scriptEngine.attach(binding.deckWebview)

        // 4-Finger Swipe-Down Gesture for Reload
        binding.deckWebview.setOnTouchListener(
            FourFingerReloadGesture {
                binding.deckWebview.reload()
            }
        )
    }

    private fun setupAssistiveEdgeBall() {
        val ball = binding.edgeDockBall
        val menu = binding.edgeQuickMenu

        // Initially hide 50% into the left edge
        ball.post {
            ball.translationX = -dpToPx(18).toFloat()
            ball.alpha = 0.5f
        }

        var dY = 0f
        var startY = 0f
        var isDragging = false
        var longPressFired = false

        // The listener consumes the whole gesture, so the framework's own long-press timer never runs
        val longPress = Runnable {
            longPressFired = true
            ball.performLongClick()
        }

        ball.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dY = view.y - event.rawY
                    startY = event.rawY
                    isDragging = false
                    longPressFired = false
                    ball.animate().alpha(1f).translationX(0f).setDuration(150).start()
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longPressFired && Math.abs(event.rawY - startY) > dpToPx(6)) {
                        isDragging = true
                        view.removeCallbacks(longPress)
                    }
                    if (isDragging) {
                        val newY = (event.rawY + dY).coerceIn(
                            dpToPx(20).toFloat(),
                            (binding.containerRootLayout.height - view.height - dpToPx(20)).toFloat()
                        )
                        view.y = newY
                        menu.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (isDragging) {
                        // Re-dock half into screen edge
                        if (!isQuickMenuOpen) {
                            ball.animate().translationX(-dpToPx(18).toFloat()).alpha(0.5f).setDuration(250).start()
                        }
                    } else if (!longPressFired) {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    if (!isQuickMenuOpen) {
                        ball.animate().translationX(-dpToPx(18).toFloat()).alpha(0.5f).setDuration(250).start()
                    }
                    true
                }
                else -> false
            }
        }

        // Tap to toggle quick menu
        ball.setOnClickListener {
            toggleQuickMenu()
        }

        // Press & Hold (Long Press) to open App Switcher
        ball.setOnLongClickListener {
            ball.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openAppSwitcher()
            true
        }

        // Quick Menu Actions
        binding.btnBallReload.setOnClickListener {
            binding.deckWebview.reload()
            hideQuickMenu()
        }

        binding.btnBallHome.setOnClickListener {
            finish()
        }

        binding.btnBallShield.setOnClickListener {
            Toast.makeText(this, "Shield: ${adBlockEngine.totalBlockedRequests} requests blocked", Toast.LENGTH_SHORT).show()
            hideQuickMenu()
        }

        binding.btnBallScripts.setOnClickListener {
            hideQuickMenu()
            showScriptMenu()
        }

        binding.btnBallClose.setOnClickListener {
            hideQuickMenu()
        }
    }

    private fun toggleQuickMenu() {
        if (isQuickMenuOpen) {
            hideQuickMenu()
        } else {
            showQuickMenu()
        }
    }

    private fun showQuickMenu() {
        isQuickMenuOpen = true
        binding.edgeDockBall.animate().translationX(0f).alpha(1f).setDuration(150).start()
        binding.edgeQuickMenu.visibility = View.VISIBLE
        binding.edgeQuickMenu.alpha = 0f
        binding.edgeQuickMenu.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideQuickMenu() {
        isQuickMenuOpen = false
        binding.edgeQuickMenu.visibility = View.GONE
        binding.edgeDockBall.animate().translationX(-dpToPx(18).toFloat()).alpha(0.5f).setDuration(250).start()
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
                onAppSelected = { app ->
                    loadAppData(app.id, app.url)
                }
            )
            sheet.show(supportFragmentManager, "app_switcher")
        }
    }

    private fun openExternalUrl(uri: Uri) {
        ExternalLinks.open(this, uri)
    }

    /** The running page's scripts and any commands they registered with GM_registerMenuCommand. */
    private fun showScriptMenu() {
        val url = binding.deckWebview.url.orEmpty()
        lifecycleScope.launch {
            val running = scriptRepository.getEnabledScriptsList()
                .filter { userscriptsOn && UserscriptMeta.parse(it.code).appliesTo(url) }
            val commands = scriptEngine.bridge.menuCommands.toList()
            val labels = running.map { "● ${it.name}" } + commands.map { "${it.scriptName}: ${it.caption}" } + "Manage userscripts"
            AlertDialog.Builder(this@WebContainerActivity)
                .setTitle(if (running.isEmpty()) "No scripts on this page" else "${running.size} running on this page")
                .setItems(labels.toTypedArray()) { _, which ->
                    val commandIndex = which - running.size
                    when {
                        which < running.size -> openScriptManager()
                        commandIndex < commands.size -> scriptEngine.bridge.runMenuCommand(commands[commandIndex])
                        else -> openScriptManager()
                    }
                }
                .showCosmic()
        }
    }

    private fun openScriptManager() {
        startActivity(Intent(this, UserscriptsActivity::class.java).apply {
            putExtra(UserscriptsActivity.EXTRA_FILTER_DOMAIN, Uri.parse(binding.deckWebview.url.orEmpty()).host.orEmpty())
        })
    }

    /** Sites that refuse the shelf's own icon fetch still hand their favicon to the page they load. */
    private fun storeFavicon(icon: Bitmap) {
        val app = currentApp?.takeIf { it.iconUrl == null && it.name.isNotEmpty() } ?: return
        currentApp = app.copy(iconUrl = "")
        lifecycleScope.launch {
            webAppRepository.updateIconUrl(app.id, AppIcons.save(this@WebContainerActivity, app.id, icon))
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
            val settings = app.effectiveSettings(this@WebContainerActivity)
            binding.deckWebview.applySettings(settings)
            webViewClient.updateWebApp(app, settings)
            webChromeClient.updateWebApp(app, settings)

            // Configure True Fullscreen
            configureFullscreen(settings.fullscreen)

            val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
            showProgressBar = prefs.getBoolean(SettingsActivity.KEY_PROGRESS_BAR, true)
            clearCacheOnExit = prefs.getBoolean(SettingsActivity.KEY_CLEAR_CACHE_EXIT, true)

            // Load scripts and attach pre-DOM engine
            userscriptsOn = app.userscriptsEnabled
            val scripts = if (userscriptsOn) scriptRepository.getEnabledScriptsList() else emptyList()
            val host = Uri.parse(initialUrl).host ?: ""
            scriptEngine.register(
                webView = binding.deckWebview,
                scripts = scripts,
                cosmeticCss = if (settings.adblock) adBlockEngine.getCosmeticCss(host) else "",
                scriptlets = if (settings.adblock) adBlockEngine.getScriptletDefusers(host) else ""
            )

            // Show HUD pill if matching userscripts are active
            val matching = scripts.count { UserscriptMeta.parse(it.code).appliesTo(initialUrl) }
            if (matching > 0) {
                showScriptHud(matching)
            }

            // Start navigation
            val keepHttp = settings.allowHttp || app.url.startsWith("http://", ignoreCase = true)
            val startUrl = if (keepHttp) initialUrl else initialUrl.replaceFirst("http://", "https://")
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
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
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
                if (isQuickMenuOpen) {
                    hideQuickMenu()
                } else if (binding.deckWebview.canGoBack()) {
                    binding.deckWebview.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onPause() {
        adBlockEngine.flushStats()
        super.onPause()
    }

    override fun onDestroy() {
        if (clearCacheOnExit) binding.deckWebview.clearCache(true)
        binding.deckWebview.destroy()
        super.onDestroy()
    }
}
