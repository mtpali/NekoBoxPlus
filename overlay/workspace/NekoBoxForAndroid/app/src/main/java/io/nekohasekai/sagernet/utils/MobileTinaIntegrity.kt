package io.nekohasekai.sagernet.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ProxyService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.ui.MainActivity
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Detects repackaging plus common manifest/resource edits made by APK modification tools. */
object MobileTinaIntegrity {

    private val verified = AtomicBoolean(false)
    private val verificationLock = Any()

    private val expectedDigest = byteArrayOf(-25, 115, -69, -109, -110, 94, -107, -16, -35, -82, 125, -11, -14, -120, -119, -87, -113, -12, -85, -41, -60, -121, -19, -31, -32, -117, 15, -125, -121, -26, -15, 100)

    fun verify(context: Context) {
        if (BuildConfig.DEBUG) return
        if (verified.get()) return
        synchronized(verificationLock) {
            if (verified.get()) return
            verifyOnce(context)
            verified.set(true)
        }
    }

    private fun verifyOnce(context: Context) {
        if (context.packageName != BuildConfig.APPLICATION_ID) fail()
        verifyReleaseAndManifest(context)
        val textDigest = protectedTextDigest(context)
        if (!MessageDigest.isEqual(textDigest, expectedDigest)) fail()

        if (!BuildConfig.DEBUG) {
            val signerDigest = signingCertificateSha256(context)
            verifySigningCertificate(signerDigest)
            val nonce = SystemClock.elapsedRealtimeNanos() xor
                (Process.myPid().toLong() shl 32) xor Process.myTid().toLong()
            if (!N.c(context.applicationContext, signerDigest, textDigest, nonce)) fail()
        }
    }

    fun installContinuousVerification(application: Application) {
        // Installed APKs are immutable for the lifetime of this process. Android restarts
        // the process on replacement. Verify at process start and keep connection checks;
        // re-reading all assets on every resume or a main-thread timer adds no protection.
        verify(application)
    }

    private fun verifyReleaseAndManifest(context: Context) {
        if (BuildConfig.DEBUG) return

        val packageManager = context.packageManager
        val packageInfo = packageInfo(packageManager, context.packageName)
        val applicationInfo = packageInfo.applicationInfo ?: fail()
        val forbiddenFlags = ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_TEST_ONLY
        if (applicationInfo.flags and forbiddenFlags != 0) fail()
        if (applicationInfo.className != SagerNet::class.java.name) fail()
        if (packageInfo.versionName != BuildConfig.VERSION_NAME) fail()
        if (PackageInfoCompat.longVersionCode(packageInfo) != BuildConfig.VERSION_CODE.toLong()) fail()

        val source = runCatching { File(applicationInfo.sourceDir).canonicalFile }.getOrElse { fail() }
        val publicSource = runCatching { File(applicationInfo.publicSourceDir).canonicalFile }
            .getOrElse { fail() }
        if (!source.isFile || source != publicSource || source.extension.lowercase() != "apk") fail()

        val activities = packageInfo.activities.orEmpty()
        val mainActivity = activities.singleOrNull { it.name == MainActivity::class.java.name } ?: fail()
        if (!mainActivity.exported) fail()

        val services = packageInfo.services.orEmpty()
        val proxyService = services.singleOrNull { it.name == ProxyService::class.java.name } ?: fail()
        val vpnService = services.singleOrNull { it.name == VpnService::class.java.name } ?: fail()
        if (proxyService.exported || vpnService.exported) fail()
        if (vpnService.permission != "android.permission.BIND_VPN_SERVICE") fail()
    }

    private fun verifySigningCertificate(actual: ByteArray) {
        val token = decodeSha256(BuildConfig.MOBILETINA_SIGNER_TOKEN) ?: fail()
        val expected = ByteArray(token.size) { index ->
            (token[index].toInt() xor expectedDigest[index].toInt()).toByte()
        }
        if (!MessageDigest.isEqual(actual, expected)) fail()
    }

    private fun signingCertificateSha256(context: Context): ByteArray {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        if (signatures.size != 1) fail()
        return MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
    }

    private fun protectedTextDigest(context: Context): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val texts = listOf(context.getString(R.string.app_name), context.getString(R.string.app_name_long)) +
            MobileTinaAboutContent.protectedValues() + MobileTinaWelcomeContent.welcome()
        texts.forEach { value -> digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        MobileTinaAssets.State.values().forEach { asset ->
            val data = context.resources.openRawResource(asset.rawId).use { it.readBytes() }
            digest.update(MessageDigest.getInstance("SHA-256").digest(data))
        }
        return digest.digest()
    }

    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        return packageManager.getPackageInfo(packageName, flags)
    }

    private fun decodeSha256(value: String): ByteArray? {
        if (!value.matches(Regex("[0-9a-f]{64}"))) return null
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun fail(): Nothing = throw SecurityException()

    private object PackageInfoCompat {
        fun longVersionCode(packageInfo: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode
            else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
    }
}
