package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MobileTinaGeositeCompatibilityTest {
    private val source = """{"outbounds":[{"type":"vless","tag":"proxy","server":"example.com","uuid":"00000000-0000-0000-0000-000000000001","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"example.org","reality":{"enabled":true,"public_key":"fixture-key","short_id":"01020304"}}}],"route":{"rule_set":[{"type":"local","tag":"legacy-ir","format":"binary","path":"geosite:ir"},{"type":"local","tag":"other","format":"binary","path":"geosite:cn"}],"rules":[{"rule_set":["legacy-ir"],"outbound":"direct"},{"outbound":"proxy"}],"final":"proxy"},"dns":{"rules":[{"rule_set":["legacy-ir"],"server":"local"}]}}"""
    private fun root(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun theBundledCategoryAliasPreservesRealityOutboundRoutingTagsAndDns() {
        val before = root(source)
        val after = root(MobileTinaGeositeCompatibility.rewrite(source) { setOf("category-ir") })
        val definitions = after.getAsJsonObject("route").getAsJsonArray("rule_set")
        assertEquals("geosite:category-ir", definitions[0].asJsonObject.get("path").asString)
        assertEquals("legacy-ir", definitions[0].asJsonObject.get("tag").asString)
        assertEquals("geosite:cn", definitions[1].asJsonObject.get("path").asString)
        assertEquals(before.get("outbounds"), after.get("outbounds"))
        assertEquals(before.get("dns"), after.get("dns"))
        assertEquals(before.getAsJsonObject("route").get("rules"), after.getAsJsonObject("route").get("rules"))
    }
    @Test fun aUserDatabaseWithTheOriginalIranCodeIsUsedUnchanged() {
        assertSame(source, MobileTinaGeositeCompatibility.rewrite(source) { setOf("ir") })
        assertSame(source, MobileTinaGeositeCompatibility.rewrite(source) { setOf("ir", "category-ir") })
    }
    @Test fun missingBothAliasesUsesAnInlineIrSuffixInsteadOfDroppingTheRule() {
        val after = root(MobileTinaGeositeCompatibility.rewrite(source) { emptySet() })
        val definitions = after.getAsJsonObject("route").getAsJsonArray("rule_set")
        val fallback = definitions[0].asJsonObject
        assertEquals("inline", fallback.get("type").asString)
        assertEquals("legacy-ir", fallback.get("tag").asString)
        assertFalse(fallback.has("path")); assertFalse(fallback.has("format"))
        assertEquals("ir", fallback.getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("domain_suffix")[0].asString)
        assertEquals(root(source).getAsJsonObject("route").getAsJsonArray("rule_set")[1], definitions[1])
        assertEquals(root(source).getAsJsonObject("route").get("rules"), after.getAsJsonObject("route").get("rules"))
    }
    @Test fun unrelatedConfigsDoNotOpenTheGeositeDatabase() {
        val value = """{"route":{"rules":[]},"outbounds":[{"type":"direct"}]}"""
        assertSame(value, MobileTinaGeositeCompatibility.rewrite(value) { error("unnecessary IO") })
    }
    @Test fun theTransformationIsIdempotentWithoutAnotherAssetRead() {
        val once = MobileTinaGeositeCompatibility.rewrite(source) { setOf("category-ir") }
        assertSame(once, MobileTinaGeositeCompatibility.rewrite(once) { error("unnecessary IO") })
        val fallback = MobileTinaGeositeCompatibility.rewrite(source) { emptySet() }
        assertSame(fallback, MobileTinaGeositeCompatibility.rewrite(fallback) { error("unnecessary IO") })
    }
    @Test fun remoteRulesAndStringsContainingTheOldAliasAreNotRewritten() {
        val value = """{"route":{"rule_set":[{"type":"remote","tag":"geosite:ir","url":"https://example.org/geosite:ir"}]}}"""
        assertSame(value, MobileTinaGeositeCompatibility.rewrite(value) { error("unnecessary IO") })
    }
    @Test fun anUnreadableDatabaseIsNotSilentlyTreatedAsAnEmptyDatabase() {
        try {
            MobileTinaGeositeCompatibility.rewrite(source) { throw IllegalStateException("asset unreadable") }
            fail("must preserve the real asset error")
        } catch (expected: IllegalStateException) {
            assertEquals("asset unreadable", expected.message)
        }
    }
}
