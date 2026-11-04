package net.mangadot.scaffold.ui.container

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * A page asking for location, the camera or the microphone. Android asks the person first where it
 * hasn't been allowed yet. Create it while the screen is being created.
 */
class PagePermissions(private val activity: ComponentActivity) {

    private var pendingLocation: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingMedia: Pair<PermissionRequest, List<String>>? = null

    private val locationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        pendingLocation?.let { (origin, callback) -> callback.invoke(origin, results.values.any { it }, false) }
        pendingLocation = null
    }

    private val mediaPermission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingMedia?.let { (request, allowed) -> grantHeld(request, allowed) }
        pendingMedia = null
    }

    fun requestLocation(origin: String, callback: GeolocationPermissions.Callback) {
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            callback.invoke(origin, true, false)
        } else {
            pendingLocation = origin to callback
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    /** [allowed] is what the app's settings let the page have. */
    fun requestMedia(request: PermissionRequest, allowed: List<String>) {
        val missing = allowed.mapNotNull(::androidPermissionFor).filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            grantHeld(request, allowed)
        } else {
            pendingMedia = request to allowed
            mediaPermission.launch(missing.toTypedArray())
        }
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    /** Grants what Android also allows; DRM needs no Android permission. */
    private fun grantHeld(request: PermissionRequest, allowed: List<String>) {
        val held = allowed.filter { resource ->
            val permission = androidPermissionFor(resource)
            permission == null || ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (held.isEmpty()) request.deny() else request.grant(held.toTypedArray())
    }
}
