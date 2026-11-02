package net.mangadot.scaffold

import android.app.Application

class ScaffoldApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        net.mangadot.scaffold.ui.theme.CosmicAccent.load(this)
        // Initialize adblock and local state stores
    }
}
