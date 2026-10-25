package io.nekohasekai.sagernet.utils


/** A leading hash is a protection marker only when the complete remainder is valid Base64 UTF-8. */
object MobileTinaProtectedPayload {
    fun decode(input: String?): String? {
        val text = input?.trim()?.trimStart('\uFEFF', '\u200B', '\u2060')?.trim() ?: return null
        if (!text.startsWith('#')) return null
        val encoded = text.drop(1).filterNot(Char::isWhitespace)
        if (encoded.length < 8 || !encoded.matches(Regex("[A-Za-z0-9+/_=-]+"))) return null
        return runCatching {
            MobileTinaBase64.decodeText(encoded).takeIf { decoded ->
                decoded.isNotBlank() && decoded.none { c -> c.isISOControl() && c !in "\r\n\t" }
            }
        }.getOrNull()
    }

    fun cleanTitle(value: String?): String? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val before = text.substringBefore('#').trim()
        val suffix = text.substringAfter('#', "").filterNot(Char::isWhitespace)
        if (suffix.length >= 24 && suffix.matches(Regex("[A-Za-z0-9+/_=-]+"))) return before.ifBlank { null }
        if (text.length >= 32 && text.matches(Regex("[A-Za-z0-9+/_=-]+")) &&
            (text.any(Char::isDigit) || text.any { it in "+/=_-" })) return null
        return text
    }
}
