package net.mangadot.scaffold.ui.folder

import android.os.Bundle
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WebAppRepository(AppDatabase.getInstance(this).webAppDao())

        binding.root.setOnClickListener { finish() }
        binding.folderEmpty.setOnClickListener {
            AppTasks.goToShelf(this)
            finish()
        }

        val adapter = AppAdapter(onAppClick = ::openApp, onAppOptionsClick = {})
        adapter.showDomains = false
        binding.folderGrid.adapter = adapter

        lifecycleScope.launch {
            val apps = ShelfSort.current(this@FolderActivity, AppDatabase.getInstance(this@FolderActivity).webAppDao().getAll())
            binding.folderEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
            binding.folderGrid.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
            // A few apps sit centred rather than bunched at one side
            binding.folderGrid.layoutManager = GridLayoutManager(this@FolderActivity, columns().coerceAtMost(apps.size.coerceAtLeast(1)))
            adapter.submitList(apps)
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
        const val PANEL_MARGIN_DP = 80
        const val PANEL_MAX_DP = 400
        const val CELL_DP = 80
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 4
    }
}
