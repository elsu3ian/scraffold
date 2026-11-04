package net.mangadot.scaffold.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.BuildConfig
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.backup.BackupManager
import net.mangadot.scaffold.data.settings.GlobalWebSettingsStore
import net.mangadot.scaffold.engine.FilterLists
import net.mangadot.scaffold.databinding.ActivitySettingsBinding
import net.mangadot.scaffold.databinding.DialogAdblockSettingsBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.cache.CacheManager
import net.mangadot.scaffold.data.usage.UsageStore
import net.mangadot.scaffold.ui.about.AboutActivity
import net.mangadot.scaffold.ui.shelf.MainActivity
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyDialogSurface
import net.mangadot.scaffold.ui.theme.applyHeader
import net.mangadot.scaffold.ui.usage.UsageActivity
import net.mangadot.scaffold.ui.userscript.UserscriptsActivity
import net.mangadot.scaffold.ui.theme.showCosmic
import java.time.LocalDate

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
            val before = CosmicThemeStore.load(this@SettingsActivity)
            val message = try {
                val restored = manager.restore(backup)
                loadPreferences()
                "Imported ${restored.apps} apps and ${restored.scripts} scripts"
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
            // The backup's theme shows straight away, here and on every screen opened from now on
            val after = CosmicThemeStore.load(this@SettingsActivity)
            if (after != before) {
                CosmicAccent.color = after.accent
                recreate()
            }
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

        binding.settingsTvAboutVersion.text = getString(R.string.release_stage)
    }

    // Default app settings can switch ad blocking too, so it's read again on the way back
    override fun onResume() {
        super.onResume()
        showAdblockState()
        binding.settingsTvUsageToday.text = "Today ${UsageStore.format(UsageStore.totalFor(this, LocalDate.now()))}"
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
        binding.settingsRowGuide.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_SHOW_GUIDE, true)
            )
            finish()
        }

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
                .setTitle("Clear temporary files?")
                .setMessage("Pages may open a little slower the first time after this. You'll stay signed in.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        CacheManager.purgeTemporaryHttpCache(this@SettingsActivity)
                        updateCacheDisplay()
                        Toast.makeText(this@SettingsActivity, "Cleared", Toast.LENGTH_SHORT).show()
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

        binding.settingsRowUsage.setOnClickListener {
            startActivity(Intent(this, UsageActivity::class.java))
        }

        // About Row
        binding.settingsRowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun showAdblockDialog() {
        val dialogBinding = DialogAdblockSettingsBinding.inflate(LayoutInflater.from(this))
        dialogBinding.dialogTvBlockedCount.text = "${adBlockEngine.totalBlockedRequests} blocked so far"

        fun showFilterStatus() {
            dialogBinding.dialogTvFilterStatus.text = filterStatus()
        }
        showFilterStatus()

        // Off, it only explains itself; turning it on asks for the tick first
        fun showPanels() {
            val on = GlobalWebSettingsStore.adblock(this)
            dialogBinding.dialogOnPanel.visibility = if (on) View.VISIBLE else View.GONE
            dialogBinding.dialogOffPanel.visibility = if (on) View.GONE else View.VISIBLE
            dialogBinding.btnTurnOffAdblock.visibility = if (on) View.VISIBLE else View.GONE
            dialogBinding.dialogTvBlockedCount.text =
                if (on) "${adBlockEngine.totalBlockedRequests} blocked so far" else "Off"
            dialogBinding.dialogTvBlockedCount.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent_emerald else R.color.text_muted))
            showFilterStatus()
        }
        fun setAdblock(on: Boolean) {
            GlobalWebSettingsStore.save(this, GlobalWebSettingsStore.load(this).copy(adblock = on))
            if (on) adBlockEngine.updateListsIfDue()
            showAdblockState()
            showPanels()
        }
        showPanels()
        fun showTurnOn(ticked: Boolean) {
            dialogBinding.btnTurnOnAdblock.isEnabled = ticked
            dialogBinding.btnTurnOnAdblock.alpha = if (ticked) 1f else 0.4f
        }
        dialogBinding.dialogCheckUnderstand.isChecked = false
        showTurnOn(false)
        dialogBinding.dialogCheckUnderstand.setOnCheckedChangeListener { _, ticked -> showTurnOn(ticked) }
        dialogBinding.btnTurnOnAdblock.setOnClickListener { setAdblock(true) }
        dialogBinding.btnTurnOffAdblock.setOnClickListener {
            dialogBinding.dialogCheckUnderstand.isChecked = false
            setAdblock(false)
        }
        dialogBinding.dialogSwitchGenericStandIns.isChecked = adBlockEngine.genericStandIns
        dialogBinding.dialogSwitchGenericStandIns.setOnCheckedChangeListener { _, checked -> adBlockEngine.genericStandIns = checked }
        dialogBinding.btnUpdateFilters.setOnClickListener {
            dialogBinding.btnUpdateFilters.isEnabled = false
            dialogBinding.dialogTvFilterStatus.text = "Downloading the block lists..."
            lifecycleScope.launch {
                val arrived = adBlockEngine.updateListsNow()
                dialogBinding.btnUpdateFilters.isEnabled = true
                showFilterStatus()
                if (arrived == 0) Toast.makeText(this@SettingsActivity, "Couldn't download the block lists", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnMyFilters.setOnClickListener { MyFilters.show(this) }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        CosmicThemeStore.load(this).applyDialogSurface(dialog.window, dialogBinding.root, R.drawable.bg_card_solid)
        dialogBinding.btnCloseAdblock.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCloseOff.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** How many block lists there are, how many rules they hold, and when they last came. */
    private fun filterStatus(): String {
        val updated = FilterLists.lastUpdated(this)
        val lists = FilterLists.downloadedCount(this)
        if (updated == 0L || lists == 0) return "Block lists not downloaded yet"
        val rules = adBlockEngine.ruleCount
        val age = if (System.currentTimeMillis() - updated < DateUtils.MINUTE_IN_MILLIS) "just now"
            else DateUtils.getRelativeTimeSpanString(updated).toString().lowercase()
        // Some lists can fail to download; they're tried again later
        val all = FilterLists.listCount
        val listed = if (lists < all) "$lists of $all block lists" else "$lists block lists"
        val count = if (rules > 0) "$listed, ${"%,d".format(rules)} rules" else listed
        return "$count${System.lineSeparator()}Updated $age"
    }

    /** Whether ads are blocked in the browsers and the apps that use the default settings. */
    private fun showAdblockState() {
        val on = GlobalWebSettingsStore.adblock(this)
        binding.settingsTvAdblockState.text = if (on) "On" else "Off"
        binding.settingsTvAdblockState.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent_emerald else R.color.text_muted))
    }

    private fun updateCacheDisplay() {
        lifecycleScope.launch {
            val formatted = CacheManager.getFormattedCacheSize(this@SettingsActivity)
            val auto = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_PURGE, true)
            binding.settingsTvCacheSize.text = if (auto) "$formatted, cleared by itself at 500 MB" else "$formatted"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
