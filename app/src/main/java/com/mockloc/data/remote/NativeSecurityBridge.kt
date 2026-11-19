package com.mockloc.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

interface SecurityBridgeGateway {
    fun getEndpointUrl(context: Context): String
    fun verifyAndGrantLease(
        context: Context,
        payload: String,
        sigHex: String,
        leaseSeconds: Long,
        effectiveEnabled: Boolean,
        minVersionCode: Int,
        currentVersionCode: Int
    ): Long
    fun checkLeaseToken(context: Context): Long
    fun revokeLease()
}

object NativeSecurityBridge : SecurityBridgeGateway {

    private val nativeLoaded: Boolean = try {
        System.loadLibrary("mocksec")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    @JvmStatic
    external fun nativeGetEndpointUrl(context: Context): String

    @JvmStatic
    external fun nativeVerifyAndGrantLease(
        context: Context,
        payload: String,
        sigHex: String,
        leaseSeconds: Long,
        effectiveEnabled: Boolean,
        minVersionCode: Int,
        currentVersionCode: Int
    ): Long

    @JvmStatic
    external fun nativeCheckLeaseToken(context: Context): Long

    @JvmStatic
    external fun nativeRevokeLease()

    override fun getEndpointUrl(context: Context): String {
        if (!nativeLoaded) return ""
        return try {
            nativeGetEndpointUrl(context)
        } catch (_: Throwable) {
            ""
        }
    }

    override fun verifyAndGrantLease(
        context: Context,
        payload: String,
        sigHex: String,
        leaseSeconds: Long,
        effectiveEnabled: Boolean,
        minVersionCode: Int,
        currentVersionCode: Int
    ): Long {
        if (!nativeLoaded) return 0L
        return try {
            nativeVerifyAndGrantLease(
                context,
                payload,
                sigHex,
                leaseSeconds,
                effectiveEnabled,
                minVersionCode,
                currentVersionCode
            )
        } catch (_: Throwable) {
            0L
        }
    }

    override fun checkLeaseToken(context: Context): Long {
        if (!nativeLoaded) return 0L
        return try {
            nativeCheckLeaseToken(context)
        } catch (_: Throwable) {
            0L
        }
    }

    override fun revokeLease() {
        if (!nativeLoaded) return
        try {
            nativeRevokeLease()
        } catch (_: Throwable) {
        }
    }
}

object DeviceSecurityUtils {

    @SuppressLint("HardwareIds")
    fun computeDeviceFingerprint(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
        val raw = "$androidId|${Build.BRAND}|${Build.MODEL}|${Build.BOARD}"
        return sha256Hex(raw.toByteArray(Charsets.UTF_8))
    }

    @Suppress("DEPRECATION")
    fun computeAppSignatureSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                val signers = if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
                signers?.firstOrNull()?.toByteArray()
            } else {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (certBytes == null) "" else sha256Hex(certBytes)
        } catch (_: Throwable) {
            ""
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
