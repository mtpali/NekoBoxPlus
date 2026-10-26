package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.CancellationException

/** A memory failure disables further package-resource allocations for this screen. */
internal class AppSelectionAssetPolicy {
    @Volatile var exhausted = false
        private set

    fun <T> load(block: () -> T): T? {
        if (exhausted) return null
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { exhausted = true; null }
        catch (_: RuntimeException) { null }
    }

    companion object {
        const val MAX_ICON_PIXELS = 128
        const val CACHE_BYTES = 512 * 1024
        fun iconPixels(requested: Int): Int = requested.coerceIn(1, MAX_ICON_PIXELS)
    }
}
