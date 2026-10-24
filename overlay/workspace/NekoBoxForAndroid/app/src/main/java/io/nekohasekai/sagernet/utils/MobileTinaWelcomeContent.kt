package io.nekohasekai.sagernet.utils

import java.nio.charset.StandardCharsets

/** Raises the cost of simple resource-only APK edits. This is obfuscation, not a trust boundary. */
object MobileTinaWelcomeContent {
    private fun decode(seed: Int, values: IntArray): String {
        val bytes = ByteArray(values.size) { index ->
            (values[index] xor ((seed + index * 73) and 0xff)).toByte()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun welcome() = decode(58, intArrayOf(83,237,191,97,63,192,130,88,239,235,37,125,156,207,85,238,168,122,48,192,192,67,233,167,115,81,174,132,88,12,188,112,61,209,141,88,94,245,48,99,130,134,91,31,175,99,61,143,158,90,18,164,60,93,170,128,92,8,176,108,49,237,137,92,90,240,44,111,190,138,95,27,171,103,49,179,146,70,22,160,107))
}
