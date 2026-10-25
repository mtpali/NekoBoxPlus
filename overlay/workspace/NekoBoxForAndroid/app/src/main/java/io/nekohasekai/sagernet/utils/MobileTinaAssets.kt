package io.nekohasekai.sagernet.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import android.util.LruCache
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decrypts the MobileTina state artwork in memory without creating plaintext files. */
object MobileTinaAssets {
    enum class State(val rawId: Int, val sha256: String) {
        WHITE(R.raw.mt_auto_white, "cd00d42eed21c4790e94ce7dbd62740304dbce31c25bc8cb2ac492bd4a95d3b3"),
        YELLOW(R.raw.mt_auto_yellow, "2139988a7e22839298f67991c66bf4dffe0e7e39bf276af339de7f7aca3edd67"),
        BLUE(R.raw.mt_auto_blue, "63ed57f28a66e361daa747135253d8d07ab6129f46c67b23d14a8fd397f4adb8"),
        RED(R.raw.mt_auto_red, "b93250c6c425ba0722f8307d135eb530f5d54ddafb8260e06f3c2a247cef1c54"),
        NAV(R.raw.mt_nav, "b93ea494eb517d9fe940e7780dd2eab58ed5fd3994f6dd876114b8a4db4039c8"),
    }

    private val cache = object : LruCache<Int, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    fun bitmap(state: State): Bitmap? = cache.get(state.rawId) ?: decode(state)

    private fun cipherMaterial(): ByteArray {
        // Derive the scrambling material from integrity metadata already embedded in the app.
        // Nothing here is a credential or security boundary; R8 still obscures the data flow.
        val source = State.values().take(4).joinToString("|") { it.sha256.reversed() } + "|mt/assets/v2"
        return MessageDigest.getInstance("SHA-512").digest(source.toByteArray())
    }

    fun apply(imageView: ImageView, state: State) {
        val bitmap = bitmap(state)
        imageView.setImageBitmap(bitmap)
    }

    private fun decode(state: State): Bitmap? = runCatching {
        val encrypted = SagerNet.application.resources.openRawResource(state.rawId).use { it.readBytes() }
        val material = cipherMaterial()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(material.copyOfRange(0, 32), "AES"),
                IvParameterSpec(material.copyOfRange(32, 48)),
            )
        }
        val plain = cipher.doFinal(encrypted)
        val digest = MessageDigest.getInstance("SHA-256").digest(plain)
            .joinToString("") { "%02x".format(it) }
        check(digest == state.sha256) { "MobileTina asset integrity check failed" }
        BitmapFactory.decodeByteArray(plain, 0, plain.size)
            ?.also { cache.put(state.rawId, it) }
            ?: error("Unable to decode MobileTina image")
    }.onFailure(Logs::w).getOrNull()
}
