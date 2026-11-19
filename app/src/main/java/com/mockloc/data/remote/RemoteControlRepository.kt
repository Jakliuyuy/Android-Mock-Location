package com.mockloc.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.domain.geocoding.GeocodingHttpResponse
import com.mockloc.domain.geocoding.HttpURLConnectionGeocodingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom

data class RemoteAnnouncement(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "系统公告",
    val message: String = "",
    val forceShow: Boolean = false,
    val actionUrl: String = ""
)

data class RemoteControlConfig(
    val appEnabled: Boolean = true,
    val effectiveEnabled: Boolean = true,
    val deviceBlocked: Boolean = false,
    val minVersionCode: Int = 1,
    val serverTime: Long = 0L,
    val leaseSeconds: Long = 0L,
    val nonce: String = "",
    val signature: String = "",
    val disableReason: String = DEFAULT_DISABLE_REASON,
    val announcement: RemoteAnnouncement = RemoteAnnouncement()
) {
    fun buildCanonicalPayload(): String {
        return listOf(
            nonce,
            if (effectiveEnabled) "1" else "0",
            minVersionCode.toString(),
            serverTime.toString(),
            leaseSeconds.toString(),
            if (announcement.enabled) "1" else "0",
            announcement.id,
            if (announcement.forceShow) "1" else "0",
            announcement.title,
            announcement.message,
            announcement.actionUrl,
            disableReason
        ).joinToString("|")
    }

    companion object {
        const val DEFAULT_DISABLE_REASON = "当前软件已停止服务或版本已过期，请联系发布者获取最新版本。"
        const val DEFAULT_NETWORK_REQUIRED_REASON = "无法连接授权服务器或授权已过期。请保持网络畅通后重试。"
        const val DEFAULT_SECURITY_FAILED_REASON = "安全校验失败（签名异常或检测到非法篡改/调试环境），已禁止运行。"
    }
}

data class RemoteControlCheckResult(
    val isBlocked: Boolean,
    val blockReason: String,
    val blockActionUrl: String,
    val isNetworkRequired: Boolean = false,
    val announcementToAutoShow: RemoteAnnouncement?,
    val latestAnnouncement: RemoteAnnouncement?,
    val fromNetwork: Boolean
)

fun interface RemoteJsonPostClient {
    fun postJson(url: String, jsonBody: String): GeocodingHttpResponse
}

object RemoteControlParser {

    fun parse(jsonBody: String): RemoteControlConfig? {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        if (!root.has("signature") && !root.has("appEnabled") && !root.has("effectiveEnabled")) {
            return null
        }

        val appEnabled = root.optBoolean("appEnabled", true)
        val deviceBlocked = root.optBoolean("deviceBlocked", false)
        val effectiveEnabled = root.optBoolean("effectiveEnabled", appEnabled && !deviceBlocked)
        val minVersionCode = root.optInt("minVersionCode", 1).coerceAtLeast(1)
        val serverTime = root.optLong("serverTime", 0L)
        val leaseSeconds = root.optLong("leaseSeconds", 0L).coerceAtLeast(0L)
        val nonce = root.optString("nonce", "")
        val signature = root.optString("signature", "")
        val disableReason = root.optString("disableReason", "").ifEmpty {
            RemoteControlConfig.DEFAULT_DISABLE_REASON
        }

        val annObj = root.optJSONObject("announcement")
        val announcement = if (annObj != null) {
            RemoteAnnouncement(
                enabled = annObj.optBoolean("enabled", false),
                id = annObj.optString("id", ""),
                title = annObj.optString("title", "").ifEmpty { "系统公告" },
                message = annObj.optString("message", ""),
                forceShow = annObj.optBoolean("forceShow", false),
                actionUrl = annObj.optString("actionUrl", "")
            )
        } else {
            RemoteAnnouncement()
        }

        return RemoteControlConfig(
            appEnabled = appEnabled,
            effectiveEnabled = effectiveEnabled,
            deviceBlocked = deviceBlocked,
            minVersionCode = minVersionCode,
            serverTime = serverTime,
            leaseSeconds = leaseSeconds,
            nonce = nonce,
            signature = signature,
            disableReason = disableReason,
            announcement = announcement
        )
    }
}

