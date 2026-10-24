package io.nekohasekai.sagernet.bg.proto

internal enum class PingFailureKind { DomainNotFound, Refused, Unreachable, Timeout, Other }

internal fun classifyPingFailure(message: String, resolution: Boolean = false): PingFailureKind {
    if (resolution) return PingFailureKind.DomainNotFound
    return when {
        message.contains("ECONNREFUSED", ignoreCase = true) -> PingFailureKind.Refused
        message.contains("ENETUNREACH", ignoreCase = true) ||
            message.contains("EHOSTUNREACH", ignoreCase = true) ||
            message.contains("EAFNOSUPPORT", ignoreCase = true) -> PingFailureKind.Unreachable
        message.contains("deadline exceeded", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) -> PingFailureKind.Timeout
        else -> PingFailureKind.Other
    }
}
