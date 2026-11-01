package net.mangadot.scaffold.engine

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import java.io.ByteArrayInputStream

/**
 * Intercepts Service Worker background requests, preventing ad/telemetry leaks
 * that bypass standard WebViewClient.
 */
object ServiceWorkerBlocker {

    fun initialize(context: Context, adBlockEngine: AdBlockEngine) {
        val controller = ServiceWorkerControllerCompat.getInstance()
        controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                if (adBlockEngine.shouldBlock(request.url)) {
                    // Return empty 200 OK zero-byte response to cleanly swallow the request
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        200,
                        "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(request)
            }
        })
    }
}
