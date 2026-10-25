package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class MobileTinaExpiryPolicyTest {
    @Test fun explicitOffsetAndTehranLocalDateReferToTheSameInstant() {
        assertEquals(MobileTinaSubscriptionExpiry.parseDate("2026-09-04T12:00:00+03:30"),
            MobileTinaSubscriptionExpiry.parseDate("2026-09-04 12:00:00"))
    }
    @Test fun validRenewalClearsACommentTimerButInvalidPayloadDoesNot() {
        assertEquals(true to null, MobileTinaSubscriptionExpiry.commentExpiry("""{"outbounds":[]}"""))
        assertEquals(false to null, MobileTinaSubscriptionExpiry.commentExpiry("""{"outbounds":[],"_comment":"bad timestamp"}"""))
        assertEquals(false to null, MobileTinaSubscriptionExpiry.commentExpiry("partial {"))
    }
    @Test fun supportedIsoCommentCarriesTheExactInstant() {
        val result = MobileTinaSubscriptionExpiry.commentExpiry("""{"outbounds":[],"_comment":"2030-01-01T00:00:00Z"}""")
        assertTrue(result.first)
        assertEquals(1893456000000L, result.second)
    }
    @Test fun requestedLocalTimestampUsesTehranAndSurvivesHashBase64Envelope() {
        val json = """{"outbounds":[],"_comment":"2026-09-06T11:23:00"}"""
        val expected = java.time.Instant.parse("2026-09-06T07:53:00Z").toEpochMilli()
        assertEquals(true to expected, MobileTinaSubscriptionExpiry.commentExpiry(json))
        val wrapped = "#" + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        assertEquals(true to expected, MobileTinaSubscriptionExpiry.commentExpiry(wrapped))
    }
}
