package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.utils.GeoAssetSuggestionRepository
import libcore.Libcore
import java.io.File

internal object MobileTinaGeositeCompatibility {
    private val legacy = Regex("geosite:ir", RegexOption.IGNORE_CASE)
    @Volatile private var cached: Pair<String, Set<String>>? = null

    fun forRuntime(config: String, routingAssetsPath: String?): String {
        if (!legacy.containsMatchIn(config)) return config
        return rewrite(config) {
            val file = routingAssetsPath?.let { File(it, "geosite.db") }
                ?: GeoAssetSuggestionRepository.resolveExternalFile("geosite")
            val signature = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            cached?.takeIf { it.first == signature }?.second ?: synchronized(this) {
                cached?.takeIf { it.first == signature }?.second ?: Libcore.listGeositeCodes(file.absolutePath)
                    .lineSequence().map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it == "ir" || it == "category-ir" }
                    .toSet().also { cached = signature to it }
            }
        }
    }

    internal fun rewrite(config: String, availableCodes: () -> Set<String>): String {
        if (!legacy.containsMatchIn(config)) return config
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrNull() ?: return config
        val definitions = root.getAsJsonObject("route")?.getAsJsonArray("rule_set") ?: return config
        val affected = definitions.withIndex().filter { (_, element) ->
            element.isJsonObject && element.asJsonObject.let {
                it.get("type")?.asString == "local" && it.get("path")?.asString?.equals("geosite:ir", true) == true
            }
        }
        if (affected.isEmpty()) return config
        val codes = availableCodes()
        val code = when { "ir" in codes -> "ir"; "category-ir" in codes -> "category-ir"; else -> null }
        if (code == "ir" && affected.all { it.value.asJsonObject.get("path").asString == "geosite:ir" }) return config
        for ((index, element) in affected) {
            if (code != null) element.asJsonObject.addProperty("path", "geosite:$code")
            else definitions.set(index, JsonObject().apply {
                addProperty("type", "inline")
                element.asJsonObject.get("tag")?.let { add("tag", it.deepCopy()) }
                add("rules", JsonArray().apply { add(JsonObject().apply {
                    add("domain_suffix", JsonArray().apply { add("ir") })
                }) })
            })
        }
        return root.toString()
    }
}
