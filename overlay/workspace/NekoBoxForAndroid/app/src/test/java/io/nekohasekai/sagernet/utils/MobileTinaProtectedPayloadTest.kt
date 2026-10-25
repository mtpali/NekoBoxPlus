package io.nekohasekai.sagernet.utils

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MobileTinaProtectedPayloadTest {
    @Test fun hashBase64ImportsUrlWithQueryIntact() {
        val url = "https://example.com/subscription?token=a+b&client=phone"
        val encoded = Base64.getEncoder().encodeToString(url.toByteArray())
        assertEquals(url, MobileTinaProtectedPayload.decode("\uFEFF #$encoded"))
        assertEquals(url, MobileTinaImportNormalizer.subscriptionUrl("#$encoded"))
        assertNull(MobileTinaProtectedPayload.decode(encoded))
    }

    @Test fun urlSafeUnpaddedAndWrappedLinesRemainProtected() {
        val payload = "socks://127.0.0.1:1080#تست\nsocks://127.0.0.2:1081#دوم"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        assertEquals(payload, MobileTinaProtectedPayload.decode("#" + encoded.chunked(12).joinToString("\n")))
    }

    @Test fun ordinaryCommentsAndInvalidUtf8AreNotProtectedInputs() {
        assertNull(MobileTinaProtectedPayload.decode("# ordinary comment\nsocks://127.0.0.1:1080"))
        assertNull(MobileTinaProtectedPayload.decode("#not-a-valid-encoded-link!"))
        assertNull(MobileTinaProtectedPayload.decode("#//////////8="))
    }

    @Test fun opaqueTitleFragmentIsDroppedAndHumanTitleIsRetained() {
        val token = "aB02".repeat(16)
        assertEquals("اشتراک من", MobileTinaProtectedPayload.cleanTitle("اشتراک من #$token"))
        assertNull(MobileTinaProtectedPayload.cleanTitle("#$token"))
        assertNull(MobileTinaProtectedPayload.cleanTitle(token))
        assertEquals("سرویس # تهران", MobileTinaProtectedPayload.cleanTitle("سرویس # تهران"))
    }
}
