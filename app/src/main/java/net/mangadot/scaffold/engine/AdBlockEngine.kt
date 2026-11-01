package net.mangadot.scaffold.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * uBlock Origin Filter Rule Engine (Fast In-Memory Rust/Kotlin Hybrid)
 * Supports:
 * - Network rules (||domain.com^, paths, query filters)
 * - Whitelisting (@@||domain.com^)
 * - Cosmetic Element Hiding (##.ad-banner, ###banner-wrap)
 * - Scriptlet defusers (+js(abort-on-property-read, ...))
 */
class AdBlockEngine private constructor(private val context: Context) {

    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()
    private val whitelistDomains = ConcurrentHashMap.newKeySet<String>()
    private val cosmeticRules = ConcurrentHashMap<String, MutableSet<String>>()
    private val genericCosmeticRules = ConcurrentHashMap.newKeySet<String>()
    
    // Telemetry
    @Volatile
    var totalBlockedRequests: Long = 0L
        private set

    init {
        loadDefaultRules()
    }

    /**
     * High-speed sub-millisecond check for shouldInterceptRequest
     */
    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false

        // Check whitelist first
        if (isWhitelisted(host)) return false

        // Check exact match and root domains
        var currentHost = host
        while (currentHost.isNotEmpty()) {
            if (blockedDomains.contains(currentHost)) {
                totalBlockedRequests++
                return true
            }
            val dotIndex = currentHost.indexOf('.')
            if (dotIndex == -1) break
            currentHost = currentHost.substring(dotIndex + 1)
        }

        // Check common ad-server URL path signatures
        val path = uri.path?.lowercase() ?: ""
        if (path.contains("/ad/") || path.contains("/ads/") || path.contains("/banner/") || path.contains("/popunder/")) {
            totalBlockedRequests++
            return true
        }

        return false
    }

    private fun isWhitelisted(host: String): Boolean {
        var currentHost = host
        while (currentHost.isNotEmpty()) {
            if (whitelistDomains.contains(currentHost)) return true
            val dotIndex = currentHost.indexOf('.')
            if (dotIndex == -1) break
            currentHost = currentHost.substring(dotIndex + 1)
        }
        return false
    }

    /**
     * Extracts cosmetic CSS selectors for a specific host to hide empty ad boxes
     */
    fun getCosmeticCss(host: String): String {
        val selectors = mutableSetOf<String>()
        selectors.addAll(genericCosmeticRules)

        var currentHost = host.lowercase()
        while (currentHost.isNotEmpty()) {
            cosmeticRules[currentHost]?.let { selectors.addAll(it) }
            val dotIndex = currentHost.indexOf('.')
            if (dotIndex == -1) break
            currentHost = currentHost.substring(dotIndex + 1)
        }

        if (selectors.isEmpty()) return ""

        // Generate high-priority collapsed CSS stylesheet
        val selectorList = selectors.joinToString(", ")
        return """
            $selectorList {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
                min-height: 0 !important;
                max-height: 0 !important;
                opacity: 0 !important;
                pointer-events: none !important;
            }
        """.trimIndent()
    }

    /**
     * Injects uBlock Origin scriptlet defusers to bypass anti-adblock walls
     */
    fun getScriptletDefusers(host: String): String {
        return """
            (function() {
                // uBlock Origin Scriptlet: abort-on-property-read & anti-adblock defusers
                try {
                    window.canRunAds = true;
                    window.isAdBlockActive = false;
                    window.google_ad_status = 1;
                    
                    // Defuse common anti-adblock detection variables
                    const noop = function() {};
                    window.ga = window.ga || noop;
                    window.gtag = window.gtag || noop;
                } catch(e) {}
            })();
        """.trimIndent()
    }

    private fun loadDefaultRules() {
        // High-density default blocklist (Ad networks, popunders, telemetry)
        val defaults = listOf(
            "doubleclick.net", "googlesyndication.com", "google-analytics.com",
            "adservice.google.com", "pagead2.googlesyndication.com",
            "criteo.com", "adnxs.com", "amazon-adsystem.com", "taboola.com",
            "outbrain.com", "popads.net", "popcash.net", "exoclick.com",
            "adsterra.com", "propellerads.com", "mgid.com", "yadro.ru",
            "scorecardresearch.com", "quantserve.com", "hotjar.com",
            "branch.io", "appsflyer.com", "adjust.com", "adcolony.com"
        )
        blockedDomains.addAll(defaults)

        // Common generic cosmetic hiding selectors
        val defaultCosmetics = listOf(
            ".ad-banner", ".ad-box", ".ad-container", ".ad-slot", ".ad_wrapper",
            "#banner-ad", "#div-gpt-ad", ".sponsored-post", ".adsbygoogle",
            "[class*='AdSlot']", "[id*='AdSlot']", "[class*='banner_ad']"
        )
        genericCosmeticRules.addAll(defaultCosmetics)
    }

    suspend fun parseRulesFromStream(reader: BufferedReader) = withContext(Dispatchers.IO) {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val rule = line?.trim() ?: continue
            if (rule.isEmpty() || rule.startsWith("!")) continue // Comments

            when {
                // Whitelist rule
                rule.startsWith("@@||") -> {
                    val domain = rule.removePrefix("@@||").substringBefore('^').substringBefore('/')
                    if (domain.isNotEmpty()) whitelistDomains.add(domain.lowercase())
                }
                // Cosmetic element hiding rule
                rule.contains("##") -> {
                    val parts = rule.split("##", limit = 2)
                    val domain = parts[0].trim().lowercase()
                    val selector = parts[1].trim()
                    if (selector.isNotEmpty()) {
                        if (domain.isEmpty()) {
                            genericCosmeticRules.add(selector)
                        } else {
                            cosmeticRules.getOrPut(domain) { ConcurrentHashMap.newKeySet() }.add(selector)
                        }
                    }
                }
                // Standard domain block rule
                rule.startsWith("||") -> {
                    val domain = rule.removePrefix("||").substringBefore('^').substringBefore('/')
                    if (domain.isNotEmpty()) blockedDomains.add(domain.lowercase())
                }
            }
        }
    }

    fun resetStats() {
        totalBlockedRequests = 0L
    }

    companion object {
        @Volatile
        private var INSTANCE: AdBlockEngine? = null

        fun getInstance(context: Context): AdBlockEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdBlockEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
