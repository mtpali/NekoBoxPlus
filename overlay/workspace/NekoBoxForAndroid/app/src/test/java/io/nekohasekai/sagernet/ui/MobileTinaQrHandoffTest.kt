package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaQrHandoffTest {
    @Test fun aScannerTokenCanBeConsumedOnlyOnceAndUnknownTokensCannotImport() {
        val texts = arrayListOf("https://example.test/sub", "#c29ja3M6Ly8xOjE=")
        val token = MobileTinaQrHandoff.publish(texts)
        texts.clear()
        assertEquals(listOf("https://example.test/sub", "#c29ja3M6Ly8xOjE="), MobileTinaQrHandoff.take(token))
        assertNull(MobileTinaQrHandoff.take(token))
        assertNull(MobileTinaQrHandoff.take("external-application-supplied-token"))
    }
    @Test fun abandonedScannerHandoffsHaveABoundedMemoryFootprint() {
        val abandoned = MobileTinaQrHandoff.publish(listOf("first"))
        val tokens = (1..4).map { MobileTinaQrHandoff.publish(listOf(it.toString())) }
        assertNull(MobileTinaQrHandoff.take(abandoned))
        tokens.forEachIndexed { index, token -> assertEquals(listOf((index + 1).toString()), MobileTinaQrHandoff.take(token)) }
    }
}
