package net.mangadot.scaffold

import android.app.Application
import android.webkit.WebView

class ScaffoldApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        net.mangadot.scaffold.ui.theme.CosmicAccent.load(this)
        // Left by the theme before it moved to scraffold_cosmic
        deleteSharedPreferences("scraffold_cosmic_theme")
        // Lets chrome://inspect see pages in debug builds
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }
}
