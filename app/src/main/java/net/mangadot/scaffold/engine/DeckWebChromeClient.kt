package net.mangadot.scaffold.engine

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import net.mangadot.scaffold.data.db.entity.WebAppEntity
import net.mangadot.scaffold.data.settings.WebAppSettings

/**
 * WebChromeClient enforcing two-tier hardware permissions (Camera & Mic),
 * full-screen HTML5 video playback, external popup link routing, and title/favicon reception.
 */
class DeckWebChromeClient(
    private var webApp: WebAppEntity,
    private val onProgressUpdate: ((progress: Int) -> Unit)? = null,
    private val onTitleReceived: ((title: String) -> Unit)? = null,
    private val onFaviconReceived: ((icon: Bitmap) -> Unit)? = null,
    private val onShowFullscreenVideo: ((view: View, callback: CustomViewCallback) -> Unit)? = null,
    private val onHideFullscreenVideo: (() -> Unit)? = null,
    private val onExternalLinkRequested: ((uri: Uri) -> Unit)? = null,
    private val onLocationRequested: ((origin: String, callback: GeolocationPermissions.Callback) -> Unit)? = null
) : WebChromeClient() {

    private var settings = WebAppSettings()

    fun updateWebApp(updated: WebAppEntity, webSettings: WebAppSettings) {
        this.webApp = updated
        this.settings = webSettings
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        if (settings.allowLocation && onLocationRequested != null) {
            onLocationRequested.invoke(origin, callback)
        } else {
            callback.invoke(origin, false, false)
        }
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
                    if (settings.allowDrm) grantedResources.add(resource)
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

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        // A window nobody tapped for is a popup
        if (!isUserGesture) return false
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val tempWebView = WebView(view?.context ?: return false)
        tempWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                onExternalLinkRequested?.invoke(uri)
                view?.post { view.destroy() }
                return true
            }
        }
        transport.webView = tempWebView
        resultMsg.sendToTarget()
        return true
    }
}
