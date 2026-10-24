package io.nekohasekai.sagernet.ui

/** Counts a sequence for one subscription, never sharing taps between groups. */
internal class MobileTinaAccessCounter {
    private var group = -1L
    private var previous = 0L
    private var taps = 0
    fun tap(groupId: Long, elapsedMillis: Long): Boolean {
        if (group != groupId || elapsedMillis - previous > 2_000L || elapsedMillis < previous) taps = 0
        group = groupId
        previous = elapsedMillis
        taps++
        if (taps < 10) return false
        taps = 0
        return true
    }
}
