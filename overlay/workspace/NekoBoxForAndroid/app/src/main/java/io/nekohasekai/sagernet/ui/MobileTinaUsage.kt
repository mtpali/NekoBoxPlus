package io.nekohasekai.sagernet.ui

import java.util.Locale

internal data class MobileTinaUsage(
    val used: Long?,
    val total: Long?,
    val expiresAt: Long?,
) {
    val visible: Boolean get() = used != null || total != null || expiresAt != null
    val fraction: Float get() = if (total != null && total > 0 && used != null) {
        (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    } else 0f

    companion object {
        private val pair = Regex("(?:^|[;,\\s])([a-z]+)\\s*=\\s*([0-9]+)", RegexOption.IGNORE_CASE)
        fun parse(header: String?, fallbackExpiry: Long = 0L): MobileTinaUsage {
            val values = pair.findAll(header.orEmpty()).mapNotNull {
                val value = it.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                it.groupValues[1].lowercase(Locale.ROOT) to value
            }.toMap()
            val upload = values["upload"]
            val download = values["download"]
            val used = if (upload != null || download != null) {
                val a = upload ?: 0L
                val b = download ?: 0L
                if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
            } else null
            return MobileTinaUsage(
                used,
                values["total"],
                values["expire"]?.takeIf { it > 0 } ?: fallbackExpiry.takeIf { it > 0 },
            )
        }
    }
}
