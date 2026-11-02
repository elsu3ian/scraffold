package net.mangadot.scaffold.engine

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat

/**
 * Intercepts Service Worker background requests, preventing ad/telemetry leaks
 * that bypass standard WebViewClient.
 */
object ServiceWorkerBlocker {

    fun initialize(context: Context, adBlockEngine: AdBlockEngine) {
        val controller = ServiceWorkerControllerCompat.getInstance()
        controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                if (adBlockEngine.shouldBlock(request, null)) return adBlockEngine.blockedResponse()
                return super.shouldInterceptRequest(request)
            }
        })
    }
}
