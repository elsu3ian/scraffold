package net.mangadot.scaffold.ui.container

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient

/**
 * A page's video played full screen, laid over the whole window until the page ends it or back is
 * pressed. [onShown] and [onHidden] let a screen hide its bars and bring them back.
 */
class FullscreenVideo(
    private val activity: Activity,
    private val onShown: () -> Unit = {},
    private val onHidden: () -> Unit = {}
) {
    private var view: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null

    val isShowing: Boolean
        get() = view != null

    /** For WebChromeClient.onShowCustomView. */
    fun show(view: View, callback: WebChromeClient.CustomViewCallback) {
        this.view = view
        this.callback = callback
        decor().addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onShown()
    }

    /** For WebChromeClient.onHideCustomView: the page ended it. */
    fun hide() {
        view?.let { decor().removeView(it) }
        view = null
        callback = null
        onHidden()
    }

    /** Back ended it, so the page is told before it goes. */
    fun exit() {
        callback?.onCustomViewHidden()
        hide()
    }

    private fun decor() = activity.window.decorView as ViewGroup
}
