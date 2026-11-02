package net.mangadot.scaffold.ui.shelf

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mangadot.scaffold.databinding.LayoutBottomSheetAddWebappBinding

class AddWebAppBottomSheetDialog(
    private val onSave: (name: String, url: String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: LayoutBottomSheetAddWebappBinding? = null
    private val binding get() = _binding!!

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
            Toast.makeText(requireContext(), "Upload custom icon from storage", Toast.LENGTH_SHORT).show()
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
            onSave(name, url)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
