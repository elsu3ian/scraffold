package net.mangadot.scaffold.ui.shelf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mangadot.scaffold.R
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.databinding.LayoutBottomSheetAppActionsBinding
import net.mangadot.scaffold.ui.userscript.UserscriptsActivity

class AppActionBottomSheetDialog(
    private val app: WebAppEntity,
    private val onLaunch: (WebAppEntity) -> Unit,
    private val onDelete: (WebAppEntity) -> Unit,
    private val onToggleFullscreen: (WebAppEntity, Boolean) -> Unit
) : BottomSheetDialogFragment() {

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
        binding.sheetSwitchFullscreen.isChecked = app.fullScreenMode

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
            onToggleFullscreen(app, isChecked)
        }

        // Action 3: Userscripts
        binding.sheetBtnUserscripts.setOnClickListener {
            dismiss()
            val intent = Intent(requireContext(), UserscriptsActivity::class.java).apply {
                putExtra(UserscriptsActivity.EXTRA_FILTER_DOMAIN, Uri.parse(app.url).host ?: "")
            }
            startActivity(intent)
        }

        // Action 4: Delete
        binding.sheetBtnDelete.setOnClickListener {
            dismiss()
            onDelete(app)
        }
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
