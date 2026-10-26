package io.nekohasekai.sagernet.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import java.io.InputStream
import java.io.ByteArrayOutputStream

internal data class MobileTinaAppTrafficRow(val uid: Int, val upload: Long, val download: Long) {
    val total: Long get() = if (Long.MAX_VALUE - upload < download) Long.MAX_VALUE else upload + download
}

internal data class MobileTinaAppTrafficSnapshot(
    val started: Long = 0,
    val live: Boolean = false,
    val rows: List<MobileTinaAppTrafficRow> = emptyList(),
)

/** One UID aggregate per app. No destinations, packets or connection history. */
internal object MobileTinaAppTrafficModel {
    const val MAX_ROWS = 4097
    const val MAX_BYTES = 512 * 1024
    fun rows(json: String): List<MobileTinaAppTrafficRow> = runCatching {
        if (json.length > MAX_BYTES) return emptyList()
        JsonParser.parseString(json).asJsonArray.take(MAX_ROWS).mapNotNull { value ->
            runCatching {
                val row = value.asJsonObject
                MobileTinaAppTrafficRow(row["uid"].asInt.coerceAtLeast(-1),
                    row["upload"].asLong.coerceAtLeast(0), row["download"].asLong.coerceAtLeast(0))
            }.getOrNull()?.takeIf { it.total > 0 }
        }.distinctBy { it.uid }.sortedWith(compareByDescending<MobileTinaAppTrafficRow> { it.total }.thenBy { it.uid })
    }.getOrDefault(emptyList())

    fun encode(snapshot: MobileTinaAppTrafficSnapshot): String = JsonObject().apply {
        addProperty("started", snapshot.started)
        addProperty("live", snapshot.live)
        add("rows", JsonArray().apply {
            snapshot.rows.take(MAX_ROWS).forEach { row ->
                add(JsonObject().apply {
                    addProperty("uid", row.uid); addProperty("upload", row.upload); addProperty("download", row.download)
                })
            }
        })
    }.toString()

    fun decode(json: String): MobileTinaAppTrafficSnapshot = runCatching {
        require(json.length <= MAX_BYTES)
        val value = JsonParser.parseString(json).asJsonObject
        MobileTinaAppTrafficSnapshot(value["started"].asLong,
            value["live"]?.asBoolean == true, rows(value["rows"].toString()))
    }.getOrDefault(MobileTinaAppTrafficSnapshot())

    fun read(input: InputStream): MobileTinaAppTrafficSnapshot {
        val output = ByteArrayOutputStream(1024)
        val buffer = ByteArray(4096)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count < 0) return decode(output.toString("UTF-8"))
            if (count == 0) return MobileTinaAppTrafficSnapshot()
            output.write(buffer, 0, count)
        }
        return MobileTinaAppTrafficSnapshot()
    }

    fun volume(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        if (bytes < 1000) return "<0.001 MB"
        return if (bytes >= 1_000_000_000L) String.format(Locale.ROOT, "%.3f GB", bytes / 1_000_000_000.0)
            else String.format(Locale.ROOT, "%.3f MB", bytes / 1_000_000.0)
    }
}

/** A clear takes a baseline even for open connections; later bytes are still counted. */
internal class MobileTinaAppTrafficPeriod(var started: Long) {
    private var baseline = emptyMap<Int, MobileTinaAppTrafficRow>()
    fun clear(raw: List<MobileTinaAppTrafficRow>, now: Long) {
        baseline = raw.associateBy { it.uid }
        started = now
    }
    fun snapshot(raw: List<MobileTinaAppTrafficRow>, live: Boolean = true): MobileTinaAppTrafficSnapshot =
        MobileTinaAppTrafficSnapshot(started, live, raw.mapNotNull { row ->
            val before = baseline[row.uid]
            row.copy(upload = (row.upload - (before?.upload ?: 0)).coerceAtLeast(0),
                download = (row.download - (before?.download ?: 0)).coerceAtLeast(0)).takeIf { it.total > 0 }
        }.sortedWith(compareByDescending<MobileTinaAppTrafficRow> { it.total }.thenBy { it.uid }))
}
