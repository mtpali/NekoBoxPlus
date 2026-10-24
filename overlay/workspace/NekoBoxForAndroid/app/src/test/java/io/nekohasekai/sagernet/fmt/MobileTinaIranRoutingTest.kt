package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MobileTinaIranRoutingTest {
    private val source = """{"outbounds":[{"type":"vless","tag":"proxy","server":"example.com"}],"inbounds":[{"type":"tun","tag":"tun-in"}],"route":{"rules":[{"action":"hijack-dns","port":[53]},{"outbound":"proxy"}],"final":"proxy"},"dns":{"servers":[{"type":"https","tag":"remote","server":"example.org"}],"final":"remote"},"unknown":{"keep":true}}"""
    @Test fun retainsCatchAllDnsUnknownFieldsAndOriginalOutbound() {
        val root = JsonParser.parseString(MobileTinaIranRouting.apply(source, true)).asJsonObject
        assertTrue(root.getAsJsonObject("unknown").get("keep").asBoolean)
        assertEquals("proxy", root.getAsJsonObject("route").get("final").asString)
        assertEquals("remote", root.getAsJsonObject("dns").get("final").asString)
        assertEquals("vless", root.getAsJsonArray("outbounds")[0].asJsonObject.get("type").asString)
        val rules = root.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals("sniff", rules[0].asJsonObject.get("action").asString)
        assertEquals("hijack-dns", rules[1].asJsonObject.get("action").asString)
        assertEquals("ir", rules[2].asJsonObject.getAsJsonArray("domain_suffix")[0].asString)
        assertEquals("proxy", rules[3].asJsonObject.get("outbound").asString)
    }
    @Test fun idempotentAndDisabledPathPreservesOriginalString() {
        assertSame(source, MobileTinaIranRouting.apply(source, false))
        val once = MobileTinaIranRouting.apply(source, true)
        assertEquals(once, MobileTinaIranRouting.apply(once, true))
    }
    @Test fun chainedDirectOutboundIsNeverReused() {
        val input = """{"outbounds":[{"type":"direct","tag":"mobiletina-ir-direct","detour":"proxy"}]}"""
        val root = JsonParser.parseString(MobileTinaIranRouting.apply(input, true)).asJsonObject
        val generated = root.getAsJsonArray("outbounds")[1].asJsonObject
        assertEquals("mobiletina-ir-direct-1", generated.get("tag").asString)
        assertFalse(generated.has("detour"))
    }
    @Test fun scalarSuffixAndRulesBehindCatchAllAreHandledWithoutDroppingOtherDomains() {
        val input = """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"outbound":"proxy"},{"domain_suffix":"ir","outbound":"direct"},{"domain_suffix":["com","ir"],"outbound":"direct","network":"tcp"}]}}"""
        val root = JsonParser.parseString(MobileTinaIranRouting.apply(input, true)).asJsonObject
        val rules = root.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals("ir", rules[0].asJsonObject.getAsJsonArray("domain_suffix")[0].asString)
        assertEquals("proxy", rules[1].asJsonObject.get("outbound").asString)
        assertEquals("tcp", rules[2].asJsonObject.get("network").asString)
        assertEquals(2, rules[2].asJsonObject.getAsJsonArray("domain_suffix").size())
    }

}
