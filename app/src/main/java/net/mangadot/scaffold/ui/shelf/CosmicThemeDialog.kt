package net.mangadot.scaffold.ui.shelf

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import net.mangadot.scaffold.R
import net.mangadot.scaffold.databinding.LayoutDialogCosmicThemeBinding
import net.mangadot.scaffold.ui.theme.CosmicAccent
import net.mangadot.scaffold.ui.theme.CosmicColorPicker
import net.mangadot.scaffold.ui.theme.CosmicPalette
import net.mangadot.scaffold.ui.theme.CosmicTheme
import net.mangadot.scaffold.ui.theme.CosmicThemeStore
import net.mangadot.scaffold.ui.theme.NebulaDrawable
import net.mangadot.scaffold.ui.theme.applyDialogSurface

/**
 * Every edit is saved and applied straight away through [onThemeChanged], as in the
 * userscript's editor. Closing the dialog keeps what is on screen.
 */
class CosmicThemeDialog(
    context: Context,
    private val onThemeChanged: (CosmicTheme) -> Unit,
    private val onAccentChanged: () -> Unit
) : Dialog(context) {

    private val binding: LayoutDialogCosmicThemeBinding
    private var theme: CosmicTheme = CosmicThemeStore.load(context)
    private var isRendering = false
    private val paletteChips = mutableMapOf<String, TextView>()
    private val accent = CosmicAccent.color

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = LayoutDialogCosmicThemeBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        // Make window background transparent so custom border and corners render cleanly
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Lighter than the prototype's backdrop so the live nebula stays visible
        window?.setDimAmount(0.55f)

        setupPreviewStrip()
        setupPaletteChips()
        setupListeners()
        render()
    }

    override fun onStart() {
        super.onStart()
        val metrics = context.resources.displayMetrics
        val maxWidth = (MAX_WIDTH_DP * metrics.density).toInt()
        val width = minOf((metrics.widthPixels * WIDTH_FRACTION).toInt(), maxWidth)
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setupPreviewStrip() {
        val radius = PREVIEW_RADIUS_DP * context.resources.displayMetrics.density
        binding.layoutCosmicPreview.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        binding.layoutCosmicPreview.clipToOutline = true
    }

    private fun setupPaletteChips() {
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (10 * density).toInt()
        val verticalPadding = (6 * density).toInt()

        CosmicTheme.PALETTES.forEach { palette ->
            val chip = TextView(context).apply {
                text = palette.name
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                compoundDrawablePadding = (6 * density).toInt()
                isClickable = true
                isFocusable = true
                setCompoundDrawablesRelative(createChipSwatch(palette, density), null, null, null)
                setOnClickListener { updateTheme(theme.withPalette(palette)) }
            }
            paletteChips[palette.name] = chip
            binding.groupCosmicPalettes.addView(chip)
        }
    }

    private fun createChipSwatch(palette: CosmicPalette, density: Float): GradientDrawable {
        val size = (10 * density).toInt()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.glow1, palette.glow2, palette.glow3)
        ).apply {
            shape = GradientDrawable.OVAL
            setBounds(0, 0, size, size)
        }
    }

    private fun setupListeners() {
        binding.btnCosmicClose.setOnClickListener {
            dismiss()
        }

        binding.btnCosmicSync.background = GradientDrawable().apply {
            cornerRadius = 8 * context.resources.displayMetrics.density
            setColor(ColorUtils.setAlphaComponent(accent, SELECTED_FILL_ALPHA))
            setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 128))
        }
        binding.btnCosmicSync.setOnClickListener {
            updateTheme(theme.syncedTo(accent))
        }

        binding.btnCosmicReset.setOnClickListener {
            updateTheme(theme.withDefaultGlows())
        }

        binding.btnCosmicRotate.setOnClickListener {
            updateTheme(theme.copy(gradientRotation = (theme.gradientRotation + ROTATE_STEP) % 360))
        }

        binding.switchCosmicRadiant.setOnCheckedChangeListener { _, isChecked ->
            if (!isRendering) updateTheme(theme.copy(radiantBackground = isChecked))
        }

        binding.switchCosmicStyling.setOnCheckedChangeListener { _, isChecked ->
            if (!isRendering) updateTheme(theme.copy(cosmicStyling = isChecked))
        }

        binding.switchCosmicDim.setOnCheckedChangeListener { _, isChecked ->
            if (!isRendering) updateTheme(theme.copy(dimInReader = isChecked))
        }

        binding.switchCosmicAutoSync.setOnCheckedChangeListener { _, isChecked ->
            if (!isRendering) {
                val updated = theme.copy(autoSync = isChecked)
                updateTheme(if (isChecked) updated.syncedTo(accent) else updated)
            }
        }

        binding.seekCosmicIntensity.setOnSeekBarChangeListener(createSeekListener { progress ->
            updateTheme(theme.copy(glowIntensity = progress))
        })

        binding.seekCosmicRotation.setOnSeekBarChangeListener(createSeekListener { progress ->
            updateTheme(theme.copy(gradientRotation = progress))
        })

        bindSwatch(binding.viewCosmicSwatch1, "Glow 1", { theme.glow1 }) { color -> theme.copy(glow1 = color, autoSync = false) }
        bindSwatch(binding.viewCosmicSwatch2, "Glow 2", { theme.glow2 }) { color -> theme.copy(glow2 = color, autoSync = false) }
        bindSwatch(binding.viewCosmicSwatch3, "Glow 3", { theme.glow3 }) { color -> theme.copy(glow3 = color, autoSync = false) }

        binding.rowCosmicAccent.setOnClickListener {
            CosmicColorPicker(
                context,
                title = "Accent color",
                initial = theme.accent,
                defaultColor = CosmicAccent.DEFAULT,
                onDone = { color ->
                    if (color != theme.accent) {
                        CosmicThemeStore.save(context, theme.copy(accent = color))
                        CosmicAccent.color = color
                        onAccentChanged()
                    }
                }
            ).show()
        }

        bindHexField(binding.etCosmicGlow1, { theme.glow1 }) { color ->
            theme.copy(glow1 = color, autoSync = false)
        }
        bindHexField(binding.etCosmicGlow2, { theme.glow2 }) { color ->
            theme.copy(glow2 = color, autoSync = false)
        }
        bindHexField(binding.etCosmicGlow3, { theme.glow3 }) { color ->
            theme.copy(glow3 = color, autoSync = false)
        }
    }

    private fun bindSwatch(
        swatch: View,
        title: String,
        currentColor: () -> Int,
        withColor: (Int) -> CosmicTheme
    ) {
        swatch.setOnClickListener {
            CosmicColorPicker(
                context,
                title = title,
                initial = currentColor(),
                onPreview = { color -> updateTheme(withColor(color)) },
                onDone = { color -> updateTheme(withColor(color)) }
            ).show()
        }
    }

    private fun createSeekListener(onChanged: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChanged(progress / SLIDER_STEP * SLIDER_STEP)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    private fun bindHexField(
        field: EditText,
        currentColor: () -> Int,
        withColor: (Int) -> CosmicTheme
    ) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isRendering) return
                val text = s?.toString().orEmpty().trim().let {
                    if (it.isEmpty() || it.startsWith("#")) it else "#$it"
                }
                if (HEX_COLOR.matches(text)) {
                    val color = Color.parseColor(text)
                    if (color != currentColor()) updateTheme(withColor(color))
                }
            }
        })

        // A half-typed value is dropped once the field loses focus
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) field.setText(formatHex(currentColor()))
        }
    }

    private fun updateTheme(updated: CosmicTheme) {
        theme = updated
        CosmicThemeStore.save(context, updated)
        render()
        onThemeChanged(updated)
    }

    private fun render() {
        isRendering = true

        theme.applyDialogSurface(window, binding.root, R.drawable.bg_dialog_cosmic)
        binding.layoutCosmicPreview.background = NebulaDrawable(theme)
        binding.tvCosmicPreviewLabel.text =
            "PREVIEW · ${theme.gradientRotation}° · ${theme.glowIntensity}%"
        binding.tvCosmicIntensity.text = "${theme.glowIntensity}%"
        binding.tvCosmicRotation.text = "${theme.gradientRotation}°"
        binding.seekCosmicIntensity.progress = theme.glowIntensity
        binding.seekCosmicRotation.progress = theme.gradientRotation
        binding.switchCosmicRadiant.isChecked = theme.radiantBackground
        binding.switchCosmicStyling.isChecked = theme.cosmicStyling
        binding.switchCosmicDim.isChecked = theme.dimInReader
        binding.switchCosmicAutoSync.isChecked = theme.autoSync

        binding.tvCosmicAccentHex.text = formatHex(theme.accent)
        binding.viewCosmicAccentSwatch.background = GradientDrawable().apply {
            cornerRadius = 6 * context.resources.displayMetrics.density
            setColor(theme.accent)
        }

        applyDot(binding.viewPreviewDot1, theme.glow1)
        applyDot(binding.viewPreviewDot2, theme.glow2)
        applyDot(binding.viewPreviewDot3, theme.glow3)

        renderGlow(binding.etCosmicGlow1, binding.viewCosmicSwatch1, theme.glow1)
        renderGlow(binding.etCosmicGlow2, binding.viewCosmicSwatch2, theme.glow2)
        renderGlow(binding.etCosmicGlow3, binding.viewCosmicSwatch3, theme.glow3)

        CosmicTheme.PALETTES.forEach { palette ->
            paletteChips[palette.name]?.let { renderChip(it, palette) }
        }

        isRendering = false
    }

    private fun renderGlow(field: EditText, swatch: View, color: Int) {
        val hex = formatHex(color)
        // Leave the field alone while it is being typed in, so the cursor doesn't jump
        if (!field.hasFocus() && !field.text.toString().equals(hex, ignoreCase = true)) {
            field.setText(hex)
        }
        swatch.background = GradientDrawable().apply {
            cornerRadius = 6 * context.resources.displayMetrics.density
            setColor(color)
        }
    }

    private fun renderChip(chip: TextView, palette: CosmicPalette) {
        val density = context.resources.displayMetrics.density
        val isSelected = theme.usesPalette(palette)
        chip.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        chip.setTextColor(
            ContextCompat.getColor(context, if (isSelected) R.color.text_primary else R.color.text_secondary)
        )
        chip.background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(
                if (isSelected) ColorUtils.setAlphaComponent(accent, SELECTED_FILL_ALPHA)
                else CHIP_FILL
            )
            setStroke(
                density.toInt().coerceAtLeast(1),
                if (isSelected) accent else ContextCompat.getColor(context, R.color.border_primary)
            )
        }
    }

    private fun applyDot(view: View, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun formatHex(color: Int): String {
        return String.format("#%06x", color and 0xFFFFFF)
    }

    private companion object {
        const val MAX_WIDTH_DP = 390
        const val WIDTH_FRACTION = 0.94f
        const val PREVIEW_RADIUS_DP = 10
        const val SLIDER_STEP = 5
        const val ROTATE_STEP = 45
        const val SELECTED_FILL_ALPHA = 38
        val CHIP_FILL = Color.parseColor("#18181B")
        val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}
