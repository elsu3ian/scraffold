package net.mangadot.scaffold.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.data.settings.GlobalWebSettingsStore
import net.mangadot.scaffold.data.settings.WebAppSettings
import net.mangadot.scaffold.data.settings.ownSettings
import net.mangadot.scaffold.data.settings.withSettings
import net.mangadot.scaffold.databinding.ActivityWebAppSettingsBinding
import net.mangadot.scaffold.ui.theme.CosmicActivity
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.applyHeader

/**
 * One app's settings, or the global defaults when no app id is passed.
 * Changes apply on Save and take effect the next time the app opens.
 */
class WebAppSettingsActivity : CosmicActivity() {

    private lateinit var binding: ActivityWebAppSettingsBinding
    private lateinit var repository: WebAppRepository
    private var app: WebAppEntity? = null
    private val toggles = mutableMapOf<String, MaterialSwitch>()
    private val toggleRows = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebAppSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CosmicThemeStore.load(this).let { theme ->
            theme.applyBackground(binding.appSettingsRootLayout)
            theme.applyHeader(binding.appSettingsHeaderLayout, binding.appSettingsScroll)
        }
        repository = WebAppRepository(AppDatabase.getInstance(this).webAppDao())

        ViewCompat.setOnApplyWindowInsetsListener(binding.appSettingsRootLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            binding.appSettingsHeaderLayout.setPadding(
                binding.appSettingsHeaderLayout.paddingLeft,
                bars.top + (12 * density).toInt(),
                binding.appSettingsHeaderLayout.paddingRight,
                (12 * density).toInt()
            )
            binding.appSettingsScroll.setPadding(
                binding.appSettingsScroll.paddingLeft,
                binding.appSettingsScroll.paddingTop,
                binding.appSettingsScroll.paddingRight,
                bars.bottom
            )
            insets
        }

        buildToggles()
        binding.appSettingsBtnBack.setOnClickListener { finish() }
        binding.appSettingsBtnSave.setOnClickListener { save() }
        binding.appSettingsSwitchOverride.setOnCheckedChangeListener { _, _ -> updateEnabledState() }

