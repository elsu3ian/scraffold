package net.mangadot.scaffold.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterSetTest {

    private fun filters(vararg rules: String) = FilterSet.build(listOf { rules.asSequence() })

    private fun FilterSet.blocks(
        url: String,
        source: String? = "site.example",
        type: Int = FilterSet.TYPE_UNKNOWN
    ) = isBlocked(url, url.substringAfter("://").substringBefore('/').substringBefore('?'), source, type)

    @Test
    fun blocksAHostAndItsSubdomains() {
        val set = filters("||ads.example^")
        assertTrue(set.blocks("https://ads.example/x.js"))
        assertTrue(set.blocks("https://cdn.ads.example/x.js"))
        assertFalse(set.blocks("https://notads.example/x.js"))
    }

    @Test
    fun matchesPathsWildcardsAndSeparators() {
        val set = filters("/banner/*/track^", "||cdn.example/ads/", "|https://exact.example/a.js|")
        assertTrue(set.blocks("https://any.example/banner/1/track?x=1"))
        assertFalse(set.blocks("https://any.example/banner/1/tracker"))
        assertTrue(set.blocks("https://cdn.example/ads/x.png"))
        assertFalse(set.blocks("https://cdn.example/news/x.png"))
        assertTrue(set.blocks("https://exact.example/a.js"))
        assertFalse(set.blocks("https://exact.example/a.js?y"))
    }

    @Test
    fun exceptionsWinUnlessTheBlockIsImportant() {
        val set = filters("||tracker.example^", "@@||tracker.example/allowed.js", "||forced.example^\$important", "@@||forced.example^")
        assertTrue(set.blocks("https://tracker.example/other.js"))
        assertFalse(set.blocks("https://tracker.example/allowed.js"))
        assertTrue(set.blocks("https://forced.example/x"))
    }

    @Test
    fun honorsThirdPartyAndPageDomains() {
        val set = filters("||widget.example^\$third-party", "||only.example^\$domain=a.example|b.example", "||except.example^\$domain=~a.example")
        assertFalse(set.blocks("https://widget.example/x", source = "www.widget.example"))
        assertTrue(set.blocks("https://widget.example/x", source = "site.example"))
        assertTrue(set.blocks("https://only.example/x", source = "sub.a.example"))
        assertFalse(set.blocks("https://only.example/x", source = "c.example"))
        assertFalse(set.blocks("https://except.example/x", source = "a.example"))
        assertTrue(set.blocks("https://except.example/x", source = "c.example"))
    }

    @Test
    fun honorsRequestTypes() {
        val set = filters("||img.example^\$image", "||notjs.example^\$~script")
        assertTrue(set.blocks("https://img.example/a", type = FilterSet.TYPE_IMAGE))
        assertFalse(set.blocks("https://img.example/a", type = FilterSet.TYPE_SCRIPT))
        assertFalse(set.blocks("https://notjs.example/a", type = FilterSet.TYPE_SCRIPT))
        assertTrue(set.blocks("https://notjs.example/a", type = FilterSet.TYPE_IMAGE))
    }

    @Test
    fun denyAllowSparesTheNamedHosts() {
        val set = filters("*\$script,third-party,domain=a.example,denyallow=cdn.example")
        assertTrue(set.blocks("https://other.example/x.js", source = "a.example", type = FilterSet.TYPE_SCRIPT))
        assertFalse(set.blocks("https://cdn.example/x.js", source = "a.example", type = FilterSet.TYPE_SCRIPT))
        assertFalse(set.blocks("https://other.example/x.js", source = "c.example", type = FilterSet.TYPE_SCRIPT))
    }

    @Test
    fun aPageWideExceptionTurnsBlockingOffThere() {
        val set = filters("||ads.example^", "@@||kept.example^\$document")
        assertFalse(set.blocks("https://ads.example/x", source = "www.kept.example"))
        assertTrue(set.blocks("https://ads.example/x", source = "site.example"))
    }

    @Test
    fun retiredRulesAndUnknownOptionsAreLeftOut() {
        val set = filters("||old.example^\$script", "||old.example^\$script,badfilter", "||param.example^\$removeparam=x", "*\$popup")
        assertFalse(set.blocks("https://old.example/x", type = FilterSet.TYPE_SCRIPT))
        assertFalse(set.blocks("https://param.example/x"))
        assertFalse(set.blocks("https://anything.example/x"))
    }

    @Test
    fun aRuleThatMatchesEverythingIsIgnored() {
        val set = filters("*", "^", "||", "\$third-party")
        assertFalse(set.blocks("https://anything.example/x"))
    }

    @Test
    fun keepsOnlySelectorsItCanApply() {
        val set = filters("site.example##.ad", "site.example##.x:has-text(ad)", "site.example#@#.y", "##.generic", "site.example##+js(noop)")
        assertEquals(listOf(".ad"), set.selectorsFor("www.site.example"))
        assertTrue(set.selectorsFor("other.example").isEmpty())
    }
}
