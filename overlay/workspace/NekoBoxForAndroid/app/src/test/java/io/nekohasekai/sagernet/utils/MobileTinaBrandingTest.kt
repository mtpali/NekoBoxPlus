package io.nekohasekai.sagernet.utils

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class MobileTinaBrandingTest {
    private val names = listOf("white", "yellow", "blue", "red", "nav")
    private val hashes = listOf(
        "cd00d42eed21c4790e94ce7dbd62740304dbce31c25bc8cb2ac492bd4a95d3b3",
        "2139988a7e22839298f67991c66bf4dffe0e7e39bf276af339de7f7aca3edd67",
        "63ed57f28a66e361daa747135253d8d07ab6129f46c67b23d14a8fd397f4adb8",
        "b93250c6c425ba0722f8307d135eb530f5d54ddafb8260e06f3c2a247cef1c54",
        "b93ea494eb517d9fe940e7780dd2eab58ed5fd3994f6dd876114b8a4db4039c8",
    )
    private fun payload(name: String): ByteArray {
        val root = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.isDirectory }
        return File(root, (if (name == "nav") "mt_nav" else "mt_auto_$name") + ".bin").readBytes()
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun allThreeProfilesAndTheDefaultTitleSurviveEncoding() {
        assertEquals("instagram : mobile.tina", MobileTinaAboutContent.brandTitle())
        assertEquals(setOf("mobile.tina", "mobile.tina2", "mobile.tinaa"), MobileTinaAboutContent.socialHandles().toSet())
        MobileTinaAboutContent.socialHandles().forEach {
            assertEquals("instagram://user?username=$it", MobileTinaAboutContent.profileAppUrl(it))
            assertTrue(MobileTinaAboutContent.profileWebUrl(it).contains("/$it"))
        }
    }
    @Test fun decryptionKeepsTheOriginalFiveArtworkFilesByteForByte() {
        val seed = hashes.take(4).joinToString("|") { it.reversed() } + "|mt/assets/v2"
        val material = MessageDigest.getInstance("SHA-512").digest(seed.toByteArray())
        names.forEachIndexed { index, name ->
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(material.copyOfRange(0,32),"AES"), IvParameterSpec(material.copyOfRange(32,48)))
            assertEquals(hashes[index], hex(MessageDigest.getInstance("SHA-256").digest(cipher.doFinal(payload(name)))))
        }
    }
    private fun digest(alter: Boolean): String {
        val h = MessageDigest.getInstance("SHA-256")
        (listOf("VPN", "VPN") + MobileTinaAboutContent.protectedValues() + MobileTinaWelcomeContent.welcome()).forEach {
            h.update(it.toByteArray(Charsets.UTF_8)); h.update(0.toByte())
        }
        names.forEach { name ->
            val bytes = payload(name)
            if (alter && name == "nav") bytes[0] = (bytes[0].toInt() xor 1).toByte()
            h.update(MessageDigest.getInstance("SHA-256").digest(bytes))
        }
        return hex(h.digest())
    }
    @Test fun utf8AndArtworkDigestMatchesNativeContract() {
        assertEquals("98aa6114f619ca05de0959bb44a6b9ae4113a47198073f71a59ad527f2eb2c04", digest(false))
    }
    @Test fun editedHeaderPayloadBreaksTheNativeContract() { assertNotEquals(digest(false), digest(true)) }
}
