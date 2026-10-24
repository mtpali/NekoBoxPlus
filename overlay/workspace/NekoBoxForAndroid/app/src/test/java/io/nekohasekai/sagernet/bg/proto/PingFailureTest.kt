package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class PingFailureTest {
    @Test fun icmpNetworkAndAddressFamilyFailuresRemainUnreachable() {
        listOf("ENETUNREACH", "EHOSTUNREACH", "EAFNOSUPPORT").forEach {
            assertEquals(PingFailureKind.Unreachable, classifyPingFailure("sendto: $it"))
        }
    }
    @Test fun resolutionAndTimeoutFailuresRemainDistinct() {
        assertEquals(PingFailureKind.DomainNotFound, classifyPingFailure("lookup failed", resolution = true))
        assertEquals(PingFailureKind.Timeout, classifyPingFailure("request timed out"))
        assertEquals(PingFailureKind.Other, classifyPingFailure("unknown failure"))
    }
}
