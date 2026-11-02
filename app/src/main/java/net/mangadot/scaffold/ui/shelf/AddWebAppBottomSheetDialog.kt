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
import kotlinx.coroutines.launch
import net.mangadot.scaffold.data.db.AppDatabase
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.repository.WebAppRepository
import net.mangadot.scaffold.data.settings.effectiveSettings
import net.mangadot.scaffold.data.settings.withSettings
import net.mangadot.scaffold.databinding.LayoutBottomSheetAddWebappBinding
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.ui.theme.CosmicBottomSheet

/** Asks for an app's details, filled in with [name] and [url] when they're known, and adds it to the shelf. */
fun FragmentActivity.showAddWebApp(name: String = "", url: String = "") {
    val repository = WebAppRepository(AppDatabase.getInstance(this).webAppDao())
    val sheet = AddWebAppBottomSheetDialog(name, url) { chosenName, chosenUrl, userscripts, pinchZoom, customIcon ->
        lifecycleScope.launch {
            val blank = WebAppEntity(name = chosenName, url = chosenUrl, userscriptsEnabled = userscripts)
            val app = if (customIcon == null) blank else blank.copy(iconUrl = AppIcons.save(this@showAddWebApp, blank.id, customIcon, custom = true))
            repository.insertWebApp(
                if (pinchZoom) app else app.copy(overrideGlobal = true)
                    .withSettings(app.effectiveSettings(this@showAddWebApp).copy(pinchZoom = false))
            )
            Toast.makeText(this@showAddWebApp, "Added $chosenName to homepage", Toast.LENGTH_SHORT).show()
        }
    }
    sheet.show(supportFragmentManager, "add_webapp")
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
            var name = binding.etAddName.text.toString().trim()

            if (url.isEmpty() || url == "https://") {
                Toast.makeText(requireContext(), "Please enter a valid website URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (name.isEmpty()) {
                // Auto-generate name from host
                name = try {
                    val host = android.net.Uri.parse(url).host ?: "WebApp"
                    host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
                } catch (e: Exception) {
                    "WebApp"
                }
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
