package net.mangadot.scaffold.ui.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import net.mangadot.scaffold.R

/**
 * Native versions of the userscript's "Cosmic styling" rules. With the switch off, surfaces
 * go back to their plain look and only the nebula remains.
 */

/**
 * Clear over the nebula, and frosted glass once [scroller] has moved, as the userscript's sticky
 * header does. With [overlay], [scroller] must be the next view after the header: it then runs
 * underneath, so the glass has its content to blur. Needs Android 12; before that the header
 * stays a tinted pane above the content.
 */
fun CosmicTheme.applyHeader(header: View, scroller: View? = null, overlay: Boolean = false) {
    if (!cosmicStyling) {
        scroller?.setOnScrollChangeListener(null)
        header.setBackgroundColor(ContextCompat.getColor(header.context, R.color.bg_surface))
        return
    }

    val tint = ContextCompat.getDrawable(header.context, R.drawable.bg_cosmic_header_scrolled)
    val glass = if (overlay && scroller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runUnderHeader(header, scroller)
        LayerDrawable(arrayOf(BackdropBlurDrawable(header, scroller), tint))
    } else {
        tint
    }

    fun update() {
        if (scroller?.canScrollVertically(-1) == true) {
            header.background = glass
            // The content moved, and the header's own recording doesn't know that
            header.invalidate()
        } else {
            header.setBackgroundColor(Color.TRANSPARENT)
        }
    }
    scroller?.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    update()
}

private fun runUnderHeader(header: View, scroller: View) {
    val paddingTop = scroller.paddingTop
    (scroller as? ViewGroup)?.clipToPadding = false
    // Drawn after the header in its layout otherwise
    header.translationZ = header.resources.displayMetrics.density
    header.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
        val height = bottom - top
        val params = scroller.layoutParams as ViewGroup.MarginLayoutParams
        if (params.topMargin != -height) {
            header.post {
                params.topMargin = -height
                scroller.layoutParams = params
                scroller.setPadding(scroller.paddingLeft, paddingTop + height, scroller.paddingRight, scroller.paddingBottom)
            }
        }
    }
}

/** Draws what is behind the header, blurred. Android has no backdrop filter for ordinary views. */
@RequiresApi(Build.VERSION_CODES.S)
private class BackdropBlurDrawable(private val header: View, private val scroller: View) : Drawable() {

    private val node = RenderNode("cosmic_header_glass").apply {
        val radius = BLUR_RADIUS_DP * header.resources.displayMetrics.density
        setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    override fun draw(canvas: Canvas) {
        if (!canvas.isHardwareAccelerated) return
        val width = header.width
        val height = header.height
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording(width, height)
        (header.parent as? View)?.background?.draw(recording)
        recording.translate((scroller.left - scroller.scrollX).toFloat(), (scroller.top - scroller.scrollY).toFloat())
        scroller.draw(recording)
        node.endRecording()
        canvas.drawRenderNode(node)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
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
