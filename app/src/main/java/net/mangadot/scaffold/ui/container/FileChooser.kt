package net.mangadot.scaffold.ui.container

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Lets a page's file upload open the phone's file picker. Create it while the screen is being created. */
class FileChooser(private val activity: ComponentActivity) {

    private var pending: ValueCallback<Array<Uri>>? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pending = null
    }

    /** For WebChromeClient.onShowFileChooser. */
    fun choose(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        // A page asking again before the last choice came back gets nothing for the first
        pending?.onReceiveValue(null)
        pending = callback
        return try {
            picker.launch(params.createIntent())
            true
        } catch (e: Exception) {
            pending = null
            Toast.makeText(activity, "No app can choose files on this device", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
