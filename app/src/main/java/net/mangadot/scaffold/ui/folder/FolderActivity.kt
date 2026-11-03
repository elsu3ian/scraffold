package net.mangadot.scaffold.ui.folder

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.databinding.ActivityFolderBinding
import net.mangadot.scaffold.ui.container.AppTasks
import net.mangadot.scaffold.ui.shelf.AppAdapter
import net.mangadot.scaffold.ui.shelf.ShelfSort
import net.mangadot.scaffold.ui.theme.CosmicActivity

/** The open folder: every app on the shelf in a panel over the home screen. Tapping outside closes it. */
class FolderActivity : CosmicActivity() {

    private lateinit var binding: ActivityFolderBinding
    private lateinit var repository: WebAppRepository
    private lateinit var opening: FolderOpening
    private var laidOut = false
    private var shown = false
    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WebAppRepository(AppDatabase.getInstance(this).webAppDao())

        opening = FolderOpening(window, binding.folderBackdrop, binding.folderPanel, binding.folderGrid, listOf(binding.folderTitle, binding.folderEmpty))
        binding.root.setOnClickListener { opening.close(::finish) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = opening.close(::finish)
        })
        binding.folderEmpty.setOnClickListener {
            AppTasks.goToShelf(this)
            finish()
        }

        val adapter = AppAdapter(onAppClick = ::openApp, onAppOptionsClick = {})
        adapter.showDomains = false
        binding.folderGrid.adapter = adapter
        // The icons fly in themselves; the grid's own fade-in for new items would hide them on the way
        binding.folderGrid.itemAnimator = null

        lifecycleScope.launch {
            val apps = ShelfSort.current(this@FolderActivity, AppDatabase.getInstance(this@FolderActivity).webAppDao().getAll())
            binding.folderEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            binding.folderGrid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
            // A few apps sit centred rather than bunched at one side
            binding.folderGrid.layoutManager = GridLayoutManager(this@FolderActivity, columns().coerceAtMost(apps.size.coerceAtLeast(1)))
            adapter.submitList(apps) {
                binding.folderPanel.doOnPreDraw {
                    laidOut = true
                    // Should the launcher never say its animation is done, the folder still opens
                    binding.root.postDelayed({ shown = true; open() }, MAX_LAUNCH_WAIT_MS)
                    open()
                }
            }
        }
    }

    /**
     * Launchers play their own animation out of the widget, and the window only shows once it ends.
     * Starting ours then lets the icons be seen flying rather than having nearly landed already.
     */
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        shown = true
        open()
    }

    private fun open() {
        if (!laidOut || !shown || opened) return
        opened = true
        val source = intent.sourceBounds
        val style = intent.getIntExtra(FolderWidgetProvider.EXTRA_STYLE, FolderWidgetProvider.STYLE_UNKNOWN)
        opening.open(source, source?.let { FolderWidgetProvider.iconSpots(this, style, it) }.orEmpty())
    }

    /** The folder animates itself, so the window comes and goes without one. */
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun columns(): Int {
        val panelDp = (resources.configuration.screenWidthDp - PANEL_MARGIN_DP).coerceAtMost(PANEL_MAX_DP)
        return (panelDp / CELL_DP).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    private fun openApp(app: WebAppEntity) {
        // This screen closes straight away, so the record is kept outside its lifetime
        FolderWidgetProvider.scope.launch { repository.recordLaunch(app.id) }
        AppTasks.open(this, app)
        finish()
    }

    private companion object {
        const val MAX_LAUNCH_WAIT_MS = 2000L
        const val PANEL_MARGIN_DP = 80
        const val PANEL_MAX_DP = 400
        const val CELL_DP = 80
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 4
    }
}
