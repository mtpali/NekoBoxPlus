package io.nekohasekai.sagernet.utils

import java.io.ByteArrayOutputStream

/** Platform-independent decoder: standard/URL-safe alphabets, optional padding and line wrapping. */
internal object MobileTinaBase64 {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeText(value: String): String {
        val encoded = value.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/')
        val body = encoded.trimEnd('=')
        val padding = encoded.length - body.length
        require(body.isNotEmpty() && padding <= 2 && '=' !in body && body.length % 4 != 1)
        if (padding > 0) require(encoded.length % 4 == 0 && padding == (4 - body.length % 4) % 4)
        val output = ByteArrayOutputStream(body.length * 3 / 4)
        var bits = 0
        var buffer = 0
        body.forEach { char ->
            val digit = alphabet.indexOf(char)
            require(digit >= 0)
            buffer = (buffer shl 6) or digit
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer ushr bits) and 255)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        val bytes = output.toByteArray()
        val result = String(bytes, Charsets.UTF_8)
        require(result.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        return result
    }
}
