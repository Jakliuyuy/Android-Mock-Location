package com.mockloc.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.domain.geocoding.GeocodingHttpClient
import com.mockloc.domain.geocoding.HttpURLConnectionGeocodingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    val minVersionCode: Int = 1,
    val disableReason: String = DEFAULT_DISABLE_REASON,
    val announcement: RemoteAnnouncement = RemoteAnnouncement()
) {
    companion object {
        const val DEFAULT_DISABLE_REASON = "当前软件已停止服务或版本已过期，请联系发布者获取最新版本。"
    }
}

data class RemoteControlCheckResult(
    val isBlocked: Boolean,
    val blockReason: String,
    val blockActionUrl: String,
    val announcementToAutoShow: RemoteAnnouncement?,
    val latestAnnouncement: RemoteAnnouncement?,
    val fromNetwork: Boolean
)

object RemoteControlParser {

    fun parse(jsonBody: String): RemoteControlConfig? {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        // 至少包含核心控制字段之一才视为有效远程配置
        if (!root.has("appEnabled") && !root.has("minVersionCode") && !root.has("announcement")) {
            return null
        }

        val appEnabled = root.optBoolean("appEnabled", true)
        val minVersionCode = root.optInt("minVersionCode", 1).coerceAtLeast(1)
        val disableReason = root.optString("disableReason", "").trim()
            .ifEmpty { RemoteControlConfig.DEFAULT_DISABLE_REASON }

        val annObj = root.optJSONObject("announcement")
        val announcement = if (annObj != null) {
            RemoteAnnouncement(
                enabled = annObj.optBoolean("enabled", false),
                id = annObj.optString("id", "").trim(),
                title = annObj.optString("title", "").trim().ifEmpty { "系统公告" },
                message = annObj.optString("message", "").trim(),
                forceShow = annObj.optBoolean("forceShow", false),
                actionUrl = annObj.optString("actionUrl", "").trim()
            )
        } else {
            RemoteAnnouncement()
        }

        return RemoteControlConfig(
            appEnabled = appEnabled,
            minVersionCode = minVersionCode,
            disableReason = disableReason,
            announcement = announcement
        )
    }
}

