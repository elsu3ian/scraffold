package net.mangadot.scaffold.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.databinding.ActivitySettingsBinding
import net.mangadot.scaffold.engine.cache.CacheManager
import net.mangadot.scaffold.ui.about.AboutActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateCacheDisplay()
    }

    private fun setupListeners() {
        binding.settingsBtnBack.setOnClickListener {
            finish()
        }

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

        binding.settingsRowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun updateCacheDisplay() {
        lifecycleScope.launch {
            val formatted = CacheManager.getFormattedCacheSize(this@SettingsActivity)
            binding.settingsTvCacheSize.text = "$formatted (500 MB auto-purge threshold)"
        }
    }
}
