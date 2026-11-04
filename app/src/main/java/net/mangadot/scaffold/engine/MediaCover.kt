package net.mangadot.scaffold.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The picture on the media notification. Android shows it as the cover, spreads it across the player
 * as its background, and takes the player's colors from it. A page's own cover is fetched; without
 * one, a plain cover is drawn in the accent.
 */
object MediaCover {

    private const val SIZE = 512
    private const val MAX_BYTES = 4L * 1024 * 1024

    private val client by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** The page's cover, scaled down to what a notification shows. Null when it can't be had. Off the main thread. */
    fun fetch(url: String): Bitmap? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body ?: return null
            if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) return null
            // Only as large as needed: a phone's media player is a few hundred pixels across
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** A square in [color], darker toward the corner, for the player to take its colors from. */
    fun drawn(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val darker = ColorUtils.blendARGB(color, android.graphics.Color.BLACK, 0.45f)
        Canvas(bitmap).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), color, darker, Shader.TileMode.CLAMP)
        })
        return bitmap
    }
}
