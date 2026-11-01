package net.mangadot.scaffold.engine

import android.graphics.Bitmap
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import net.mangadot.scaffold.data.db.entity.WebAppEntity

/**
 * WebChromeClient enforcing two-tier hardware permissions (Camera & Mic),
 * full-screen HTML5 video playback, and title/favicon reception.
 */
class DeckWebChromeClient(
    private var webApp: WebAppEntity,
    private val onProgressUpdate: ((progress: Int) -> Unit)? = null,
    private val onTitleReceived: ((title: String) -> Unit)? = null,
    private val onFaviconReceived: ((icon: Bitmap) -> Unit)? = null,
    private val onShowFullscreenVideo: ((view: View, callback: CustomViewCallback) -> Unit)? = null,
    private val onHideFullscreenVideo: (() -> Unit)? = null
) : WebChromeClient() {

    fun updateWebApp(updated: WebAppEntity) {
        this.webApp = updated
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val grantedResources = mutableListOf<String>()

        for (resource in request.resources) {
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    if (webApp.allowCamera) grantedResources.add(resource)
                }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    if (webApp.allowMic) grantedResources.add(resource)
                }
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> {
                    // Widevine DRM streaming
                    grantedResources.add(resource)
                }
            }
        }

        if (grantedResources.isNotEmpty()) {
            request.grant(grantedResources.toTypedArray())
        } else {
            // Silently reject if per-app permissions are disabled
            request.deny()
        }
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        onShowFullscreenVideo?.invoke(view, callback)
    }

    override fun onHideCustomView() {
        onHideFullscreenVideo?.invoke()
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressUpdate?.invoke(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        super.onReceivedTitle(view, title)
        title?.let { onTitleReceived?.invoke(it) }
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        icon?.let { onFaviconReceived?.invoke(it) }
    }
}