class RemoteControlRepository(
    context: Context,
    private val currentVersionCode: Int,
    private val currentVersionName: String = "2.1.0",
    private val postClient: RemoteJsonPostClient = DefaultRemoteJsonPostClient(),
    private val securityBridge: SecurityBridgeGateway = NativeSecurityBridge,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deviceFingerprintProvider: () -> String = { DeviceSecurityUtils.computeDeviceFingerprint(context.applicationContext ?: context) },
    private val appSignatureProvider: () -> String = { DeviceSecurityUtils.computeAppSignatureSha256(context.applicationContext ?: context) },
    private val nonceGenerator: () -> String = { generateNonceHex() },
    private val prefs: SharedPreferences = (context.applicationContext ?: context).getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
) {

    private val appContext: Context = context.applicationContext ?: context

    private class DefaultRemoteJsonPostClient : RemoteJsonPostClient {
        private val client = HttpURLConnectionGeocodingClient(
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            maxResponseBytes = 256 * 1024
        )

        override fun postJson(url: String, jsonBody: String): GeocodingHttpResponse {
            return client.postJson(url, jsonBody)
        }
    }

    suspend fun fetchAndEvaluate(manualOpenAnnouncement: Boolean = false): RemoteControlCheckResult =
        checkAndRefresh(manualOpenAnnouncement)

    fun getCachedState(): RemoteControlCheckResult {
        val permanentRevoked = prefs.getBoolean(KEY_REVOKED_PERMANENT, false)
        if (permanentRevoked) {
            return RemoteControlCheckResult(
                isBlocked = true,
                blockReason = prefs.getString(KEY_REVOKED_REASON, RemoteControlConfig.DEFAULT_DISABLE_REASON)
                    ?: RemoteControlConfig.DEFAULT_DISABLE_REASON,
                blockActionUrl = prefs.getString(KEY_REVOKED_URL, "") ?: "",
                isNetworkRequired = false,
                announcementToAutoShow = null,
                latestAnnouncement = loadCachedAnnouncement(),
                fromNetwork = false
            )
        }
        return RemoteControlCheckResult(
            isBlocked = false,
            blockReason = "",
            blockActionUrl = "",
            isNetworkRequired = false,
            announcementToAutoShow = null,
            latestAnnouncement = loadCachedAnnouncement(),
            fromNetwork = false
        )
    }

    suspend fun checkAndRefresh(manualOpenAnnouncement: Boolean = false): RemoteControlCheckResult {
        val endpoint = securityBridge.getEndpointUrl(appContext)
        if (endpoint.isBlank()) {
            securityBridge.revokeLease()
            return RemoteControlCheckResult(
                isBlocked = true,
                blockReason = RemoteControlConfig.DEFAULT_SECURITY_FAILED_REASON,
                blockActionUrl = "",
                isNetworkRequired = false,
                announcementToAutoShow = null,
                latestAnnouncement = loadCachedAnnouncement(),
                fromNetwork = false
            )
        }

        val sentNonce = nonceGenerator()
        val requestJson = JSONObject().apply {
            put("nonce", sentNonce)
            put("packageName", appContext.packageName)
            put("versionCode", currentVersionCode)
            put("versionName", currentVersionName)
            put("deviceId", runCatching { deviceFingerprintProvider() }.getOrDefault(""))
            put("sigSha256", runCatching { appSignatureProvider() }.getOrDefault(""))
        }.toString()

        val fetchedConfig = fetchFromServer(endpoint, requestJson)
        if (fetchedConfig != null && fetchedConfig.nonce == sentNonce) {
            val effectiveEnabled = fetchedConfig.effectiveEnabled &&
                fetchedConfig.appEnabled &&
                !fetchedConfig.deviceBlocked

            val token = securityBridge.verifyAndGrantLease(
                context = appContext,
                payload = fetchedConfig.buildCanonicalPayload(),
                sigHex = fetchedConfig.signature,
                leaseSeconds = fetchedConfig.leaseSeconds,
                effectiveEnabled = effectiveEnabled,
                minVersionCode = fetchedConfig.minVersionCode,
                currentVersionCode = currentVersionCode
            )

            // 1. 服务端明确停用 / 设备被拉黑 / 版本过低
            val versionTooLow = currentVersionCode < fetchedConfig.minVersionCode
            if (!effectiveEnabled || versionTooLow) {
                securityBridge.revokeLease()
                val reason = if (versionTooLow) {
                    "当前版本（v$currentVersionName）已停用，请更新至最新版本（最低要求代码版本 ${fetchedConfig.minVersionCode}）。\n\n${fetchedConfig.disableReason}"
                } else {
                    fetchedConfig.disableReason
                }
                val actionUrl = fetchedConfig.announcement.actionUrl
                savePermanentRevoke(reason, actionUrl)
                saveCachedAnnouncement(fetchedConfig.announcement)
                return RemoteControlCheckResult(
                    isBlocked = true,
                    blockReason = reason,
                    blockActionUrl = actionUrl,
                    isNetworkRequired = false,
                    announcementToAutoShow = null,
                    latestAnnouncement = fetchedConfig.announcement.takeIf { it.enabled && it.message.isNotBlank() },
                    fromNetwork = true
                )
            }

            // 2. Ed25519 验签失败 / 重打包签名不匹配 / 调试器拦截
            if (token == 0L) {
                securityBridge.revokeLease()
                return RemoteControlCheckResult(
                    isBlocked = true,
                    blockReason = RemoteControlConfig.DEFAULT_SECURITY_FAILED_REASON,
                    blockActionUrl = "",
                    isNetworkRequired = false,
                    announcementToAutoShow = null,
                    latestAnnouncement = null,
                    fromNetwork = true
                )
            }

            // 3. 验签成功，租期授予成功
            clearPermanentRevoke()
            saveCachedAnnouncement(fetchedConfig.announcement)
            return buildAllowedResult(
                announcement = fetchedConfig.announcement,
                manualOpenAnnouncement = manualOpenAnnouncement,
                fromNetwork = true
            )
        }

        // 网络失败或响应不可解析 -> 检查本地是否已被永久封禁
        val permanentRevoked = prefs.getBoolean(KEY_REVOKED_PERMANENT, false)
        if (permanentRevoked) {
            securityBridge.revokeLease()
            return RemoteControlCheckResult(
                isBlocked = true,
                blockReason = prefs.getString(KEY_REVOKED_REASON, RemoteControlConfig.DEFAULT_DISABLE_REASON)
                    ?: RemoteControlConfig.DEFAULT_DISABLE_REASON,
                blockActionUrl = prefs.getString(KEY_REVOKED_URL, "") ?: "",
                isNetworkRequired = false,
                announcementToAutoShow = null,
                latestAnnouncement = loadCachedAnnouncement(),
                fromNetwork = false
            )
        }

        // Strict Fail-Closed：校验单调时钟租期是否仍然有效
        val activeToken = securityBridge.checkLeaseToken(appContext)
        if (activeToken == 0L) {
            return RemoteControlCheckResult(
                isBlocked = true,
                blockReason = RemoteControlConfig.DEFAULT_NETWORK_REQUIRED_REASON,
                blockActionUrl = "",
                isNetworkRequired = true,
                announcementToAutoShow = null,
                latestAnnouncement = loadCachedAnnouncement(),
                fromNetwork = false
            )
        }

        val cachedAnnouncement = loadCachedAnnouncement() ?: RemoteAnnouncement()
        return buildAllowedResult(
            announcement = cachedAnnouncement,
            manualOpenAnnouncement = manualOpenAnnouncement,
            fromNetwork = false
        )
    }

    fun hasActiveLease(): Boolean {
        if (prefs.getBoolean(KEY_REVOKED_PERMANENT, false)) {
            return false
        }
        return securityBridge.checkLeaseToken(appContext) != 0L
    }

    fun getDeniedReason(): String {
        if (prefs.getBoolean(KEY_REVOKED_PERMANENT, false)) {
            return prefs.getString(KEY_REVOKED_REASON, RemoteControlConfig.DEFAULT_DISABLE_REASON)
                ?: RemoteControlConfig.DEFAULT_DISABLE_REASON
        }
        return RemoteControlConfig.DEFAULT_NETWORK_REQUIRED_REASON
    }

    fun markAnnouncementDismissed(announcementId: String) {
        if (announcementId.isBlank()) return
        prefs.edit().putString(KEY_DISMISSED_ANN_ID, announcementId).apply()
    }

    private suspend fun fetchFromServer(endpoint: String, requestJson: String): RemoteControlConfig? =
        withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()
            runInterruptible {
                try {
                    val response = postClient.postJson(endpoint, requestJson)
                    if (response.statusCode !in 200..299 || response.body.isBlank()) {
                        return@runInterruptible null
                    }
                    RemoteControlParser.parse(response.body)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
            }
        }

    private fun buildAllowedResult(
        announcement: RemoteAnnouncement,
        manualOpenAnnouncement: Boolean,
        fromNetwork: Boolean
    ): RemoteControlCheckResult {
        val validAnnouncement = announcement.takeIf { it.enabled && it.message.isNotBlank() }
        val dismissedId = prefs.getString(KEY_DISMISSED_ANN_ID, "") ?: ""
        val shouldAutoShow = validAnnouncement != null && (
            validAnnouncement.forceShow ||
                validAnnouncement.id.isBlank() ||
                validAnnouncement.id != dismissedId
            )

        return RemoteControlCheckResult(
            isBlocked = false,
            blockReason = "",
            blockActionUrl = "",
            isNetworkRequired = false,
            announcementToAutoShow = if (manualOpenAnnouncement) null else validAnnouncement.takeIf { shouldAutoShow },
            latestAnnouncement = validAnnouncement,
            fromNetwork = fromNetwork
        )
    }

    private fun savePermanentRevoke(reason: String, actionUrl: String) {
        prefs.edit()
            .putBoolean(KEY_REVOKED_PERMANENT, true)
            .putString(KEY_REVOKED_REASON, reason)
            .putString(KEY_REVOKED_URL, actionUrl)
            .apply()
    }

    private fun clearPermanentRevoke() {
        prefs.edit()
            .putBoolean(KEY_REVOKED_PERMANENT, false)
            .remove(KEY_REVOKED_REASON)
            .remove(KEY_REVOKED_URL)
            .apply()
    }

    private fun saveCachedAnnouncement(ann: RemoteAnnouncement) {
        prefs.edit()
            .putBoolean(KEY_ANN_ENABLED, ann.enabled)
            .putString(KEY_ANN_ID, ann.id)
            .putString(KEY_ANN_TITLE, ann.title)
            .putString(KEY_ANN_MESSAGE, ann.message)
            .putBoolean(KEY_ANN_FORCE_SHOW, ann.forceShow)
            .putString(KEY_ANN_ACTION_URL, ann.actionUrl)
            .apply()
    }

    private fun loadCachedAnnouncement(): RemoteAnnouncement? {
        val enabled = prefs.getBoolean(KEY_ANN_ENABLED, false)
        val message = prefs.getString(KEY_ANN_MESSAGE, "") ?: ""
        if (!enabled || message.isBlank()) return null
        return RemoteAnnouncement(
            enabled = true,
            id = prefs.getString(KEY_ANN_ID, "") ?: "",
            title = prefs.getString(KEY_ANN_TITLE, "系统公告") ?: "系统公告",
            message = message,
            forceShow = prefs.getBoolean(KEY_ANN_FORCE_SHOW, false),
            actionUrl = prefs.getString(KEY_ANN_ACTION_URL, "") ?: ""
        )
    }

    companion object {
        private const val PREFS_NAME = "mockloc_remote_control"
        private const val KEY_REVOKED_PERMANENT = "revoked_permanent"
        private const val KEY_REVOKED_REASON = "revoked_reason"
        private const val KEY_REVOKED_URL = "revoked_url"
        private const val KEY_DISMISSED_ANN_ID = "dismissed_ann_id"

        private const val KEY_ANN_ENABLED = "ann_enabled"
        private const val KEY_ANN_ID = "ann_id"
        private const val KEY_ANN_TITLE = "ann_title"
        private const val KEY_ANN_MESSAGE = "ann_message"
        private const val KEY_ANN_FORCE_SHOW = "ann_force_show"
        private const val KEY_ANN_ACTION_URL = "ann_action_url"

        private val secureRandom = SecureRandom()

        fun generateNonceHex(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        @Volatile
        private var instance: RemoteControlRepository? = null

        fun getInstance(context: Context): RemoteControlRepository {
            return instance ?: synchronized(this) {
                instance ?: buildDefault(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDefault(context: Context): RemoteControlRepository {
            val (verCode, verName) = runCatching {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    pInfo.versionCode
                }
                code to (pInfo.versionName ?: "2.1.0")
            }.getOrDefault(1 to "2.1.0")
            return RemoteControlRepository(
                context = context,
                currentVersionCode = verCode,
                currentVersionName = verName
            )
        }
    }
}
