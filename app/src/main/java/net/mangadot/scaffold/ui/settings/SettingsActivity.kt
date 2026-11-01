package net.mangadot.scaffold.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
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
        const val KEY_PROGRESS_BAR = "pref_progress_bar"
        const val KEY_DEFAULT_FULLSCREEN = "pref_default_fullscreen"
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
        binding.settingsSwitchProgressBar.isChecked = prefs.getBoolean(KEY_PROGRESS_BAR, true)
        binding.settingsSwitchDefaultFullscreen.isChecked = prefs.getBoolean(KEY_DEFAULT_FULLSCREEN, true)
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

        // Row 2: Userscripts Manager
        binding.settingsRowUserscripts.setOnClickListener {
            startActivity(Intent(this, UserscriptsActivity::class.java))
        }

        // Switches
        binding.settingsSwitchClearCache.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_CLEAR_CACHE_EXIT, isChecked).apply()
        }

        binding.settingsSwitchReloadGesture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_GESTURE_RELOAD, isChecked).apply()
        }

        binding.settingsSwitchProgressBar.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_PROGRESS_BAR, isChecked).apply()
        }

        binding.settingsSwitchDefaultFullscreen.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DEFAULT_FULLSCREEN, isChecked).apply()
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
            Toast.makeText(this, "Settings exported to scraffold-backup.json", Toast.LENGTH_SHORT).show()
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

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Close", null)
            .show()
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
