package net.mangadot.scaffold.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shield's rules as the adblock-rust engine reads them. Each test builds the engine from its own
 * rules alone, with no lists, so only those rules decide.
 */
@RunWith(AndroidJUnit4::class)
class AdblockNativeTest {

    private fun filters(vararg rules: String) {
        val count = AdblockNative.build(emptyArray(), emptyArray(), emptyArray(), rules.joinToString("\n"), emptyArray(), null)
        assertTrue("The rules should build", count >= 0)
    }

    private fun blocks(url: String, source: String = "site.example", type: String = "other") =
        AdblockNative.check(url, "https://$source/", type, false) and AdblockNative.BLOCK != 0

    private fun hidden(page: String): String =
        AdblockNative.cosmetics(page)?.let { JSONObject(it).optString("css") }.orEmpty()

    @Test
    fun blocksAHostAndItsSubdomains() {
        filters("||ads.example^")
        assertTrue(blocks("https://ads.example/x.js"))
        assertTrue(blocks("https://cdn.ads.example/x.js"))
        assertFalse(blocks("https://notads.example/x.js"))
    }

    @Test
    fun matchesPathsWildcardsAndSeparators() {
        filters("/banner/*/track^", "||cdn.example/ads/", "|https://exact.example/a.js|")
        assertTrue(blocks("https://any.example/banner/1/track?x=1"))
        assertFalse(blocks("https://any.example/banner/1/tracker"))
        assertTrue(blocks("https://cdn.example/ads/x.png"))
        assertFalse(blocks("https://cdn.example/news/x.png"))
        assertTrue(blocks("https://exact.example/a.js"))
        assertFalse(blocks("https://exact.example/a.js?y"))
    }

    @Test
    fun exceptionsWinUnlessTheBlockIsImportant() {
        filters("||tracker.example^", "@@||tracker.example/allowed.js", "||forced.example^\$important", "@@||forced.example^")
        assertTrue(blocks("https://tracker.example/other.js"))
        assertFalse(blocks("https://tracker.example/allowed.js"))
        assertTrue(blocks("https://forced.example/x"))
    }

    @Test
    fun honorsThirdPartyAndPageDomains() {
        filters("||widget.example^\$third-party", "||only.example^\$domain=a.example|b.example", "||except.example^\$domain=~a.example")
        assertFalse(blocks("https://widget.example/x", source = "www.widget.example"))
        assertTrue(blocks("https://widget.example/x", source = "site.example"))
        assertTrue(blocks("https://only.example/x", source = "sub.a.example"))
        assertFalse(blocks("https://only.example/x", source = "c.example"))
        assertFalse(blocks("https://except.example/x", source = "a.example"))
        assertTrue(blocks("https://except.example/x", source = "c.example"))
    }

    @Test
    fun honorsRequestTypes() {
        filters("||img.example^\$image", "||notjs.example^\$~script")
        assertTrue(blocks("https://img.example/a", type = "image"))
        assertFalse(blocks("https://img.example/a", type = "script"))
        assertFalse(blocks("https://notjs.example/a", type = "script"))
        assertTrue(blocks("https://notjs.example/a", type = "image"))
    }

    /** adblock-rust 0.13 has no denyallow, so it drops the rule rather than block the hosts it spares. */
    @Test
    fun aRuleWithAnOptionTheEngineLacksBlocksNothing() {
        filters("*\$script,third-party,domain=a.example,denyallow=cdn.example")
        assertFalse(blocks("https://cdn.example/x.js", source = "a.example", type = "script"))
        assertFalse(blocks("https://other.example/x.js", source = "a.example", type = "script"))
    }

    @Ignore("Not applied yet: the engine is asked about each request, never about the page it's on")
    @Test
    fun aPageWideExceptionTurnsBlockingOffThere() {
        filters("||ads.example^", "@@||kept.example^\$document")
        assertFalse(blocks("https://ads.example/x", source = "www.kept.example"))
        assertTrue(blocks("https://ads.example/x", source = "site.example"))
    }

    @Test
    fun aRetiredRuleIsLeftOut() {
        filters("||old.example^\$script", "||old.example^\$script,badfilter")
        assertFalse(blocks("https://old.example/x", type = "script"))
    }

    @Test
    fun popupRulesOnlyApplyToPopups() {
        filters("||pop.example^\$popup")
        assertFalse(blocks("https://pop.example/x", type = "document"))
        assertTrue(AdblockNative.check("https://pop.example/x", "https://site.example/", "document", true) and AdblockNative.BLOCK != 0)
    }

    @Test
    fun hidesWhatASiteRuleNamesOnlyOnThatSite() {
        filters("site.example##.ad", "site.example#@#.kept", "site.example##.kept")
        assertTrue(hidden("https://www.site.example/").contains(".ad"))
        assertFalse(hidden("https://www.site.example/").contains(".kept"))
        assertFalse(hidden("https://other.example/").contains(".ad"))
    }
}
