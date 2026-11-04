package net.mangadot.scaffold.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.LayoutInflaterCompat
import com.google.android.material.materialswitch.MaterialSwitch
import net.mangadot.scaffold.R

/**
 * Shows a dialog on the same surface as the theme's own windows: Cosmic glass while Cosmic styling
 * is on, the plain card otherwise. Dialogs have their own inflater, so their buttons need the
 * accent set after showing too.
 */
fun AlertDialog.Builder.showCosmic(): AlertDialog = show().also { dialog ->
    listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach {
        dialog.getButton(it)?.setTextColor(CosmicAccent.color)
    }
    val panel = dialog.findViewById<View>(androidx.appcompat.R.id.parentPanel) ?: return@also
    val margin = (DIALOG_MARGIN_DP * dialog.context.resources.displayMetrics.density).toInt()
    // Material's own surface goes, keeping its distance from the screen's sides
    dialog.window?.setBackgroundDrawable(InsetDrawable(ColorDrawable(Color.TRANSPARENT), margin, 0, margin, 0))
    CosmicThemeStore.load(dialog.context).applyDialogSurface(dialog.window, panel, R.drawable.bg_card_solid)
}

private const val DIALOG_MARGIN_DP = 24

/** Gives an input in a dialog the same side margins as the dialog's title. */
fun dialogField(input: View): View = FrameLayout(input.context).apply {
    val density = resources.displayMetrics.density
    setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
    addView(input)
}

/**
 * The user's accent color. Layouts keep naming @color/accent_emerald, and views are retinted
 * as they inflate because Android can't change a color resource at runtime on minSdk 26.
 */
object CosmicAccent {
    val DEFAULT: Int = Color.parseColor("#10B981")
    private val BRIGHT_DEFAULT: Int = Color.parseColor("#34D399")

    @Volatile
    var color: Int = DEFAULT

    val bright: Int
        get() = ColorUtils.blendARGB(color, Color.WHITE, 0.25f)

    fun load(context: Context) {
        color = CosmicThemeStore.load(context).accent
    }

    fun tint(view: View, context: Context) {
        when (view) {
            is TextView -> when (view.currentTextColor) {
                DEFAULT -> view.setTextColor(color)
                BRIGHT_DEFAULT -> view.setTextColor(bright)
            }
            is SeekBar -> {
                val tint = ColorStateList.valueOf(color)
                view.progressTintList = tint
                view.thumbTintList = tint
            }
            is ProgressBar -> view.progressTintList = ColorStateList.valueOf(color)
        }
        if (view is MaterialSwitch) {
            view.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color, ContextCompat.getColor(context, R.color.border_primary))
            )
        }
    }

    /** The active tab's underline. */
    fun tabIndicator(context: Context): Drawable {
        val line = GradientDrawable().apply { setColor(color) }
        return LayerDrawable(arrayOf(line)).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            setLayerHeight(0, (2.5f * context.resources.displayMetrics.density).toInt())
        }
    }
}

/** Every screen extends this so everything it inflates, sheets and list rows included, follows the accent. */
abstract class CosmicActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        LayoutInflaterCompat.setFactory2(layoutInflater, object : LayoutInflater.Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
                val view = when (name) {
                    MATERIAL_SWITCH -> MaterialSwitch(context, attrs)
                    "ProgressBar" -> ProgressBar(context, attrs)
                    else -> delegate.createView(parent, name, context, attrs)
                }
                view?.let { CosmicAccent.tint(it, context) }
                return view
            }

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
                return onCreateView(null, name, context, attrs)
            }
        })
        super.onCreate(savedInstanceState)
    }

    private companion object {
        const val MATERIAL_SWITCH = "com.google.android.material.materialswitch.MaterialSwitch"
    }
}
