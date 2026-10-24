package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Runtime-only sing-box 1.14 transformation; saved profiles are never rewritten. */
internal object MobileTinaIranRouting {
    fun apply(config: String, enabled: Boolean): String {
        if (!enabled) return config
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrNull() ?: return config
        val outbounds = root.getAsJsonArray("outbounds") ?: return config
        val direct = outbounds.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.let {
                it.string("type") == "direct" && it.string("tag").isNotBlank() &&
                    it.string("detour").isBlank() && it.string("override_address").isBlank()
            }
        }?.asJsonObject?.string("tag") ?: uniqueTag(outbounds, "mobiletina-ir-direct").also { tag ->
            outbounds.add(JsonObject().apply { addProperty("type", "direct"); addProperty("tag", tag) })
        }
        val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
        val rules = route.getAsJsonArray("rules") ?: JsonArray().also { route.add("rules", it) }
        if (rules.none { it.isJsonObject && it.asJsonObject.matchesSuffix() && it.asJsonObject.string("outbound") == direct }) {
            val replacement = JsonArray()
            var inserted = false
            for (rule in rules) {
                val action = if (rule.isJsonObject) rule.asJsonObject.string("action") else ""
                if (!inserted && action !in setOf("sniff", "resolve", "hijack-dns")) {
                    replacement.add(routeRule(direct)); inserted = true
                }
                replacement.add(rule)
            }
            if (!inserted) replacement.add(routeRule(direct))
            route.add("rules", replacement)
        }
        val inbounds = root.getAsJsonArray("inbounds")
        val sniffInbounds = inbounds?.filter { it.isJsonObject && it.asJsonObject.string("type") in setOf("tun", "mixed", "http", "socks") }
            ?.mapNotNull { it.asJsonObject.string("tag").takeIf(String::isNotBlank) }.orEmpty()
        val finalRules = route.getAsJsonArray("rules")
        if (sniffInbounds.isNotEmpty() && finalRules.none { it.isJsonObject && it.asJsonObject.string("action") == "sniff" }) {
            val replacement = JsonArray()
            replacement.add(JsonObject().apply {
                add("inbound", JsonArray().apply { sniffInbounds.forEach { add(it) } })
                addProperty("action", "sniff")
                add("sniffer", JsonArray().apply { add("http"); add("tls"); add("quic") })
                // sing-box sniff fills routing metadata; it does not rewrite destinations.
            })
            finalRules.forEach { replacement.add(it) }
            route.add("rules", replacement)
        }
        root.getAsJsonObject("dns")?.let { dns ->
            val servers = dns.getAsJsonArray("servers") ?: JsonArray().also { dns.add("servers", it) }
            val local = servers.firstOrNull { it.isJsonObject && it.asJsonObject.string("type") == "local" &&
                it.asJsonObject.string("tag").isNotBlank() }?.asJsonObject?.string("tag")
                ?: uniqueTag(servers, "mobiletina-ir-dns").also { tag ->
                    servers.add(JsonObject().apply { addProperty("type", "local"); addProperty("tag", tag) })
                }
            val dnsRules = dns.getAsJsonArray("rules") ?: JsonArray()
            if (dnsRules.none { it.isJsonObject && it.asJsonObject.matchesSuffix() && it.asJsonObject.string("server") == local }) {
                val replacement = JsonArray()
                replacement.add(JsonObject().apply { add("domain_suffix", suffix()); addProperty("server", local) })
                dnsRules.forEach { replacement.add(it) }
                dns.add("rules", replacement)
            }
        }
        return root.toString()
    }

    private fun routeRule(tag: String) = JsonObject().apply {
        add("domain_suffix", suffix()); addProperty("outbound", tag)
    }
    private fun suffix() = JsonArray().apply { add("ir") }
    private fun JsonObject.matchesSuffix(): Boolean = getAsJsonArray("domain_suffix")?.any {
        it.isJsonPrimitive && it.asString in setOf("ir", ".ir")
    } == true
    private fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun uniqueTag(array: JsonArray, base: String): String {
        val existing = array.filter { it.isJsonObject }.map { it.asJsonObject.string("tag") }.toSet()
        var tag = base; var suffix = 1
        while (tag in existing) { tag = "$base-${suffix++}" }
        return tag
    }
}
