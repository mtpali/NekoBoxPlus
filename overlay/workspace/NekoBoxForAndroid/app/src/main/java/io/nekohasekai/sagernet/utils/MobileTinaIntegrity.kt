package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sagernet.BuildConfig
import java.security.MessageDigest

/** Release signer pin; this is a tamper check, never a claim of unbreakable DRM. */
object MobileTinaIntegrity {
    @Suppress("DEPRECATION")
    fun verify(context: Context) {
        if (BuildConfig.DEBUG) return
        check(context.packageName == BuildConfig.APPLICATION_ID)
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val expected = BuildConfig.MOBILETINA_SIGNER_SHA256.lowercase()
        check(expected.matches(Regex("[a-f0-9]{64}")))
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        check(signatures.any { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(digest.toByteArray(), expected.toByteArray())
        })
    }
}