class RemoteControlRepository(
    private val prefs: SharedPreferences,
    private val httpClient: GeocodingHttpClient = HttpURLConnectionGeocodingClient(
        connectTimeoutMs = 4_000,
        readTimeoutMs = 4_000
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentVersionCodeProvider: () -> Int = { 1 },
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    fun getCachedState(): RemoteControlCheckResult {
        val currentVersion = currentVersionCodeProvider()
        val cachedEnabled = runCatching { prefs.getBoolean(KEY_CACHED_APP_ENABLED, true) }.getOrDefault(true)
        val cachedMinVer = runCatching { prefs.getInt(KEY_CACHED_MIN_VERSION, 1) }.getOrDefault(1)
        val cachedReason = runCatching {
            prefs.getString(KEY_CACHED_DISABLE_REASON, RemoteControlConfig.DEFAULT_DISABLE_REASON)
        }.getOrNull().orEmpty().ifEmpty { RemoteControlConfig.DEFAULT_DISABLE_REASON }
        val cachedAnn = readCachedAnnouncement()

        val blocked = !cachedEnabled || currentVersion < cachedMinVer
        val autoShowAnn = computeAutoShowAnnouncement(cachedAnn, blocked)
        return RemoteControlCheckResult(
            isBlocked = blocked,
            blockReason = cachedReason,
            blockActionUrl = cachedAnn?.actionUrl.orEmpty(),
            announcementToAutoShow = autoShowAnn,
            latestAnnouncement = cachedAnn,
            fromNetwork = false
        )
    }

    suspend fun fetchAndEvaluate(): RemoteControlCheckResult {
        val remoteConfig = fetchRemoteConfig()
        if (remoteConfig == null) {
            return getCachedState()
        }

        saveConfigToPrefs(remoteConfig)
        val currentVersion = currentVersionCodeProvider()
        val blocked = !remoteConfig.appEnabled || currentVersion < remoteConfig.minVersionCode
        val latestAnn = remoteConfig.announcement.takeIf { it.enabled && it.message.isNotEmpty() }
        val autoShowAnn = computeAutoShowAnnouncement(latestAnn, blocked)

        return RemoteControlCheckResult(
            isBlocked = blocked,
            blockReason = remoteConfig.disableReason,
            blockActionUrl = remoteConfig.announcement.actionUrl,
            announcementToAutoShow = autoShowAnn,
            latestAnnouncement = latestAnn,
            fromNetwork = true
        )
    }

    fun markAnnouncementDismissed(announcementId: String) {
        if (announcementId.isBlank()) return
        runCatching {
            prefs.edit().putString(KEY_DISMISSED_ANN_ID, announcementId).apply()
        }
    }

    private suspend fun fetchRemoteConfig(): RemoteControlConfig? {
        val cacheBuster = timeProvider()
        for (template in REMOTE_CONFIG_URLS) {
            currentCoroutineContext().ensureActive()
            val url = "$template?t=$cacheBuster"
            val parsed = try {
                withContext(ioDispatcher) {
                    val resp = runInterruptible { httpClient.get(url) }
                    if (resp.statusCode == 200) {
                        RemoteControlParser.parse(resp.body)
                    } else {
                        null
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
                null
            }
            if (parsed != null) {
                return parsed
            }
        }
        return null
    }

    private fun computeAutoShowAnnouncement(
        ann: RemoteAnnouncement?,
        isBlocked: Boolean
    ): RemoteAnnouncement? {
        if (isBlocked || ann == null || !ann.enabled || ann.message.isEmpty()) return null
        if (ann.forceShow) return ann
        val dismissedId = runCatching { prefs.getString(KEY_DISMISSED_ANN_ID, "") }.getOrNull().orEmpty()
        return if (ann.id.isNotEmpty() && ann.id == dismissedId) null else ann
    }

    private fun saveConfigToPrefs(config: RemoteControlConfig) {
        runCatching {
            prefs.edit()
                .putBoolean(KEY_CACHED_APP_ENABLED, config.appEnabled)
                .putInt(KEY_CACHED_MIN_VERSION, config.minVersionCode)
                .putString(KEY_CACHED_DISABLE_REASON, config.disableReason)
                .putBoolean(KEY_CACHED_ANN_ENABLED, config.announcement.enabled)
                .putString(KEY_CACHED_ANN_ID, config.announcement.id)
                .putString(KEY_CACHED_ANN_TITLE, config.announcement.title)
                .putString(KEY_CACHED_ANN_MESSAGE, config.announcement.message)
                .putBoolean(KEY_CACHED_ANN_FORCE_SHOW, config.announcement.forceShow)
                .putString(KEY_CACHED_ANN_ACTION_URL, config.announcement.actionUrl)
                .apply()
        }
    }

    private fun readCachedAnnouncement(): RemoteAnnouncement? {
        val enabled = runCatching { prefs.getBoolean(KEY_CACHED_ANN_ENABLED, false) }.getOrDefault(false)
        val message = runCatching { prefs.getString(KEY_CACHED_ANN_MESSAGE, "") }.getOrNull().orEmpty()
        if (!enabled || message.isEmpty()) return null
        return RemoteAnnouncement(
            enabled = true,
            id = runCatching { prefs.getString(KEY_CACHED_ANN_ID, "") }.getOrNull().orEmpty(),
            title = runCatching { prefs.getString(KEY_CACHED_ANN_TITLE, "系统公告") }.getOrNull().orEmpty()
                .ifEmpty { "系统公告" },
            message = message,
            forceShow = runCatching { prefs.getBoolean(KEY_CACHED_ANN_FORCE_SHOW, false) }.getOrDefault(false),
            actionUrl = runCatching { prefs.getString(KEY_CACHED_ANN_ACTION_URL, "") }.getOrNull().orEmpty()
        )
    }

    companion object {
        private const val PREFS_NAME = "mockloc_remote_control"
        private const val KEY_CACHED_APP_ENABLED = "cached_app_enabled"
        private const val KEY_CACHED_MIN_VERSION = "cached_min_version"
        private const val KEY_CACHED_DISABLE_REASON = "cached_disable_reason"
        private const val KEY_CACHED_ANN_ENABLED = "cached_ann_enabled"
        private const val KEY_CACHED_ANN_ID = "cached_ann_id"
        private const val KEY_CACHED_ANN_TITLE = "cached_ann_title"
        private const val KEY_CACHED_ANN_MESSAGE = "cached_ann_message"
        private const val KEY_CACHED_ANN_FORCE_SHOW = "cached_ann_force_show"
        private const val KEY_CACHED_ANN_ACTION_URL = "cached_ann_action_url"
        private const val KEY_DISMISSED_ANN_ID = "dismissed_announcement_id"

        val REMOTE_CONFIG_URLS = listOf(
            "https://ghfast.top/https://raw.githubusercontent.com/Jakliuyuy/Android-Mock-Location/main/remote_config.json",
            "https://raw.githubusercontent.com/Jakliuyuy/Android-Mock-Location/main/remote_config.json",
            "https://fastly.jsdelivr.net/gh/Jakliuyuy/Android-Mock-Location@main/remote_config.json",
            "https://cdn.jsdelivr.net/gh/Jakliuyuy/Android-Mock-Location@main/remote_config.json"
        )

        @Volatile
        private var instance: RemoteControlRepository? = null

        fun getInstance(context: Context): RemoteControlRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                val appCtx = context.applicationContext ?: context
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val versionCodeProvider = {
                    runCatching {
                        val pkgInfo = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pkgInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pkgInfo.versionCode
                        }
                    }.getOrDefault(1)
                }
                RemoteControlRepository(
                    prefs = prefs,
                    currentVersionCodeProvider = versionCodeProvider
                ).also { instance = it }
            }
        }
    }
}
