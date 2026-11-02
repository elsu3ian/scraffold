package net.mangadot.scaffold.ui.shelf

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import net.mangadot.scaffold.databinding.LayoutDialogCosmicThemeBinding

class CosmicThemeDialog(context: Context) : Dialog(context) {

    private val binding: LayoutDialogCosmicThemeBinding

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = LayoutDialogCosmicThemeBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        // Make window background transparent so custom border and corners render cleanly
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnCosmicClose.setOnClickListener {
            dismiss()
        }

        binding.chipPaletteCosmic.setOnClickListener {
            applyPalette("Cosmic")
        }

        binding.chipPaletteMint.setOnClickListener {
            applyPalette("Mint Void")
        }

        binding.chipPaletteAbyssal.setOnClickListener {
            applyPalette("Abyssal")
        }

        binding.chipPaletteCrimson.setOnClickListener {
            applyPalette("Crimson")
        }

        binding.chipPaletteSunset.setOnClickListener {
            applyPalette("Sunset")
        }
    }

    private fun applyPalette(name: String) {
        binding.tvCosmicPreviewLabel.text = "PREVIEW • $name • 100%"
        Toast.makeText(context, "Applied Cosmic Preset: $name", Toast.LENGTH_SHORT).show()
    }
}
