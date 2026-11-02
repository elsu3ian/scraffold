package net.mangadot.scaffold.ui.theme

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native port of computeNebulaGradient() from the MangaDot Cosmic userscript: a dark linear
 * base tinted from the glow colors, under three radial glows.
 */
class NebulaDrawable(private val theme: CosmicTheme) : Drawable() {

    private val basePaint = Paint(Paint.DITHER_FLAG)
    private val glowPaints = List(3) { Paint(Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG) }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return

        basePaint.shader = createBaseShader(bounds)

        val glows = listOf(
            Glow(10, 12, 0.46f, 0.22f, theme.glow1),
            Glow(88, 18, 0.44f, 0.16f, theme.glow2),
            Glow(50, 85, 0.52f, 0.14f, theme.glow3)
        )
        glows.forEachIndexed { index, glow ->
            glowPaints[index].shader = createGlowShader(bounds, glow)
        }
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        canvas.drawRect(area, basePaint)
        glowPaints.forEach { canvas.drawRect(area, it) }
    }

    private fun createBaseShader(area: Rect): Shader {
        val width = area.width().toFloat()
        val height = area.height().toFloat()
        val angle = Math.toRadians(theme.gradientRotation.toDouble())
        val dirX = sin(angle).toFloat()
        val dirY = -cos(angle).toFloat()
        val halfLength = (abs(width * dirX) + abs(height * dirY)) / 2f
        val centerX = area.left + width / 2f
        val centerY = area.top + height / 2f
        return LinearGradient(
            centerX - dirX * halfLength,
            centerY - dirY * halfLength,
            centerX + dirX * halfLength,
            centerY + dirY * halfLength,
            intArrayOf(
                voidColor(theme.glow1, 0.75f, 0.65f, 0.032f),
                voidColor(theme.glow1, 0.80f, 0.70f, 0.060f),
                voidColor(theme.glow2, 0.82f, 0.72f, 0.082f),
                voidColor(theme.glow3, 0.75f, 0.65f, 0.040f)
            ),
            BASE_STOPS,
            Shader.TileMode.CLAMP
        )
    }

    /** A near-black in the glow's own hue, so green or red themes don't sit on a purple floor. */
    private fun voidColor(glow: Int, saturationScale: Float, saturationCap: Float, lightness: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(glow, hsl)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], min(saturationCap, hsl[1] * saturationScale), lightness))
    }

    private fun createGlowShader(area: Rect, glow: Glow): Shader {
        val width = area.width().toFloat()
        val height = area.height().toFloat()
        val offset = Math.toRadians((theme.gradientRotation - DEFAULT_ROTATION).toDouble())
        val dx = glow.x - 50f
        val dy = glow.y - 50f
        val cosOffset = cos(offset).toFloat()
        val sinOffset = sin(offset).toFloat()
        val centerX = width * (50f + dx * cosOffset - dy * sinOffset).roundToInt().coerceIn(4, 96) / 100f
        val centerY = height * (50f + dx * sinOffset + dy * cosOffset).roundToInt().coerceIn(4, 96) / 100f

        // CSS sizes a default circle to the farthest corner of the box
        val farthestX = max(centerX, width - centerX)
        val farthestY = max(centerY, height - centerY)
        val radius = max(1f, hypot(farthestX, farthestY) * glow.radius)

        val alpha = (glow.alpha * theme.glowIntensity.coerceIn(0, 200) / 100f * 255f).roundToInt().coerceIn(0, 255)
        return RadialGradient(
            area.left + centerX,
            area.top + centerY,
            radius,
            ColorUtils.setAlphaComponent(glow.color, alpha),
            ColorUtils.setAlphaComponent(glow.color, 0),
            Shader.TileMode.CLAMP
        )
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        glowPaints.forEach { it.alpha = alpha }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        glowPaints.forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (basePaint.alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    private data class Glow(val x: Int, val y: Int, val radius: Float, val alpha: Float, val color: Int)

    private companion object {
        const val DEFAULT_ROTATION = 160
        val BASE_STOPS = floatArrayOf(0f, 0.35f, 0.70f, 1f)
    }
}
