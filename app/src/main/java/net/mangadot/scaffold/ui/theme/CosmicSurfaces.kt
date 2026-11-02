package net.mangadot.scaffold.ui.theme

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mangadot.scaffold.R

/**
 * Native versions of the userscript's "Cosmic styling" rules. With the switch off, surfaces
 * go back to their plain look and only the nebula remains.
 */

/** Clear over the nebula, and filled in once [scroller] has content scrolled under it. */
fun CosmicTheme.applyHeader(header: View, scroller: View? = null) {
    if (!cosmicStyling) {
        scroller?.setOnScrollChangeListener(null)
        header.setBackgroundColor(ContextCompat.getColor(header.context, R.color.bg_surface))
        return
    }

    fun update() {
        if (scroller?.canScrollVertically(-1) == true) {
            header.setBackgroundResource(R.drawable.bg_cosmic_header_scrolled)
        } else {
            header.setBackgroundColor(Color.TRANSPARENT)
        }
    }
    scroller?.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    update()
}

/** The screens' nebula on a rounded surface with an accent border, so glass cards read the same here. */
fun CosmicTheme.applyDialogSurface(
    window: Window?,
    surface: View,
    plainBackground: Int,
    isSheet: Boolean = false
) {
    if (!cosmicStyling) {
        surface.clipToOutline = false
        surface.setBackgroundResource(plainBackground)
        setBlurBehind(window, surface, false)
        return
    }

    val context = surface.context
    val density = context.resources.displayMetrics.density
    val radius = GLASS_RADIUS_DP * density
    setBlurBehind(window, surface, true)
    window?.setDimAmount(GLASS_DIM)

    val fill = if (radiantBackground) {
        NebulaDrawable(this).apply { setAlpha(GLASS_ALPHA) }
    } else {
        ColorDrawable(ContextCompat.getColor(context, R.color.bg_surface))
    }
    val border = GradientDrawable().apply {
        cornerRadii = if (isSheet) {
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        } else {
            FloatArray(8) { radius }
        }
        setStroke(
            density.toInt().coerceAtLeast(1),
            ColorUtils.setAlphaComponent(CosmicAccent.color, GLASS_BORDER_ALPHA)
        )
    }
    surface.background = LayerDrawable(arrayOf(fill, border))
    surface.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            // A sheet's bottom corners sit below the view, leaving only the top ones rounded
            val height = if (isSheet) view.height + radius.toInt() else view.height
            outline.setRoundRect(0, 0, view.width, height, radius)
        }
    }
    surface.clipToOutline = true
}

/** Returns whether the blur is really showing: it needs Android 12 and device support. */
private fun setBlurBehind(window: Window?, surface: View, enabled: Boolean): Boolean {
    if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    if (enabled && window.windowManager.isCrossWindowBlurEnabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            blurBehindRadius = (BLUR_RADIUS_DP * surface.resources.displayMetrics.density).toInt()
        }
        return true
    }
    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    return false
}

/** Bottom sheet that follows the Cosmic styling switch. */
abstract class CosmicBottomSheet : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        // Material's own opaque container would hide the rounded, translucent sheet
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = null
        CosmicThemeStore.load(requireContext()).applyDialogSurface(
            dialog?.window,
            requireView(),
            R.drawable.bg_bottom_sheet,
            isSheet = true
        )
    }
}

private const val GLASS_RADIUS_DP = 16
private const val GLASS_DIM = 0.55f
private const val GLASS_ALPHA = 235
private const val GLASS_BORDER_ALPHA = 82
private const val BLUR_RADIUS_DP = 12
