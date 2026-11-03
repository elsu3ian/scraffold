package net.mangadot.scaffold.ui.browser

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.dao.BrowserHistoryDao
import net.mangadot.scaffold.data.db.entity.BrowserHistoryEntity
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.data.settings.GlobalWebSettingsStore
import net.mangadot.scaffold.databinding.ActivityQuickBrowserBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.AppProfiles
import net.mangadot.scaffold.engine.BackgroundMedia
import net.mangadot.scaffold.engine.DeckWebView
import net.mangadot.scaffold.engine.PageZoom
import net.mangadot.scaffold.engine.ScriptEngine
import net.mangadot.scaffold.engine.TappedLink
import net.mangadot.scaffold.engine.WebDownloads
import net.mangadot.scaffold.ui.container.AppSwitcherBottomSheetDialog
import net.mangadot.scaffold.ui.container.AppTasks
import net.mangadot.scaffold.ui.container.AssistiveBall
import net.mangadot.scaffold.ui.container.PageTools
import net.mangadot.scaffold.ui.container.ShieldMeter
import net.mangadot.scaffold.ui.shelf.showAddWebApp
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicTheme
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.showCosmic
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A small browser for quick look-ups. Its logins live in its own profile on this device, apart
 * from every web app, and passwords are left to whichever autofill service the phone uses.
 */
class QuickBrowserActivity : CosmicActivity() {

    private lateinit var binding: ActivityQuickBrowserBinding
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var scripts: ScriptRepository
    private lateinit var cookies: CookieManager
    private lateinit var history: BrowserHistoryDao
    private val historyAdapter = HistoryAdapter(onClick = { open(it.url) })
    private val allHistoryAdapter = HistoryAdapter(
        onClick = { open(it.url) },
        onRemove = { visit -> lifecycleScope.launch { history.delete(visit.url) } }
    )
    private var allHistoryWatch: Job? = null
    private lateinit var assistiveBall: AssistiveBall
    private var appliedTheme: CosmicTheme? = null
    private var adblock = true

    private val tabs = mutableListOf<Tab>()
    private lateinit var current: Tab

    // Read on the network thread, where a WebView itself can't be asked
    private val pageHosts = ConcurrentHashMap<WebView, String>()
    private var rendererGone = false
    private var appliedScripts: List<Pair<String, Long>>? = null
    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private var fullscreenVideo: View? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null

    /**
     * Created without a page until it's first shown, so reopening with many tabs stays quick.
     * [url] is where it stands, kept to what's safe to store: null on the start page, and empty for a
     * window its opener is still filling in.
     */
    private class Tab(var url: String?, var title: String) {
        var web: DeckWebView? = null
        var scriptEngine: ScriptEngine? = null
        var opener: Tab? = null
        var desktop = false
        var pageZoom: PageZoom? = null
        var appliedPageZoom = PageZoom.DEFAULT_PERCENT
        // Set while a new window waits out of sight for its first address
        var heldPopup: TappedLink? = null
        // Script setup still under way, which must not reach a page that has since closed
        var setup: Job? = null
    }

