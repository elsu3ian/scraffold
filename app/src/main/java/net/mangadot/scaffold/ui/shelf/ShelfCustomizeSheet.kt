package net.mangadot.scaffold.ui.shelf

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import net.mangadot.scaffold.R
import net.mangadot.scaffold.databinding.LayoutBottomSheetCustomizeBinding
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicBottomSheet

/** Stays open while sorting, so the shelf can be watched reordering behind it. */
class ShelfCustomizeSheet(
    private var sort: ShelfSort,
    private val onOpenTheme: () -> Unit,
    private val onSortChosen: (ShelfSort) -> Unit,
    private val onDirectionChanged: () -> Unit
) : CosmicBottomSheet() {

    private var _binding: LayoutBottomSheetCustomizeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutBottomSheetCustomizeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.customizeRowTheme.setOnClickListener {
            dismiss()
            onOpenTheme()
        }

        val accent = ColorStateList.valueOf(CosmicAccent.color)
        binding.customizeBtnDirection.imageTintList = accent
        binding.customizeBtnDirection.setOnClickListener {
            sort.setDescending(requireContext(), !sort.isDescending(requireContext()))
            render()
            onDirectionChanged()
        }

        options().forEach { (option, button) ->
            button.buttonTintList = accent
            button.isChecked = option == sort
            button.setOnClickListener {
                sort = option
                render()
                onSortChosen(option)
            }
        }
        render()
    }

    private fun options() = mapOf(
        ShelfSort.RECENT to binding.customizeSortRecent,
        ShelfSort.NAME to binding.customizeSortName,
        ShelfSort.DATE to binding.customizeSortDate,
        ShelfSort.CUSTOM to binding.customizeSortCustom
    )

    private fun render() {
        options().forEach { (option, button) ->
            button.text = option.label(option.isDescending(requireContext()))
        }
        val descending = sort.isDescending(requireContext())
        binding.customizeBtnDirection.apply {
            setImageResource(if (descending) R.drawable.ic_sort_descending else R.drawable.ic_sort_ascending)
            isEnabled = sort.hasDirection
            alpha = if (sort.hasDirection) 1f else DISABLED_ALPHA
            contentDescription = when {
                !sort.hasDirection -> "Custom order has no direction"
                descending -> "Descending, tap for ascending"
                else -> "Ascending, tap for descending"
            }
            TooltipCompat.setTooltipText(this, contentDescription)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ShelfCustomizeSheet"
        private const val DISABLED_ALPHA = 0.3f
    }
}
