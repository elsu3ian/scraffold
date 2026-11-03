package net.mangadot.scaffold.engine

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * The link under the last tap on a page. One tap can open what the person picked or something a
 * script slipped in behind it, and only the first leads where the link says.
 */
class TappedLink(page: WebView) {

    @Volatile
    private var url: String? = null

    // The link around a tapped image arrives a moment later, and until then the tap gets the benefit of the doubt
    @Volatile
    private var awaitingImageLink = false

    init {
        val hit = page.hitTestResult
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> url = hit.extra
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                awaitingImageLink = true
                page.requestFocusNodeHref(Handler(Looper.getMainLooper()) { message ->
                    url = message.data.getString("url")
                    awaitingImageLink = false
                    true
                }.obtainMessage())
            }
        }
    }

    fun leadsTo(target: Uri): Boolean {
        if (awaitingImageLink) return true
        val link = url ?: return false
        return address(link) == address(target.toString())
    }

    private fun address(url: String) = url.substringBefore('#').trimEnd('/')
}