    private val atStart: Boolean
        get() = current.url == null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFiles = null
    }

    private val pageClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (adblock) {
                adBlockEngine.intercept(request, pageHosts[view], (view as? DeckWebView)?.shieldStats)?.let {
                    adBlockEngine.recordBlockedForApp(BROWSER_ID)
                    return it
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val tab = tabFor(view)
            if (tab?.heldPopup != null) {
                if (!admitPopup(tab, request.url)) return true
            } else if (adblock && adBlockEngine.stopsNavigation(view, request, pageHosts[view])) {
                return true
            }
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return false
            ExternalLinks.openNative(this@QuickBrowserActivity, request.url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            // Where the opener sets a window's address in script, this is the first it's heard of
            val tab = tabFor(view)
            if (tab?.heldPopup != null && isWebPage(url) && !admitPopup(tab, Uri.parse(url))) {
                view.stopLoading()
                return
            }
            if (view === current.web) showAddress(url)
            Uri.parse(url).host?.let { pageHosts[view] = it.lowercase() }
            (view as? DeckWebView)?.shieldStats?.enter(Uri.parse(url).host)
        }

        override fun onPageFinished(view: WebView, url: String) {
            cookies.flush()
            tabFor(view)?.scriptEngine?.onPageFinished(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (view === current.web) showAddress(url)
            if (!isWebPage(url) || isSignIn(url)) return
            tabFor(view)?.url = url
            saveTabs()
            if (isReload) return
            lifecycleScope.launch {
                history.insert(BrowserHistoryEntity(url, view.title.orEmpty()))
                history.keepRecent(HISTORY_LIMIT)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Tabs share one renderer, so they have all gone with it
            rendererGone = true
            recreate()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityQuickBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.browserRoot)
            appliedTheme = theme
        }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription("Browser"))

        val db = AppDatabase.getInstance(this)
        history = db.browserHistoryDao()
        adBlockEngine = AdBlockEngine.getInstance(this)
        adblock = GlobalWebSettingsStore.load(this).adblock
        scripts = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        cookies = AppProfiles.cookies(this, BROWSER_ID)

        ViewCompat.setOnApplyWindowInsetsListener(binding.browserRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupControls()
        setupBall()
        observeHistory()
        setupBackNavigation()

        loadTabs()
        select(current, savedInstanceState?.getBundle(KEY_PAGE))
    }

    private fun tabFor(view: WebView) = tabs.firstOrNull { it.web === view }

    /** The page for [tab], set up the first time it's needed. Scripts apply to what it loads next. */
    private fun webFor(tab: Tab, state: Bundle? = null): DeckWebView {
        tab.web?.let { return it }
        val web = DeckWebView(this)
        AppProfiles.attach(web, BROWSER_ID)
        BackgroundMedia.attach(web, "Browser", AppTasks.browserIntent(this))
        web.presentAsBrowser()
        web.shield?.enabled = adblock
        configure(web)
        tab.pageZoom = PageZoom(web)
        if (tab.desktop) web.desktopMode = true
        web.webChromeClient = PageChromeClient()
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            WebDownloads.start(this, url, cookies, null, contentDisposition, mimeType, userAgent)
        }
        web.setOnLongClickListener { showLinkMenu(web) }
        binding.browserPages.addView(web, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tab.web = web
        val engine = ScriptEngine(this, scripts, lifecycleScope)
        tab.scriptEngine = engine
        val start = tab.url
        tab.setup = lifecycleScope.launch {
            engine.attach(web, BROWSER_ID)
            val enabled = scripts.getEnabledScriptsList()
            appliedScripts = enabled.map { it.id to it.updatedAt }
            engine.register(web, enabled)
            if (tab.web !== web) return@launch
            if (state?.let(web::restoreState) != null && isWebPage(web.url.orEmpty())) return@launch
            if (!start.isNullOrEmpty() && web.url == null) web.loadUrl(start)
        }
        return web
    }

    private fun select(tab: Tab, state: Bundle? = null) {
        hideHistory()
        current = tab
        tab.heldPopup = null
        tabs.forEach { other ->
            if (other !== tab) other.web?.let {
                it.visibility = View.GONE
                it.onPause()
            }
        }
        val web = webFor(tab, state)
        applySizes(tab, web)
        web.onResume()
        web.visibility = if (atStart) View.INVISIBLE else View.VISIBLE
        binding.browserStart.visibility = if (atStart) View.VISIBLE else View.GONE
        binding.browserProgress.visibility = View.INVISIBLE
        binding.browserEtAddress.clearFocus()
        binding.browserEtAddress.setText(tab.url.orEmpty())
        if (!atStart) web.requestFocus()
        showTabCount()
        saveTabs()
    }

    /** Page and font size apply to the whole browser, so a tab catches up when it's shown. */
    private fun applySizes(tab: Tab, web: DeckWebView) {
        web.settings.textZoom = PageTools.savedTextZoom(this, BROWSER_ID)
        val zoom = PageTools.savedPageZoom(this, BROWSER_ID)
        if (zoom != tab.appliedPageZoom) {
            tab.appliedPageZoom = zoom
            tab.pageZoom?.set(zoom)
        }
    }

    private fun showTabCount() {
        binding.browserBtnTabs.text = tabs.size.toString()
    }

    private fun newTab(url: String? = null, opener: Tab? = null): Tab {
        val tab = Tab(url, "")
        tab.opener = opener
        tabs.add(if (opener != null) tabs.indexOf(opener) + 1 else tabs.size, tab)
        return tab
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        tabs.forEach { if (it.opener === tab) it.opener = null }
        tab.setup?.cancel()
        tab.web?.let { web ->
            pageHosts.remove(web)
            binding.browserPages.removeView(web)
            web.destroy()
        }
        tab.web = null
        if (tabs.isEmpty()) newTab()
        if (tab === current) {
            select(tab.opener?.takeIf { it in tabs } ?: tabs[index.coerceAtMost(tabs.size - 1)])
        } else {
            showTabCount()
            saveTabs()
        }
    }

    /** What's reopened after the app is ended, which a page's own back history doesn't survive for background tabs. */
    private fun saveTabs() {
        val list = JSONArray()
        tabs.forEach { list.put(JSONObject().put("url", it.url ?: JSONObject.NULL).put("title", it.title).put("desktop", it.desktop)) }
        getSharedPreferences(TABS_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_TABS, list.toString())
            .putInt(KEY_SELECTED, tabs.indexOf(current))
            .apply()
    }

    private fun loadTabs() {
        val prefs = getSharedPreferences(TABS_PREFS, MODE_PRIVATE)
        val saved = runCatching { JSONArray(prefs.getString(KEY_TABS, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until saved.length()) {
            val entry = saved.getJSONObject(i)
            tabs += Tab(entry.optString("url").takeIf { !entry.isNull("url") && isWebPage(it) }, entry.optString("title")).apply {
                desktop = entry.optBoolean("desktop")
            }
        }
        if (tabs.isEmpty()) tabs += Tab(null, "")
        current = tabs[prefs.getInt(KEY_SELECTED, 0).coerceIn(0, tabs.size - 1)]
    }

    private fun showTabs() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tabs")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Close", null)
            .setPositiveButton("New tab") { _, _ -> openNewTab() }
            .showCosmic()
        tabs.forEach { tab ->
            val row = layoutInflater.inflate(R.layout.item_browser_tab, list, false)
            val title = row.findViewById<TextView>(R.id.tab_tv_title)
            title.text = tab.title.ifEmpty { tab.url?.let { Uri.parse(it).host } ?: "New tab" }
            if (tab === current) title.setTextColor(CosmicAccent.color)
            row.findViewById<TextView>(R.id.tab_tv_url).text = tab.url ?: "Start page"
            row.setOnClickListener {
                dialog.dismiss()
                select(tab)
            }
            row.findViewById<ImageButton>(R.id.tab_btn_close).setOnClickListener {
                list.removeView(row)
                closeTab(tab)
                if (list.childCount == 0) dialog.dismiss()
            }
            list.addView(row)
        }
    }

    private fun openNewTab() {
        select(newTab())
        binding.browserEtAddress.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(binding.browserEtAddress, 0)
    }

    private fun showLinkMenu(web: WebView): Boolean {
        val hit = web.hitTestResult
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> hit.extra?.let(::showLinkOptions)
            // The hit only names the image here, so the link has to be asked for
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> web.requestFocusNodeHref(
                Handler(Looper.getMainLooper()) { message ->
                    message.data.getString("url")?.let(::showLinkOptions)
                    true
                }.obtainMessage()
            )
            else -> return false
        }
        return true
    }

    private fun showLinkOptions(url: String) {
        if (!isWebPage(url)) return
        AlertDialog.Builder(this)
            .setTitle(url)
            .setItems(arrayOf("Open in new tab", "Copy link")) { _, which ->
                if (which == 0) {
                    val tab = newTab(url, current)
                    webFor(tab).visibility = View.GONE
                    tab.web?.onPause()
                    showTabCount()
                    saveTabs()
                    Toast.makeText(this, "Opened in a new tab", Toast.LENGTH_SHORT).show()
                } else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", url))
                }
            }
            .showCosmic()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // Sign-in windows are real popups, which need to talk back to the page that opened them
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        web.webViewClient = pageClient
        // Signing in through another site relies on these
        cookies.setAcceptThirdPartyCookies(web, true)
    }

    private fun setupControls() {
        binding.browserBtnClose.setOnClickListener {
            hideHistory()
            showStart()
        }
        binding.browserBtnTabs.setOnClickListener { showTabs() }
        binding.browserBtnReload.setOnClickListener { if (!atStart) current.web?.reload() }
        binding.browserBtnMenu.setOnClickListener { showMenu() }

        binding.browserEtAddress.setOnEditorActionListener { field, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) open(addressFor(text))
                true
            } else {
                false
            }
        }

        binding.browserRvHistory.layoutManager = LinearLayoutManager(this)
        binding.browserRvHistory.adapter = historyAdapter
        binding.browserRvAllHistory.layoutManager = LinearLayoutManager(this)
        binding.browserRvAllHistory.adapter = allHistoryAdapter
        binding.browserBtnClearHistory.setOnClickListener { confirmClearHistory() }
    }

    private fun setupBall() {
        assistiveBall = AssistiveBall(
            ball = binding.browserBall,
            icon = binding.browserBallIcon,
            menu = binding.browserBallMenu,
            buttons = listOf(
                binding.browserBallReload,
                binding.browserBallHome,
                binding.browserBallShield,
                binding.browserBallScripts,
                binding.browserBallTextSize,
                binding.browserBallDesktop,
                binding.browserBallHistory,
                binding.browserBallCopyLink
            ),
            onOpen = {
                binding.browserBallDesktop.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (current.desktop) R.color.accent_emerald else R.color.text_primary)
                )
            },
            onLongPress = { openAppSwitcher() }
        )
        binding.browserBallHome.setOnClickListener {
            assistiveBall.close()
            AppTasks.goToShelf(this)
        }
        binding.browserBallReload.setOnClickListener {
            assistiveBall.close()
            if (!atStart) current.web?.reload()
        }
        binding.browserBallShield.setOnClickListener {
            assistiveBall.close()
            ShieldMeter.show(this, webFor(current).shieldStats, adblock)
        }
        binding.browserBallScripts.setOnClickListener {
            assistiveBall.close()
            current.scriptEngine?.let { PageTools.showScriptMenu(this, current.url.orEmpty(), scripts, it) }
        }
        binding.browserBallTextSize.setOnClickListener {
            assistiveBall.close()
            val web = webFor(current)
            current.pageZoom?.let { PageTools.showSizeDialog(this, BROWSER_ID, web.settings, it) }
            current.appliedPageZoom = -1
        }
        binding.browserBallDesktop.setOnClickListener {
            assistiveBall.close()
            toggleDesktopSite()
        }
        binding.browserBallHistory.setOnClickListener {
            assistiveBall.close()
            showHistory()
        }
        binding.browserBallCopyLink.setOnClickListener {
            assistiveBall.close()
            val url = current.url
            if (url.isNullOrEmpty()) {
                Toast.makeText(this, "No page open", Toast.LENGTH_SHORT).show()
            } else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", url))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** For the tab on screen, the way browsers usually do it. */
    private fun toggleDesktopSite() {
        val tab = current
        tab.desktop = !tab.desktop
        val web = webFor(tab)
        web.desktopMode = tab.desktop
        if (!atStart) web.reload()
        saveTabs()
        Toast.makeText(this, if (tab.desktop) "Desktop site on" else "Desktop site off", Toast.LENGTH_SHORT).show()
    }

    /** Scripts changed in the manager take effect on coming back, same as in web apps. */
    private fun refreshScriptsIfChanged() {
        lifecycleScope.launch {
            val enabled = scripts.getEnabledScriptsList()
            val signature = enabled.map { it.id to it.updatedAt }
            if (signature == appliedScripts) return@launch
            appliedScripts = signature
            tabs.forEach { tab -> tab.web?.let { web -> tab.scriptEngine?.register(web, enabled) } }
            if (!atStart) current.web?.reload()
        }
    }

    private fun openAppSwitcher() {
        if (assistiveBall.isOpen) assistiveBall.close()
        lifecycleScope.launch {
            val apps = WebAppRepository(AppDatabase.getInstance(this@QuickBrowserActivity).webAppDao()).allWebApps.first()
            if (apps.isEmpty()) {
                Toast.makeText(this@QuickBrowserActivity, "No apps on shelf", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AppSwitcherBottomSheetDialog(apps = apps, onAppSelected = { AppTasks.open(this@QuickBrowserActivity, it) })
                .show(supportFragmentManager, "app_switcher")
        }
    }

    private fun showHistory() {
        hideKeyboard()
        binding.browserHistoryPage.visibility = View.VISIBLE
        allHistoryWatch?.cancel()
        allHistoryWatch = lifecycleScope.launch {
            history.recent(HISTORY_LIMIT).collectLatest { visits ->
                allHistoryAdapter.submitList(visits)
                binding.browserTvHistoryEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE
                binding.browserBtnClearHistory.visibility = if (visits.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun hideHistory() {
        allHistoryWatch?.cancel()
        allHistoryWatch = null
        binding.browserHistoryPage.visibility = View.GONE
    }

    private fun observeHistory() {
        lifecycleScope.launch {
            history.recent(HISTORY_SHOWN).collectLatest { visits ->
                historyAdapter.submitList(visits)
                binding.browserTvEmpty.visibility = if (visits.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    assistiveBall.isOpen -> assistiveBall.close()
                    binding.browserHistoryPage.visibility == View.VISIBLE -> hideHistory()
                    fullscreenVideo != null -> {
                        fullscreenVideoCallback?.onCustomViewHidden()
                        hideFullscreenVideo()
                    }
                    canGoBackToPage() -> {
                        if (atStart) {
                            current.url = ""
                            showPage()
                        }
                        current.web?.goBack()
                    }
                    // A tab opened from another page goes back to it, the way a popup closing does
                    current.opener != null -> closeTab(current)
                    atStart && tabs.size > 1 -> closeTab(current)
                    atStart -> {
                        AppTasks.goToShelf(this@QuickBrowserActivity)
                        finishAndRemoveTask()
                    }
                    else -> showStart()
                }
            }
        })
    }

    /** The start page sits on about:blank, which isn't a page to step back onto. */
    private fun canGoBackToPage(): Boolean {
        val list = current.web?.copyBackForwardList() ?: return false
        val previous = list.currentIndex - 1
        return previous >= 0 && isWebPage(list.getItemAtIndex(previous).url)
    }

    private fun open(url: String) {
        hideKeyboard()
        hideHistory()
        current.url = url
        showPage()
        val web = webFor(current)
        // Clearing focus alone would hand it to the next button, which a hardware Enter key then presses
        web.requestFocus()
        web.loadUrl(url)
        saveTabs()
    }

    private fun showPage() {
        binding.browserStart.visibility = View.GONE
        current.web?.visibility = View.VISIBLE
    }

    private fun showStart() {
        current.url = null
        current.title = ""
        current.opener = null
        current.web?.let {
            it.stopLoading()
            it.loadUrl(BLANK_PAGE)
            it.visibility = View.INVISIBLE
        }
        binding.browserStart.visibility = View.VISIBLE
        binding.browserEtAddress.setText("")
        binding.browserProgress.visibility = View.INVISIBLE
        saveTabs()
    }

    private fun showAddress(url: String) {
        if (!binding.browserEtAddress.hasFocus() && isWebPage(url)) binding.browserEtAddress.setText(url)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.browserEtAddress.windowToken, 0)
    }

    private fun showMenu() {
        val web = webFor(current)
        val actions = mutableListOf<Pair<String, () -> Unit>>("New tab" to { openNewTab() }, "History" to { showHistory() })
        if (web.canGoForward()) actions += "Forward" to { web.goForward() }
        if (!atStart) actions += "Add to homepage" to {
            // A long page title makes a poor app name; left blank, the sheet names it after the site
            web.url?.let { showAddWebApp(web.title?.takeIf { it.length <= MAX_APP_NAME }.orEmpty(), it) }
        }
        if (!atStart) actions += "Open in Chrome or default browser" to {
            web.url?.let { ExternalLinks.openInBrowser(this, Uri.parse(it)) }
        }
        actions += "Clear logins and site data" to { confirmClearLogins() }
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .showCosmic()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("This removes every page in your browser history.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> lifecycleScope.launch { history.clear() } }
            .showCosmic()
    }

    private fun confirmClearLogins() {
        AlertDialog.Builder(this)
            .setTitle("Clear logins and site data?")
            .setMessage("You'll be signed out of every site in this browser and in the web apps that share its logins. Apps set to keep their own login aren't affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                AppProfiles.clearShared()
                current.web?.clearCache(true)
                Toast.makeText(this, "Logins and site data cleared", Toast.LENGTH_LONG).show()
                // A page still open could carry on signed in, so none stay
                tabs.filter { it !== current }.forEach(::closeTab)
                showStart()
            }
            .showCosmic()
    }

    /**
     * Stays tied to the page that opened it, which sign-in windows need in order to report back. With
     * the shield on, it stays out of sight until its address shows whether anyone asked for it.
     */
    private fun openWindowTab(opener: Tab, resultMsg: Message, tapped: TappedLink?): Boolean {
        val tab = newTab("", opener)
        val web = webFor(tab)
        if (tapped == null) {
            select(tab)
        } else {
            tab.heldPopup = tapped
            web.visibility = View.GONE
            showTabCount()
        }
        (resultMsg.obj as WebView.WebViewTransport).webView = web
        resultMsg.sendToTarget()
        return true
    }

    /** Settles a held window on its first address. Returns whether it may go on loading. */
    private fun admitPopup(tab: Tab, uri: Uri): Boolean {
        val tapped = tab.heldPopup ?: return true
        tab.heldPopup = null
        val openerHost = tab.opener?.web?.let { pageHosts[it] }
        // Tabs are shown and closed once the page's own callback has returned
        return when (adBlockEngine.judgePopup(uri, openerHost, tapped, tab.opener?.web?.shieldStats)) {
            AdBlockEngine.PopupVerdict.OPEN -> {
                binding.browserPages.post { if (tab in tabs) select(tab) }
                true
            }
            AdBlockEngine.PopupVerdict.BLOCK -> {
                adBlockEngine.recordBlockedForApp(BROWSER_ID)
                binding.browserPages.post { if (tab in tabs) closeTab(tab) }
                false
            }
            AdBlockEngine.PopupVerdict.ASK -> {
                binding.browserPages.post { offerPopup(tab, uri) }
                false
            }
        }
    }

    /** A window the page opened on another site without a link being tapped. Unless asked for, it closes. */
    private fun offerPopup(tab: Tab, uri: Uri) {
        if (tab !in tabs) return
        var opened = false
        Snackbar.make(binding.browserRoot, "Blocked a popup to ${uri.host ?: uri.scheme}", Snackbar.LENGTH_LONG)
            .setAction("Open") {
                if (tab !in tabs) return@setAction
                opened = true
                select(tab)
                tab.web?.loadUrl(uri.toString())
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    if (!opened && tab in tabs && tab !== current) closeTab(tab)
                }
            })
            .show()
    }

    private fun hideFullscreenVideo() {
        fullscreenVideo?.let { (window.decorView as ViewGroup).removeView(it) }
        fullscreenVideo = null
        fullscreenVideoCallback = null
    }

    private inner class PageChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== current.web) return
            binding.browserProgress.progress = newProgress
            binding.browserProgress.visibility = if (newProgress < 100 && !atStart) View.VISIBLE else View.INVISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val url = view.url ?: return
            if (title.isNullOrEmpty() || !isWebPage(url)) return
            tabFor(view)?.title = title
            lifecycleScope.launch { history.updateTitle(url, title) }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Windows nobody asked for are the ads this browser is meant to spare you
            val opener = tabFor(view) ?: return false
            return isUserGesture && openWindowTab(opener, resultMsg, if (adblock) TappedLink(view) else null)
        }

        override fun onCloseWindow(view: WebView) {
            tabFor(view)?.let(::closeTab)
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            pendingFiles?.onReceiveValue(null)
            pendingFiles = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (e: Exception) {
                pendingFiles = null
                false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenVideo = view
            fullscreenVideoCallback = callback
            (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        override fun onHideCustomView() {
            hideFullscreenVideo()
        }
    }

    override fun onRestart() {
        super.onRestart()
        val theme = CosmicThemeStore.load(this)
        if (theme.accent != CosmicAccent.color || theme.accent != appliedTheme?.accent) {
            CosmicAccent.color = theme.accent
            recreate()
        } else if (theme != appliedTheme) {
            theme.applyBackground(binding.browserRoot)
            appliedTheme = theme
        }
        refreshScriptsIfChanged()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only the tab on screen: Android limits this bundle, and several pages' state could break it
        if (!rendererGone && !atStart) {
            current.web?.let { web -> outState.putBundle(KEY_PAGE, Bundle().also { web.saveState(it) }) }
        }
    }

    override fun onPause() {
        adBlockEngine.flushStats()
        cookies.flush()
        saveTabs()
        super.onPause()
    }

    override fun onDestroy() {
        tabs.forEach { it.web?.destroy() }
        super.onDestroy()
    }

    private class HistoryAdapter(
        private val onClick: (BrowserHistoryEntity) -> Unit,
        private val onRemove: ((BrowserHistoryEntity) -> Unit)? = null
    ) : ListAdapter<BrowserHistoryEntity, HistoryAdapter.Row>(DIFF) {

        class Row(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.history_tv_title)
            val url: TextView = view.findViewById(R.id.history_tv_url)
            val remove: ImageButton = view.findViewById(R.id.history_btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            return Row(LayoutInflater.from(parent.context).inflate(R.layout.item_browser_history, parent, false))
        }

        override fun onBindViewHolder(holder: Row, position: Int) {
            val visit = getItem(position)
            holder.title.text = visit.title.ifEmpty { Uri.parse(visit.url).host ?: visit.url }
            holder.url.text = visit.url
            holder.itemView.setOnClickListener { onClick(visit) }
            onRemove?.let { remove ->
                holder.remove.visibility = View.VISIBLE
                holder.remove.setOnClickListener { remove(visit) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<BrowserHistoryEntity>() {
                override fun areItemsTheSame(oldItem: BrowserHistoryEntity, newItem: BrowserHistoryEntity) = oldItem.url == newItem.url
                override fun areContentsTheSame(oldItem: BrowserHistoryEntity, newItem: BrowserHistoryEntity) = oldItem == newItem
            }
        }
    }

    companion object {
        const val BROWSER_ID = "browser"
        private const val BLANK_PAGE = "about:blank"
        private const val SEARCH_URL = "https://www.google.com/search?q="
        private const val HISTORY_SHOWN = 100
        private const val HISTORY_LIMIT = 2000
        private const val MAX_APP_NAME = 24
        private const val TABS_PREFS = "scraffold_browser_tabs"
        private const val KEY_TABS = "tabs"
        private const val KEY_SELECTED = "selected"
        private const val KEY_PAGE = "page"
        private val SIGN_IN_HOST = Regex("^(accounts|login|signin|auth|sso)\\.", RegexOption.IGNORE_CASE)
        private val SECRET_IN_URL = Regex("[?&#](code|state|token|access_token|id_token|password)=", RegexOption.IGNORE_CASE)

        private fun isWebPage(url: String?) =
            url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))

        /** These carry one-time login codes, which don't belong in history or in a backup file. */
        fun isSignIn(url: String): Boolean {
            return SIGN_IN_HOST.containsMatchIn(Uri.parse(url).host.orEmpty()) || SECRET_IN_URL.containsMatchIn(url)
        }

        /** An address opens as it is, and anything else, javascript: included, is searched for. */
        fun addressFor(text: String): String = when {
            isWebPage(text) -> text
            !text.contains(' ') && !text.contains(':') && Patterns.WEB_URL.matcher(text).matches() -> "https://$text"
            else -> SEARCH_URL + Uri.encode(text)
        }
    }
}
