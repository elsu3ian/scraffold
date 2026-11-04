package net.mangadot.scaffold.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import net.mangadot.scaffold.R

/**
 * The card the shelf guide and the update offer are drawn on, and their buttons, so both read the same:
 * a raised surface ringed faintly in the accent, a quiet choice and an accent one.
 */
object CosmicCard {

    fun background(context: Context): Drawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = RADIUS_DP * density
            setColor(ContextCompat.getColor(context, R.color.bg_surface_elevated))
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(CosmicAccent.color, BORDER_ALPHA))
        }
    }

    fun title(context: Context, text: CharSequence) = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }

    fun body(context: Context) = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        textSize = 13f
        setLineSpacing(0f, 1.2f)
    }

    /** A button in the card's footer; [main] is the one the card is for, in the accent. */
    fun button(context: Context, text: CharSequence, main: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        if (main) {
            setTextColor(CosmicAccent.color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(pad, pad / 2, 0, pad / 2)
        } else {
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setPadding(pad, pad / 2, pad / 2, pad / 2)
        }
        setOnClickListener { onClick() }
    }

    private const val RADIUS_DP = 16
    private const val BORDER_ALPHA = 120
}
