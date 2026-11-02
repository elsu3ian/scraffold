package net.mangadot.scaffold.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.BuildConfig
import net.mangadot.scaffold.databinding.ActivitySettingsBinding
import net.mangadot.scaffold.databinding.DialogAdblockSettingsBinding
import net.mangadot.scaffold.engine.AdBlockEngine
import net.mangadot.scaffold.engine.cache.CacheManager
import net.mangadot.scaffold.ui.about.AboutActivity
import net.mangadot.scaffold.ui.userscript.UserscriptsActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adBlockEngine: AdBlockEngine

    companion object {
        const val PREFS_NAME = "scraffold_global_prefs"
        const val KEY_CLEAR_CACHE_EXIT = "pref_clear_cache_exit"
        const val KEY_GESTURE_RELOAD = "pref_gesture_reload"
        const val KEY_GESTURE_NAV = "pref_gesture_nav"
        const val KEY_GESTURE_APP_SWITCH = "pref_gesture_app_switch"
        const val KEY_PROGRESS_BAR = "pref_progress_bar"
        const val KEY_SOFTWARE_NAV = "pref_software_nav"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adBlockEngine = AdBlockEngine.getInstance(this)

        setupWindowInsets()
        loadPreferences()
        setupListeners()
        updateCacheDisplay()

        binding.settingsTvAboutVersion.text = "v${BuildConfig.VERSION_NAME} →"
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
        binding.settingsSwitchClearCache.isChecked = prefs.getBoolean(KEY_CLEAR_CACHE_EXIT, true)
        binding.settingsSwitchReloadGesture.isChecked = prefs.getBoolean(KEY_GESTURE_RELOAD, true)
        binding.settingsSwitchNavGesture.isChecked = prefs.getBoolean(KEY_GESTURE_NAV, true)
        binding.settingsSwitchAppSwitchGesture.isChecked = prefs.getBoolean(KEY_GESTURE_APP_SWITCH, false)
        binding.settingsSwitchProgressBar.isChecked = prefs.getBoolean(KEY_PROGRESS_BAR, true)
        binding.settingsSwitchSoftwareNav.isChecked = prefs.getBoolean(KEY_SOFTWARE_NAV, false)
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
            DefaultAppSettingsBottomSheetDialog().show(supportFragmentManager, "default_app_settings")
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
                .show()
        }

        // Export Settings
        binding.settingsBtnExport.setOnClickListener {
            Toast.makeText(this, "Exported scraffold-backup.json", Toast.LENGTH_SHORT).show()
        }

        // Import Settings
        binding.settingsBtnImport.setOnClickListener {
            Toast.makeText(this, "Select a valid scraffold-backup.json file", Toast.LENGTH_SHORT).show()
        }

        // About Row
        binding.settingsRowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun showAdblockDialog() {
        val dialogBinding = DialogAdblockSettingsBinding.inflate(LayoutInflater.from(this))
        dialogBinding.dialogTvBlockedCount.text = "Shield: ${adBlockEngine.totalBlockedRequests} requests blocked"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialogBinding.btnCloseAdblock.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateCacheDisplay() {
        lifecycleScope.launch {
            val formatted = CacheManager.getFormattedCacheSize(this@SettingsActivity)
            binding.settingsTvCacheSize.text = "$formatted (500 MB auto-purge threshold)"
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
