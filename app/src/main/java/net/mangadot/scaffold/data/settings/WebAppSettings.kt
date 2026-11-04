package net.mangadot.scaffold.data.settings

import android.content.Context
import net.mangadot.scaffold.data.db.entity.WebAppEntity

/** The switches on the Web App Settings screen. */
data class WebAppSettings(
    val javascript: Boolean = true,
    val blockThirdParty: Boolean = false,
    val allowHttp: Boolean = false,
    val allowLocation: Boolean = false,
    val allowDrm: Boolean = false,
    val allowCamera: Boolean = false,
    val allowMic: Boolean = false,
    val acceptCookies: Boolean = true,
    val thirdPartyCookies: Boolean = false,
    // Off until it is turned on in Settings
    val adblock: Boolean = false,
    val fullscreen: Boolean = true,
    val pinchZoom: Boolean = true,
    val desktop: Boolean = false,
    val dataSaver: Boolean = false
)

fun WebAppEntity.ownSettings() = WebAppSettings(
    javascript = allowJavascript,
    blockThirdParty = blockThirdParty,
    allowHttp = allowHttp,
    allowLocation = allowLocation,
    allowDrm = allowDrm,
    allowCamera = allowCamera,
    allowMic = allowMic,
    acceptCookies = acceptCookies,
    thirdPartyCookies = thirdPartyCookies,
    adblock = adblockEnabled,
    fullscreen = fullScreenMode,
    pinchZoom = pinchZoom,
    desktop = desktopMode,
    dataSaver = dataSaver
)

fun WebAppEntity.withSettings(settings: WebAppSettings) = copy(
    allowJavascript = settings.javascript,
    blockThirdParty = settings.blockThirdParty,
    allowHttp = settings.allowHttp,
    allowLocation = settings.allowLocation,
    allowDrm = settings.allowDrm,
    allowCamera = settings.allowCamera,
    allowMic = settings.allowMic,
    acceptCookies = settings.acceptCookies,
    thirdPartyCookies = settings.thirdPartyCookies,
    adblockEnabled = settings.adblock,
    fullScreenMode = settings.fullscreen,
    pinchZoom = settings.pinchZoom,
    desktopMode = settings.desktop,
    dataSaver = settings.dataSaver
)

/** What actually applies to an app: its own switches when it overrides, the global defaults otherwise. */
fun WebAppEntity.effectiveSettings(context: Context): WebAppSettings {
    return if (overrideGlobal) ownSettings() else GlobalWebSettingsStore.load(context)
}

object GlobalWebSettingsStore {
    private const val PREFS_NAME = "scraffold_web_defaults"

    fun load(context: Context): WebAppSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = WebAppSettings()
        return WebAppSettings(
            javascript = prefs.getBoolean("javascript", defaults.javascript),
            blockThirdParty = prefs.getBoolean("block_third_party", defaults.blockThirdParty),
            allowHttp = prefs.getBoolean("allow_http", defaults.allowHttp),
            allowLocation = prefs.getBoolean("allow_location", defaults.allowLocation),
            allowDrm = prefs.getBoolean("allow_drm", defaults.allowDrm),
            allowCamera = prefs.getBoolean("allow_camera", defaults.allowCamera),
            allowMic = prefs.getBoolean("allow_mic", defaults.allowMic),
            acceptCookies = prefs.getBoolean("accept_cookies", defaults.acceptCookies),
            thirdPartyCookies = prefs.getBoolean("third_party_cookies", defaults.thirdPartyCookies),
            adblock = prefs.getBoolean("adblock", defaults.adblock),
            fullscreen = prefs.getBoolean("fullscreen", defaults.fullscreen),
            pinchZoom = prefs.getBoolean("pinch_zoom", defaults.pinchZoom),
            desktop = prefs.getBoolean("desktop", defaults.desktop),
            dataSaver = prefs.getBoolean("data_saver", defaults.dataSaver)
        )
    }

    /** Just the shield switch, cheap enough to read for each request. */
    fun adblock(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("adblock", WebAppSettings().adblock)

    fun save(context: Context, settings: WebAppSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("javascript", settings.javascript)
            .putBoolean("block_third_party", settings.blockThirdParty)
            .putBoolean("allow_http", settings.allowHttp)
            .putBoolean("allow_location", settings.allowLocation)
            .putBoolean("allow_drm", settings.allowDrm)
            .putBoolean("allow_camera", settings.allowCamera)
            .putBoolean("allow_mic", settings.allowMic)
            .putBoolean("accept_cookies", settings.acceptCookies)
            .putBoolean("third_party_cookies", settings.thirdPartyCookies)
            .putBoolean("adblock", settings.adblock)
            .putBoolean("fullscreen", settings.fullscreen)
            .putBoolean("pinch_zoom", settings.pinchZoom)
            .putBoolean("desktop", settings.desktop)
            .putBoolean("data_saver", settings.dataSaver)
            .apply()
    }
}
