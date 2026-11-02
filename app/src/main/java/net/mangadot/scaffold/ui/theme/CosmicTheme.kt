package net.mangadot.scaffold.ui.theme

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Preset glow colors, from NEBULA_PRESETS in the MangaDot Cosmic userscript.
 */
data class CosmicPalette(
    val name: String,
    val glow1: Int,
    val glow2: Int,
    val glow3: Int
)

data class CosmicTheme(
    val glow1: Int = DEFAULT_PALETTE.glow1,
    val glow2: Int = DEFAULT_PALETTE.glow2,
    val glow3: Int = DEFAULT_PALETTE.glow3,
    val glowIntensity: Int = 100,
    val gradientRotation: Int = 160,
    val radiantBackground: Boolean = true,
    val cosmicStyling: Boolean = true,
    val dimInReader: Boolean = true,
    val autoSync: Boolean = false,
    val accent: Int = CosmicAccent.DEFAULT,
    val staticBackground: Int = DEFAULT_STATIC_BACKGROUND
) {

    fun withPalette(palette: CosmicPalette): CosmicTheme {
        return copy(
            glow1 = palette.glow1,
            glow2 = palette.glow2,
            glow3 = palette.glow3,
            autoSync = false
        )
    }

    fun usesPalette(palette: CosmicPalette): Boolean {
        return glow1 == palette.glow1 && glow2 == palette.glow2 && glow3 == palette.glow3
    }

    /** Glow 1 is the accent itself; the other two are its hue shifted by +38 and -38 degrees. */
    fun syncedTo(accent: Int): CosmicTheme {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent, hsl)
        val saturation = hsl[1].coerceIn(0.45f, 0.85f)
        return copy(
            glow1 = accent,
            glow2 = ColorUtils.HSLToColor(
                floatArrayOf((hsl[0] + 38f) % 360f, saturation, (hsl[2] + 0.06f).coerceIn(0.35f, 0.75f))
            ),
            glow3 = ColorUtils.HSLToColor(
                floatArrayOf((hsl[0] + 322f) % 360f, saturation, (hsl[2] - 0.04f).coerceIn(0.25f, 0.70f))
            )
        )
    }

    /** Puts the glow colors and geometry back; the switches stay as they are. */
    fun withDefaultGlows(): CosmicTheme {
        val defaults = CosmicTheme()
        return copy(
            glow1 = defaults.glow1,
            glow2 = defaults.glow2,
            glow3 = defaults.glow3,
            glowIntensity = defaults.glowIntensity,
            gradientRotation = defaults.gradientRotation,
            autoSync = false
        )
    }

    fun applyBackground(view: View, dimmed: Boolean = false) {
        if (radiantBackground) {
            view.background = NebulaDrawable(this).apply {
                if (dimmed) setAlpha(DIMMED_ALPHA)
            }
        } else {
            view.setBackgroundColor(staticBackground)
        }
    }

    companion object {
        private const val DIMMED_ALPHA = 31 // the userscript's 0.12 opacity
        val DEFAULT_STATIC_BACKGROUND: Int = Color.parseColor("#09090B") // @color/bg_primary

        val PALETTES = listOf(
            CosmicPalette("Cosmic", Color.parseColor("#9333EA"), Color.parseColor("#6366F1"), Color.parseColor("#A855F7")),
            CosmicPalette("Mint Void", Color.parseColor("#10B981"), Color.parseColor("#06B6D4"), Color.parseColor("#34D399")),
            CosmicPalette("Abyssal", Color.parseColor("#3B82F6"), Color.parseColor("#6366F1"), Color.parseColor("#0EA5E9")),
            CosmicPalette("Crimson", Color.parseColor("#EF4444"), Color.parseColor("#EC4899"), Color.parseColor("#F97316")),
            CosmicPalette("Sunset", Color.parseColor("#F59E0B"), Color.parseColor("#EC4899"), Color.parseColor("#8B5CF6"))
        )

        private val DEFAULT_PALETTE: CosmicPalette
            get() = PALETTES.first()
    }
}

object CosmicThemeStore {
    private const val PREFS_NAME = "scraffold_cosmic"
    private const val KEY_GLOW_1 = "glow_1"
    private const val KEY_GLOW_2 = "glow_2"
    private const val KEY_GLOW_3 = "glow_3"
    private const val KEY_INTENSITY = "glow_intensity"
    private const val KEY_ROTATION = "gradient_rotation"
    private const val KEY_RADIANT = "radiant_background"
    private const val KEY_STYLING = "cosmic_styling"
    private const val KEY_DIM = "dim_in_reader"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_ACCENT = "accent"
    private const val KEY_STATIC_BACKGROUND = "static_background"

    fun load(context: Context): CosmicTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = CosmicTheme()
        return CosmicTheme(
            glow1 = prefs.getInt(KEY_GLOW_1, defaults.glow1),
            glow2 = prefs.getInt(KEY_GLOW_2, defaults.glow2),
            glow3 = prefs.getInt(KEY_GLOW_3, defaults.glow3),
            glowIntensity = prefs.getInt(KEY_INTENSITY, defaults.glowIntensity),
            gradientRotation = prefs.getInt(KEY_ROTATION, defaults.gradientRotation),
            radiantBackground = prefs.getBoolean(KEY_RADIANT, defaults.radiantBackground),
            cosmicStyling = prefs.getBoolean(KEY_STYLING, defaults.cosmicStyling),
            dimInReader = prefs.getBoolean(KEY_DIM, defaults.dimInReader),
            autoSync = prefs.getBoolean(KEY_AUTO_SYNC, defaults.autoSync),
            accent = prefs.getInt(KEY_ACCENT, defaults.accent),
            staticBackground = prefs.getInt(KEY_STATIC_BACKGROUND, defaults.staticBackground)
        )
    }

    fun save(context: Context, theme: CosmicTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_GLOW_1, theme.glow1)
            .putInt(KEY_GLOW_2, theme.glow2)
            .putInt(KEY_GLOW_3, theme.glow3)
            .putInt(KEY_INTENSITY, theme.glowIntensity)
            .putInt(KEY_ROTATION, theme.gradientRotation)
            .putBoolean(KEY_RADIANT, theme.radiantBackground)
            .putBoolean(KEY_STYLING, theme.cosmicStyling)
            .putBoolean(KEY_DIM, theme.dimInReader)
            .putBoolean(KEY_AUTO_SYNC, theme.autoSync)
            .putInt(KEY_ACCENT, theme.accent)
            .putInt(KEY_STATIC_BACKGROUND, theme.staticBackground)
            .apply()
    }
}
