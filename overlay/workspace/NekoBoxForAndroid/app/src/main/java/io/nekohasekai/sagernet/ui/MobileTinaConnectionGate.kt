package io.nekohasekai.sagernet.ui

import java.util.concurrent.atomic.AtomicLong

/** Invalidates an in-flight permission screen as well as its background probes. */
internal object MobileTinaConnectionGate {
    const val EXTRA_ATTEMPT = "com.nb4a.mobiletina.AUTO_ATTEMPT"
    private val generation = AtomicLong()
    fun begin(): Long = generation.incrementAndGet()
    fun cancel() { generation.incrementAndGet() }
    fun accepts(token: Long): Boolean = token > 0 && generation.get() == token
}
