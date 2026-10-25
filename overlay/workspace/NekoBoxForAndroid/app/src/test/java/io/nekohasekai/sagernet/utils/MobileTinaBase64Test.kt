package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test

class MobileTinaBase64Test {
    @Test fun rfc4648VectorsWorkWithAndWithoutPadding() {
        mapOf("Zg==" to "f", "Zm8=" to "fo", "Zm9v" to "foo", "Zm9vYg==" to "foob",
            "Zm9vYmE=" to "fooba", "Zm9vYmFy" to "foobar").forEach { (encoded, plain) ->
            assertEquals(plain, MobileTinaBase64.decodeText(encoded))
            assertEquals(plain, MobileTinaBase64.decodeText(encoded.trimEnd('=')))
        }
    }
    @Test fun urlSafeAlphabetAndWrappedUnicodeRoundTrip() {
        val text = "socks://127.0.0.1:1080#اشتراک 😀"
        for (encoder in listOf(java.util.Base64.getEncoder(), java.util.Base64.getUrlEncoder().withoutPadding())) {
            val encoded = encoder.encodeToString(text.toByteArray()).chunked(11).joinToString("\n")
            assertEquals(text, MobileTinaBase64.decodeText(encoded))
        }
    }
    @Test fun malformedPaddingLengthAlphabetAndUtf8AreRejected() {
        for (value in listOf("Z=g=", "A", "Zg=", "!!!!", "////")) {
            assertThrows(IllegalArgumentException::class.java) { MobileTinaBase64.decodeText(value) }
        }
    }
}
