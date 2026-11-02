package net.mangadot.scaffold.ui.container

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
import net.mangadot.scaffold.databinding.ActivityWebContainerBinding
import net.mangadot.scaffold.engine.*
import net.mangadot.scaffold.engine.gesture.FourFingerReloadGesture

class WebContainerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebContainerBinding
    private lateinit var webAppRepository: WebAppRepository
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var webViewClient: DeckWebViewClient
    private lateinit var webChromeClient: DeckWebChromeClient

    private var currentApp: WebAppEntity? = null
    private var isQuickMenuOpen = false

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

        val db = AppDatabase.getInstance(this)
        webAppRepository = WebAppRepository(db.webAppDao())
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        adBlockEngine = AdBlockEngine.getInstance(this)
        scriptEngine = ScriptEngine(scriptRepository, lifecycleScope)

        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        val appUrl = intent.getStringExtra(EXTRA_APP_URL) ?: "https://mangadot.net"

        setupWebView(appId, appUrl)
        setupAssistiveEdgeBall()
        setupBackNavigation()
        loadAppData(appId, appUrl)
    }

    private fun setupWebView(appId: String, initialUrl: String) {
        val dummyApp = WebAppEntity(id = appId, name = "", url = initialUrl)

        webViewClient = DeckWebViewClient(
            context = this,
            adBlockEngine = adBlockEngine,
            scriptEngine = scriptEngine,
            webApp = dummyApp
        )

        webChromeClient = DeckWebChromeClient(
            webApp = dummyApp,
            onProgressUpdate = { progress ->
                if (progress < 100) {
                    binding.containerProgressBar.visibility = View.VISIBLE
                    binding.containerProgressBar.progress = progress
                } else {
                    binding.containerProgressBar.visibility = View.GONE
                }
            },
            onExternalLinkRequested = { uri ->
                openExternalUrl(uri)
            }
        )

        binding.deckWebview.webViewClient = webViewClient
        binding.deckWebview.webChromeClient = webChromeClient

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

        ball.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dY = view.y - event.rawY
                    startY = event.rawY
                    isDragging = false
                    ball.animate().alpha(1f).translationX(0f).setDuration(150).start()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawY - startY) > dpToPx(6)) {
                        isDragging = true
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
                    if (!isDragging) {
                        view.performClick()
                    } else {
                        // Re-dock half into screen edge
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
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAppData(appId: String, initialUrl: String) {
        lifecycleScope.launch {
            val app = webAppRepository.getWebAppByUrl(initialUrl) ?: WebAppEntity(id = appId, name = "", url = initialUrl)
            currentApp = app
            binding.deckWebview.applyWebAppConfig(app)
            webViewClient.updateWebApp(app)
            webChromeClient.updateWebApp(app)

            // Configure True Fullscreen
            configureFullscreen(app.fullScreenMode)

            // Load scripts and attach pre-DOM engine
            val scripts = scriptRepository.getEnabledScriptsList()
            webViewClient.updateActiveScripts(scripts)

            val host = Uri.parse(initialUrl).host ?: ""
            val cosmeticCss = adBlockEngine.getCosmeticCss(host)
            val scriptlets = adBlockEngine.getScriptletDefusers(host)

            scriptEngine.attachPreDomScripts(
                webView = binding.deckWebview,
                appId = app.id,
                currentUrl = initialUrl,
                activeScripts = scripts,
                cosmeticCss = cosmeticCss,
                scriptlets = scriptlets
            )

            // Show HUD pill if matching userscripts are active
            val matching = scripts.filter { it.enabled && (it.matchPattern == "https://*/*" || it.matchPattern.contains(host)) }
            if (matching.isNotEmpty()) {
                showScriptHud(matching.size)
            }

            // Start navigation
            binding.deckWebview.loadUrl(initialUrl)
        }
    }

    private fun configureFullscreen(enabled: Boolean) {
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

    override fun onDestroy() {
        binding.deckWebview.destroy()
        super.onDestroy()
    }
}
