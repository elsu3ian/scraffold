package net.mangadot.scaffold.ui.shelf

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.data.settings.effectiveSettings
import net.mangadot.scaffold.data.settings.withSettings
import net.mangadot.scaffold.databinding.LayoutBottomSheetAddWebappBinding
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.engine.ManifestInstall
import net.mangadot.scaffold.engine.withManifest
import net.mangadot.scaffold.ui.theme.CosmicBottomSheet

/** Outlives the screen that added the app, so a slow manifest still reaches it. */
private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Asks for an app's details, filled in with [name] and [url] when they're known, and adds it to the shelf. */
fun FragmentActivity.showAddWebApp(name: String = "", url: String = "") {
    val repository = WebAppRepository(AppDatabase.getInstance(this).webAppDao())
    val sheet = AddWebAppBottomSheetDialog(name, url) { typedName, chosenUrl, userscripts, pinchZoom, customIcon ->
        // A name the user wrote stays. Otherwise the site's manifest names it, once it's read
        val keepName = typedName.isNotEmpty() && typedName != name
        val chosenName = typedName.ifEmpty { nameFromHost(chosenUrl) }
        lifecycleScope.launch {
            // An empty icon path keeps the shelf from fetching its own icon while the manifest is read
            val blank = WebAppEntity(name = chosenName, url = chosenUrl, iconUrl = "", userscriptsEnabled = userscripts)
            val app = if (customIcon == null) blank else blank.copy(iconUrl = AppIcons.save(this@showAddWebApp, blank.id, customIcon, custom = true))
            repository.insertWebApp(
                if (pinchZoom) app else app.copy(overrideGlobal = true)
                    .withSettings(app.effectiveSettings(this@showAddWebApp).copy(pinchZoom = false))
            )
            Toast.makeText(this@showAddWebApp, "Added $chosenName to homepage", Toast.LENGTH_SHORT).show()
            installFromManifest(repository, app.id, chosenUrl, keepName)
        }
    }
    sheet.show(supportFragmentManager, "add_webapp")
}

/**
 * Reads the site's manifest for its name, start page, scope, colors and icon, so the app opens and looks like the
 * site's own. A site without one is left as it was added. Nothing the user chose is replaced.
 */
private fun FragmentActivity.installFromManifest(repository: WebAppRepository, appId: String, url: String, keepName: Boolean) {
    val context = applicationContext
    installScope.launch {
        val site = AppIcons.fetchSite(context, url)
        val added = repository.getWebAppById(appId).first() ?: return@launch
        if (site.manifest == null) ManifestInstall.markPending(context, appId, keepName)
        var updated = site.manifest?.let { added.withManifest(it, keepName) } ?: added
        // A picked icon is never replaced. With none found, the shelf and the page get their usual tries
        if (added.iconUrl == "") {
            updated = updated.copy(iconUrl = site.icon?.let { AppIcons.save(context, appId, it) })
        }
        if (updated != added) repository.updateWebApp(updated)
    }
}

private fun nameFromHost(url: String): String {
    return try {
        val host = Uri.parse(url).host ?: "WebApp"
        host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        "WebApp"
    }
}

class AddWebAppBottomSheetDialog(
    private val initialName: String = "",
    private val initialUrl: String = "",
    private val onSave: (name: String, url: String, userscripts: Boolean, pinchZoom: Boolean, customIcon: Bitmap?) -> Unit
) : CosmicBottomSheet() {

    private var _binding: LayoutBottomSheetAddWebappBinding? = null
    private val binding get() = _binding!!
    private var customIcon: Bitmap? = null

    private val pickIcon = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                customIcon = AppIcons.decode(requireContext(), uri)
                if (customIcon == null) {
                    Toast.makeText(requireContext(), "Couldn't read that image", Toast.LENGTH_SHORT).show()
                } else {
                    binding.btnAddUploadIcon.text = "Selected"
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutBottomSheetAddWebappBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            if (initialName.isNotEmpty()) binding.etAddName.setText(initialName)
            if (initialUrl.isNotEmpty()) binding.etAddUrl.setText(initialUrl)
        }

        binding.btnAddClose.setOnClickListener {
            dismiss()
        }

        binding.btnAddCancel.setOnClickListener {
            dismiss()
        }

        binding.btnAddUploadIcon.setOnClickListener {
            pickIcon.launch("image/*")
        }

        binding.btnAddSave.setOnClickListener {
            val url = binding.etAddUrl.text.toString().trim()
            // Left empty, the app is named by the site's manifest or its host
            val name = binding.etAddName.text.toString().trim()

            if (url.isEmpty() || url == "https://") {
                Toast.makeText(requireContext(), "Please enter a valid website URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dismiss()
            onSave(name, url, binding.switchAddUserscript.isChecked, binding.switchAddZoom.isChecked, customIcon)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
