package io.nekohasekai.sagernet.bg

/** Shared due-time rules for foreground and background subscription updates. */
internal object MobileTinaSubscriptionRefreshPolicy {
    const val DEFAULT_INTERVAL_MINUTES = 60
    private const val MIN_INTERVAL_MINUTES = 15

    fun isDue(
        enabled: Boolean,
        link: String?,
        connectedOnly: Boolean,
        connected: Boolean,
        lastUpdatedSeconds: Int?,
        intervalMinutes: Int?,
        nowSeconds: Long,
    ): Boolean {
        if (!enabled || link.isNullOrBlank() || connectedOnly && !connected) return false
        val lastUpdated = (lastUpdatedSeconds ?: 0).toLong()
        if (lastUpdated <= 0L) return true
        val interval = (intervalMinutes ?: DEFAULT_INTERVAL_MINUTES)
            .coerceAtLeast(MIN_INTERVAL_MINUTES).toLong() * 60L
        return nowSeconds >= lastUpdated && nowSeconds - lastUpdated >= interval
    }
}
