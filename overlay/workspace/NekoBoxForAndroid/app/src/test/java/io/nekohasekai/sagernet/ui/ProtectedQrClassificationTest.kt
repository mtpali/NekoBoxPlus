package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProtectedQrClassificationTest {
    @Test fun scannerPreservesTheMarkerBeforeOrdinaryNormalization() = runBlocking {
        val text = "#" + java.util.Base64.getEncoder().encodeToString("https://example.com/sub?token=a+b".toByteArray())
        val result = QrCodeImportParser.parse(text)
        assertTrue(result is QrCodeImportResult.Protected)
        assertEquals(text, (result as QrCodeImportResult.Protected).text)
    }
}
