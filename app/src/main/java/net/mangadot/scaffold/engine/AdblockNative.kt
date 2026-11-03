package net.mangadot.scaffold.engine

/**
 * Brave's adblock-rust, built from native/adblock. One engine serves every thread, and a rebuild
 * swaps in whole, so a check never meets a half-loaded set of lists.
 */
object AdblockNative {

    init {
        System.loadLibrary("scraffold_adblock")
    }

    const val BLOCK = 1
    const val THIRD_PARTY = 2
    const val TRACKER = 4

    /**
     * Builds from list files plus [extraRules], keeping a copy in [cacheDir]. Returns the rule count,
     * or -1. [trackerListPaths] names the lists among them about tracking rather than ads.
     */
    @JvmStatic
    external fun build(
        listPaths: Array<String>,
        trackerListPaths: Array<String>,
        extraRules: String,
        resourcesPath: String?,
        cacheDir: String?
    ): Int

    /** Loads the copy [build] kept. Returns the rule count, or -1 when there is none to use. */
    @JvmStatic
    external fun restore(cacheDir: String, resourcesPath: String?): Int

    /** [BLOCK], [THIRD_PARTY] and [TRACKER] flags for one request. [popupRules] checks the lists' $popup rules instead. */
    @JvmStatic
    external fun check(url: String, sourceUrl: String, requestType: String, popupRules: Boolean): Int

    /** JSON with `css` and `script` for a page at [url], and `generic`, whether common ad classes count there. */
    @JvmStatic
    external fun cosmetics(url: String): String?

    /** CSS hiding what generic rules name among these classes and ids on a page at [url]. */
    @JvmStatic
    external fun genericCss(url: String, classes: Array<String>, ids: Array<String>): String?
}
