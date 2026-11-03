package net.mangadot.scaffold.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.BuildConfig
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.backup.BackupManager
import net.mangadot.scaffold.engine.FilterLists
import net.mangadot.scaffold.databinding.ActivitySettingsBinding
import net.mangadot.scaffold.databinding.DialogAdblockSettingsBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.cache.CacheManager
import net.mangadot.scaffold.ui.about.AboutActivity
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyDialogSurface
import net.mangadot.scaffold.ui.theme.applyHeader
import net.mangadot.scaffold.ui.userscript.UserscriptsActivity
import net.mangadot.scaffold.ui.theme.showCosmic

class SettingsActivity : CosmicActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adBlockEngine: AdBlockEngine

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                val message = try {
                    val saved = BackupManager(this@SettingsActivity).export(uri)
                    "Exported ${saved.apps} apps and ${saved.scripts} scripts. Logins aren't included."
                } catch (e: Exception) {
                    "Export failed: ${e.message}"
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                val manager = BackupManager(this@SettingsActivity)
                try {
                    val backup = manager.read(uri)
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Import backup")
                        .setMessage(
                            "Add ${backup.webApps.size} apps and ${backup.scripts.size} userscripts, and replace your settings? " +
                                "Items with the same ID are overwritten. Only import scripts you trust: they run on your sites."
                        )
                        .setPositiveButton("Import") { _, _ -> restore(manager, backup) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showCosmic()
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun restore(manager: BackupManager, backup: BackupManager.BackupFile) {
        lifecycleScope.launch {
            val message = try {
                val restored = manager.restore(backup)
                loadPreferences()
                "Imported ${restored.apps} apps and ${restored.scripts} scripts"
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val PREFS_NAME = "scraffold_global_prefs"
        const val KEY_CLEAR_CACHE_EXIT = "pref_clear_cache_exit"
        const val KEY_GESTURE_RELOAD = "pref_gesture_reload"
        const val KEY_GESTURE_PULL = "pref_gesture_pull"
        const val KEY_GESTURE_NAV = "pref_gesture_nav"
        const val KEY_GESTURE_APP_SWITCH = "pref_gesture_app_switch"
        const val KEY_PROGRESS_BAR = "pref_progress_bar"
        const val KEY_SOFTWARE_NAV = "pref_software_nav"
        const val KEY_AUTO_PURGE = "pref_auto_purge"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.settingsRootLayout)
            theme.applyHeader(binding.settingsHeaderLayout, binding.settingsScroll, overlay = true)
        }

        adBlockEngine = AdBlockEngine.getInstance(this)

        setupWindowInsets()
        loadPreferences()
        setupListeners()
        updateCacheDisplay()

        binding.settingsTvAboutVersion.text = "${getString(R.string.release_stage)} →"
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRootLayout) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.settingsHeaderLayout.setPadding(
                binding.settingsHeaderLayout.paddingLeft,
                statusBarHeight + dpToPx(12),
                binding.settingsHeaderLayout.paddingRight,
                dpToPx(12)
            )
            insets
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.settingsSwitchClearCache.isChecked = prefs.getBoolean(KEY_CLEAR_CACHE_EXIT, false)
        binding.settingsSwitchReloadGesture.isChecked = prefs.getBoolean(KEY_GESTURE_RELOAD, true)
        binding.settingsSwitchPullReload.isChecked = prefs.getBoolean(KEY_GESTURE_PULL, true)
        binding.settingsSwitchNavGesture.isChecked = prefs.getBoolean(KEY_GESTURE_NAV, true)
        binding.settingsSwitchAppSwitchGesture.isChecked = prefs.getBoolean(KEY_GESTURE_APP_SWITCH, false)
        binding.settingsSwitchProgressBar.isChecked = prefs.getBoolean(KEY_PROGRESS_BAR, true)
        binding.settingsSwitchSoftwareNav.isChecked = prefs.getBoolean(KEY_SOFTWARE_NAV, false)
        binding.settingsSwitchAutoPurge.isChecked = prefs.getBoolean(KEY_AUTO_PURGE, true)
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        binding.settingsBtnBack.setOnClickListener {
            finish()
        }

        // Row 1: Adblock Settings Dialog
        binding.settingsRowAdblock.setOnClickListener {
            showAdblockDialog()
        }

        // Row 2: Default Web App Settings
        binding.settingsRowDefaultApp.setOnClickListener {
            startActivity(Intent(this, WebAppSettingsActivity::class.java))
        }

        // Row 3: Userscripts Manager
        binding.settingsRowUserscripts.setOnClickListener {
            startActivity(Intent(this, UserscriptsActivity::class.java))
        }

        // 6 Toggles
        binding.settingsSwitchClearCache.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_CLEAR_CACHE_EXIT, isChecked).apply()
        }

        binding.settingsSwitchReloadGesture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_GESTURE_RELOAD, isChecked).apply()
        }

        binding.settingsSwitchPullReload.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_GESTURE_PULL, isChecked).apply()
        }

        binding.settingsSwitchNavGesture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_GESTURE_NAV, isChecked).apply()
        }

        binding.settingsSwitchAppSwitchGesture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_GESTURE_APP_SWITCH, isChecked).apply()
        }

        binding.settingsSwitchProgressBar.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_PROGRESS_BAR, isChecked).apply()
        }

        binding.settingsSwitchSoftwareNav.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SOFTWARE_NAV, isChecked).apply()
        }

        binding.settingsSwitchAutoPurge.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_PURGE, isChecked).apply()
            updateCacheDisplay()
        }

        // Clear Cache
        binding.settingsBtnClearCache.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Cache")
                .setMessage("Clear cached web media and temporary files? Logins and cookies will be preserved.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        CacheManager.purgeTemporaryHttpCache(this@SettingsActivity)
                        updateCacheDisplay()
                        Toast.makeText(this@SettingsActivity, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showCosmic()
        }

        // Export Settings
        binding.settingsBtnExport.setOnClickListener {
            exportBackup.launch("scraffold-backup.json")
        }

        // Import Settings
        binding.settingsBtnImport.setOnClickListener {
            importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
        }

        // About Row
        binding.settingsRowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun showAdblockDialog() {
        val dialogBinding = DialogAdblockSettingsBinding.inflate(LayoutInflater.from(this))
        dialogBinding.dialogTvBlockedCount.text = "Shield: ${adBlockEngine.totalBlockedRequests} requests blocked"

        fun showFilterStatus() {
            val updated = FilterLists.lastUpdated(this)
            val date = if (updated == 0L) "not downloaded yet" else "updated ${DateUtils.getRelativeTimeSpanString(updated)}"
            dialogBinding.dialogTvFilterStatus.text = "${adBlockEngine.ruleCount} rules, $date"
        }
        showFilterStatus()
        dialogBinding.dialogSwitchGenericStandIns.isChecked = adBlockEngine.genericStandIns
        dialogBinding.dialogSwitchGenericStandIns.setOnCheckedChangeListener { _, checked -> adBlockEngine.genericStandIns = checked }
        dialogBinding.btnUpdateFilters.setOnClickListener {
            dialogBinding.btnUpdateFilters.isEnabled = false
            dialogBinding.dialogTvFilterStatus.text = "Downloading lists..."
            lifecycleScope.launch {
                val arrived = adBlockEngine.updateListsNow()
                dialogBinding.btnUpdateFilters.isEnabled = true
                showFilterStatus()
                if (arrived == 0) Toast.makeText(this@SettingsActivity, "Couldn't download the lists", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnMyFilters.setOnClickListener { MyFilters.show(this) }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        CosmicThemeStore.load(this).applyDialogSurface(dialog.window, dialogBinding.root, R.drawable.bg_card_solid)
        dialogBinding.btnCloseAdblock.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateCacheDisplay() {
        lifecycleScope.launch {
            val formatted = CacheManager.getFormattedCacheSize(this@SettingsActivity)
            val auto = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_PURGE, true)
            binding.settingsTvCacheSize.text = if (auto) "$formatted (500 MB auto-purge threshold)" else "$formatted (auto-purge off)"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
