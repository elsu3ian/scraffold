package net.mangadot.scaffold.ui.shelf

import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.AppProfiles
import net.mangadot.scaffold.data.repository.ScriptRepository
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.databinding.ActivityMainBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.cache.CacheManager
import net.mangadot.scaffold.ui.about.Updates
import net.mangadot.scaffold.ui.container.AppTasks
import net.mangadot.scaffold.ui.settings.SettingsActivity
import net.mangadot.scaffold.ui.theme.CosmicTheme
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader

class MainActivity : CosmicActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: WebAppRepository
    private lateinit var scriptRepository: ScriptRepository
    private lateinit var adapter: AppAdapter
    private val iconAttempts = mutableSetOf<String>()
    private lateinit var adBlockEngine: AdBlockEngine
    private var allApps: List<WebAppEntity> = emptyList()
    private var reopenCosmicDialog = false
    private var shelfShown = false

    private var appliedTheme: CosmicTheme? = null

    /** While the guide plays on an empty shelf, it has a demo app to point at. */
    private var demoShown = false
    private var guide: ShelfGuide? = null
    private var cosmicDialog: CosmicThemeDialog? = null
    private lateinit var sort: ShelfSort
    private var dragList = mutableListOf<WebAppEntity>()

    /** Holding an app without moving it still opens its options, as in the other sorts. */
    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        private var heldApp: WebAppEntity? = null
        private var moved = false
        private var wandered = false

        override fun isLongPressDragEnabled() = adapter.dragEnabled

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                dragList = adapter.currentList.toMutableList()
                heldApp = dragList.getOrNull(viewHolder.adapterPosition)
                moved = false
            }
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            if (from !in dragList.indices || to !in dragList.indices) return false
            dragList.add(to, dragList.removeAt(from))
            adapter.submitList(dragList.toList())
            moved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            // A drag that ended without passing another app still wasn't a request for options
            if (isCurrentlyActive && maxOf(abs(dX), abs(dY)) > ViewConfiguration.get(this@MainActivity).scaledTouchSlop) {
                wandered = true
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (moved) {
                val ids = dragList.map { it.id }
                lifecycleScope.launch { repository.saveShelfOrder(ids) }
            } else if (!wandered) {
                heldApp?.let(::showAppActionSheet)
            }
            heldApp = null
            moved = false
            wandered = false
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyTheme(CosmicThemeStore.load(this))

        val db = AppDatabase.getInstance(this)
        repository = WebAppRepository(db.webAppDao())
        scriptRepository = ScriptRepository(db.scriptDao(), db.scriptStorageDao())
        adBlockEngine = AdBlockEngine.getInstance(this)
        sort = ShelfSort.load(this)

        setupWindowInsets()
        setupRecyclerView()
        setupListeners()
        observeWebApps()
        updateShieldTelemetry()
        highlightTab(binding.tabIcons, listOf(binding.tabGrid, binding.tabList))

        // An accent change rebuilds the screen, so bring the dialog straight back
        if (savedInstanceState?.getBoolean(STATE_REOPEN_COSMIC) == true) {
            showCosmicThemeDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_GUIDE, false)) showGuide()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REOPEN_COSMIC, reopenCosmicDialog)
    }

    override fun onDestroy() {
        cosmicDialog?.dismiss()
        super.onDestroy()
    }

    /** A theme changed elsewhere, by restoring a backup say, shows on coming back. */
    override fun onRestart() {
        super.onRestart()
        val theme = CosmicThemeStore.load(this)
        if (theme.accent != CosmicAccent.color) {
            CosmicAccent.color = theme.accent
            recreate()
        } else if (theme != appliedTheme) {
            applyTheme(theme)
        }
    }

    override fun onResume() {
        super.onResume()
        updateShieldTelemetry()
        runAutoPurge()
        AppProfiles.deletePending(this)
        adBlockEngine.updateListsIfDue()
    }

    private fun runAutoPurge() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(SettingsActivity.KEY_AUTO_PURGE, true)) {
            lifecycleScope.launch { CacheManager.checkAndAutoPurge(this@MainActivity) }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRootLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.headerLayout.setPadding(
                binding.headerLayout.paddingLeft,
                statusBarHeight + dpToPx(12),
                binding.headerLayout.paddingRight,
                dpToPx(12)
            )

            binding.shelfBottomBar.setPadding(
                binding.shelfBottomBar.paddingLeft,
                dpToPx(10),
                binding.shelfBottomBar.paddingRight,
                navBarHeight + dpToPx(10)
            )

            insets
        }
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter(
            onAppClick = { app -> if (app.id != AppAdapter.DEMO_ID) launchWebApp(app) },
            onAppOptionsClick = { app -> if (app.id != AppAdapter.DEMO_ID) showAppActionSheet(app) }
        )

        // Default to Icons View with 3 columns
        binding.rvShelf.layoutManager = GridLayoutManager(this, 3)
        binding.rvShelf.adapter = adapter
        dragHelper.attachToRecyclerView(binding.rvShelf)
        setupScrollingTabs()
    }

    /** The apps run under the top bar, and the view tabs scroll away with them. */
    private fun setupScrollingTabs() {
        val list = binding.rvShelf
        val tabs = binding.layoutSwitcherContainer
        val gap = list.paddingTop
        fun placeTabs() {
            tabs.translationY = -list.computeVerticalScrollOffset().coerceAtMost(tabs.bottom).toFloat()
        }
        val fit = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@OnLayoutChangeListener
            // Padding can't change in the middle of a layout, so it waits for this one to end
            list.post {
                val clear = binding.shelfTopBar.height + tabs.height + gap
                if (list.paddingTop == clear) return@post
                val atTop = !list.canScrollVertically(-1)
                list.setPadding(list.paddingLeft, clear, list.paddingRight, list.paddingBottom)
                // The apps would otherwise keep their place on screen, under the bars, as if scrolled
                if (atTop) list.scrollToPosition(0)
            }
        }
        binding.shelfTopBar.addOnLayoutChangeListener(fit)
        tabs.addOnLayoutChangeListener(fit)
        // The list only reports scrolls, not being laid out again with a new padding or layout
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeTabs() }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = placeTabs()
        })
    }

    private fun setupListeners() {
        // Toggle Search Bar
        binding.btnToggleSearch.setOnClickListener {
            val isVisible = binding.searchContainer.visibility == View.VISIBLE
            binding.searchContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                binding.etSearch.requestFocus()
            } else {
                binding.etSearch.setText("")
                filterApps("")
            }
        }

        // Live Search Filter
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnBrowser.setOnClickListener { AppTasks.openBrowser(this) }

        // Customize: theme and app order
        binding.btnCustomize.setOnClickListener {
            ShelfCustomizeSheet(
                sort = sort,
                onOpenTheme = { showCosmicThemeDialog() },
                onSortChosen = { chosen -> chooseSort(chosen) },
                onDirectionChanged = { filterApps(binding.etSearch.text.toString()) }
            ).show(supportFragmentManager, ShelfCustomizeSheet.TAG)
        }

        // Settings Button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Add App FAB
        binding.fabAddApp.setOnClickListener {
            showAddAppDialog()
        }

        // View Switcher Tabs (Icons | Grid | List)
        binding.tabIcons.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.ICONS) }
        binding.tabGrid.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.GRID) }
        binding.tabList.setOnClickListener { setLayoutMode(AppAdapter.LayoutMode.LIST) }
    }

    private fun setLayoutMode(mode: AppAdapter.LayoutMode) {
        adapter.currentLayoutMode = mode
        when (mode) {
            AppAdapter.LayoutMode.ICONS -> {
                binding.rvShelf.layoutManager = GridLayoutManager(this, 3)
                highlightTab(binding.tabIcons, listOf(binding.tabGrid, binding.tabList))
            }
            AppAdapter.LayoutMode.GRID -> {
                binding.rvShelf.layoutManager = GridLayoutManager(this, 2)
                highlightTab(binding.tabGrid, listOf(binding.tabIcons, binding.tabList))
            }
            AppAdapter.LayoutMode.LIST -> {
                binding.rvShelf.layoutManager = LinearLayoutManager(this)
                highlightTab(binding.tabList, listOf(binding.tabIcons, binding.tabGrid))
            }
        }
    }

    private fun highlightTab(active: TextView, inactives: List<TextView>) {
        active.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        active.setTypeface(null, android.graphics.Typeface.BOLD)
        active.background = CosmicAccent.tabIndicator(this)

        for (inactive in inactives) {
            inactive.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            inactive.setTypeface(null, android.graphics.Typeface.NORMAL)
            inactive.setBackgroundResource(R.drawable.bg_tab_inactive)
        }
    }

    private fun observeWebApps() {
        lifecycleScope.launch {
            repository.allWebApps.collectLatest { apps ->
                allApps = apps
                filterApps(binding.etSearch.text.toString())
                apps.filter { it.iconUrl == null && iconAttempts.add(it.id) }.forEach(::fetchIcon)
                if (!shelfShown) {
                    shelfShown = true
                    // Once the apps are on screen, so the guide can point at one
                    binding.rvShelf.post(::greet)
                }
            }
        }
        lifecycleScope.launch {
            scriptRepository.allScripts.collectLatest { scripts ->
                adapter.scripts = scripts
            }
        }
    }

    /** The guide the first time, or when Settings asks for it; otherwise news of an update, if there is one. */
    private fun greet() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        if (intent.getBooleanExtra(EXTRA_SHOW_GUIDE, false) || !prefs.getBoolean(KEY_GUIDE_SEEN, false)) {
            showGuide()
        } else {
            Updates.offerIfDue(this, lifecycleScope)
        }
    }

    private fun showGuide() {
        if (guide != null) return
        // An empty shelf gets a demo app for the guide to point at, gone again when it ends
        demoShown = adapter.currentList.isEmpty()
        val start = {
            binding.rvShelf.scrollToPosition(0)
            binding.rvShelf.post {
                if (guide != null || isFinishing) return@post
                guide = ShelfGuide(this, guideSteps()) {
                    guide = null
                    if (demoShown) {
                        demoShown = false
                        filterApps(binding.etSearch.text.toString())
                    }
                    getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_GUIDE_SEEN, true).apply()
                    Updates.offerIfDue(this, lifecycleScope)
                }.also { it.show() }
            }
        }
        if (demoShown) {
            binding.emptyStateContainer.visibility = View.GONE
            binding.rvShelf.visibility = View.VISIBLE
            adapter.submitList(listOf(AppAdapter.DEMO_APP)) { start() }
        } else {
            start()
        }
    }

    private fun guideSteps() = listOfNotNull(
        ShelfGuide.Step(
            binding.fabAddApp, "Add a web app",
            "Tap + and enter a site's address. Scraffold reads the site's own app details, like its name, icon " +
                "and colors, and puts it on your shelf as an app of its own."
        ),
        binding.rvShelf.getChildAt(0)?.let { firstApp ->
            if (demoShown) {
                ShelfGuide.Step(
                    firstApp, "Your apps",
                    "This is a demo. The apps you add show up here. Tap an app to open it, and hold it for " +
                        "its options: its settings, ad blocking, fullscreen, userscripts, clearing its data, or removing it.",
                    sticker = "DEMO",
                    onlyHere = true
                )
            } else {
                ShelfGuide.Step(
                    firstApp, "Your apps",
                    "Tap an app to open it. Hold it for its options: its settings, ad blocking, fullscreen, " +
                        "userscripts, clearing its data, or removing it."
                )
            }
        },
        ShelfGuide.Step(binding.layoutSwitcherContainer, "Three views", "Show your apps as icons, a grid or a list."),
        ShelfGuide.Step(binding.btnToggleSearch, "Search", "Find an app by its name or address."),
        ShelfGuide.Step(
            binding.btnBrowser, "Quick Browser",
            "Browse any site, with tabs. Its menu adds the page you're on to your shelf."
        ),
        ShelfGuide.Step(binding.btnCustomize, "Customize", "Change the theme and colors, and how your apps are sorted."),
        ShelfGuide.Step(
            binding.btnSettings, "Settings",
            "Turn on ad blocking here, and set up gestures, userscripts, backups and the settings new apps start with. " +
                "This guide is there too."
        ),
        ShelfGuide.Step(
            null, "Inside an app",
            "• The ball at the edge of the screen: tap it to reload, go home, see what the shield blocked, run " +
                "userscripts, change the text size or ask for the desktop site. Hold it to switch apps, and drag " +
                "it up and down, or across to the other side.\n\n" +
                "• Pull down at the top of a page, or swipe down with two fingers, to reload. Swipe sideways " +
                "with two fingers to go back and forward.\n\n" +
                "• Add the Scraffold folder widget to your home screen to open your apps from there."
        )
    )

    private fun fetchIcon(app: WebAppEntity) {
        lifecycleScope.launch {
            val icon = AppIcons.fetch(this@MainActivity, app.url) ?: return@launch
            repository.updateIconUrl(app.id, AppIcons.save(this@MainActivity, app.id, icon))
        }
    }

    private fun chooseSort(chosen: ShelfSort) {
        val shown = sortedApps()
        sort = chosen
        ShelfSort.save(this, chosen)
        if (chosen == ShelfSort.CUSTOM) {
            // The first arrangement starts from what is on screen rather than a new order
            if (allApps.all { it.shelfOrder == 0 }) {
                allApps = shown.mapIndexed { index, app -> app.copy(shelfOrder = index + 1) }
                lifecycleScope.launch { repository.saveShelfOrder(shown.map { it.id }) }
            }
            Toast.makeText(this, "Hold an app, then drag it into place", Toast.LENGTH_SHORT).show()
        }
        filterApps(binding.etSearch.text.toString())
    }

    private fun sortedApps() = sort.applyTo(allApps, sort.isDescending(this))

    private fun filterApps(query: String) {
        val q = query.trim().lowercase()
        val sorted = sortedApps()
        val filtered = if (q.isEmpty()) {
            sorted
        } else {
            sorted.filter {
                it.name.lowercase().contains(q) || it.url.lowercase().contains(q)
            }
        }

        // Moving apps around in a filtered list would have no clear meaning
        adapter.dragEnabled = sort == ShelfSort.CUSTOM && q.isEmpty() && !demoShown
        val shown = if (demoShown && filtered.isEmpty()) listOf(AppAdapter.DEMO_APP) else filtered
        adapter.submitList(shown)

        // Empty State Lifecycle
        val isEmpty = shown.isEmpty()
        binding.emptyStateContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvShelf.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateShieldTelemetry() {
        val blocked = adBlockEngine.totalBlockedRequests
        binding.tvShelfShieldCount.text = "Shield: $blocked blocked"
        adapter.blockedCounts = adBlockEngine.blockedCountsByApp()
    }

    private fun launchWebApp(app: WebAppEntity) {
        lifecycleScope.launch {
            repository.recordLaunch(app.id)
        }
        AppTasks.open(this, app)
    }

    private fun showAddAppDialog() = showAddWebApp()

    private fun showCosmicThemeDialog() {
        CosmicThemeDialog(
            this,
            onThemeChanged = { theme -> applyTheme(theme) },
            onAccentChanged = {
                reopenCosmicDialog = true
                recreate()
            }
        ).also { cosmicDialog = it }.show()
    }

    private fun applyTheme(theme: CosmicTheme) {
        appliedTheme = theme
        theme.applyBackground(binding.mainRootLayout)
        theme.applyHeader(binding.shelfTopBar, binding.rvShelf, laidOutUnder = true)
    }

    private fun showAppActionSheet(app: WebAppEntity) {
        val sheet = AppActionBottomSheetDialog(
            app = app,
            onLaunch = { launchWebApp(it) },
            onDelete = { target ->
                lifecycleScope.launch {
                    AppTasks.close(this@MainActivity, target.id)
                    repository.deleteWebAppById(target.id)
                    AppIcons.delete(this@MainActivity, target.id)
                    AppProfiles.delete(this@MainActivity, target.id)
                    Toast.makeText(this@MainActivity, "Removed ${target.name} from Shelf", Toast.LENGTH_SHORT).show()
                }
            },
            onUpdate = { updated ->
                lifecycleScope.launch { repository.updateWebApp(updated) }
            }
        )
        sheet.show(supportFragmentManager, AppActionBottomSheetDialog.TAG)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        /** Plays the guide again, from Settings. */
        const val EXTRA_SHOW_GUIDE = "show_guide"
        private const val KEY_GUIDE_SEEN = "guide_seen"
        private const val STATE_REOPEN_COSMIC = "reopen_cosmic"
    }
}