        val appId = intent.getStringExtra(EXTRA_APP_ID)
        if (appId == null) {
            binding.appSettingsTvUrl.text = "https://*"
            binding.appSettingsEtLabel.setText("Global Defaults")
            binding.appSettingsEtLabel.isEnabled = false
            binding.appSettingsRowOverride.visibility = View.GONE
            show(GlobalWebSettingsStore.load(this))
        } else {
            lifecycleScope.launch {
                val loaded = repository.getWebAppById(appId).firstOrNull()
                if (loaded == null) {
                    finish()
                    return@launch
                }
                app = loaded
                binding.appSettingsTvUrl.text = loaded.url
                binding.appSettingsEtLabel.setText(loaded.name)
                binding.appSettingsSwitchOverride.isChecked = loaded.overrideGlobal
                show(if (loaded.overrideGlobal) loaded.ownSettings() else GlobalWebSettingsStore.load(this@WebAppSettingsActivity))
            }
        }
        updateEnabledState()
    }

    private fun buildToggles() {
        addToggle(binding.appSettingsGroupSecurity, KEY_JS, "Allow Javascript")
        addToggle(binding.appSettingsGroupSecurity, KEY_THIRD_PARTY, "Block third-party requests", "Blocks files from other sites. Can break sites that use a CDN.")
        addToggle(binding.appSettingsGroupSecurity, KEY_HTTP, "Allow HTTP", "Off upgrades links to https")
        addToggle(binding.appSettingsGroupSecurity, KEY_LOCATION, "Allow location access")
        addToggle(binding.appSettingsGroupSecurity, KEY_DRM, "Allow DRM content")
        addToggle(binding.appSettingsGroupCookies, KEY_COOKIES, "Accept cookies")
        addToggle(binding.appSettingsGroupCookies, KEY_THIRD_COOKIES, "Accept third-party cookies")
        addToggle(binding.appSettingsGroupCookies, KEY_ADBLOCK, "Enable adblock")
        addToggle(binding.appSettingsGroupDisplay, KEY_FULLSCREEN, "True Fullscreen Mode", "Hide Android status & navigation bars")
        addToggle(binding.appSettingsGroupDisplay, KEY_ZOOM, "Enable Pinch-To-Zoom")
        addToggle(binding.appSettingsGroupDisplay, KEY_DESKTOP, "Request desktop version")
        addToggle(binding.appSettingsGroupDisplay, KEY_DATA_SAVER, "Do not load images (Data Saver)")
    }

    private fun addToggle(group: LinearLayout, key: String, title: String, subtitle: String? = null) {
        val row = layoutInflater.inflate(R.layout.item_settings_toggle, group, false)
        row.findViewById<TextView>(R.id.toggle_tv_title).text = title
        row.findViewById<TextView>(R.id.toggle_tv_subtitle).apply {
            text = subtitle
            visibility = if (subtitle == null) View.GONE else View.VISIBLE
        }
        toggles[key] = row.findViewById(R.id.toggle_switch)
        toggleRows += row
        group.addView(row)
    }

    private fun updateEnabledState() {
        val editable = intent.getStringExtra(EXTRA_APP_ID) == null || binding.appSettingsSwitchOverride.isChecked
        toggleRows.forEach { it.alpha = if (editable) 1f else 0.45f }
        toggles.values.forEach { it.isEnabled = editable }
    }

    private fun show(settings: WebAppSettings) {
        toggles.getValue(KEY_JS).isChecked = settings.javascript
        toggles.getValue(KEY_THIRD_PARTY).isChecked = settings.blockThirdParty
        toggles.getValue(KEY_HTTP).isChecked = settings.allowHttp
        toggles.getValue(KEY_LOCATION).isChecked = settings.allowLocation
        toggles.getValue(KEY_DRM).isChecked = settings.allowDrm
        toggles.getValue(KEY_COOKIES).isChecked = settings.acceptCookies
        toggles.getValue(KEY_THIRD_COOKIES).isChecked = settings.thirdPartyCookies
        toggles.getValue(KEY_ADBLOCK).isChecked = settings.adblock
        toggles.getValue(KEY_FULLSCREEN).isChecked = settings.fullscreen
        toggles.getValue(KEY_ZOOM).isChecked = settings.pinchZoom
        toggles.getValue(KEY_DESKTOP).isChecked = settings.desktop
        toggles.getValue(KEY_DATA_SAVER).isChecked = settings.dataSaver
    }

    private fun current() = WebAppSettings(
        javascript = toggles.getValue(KEY_JS).isChecked,
        blockThirdParty = toggles.getValue(KEY_THIRD_PARTY).isChecked,
        allowHttp = toggles.getValue(KEY_HTTP).isChecked,
        allowLocation = toggles.getValue(KEY_LOCATION).isChecked,
        allowDrm = toggles.getValue(KEY_DRM).isChecked,
        acceptCookies = toggles.getValue(KEY_COOKIES).isChecked,
        thirdPartyCookies = toggles.getValue(KEY_THIRD_COOKIES).isChecked,
        adblock = toggles.getValue(KEY_ADBLOCK).isChecked,
        fullscreen = toggles.getValue(KEY_FULLSCREEN).isChecked,
        pinchZoom = toggles.getValue(KEY_ZOOM).isChecked,
        desktop = toggles.getValue(KEY_DESKTOP).isChecked,
        dataSaver = toggles.getValue(KEY_DATA_SAVER).isChecked
    )

    private fun save() {
        val existing = app
        if (existing == null) {
            GlobalWebSettingsStore.save(this, current())
            Toast.makeText(this, "Saved global defaults", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val label = binding.appSettingsEtLabel.text.toString().trim().ifEmpty { existing.name }
        val override = binding.appSettingsSwitchOverride.isChecked
        val updated = existing.copy(name = label, overrideGlobal = override).let {
            if (override) it.withSettings(current()) else it
        }
        lifecycleScope.launch {
            repository.updateWebApp(updated)
            Toast.makeText(this@WebAppSettingsActivity, "Saved ${updated.name} settings", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_APP_ID = "extra_app_id"
        private const val KEY_JS = "js"
        private const val KEY_THIRD_PARTY = "third_party"
        private const val KEY_HTTP = "http"
        private const val KEY_LOCATION = "location"
        private const val KEY_DRM = "drm"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_THIRD_COOKIES = "third_cookies"
        private const val KEY_ADBLOCK = "adblock"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_DESKTOP = "desktop"
        private const val KEY_DATA_SAVER = "data_saver"
    }
}
