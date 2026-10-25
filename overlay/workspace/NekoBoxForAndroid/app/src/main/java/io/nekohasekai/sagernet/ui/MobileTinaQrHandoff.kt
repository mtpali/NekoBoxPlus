package io.nekohasekai.sagernet.ui

import java.util.UUID

/** Scanner and MainActivity share a process. Exported intents alone cannot authorize silent import. */
internal object MobileTinaQrHandoff {
    private val pending = linkedMapOf<String, List<String>>()
    @Synchronized fun publish(texts: List<String>): String {
        val token = UUID.randomUUID().toString()
        if (pending.size >= 4) pending.remove(pending.keys.first())
        pending[token] = texts.toList()
        return token
    }
    @Synchronized fun take(token: String): List<String>? = pending.remove(token)
}
