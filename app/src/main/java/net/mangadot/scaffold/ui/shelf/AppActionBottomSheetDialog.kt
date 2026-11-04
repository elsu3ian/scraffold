package net.mangadot.scaffold.ui.shelf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import net.mangadot.scaffold.ui.theme.CosmicBottomSheet
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.settings.WebAppSettings
import net.mangadot.scaffold.data.settings.effectiveSettings
import net.mangadot.scaffold.data.settings.withSettings
import net.mangadot.scaffold.databinding.LayoutBottomSheetAppActionsBinding
import net.mangadot.scaffold.engine.AppData
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.AppProfiles
import net.mangadot.scaffold.ui.theme.showCosmic
import net.mangadot.scaffold.ui.settings.WebAppSettingsActivity
import net.mangadot.scaffold.ui.userscript.UserscriptsActivity

class AppActionBottomSheetDialog(
    private val app: WebAppEntity,
    private val onLaunch: (WebAppEntity) -> Unit,
    private val onDelete: (WebAppEntity) -> Unit,
    private val onUpdate: (WebAppEntity) -> Unit
) : CosmicBottomSheet() {

    private var _binding: LayoutBottomSheetAppActionsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutBottomSheetAppActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sheetTvAppName.text = app.name
        binding.sheetTvAppUrl.text = app.url
        binding.sheetTvIconMonogram.text = extractMonogram(app.name)
        AppIcons.bind(binding.sheetIvAppIcon, binding.sheetTvIconMonogram, app)
        val settings = app.effectiveSettings(requireContext())
        binding.sheetSwitchFullscreen.isChecked = settings.fullscreen
        binding.sheetSwitchAdblock.isChecked = settings.adblock

        // Pick gradient based on app hash
        val drawables = listOf(
            R.drawable.bg_squircle_emerald,
            R.drawable.bg_squircle_purple,
            R.drawable.bg_squircle_orange,
            R.drawable.bg_squircle_cyan,
            R.drawable.bg_squircle_rose
        )
        val bgIndex = Math.abs(app.name.hashCode()) % drawables.size
        binding.sheetIconContainer.setBackgroundResource(drawables[bgIndex])

        // Action 1: Launch
        binding.sheetBtnLaunch.setOnClickListener {
            dismiss()
            onLaunch(app)
        }

        // Action 2: Fullscreen Toggle
        binding.sheetSwitchFullscreen.setOnCheckedChangeListener { _, isChecked ->
            applyOverride { it.copy(fullscreen = isChecked) }
        }

        binding.sheetSwitchAdblock.setOnCheckedChangeListener { _, isChecked ->
            applyOverride { it.copy(adblock = isChecked) }
        }

        binding.sheetBtnSettings.setOnClickListener {
            dismiss()
            startActivity(Intent(requireContext(), WebAppSettingsActivity::class.java).apply {
                putExtra(WebAppSettingsActivity.EXTRA_APP_ID, app.id)
            })
        }

        // Action 3: Userscripts
        binding.sheetBtnUserscripts.setOnClickListener {
            dismiss()
            val intent = Intent(requireContext(), UserscriptsActivity::class.java).apply {
                putExtra(UserscriptsActivity.EXTRA_FILTER_DOMAIN, Uri.parse(app.url).host ?: "")
            }
            startActivity(intent)
        }

        binding.sheetBtnClearCache.setOnClickListener {
            val context = requireContext().applicationContext
            AppData.clearCache(context, app)
            Toast.makeText(context, "Cache cleared for ${app.name}", Toast.LENGTH_SHORT).show()
        }

        binding.sheetBtnClearData.setOnClickListener {
            val context = requireContext()
            dismiss()
            val sharesLogin = !AppProfiles.keepsOwnLogin(context, app.id)
            val message = "${app.name} will be signed out, and what its site saved on this phone is erased. " +
                "It stays on your shelf with its settings." +
                if (sharesLogin) " Apps that share sign-ins stay signed in to their own sites." else ""
            AlertDialog.Builder(context)
                .setTitle("Clear ${app.name}'s data?")
                .setMessage(message)
                .setPositiveButton("Clear data") { _, _ ->
                    AppData.clearData(context.applicationContext, app)
                    Toast.makeText(context, "${app.name}'s data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .showCosmic()
        }

        // Action 4: Delete
        binding.sheetBtnDelete.setOnClickListener {
            dismiss()
            onDelete(app)
        }
    }

    /** A switch flipped here makes this app stop following the global defaults. */
    private fun applyOverride(change: (WebAppSettings) -> WebAppSettings) {
        val current = app.effectiveSettings(requireContext())
        val switches = current.copy(
            fullscreen = binding.sheetSwitchFullscreen.isChecked,
            adblock = binding.sheetSwitchAdblock.isChecked
        )
        onUpdate(app.copy(overrideGlobal = true).withSettings(change(switches)))
    }

    private fun extractMonogram(name: String): String {
        val words = name.trim().split("\\s+".toRegex())
        return when {
            words.size >= 2 -> "${words[0].firstOrNull() ?: 'S'}${words[1].firstOrNull() ?: 'C'}".uppercase()
            name.length >= 2 -> name.take(2).uppercase()
            name.isNotEmpty() -> name.first().uppercase()
            else -> "SC"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AppActionBottomSheetDialog"
    }
}
