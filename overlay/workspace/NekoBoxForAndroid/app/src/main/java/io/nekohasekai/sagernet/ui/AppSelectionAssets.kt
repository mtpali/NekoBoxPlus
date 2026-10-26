package io.nekohasekai.sagernet.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Activity-owned resources: labels on IO; only visible icons, one decode at a time. */
internal class AppSelectionAssets(private val packageManager: PackageManager) {
    private val policy = AppSelectionAssetPolicy()
    private val iconMutex = Mutex()
    private val unavailable = mutableSetOf<String>()
    @Volatile private var closed = false
    private val icons = object : LruCache<String, Bitmap>(AppSelectionAssetPolicy.CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun label(info: ApplicationInfo): String {
        if (closed) return info.packageName
        return policy.load { info.loadLabel(packageManager).toString() }?.takeIf { it.isNotBlank() } ?: info.packageName
    }

    suspend fun icon(packageName: String, requestedPixels: Int): Bitmap? = withContext(Dispatchers.IO) {
        iconMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (closed || policy.exhausted || packageName in unavailable) return@withLock null
            val pixels = AppSelectionAssetPolicy.iconPixels(requestedPixels)
            val key = "$packageName:$pixels"
            icons.get(key)?.let { return@withLock it }
            val bitmap = policy.load {
                val info = PackageCache.installedApps[packageName] ?: return@load null
                if (info.icon == 0) return@load null
                // getDrawable returns null for a missing resource; never decode the platform default.
                val drawable = packageManager.getDrawable(packageName, info.icon, info) ?: return@load null
                val target = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
                try {
                    drawable.setBounds(0, 0, pixels, pixels)
                    drawable.draw(Canvas(target))
                    target
                } catch (error: Throwable) {
                    target.recycle()
                    throw error
                }
            }
            if (policy.exhausted) icons.evictAll()
            currentCoroutineContext().ensureActive()
            if (closed) return@withLock null
            if (bitmap != null) icons.put(key, bitmap) else unavailable.add(packageName)
            bitmap
        }
    }

    fun close() {
        closed = true
        // Visible rows may still reference a bitmap; let ownership/GC release it, never recycle it here.
        icons.evictAll()
    }
}
