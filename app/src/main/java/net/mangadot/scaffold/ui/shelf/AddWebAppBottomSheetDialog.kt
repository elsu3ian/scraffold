package net.mangadot.scaffold.ui.shelf

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.mangadot.scaffold.databinding.LayoutBottomSheetAddWebappBinding
import net.mangadot.scaffold.engine.AppIcons
import net.mangadot.scaffold.ui.theme.CosmicBottomSheet

class AddWebAppBottomSheetDialog(
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
