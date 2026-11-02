package net.mangadot.scaffold.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.databinding.LayoutBottomSheetAppSwitcherBinding
import net.mangadot.scaffold.ui.shelf.AppAdapter

class AppSwitcherBottomSheetDialog(
    private val apps: List<WebAppEntity>,
    private val onAppSelected: (WebAppEntity) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: LayoutBottomSheetAppSwitcherBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutBottomSheetAppSwitcherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvSwitcherCount.text = "${apps.size} Apps on Shelf"

        val adapter = AppAdapter(
            onAppClick = { app ->
                dismiss()
                onAppSelected(app)
            },
            onAppOptionsClick = {}
        )
        adapter.currentLayoutMode = AppAdapter.LayoutMode.LIST

        binding.rvSwitcherApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSwitcherApps.adapter = adapter
        adapter.submitList(apps)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
