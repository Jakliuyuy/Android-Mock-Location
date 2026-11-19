package com.mockloc.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.mockloc.data.config.MockConfig
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import de.robv.android.xposed.XSharedPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface PrefsReader {
    fun hasFileChanged(): Boolean
    fun reload()
    fun readConfig(fallback: MockConfig): MockConfig
}

class XposedPrefsReader(
    packageName: String = HookConfigStore.MOCKLOC_PACKAGE,
    prefsName: String = IpcProtocol.PREFS_NAME
) : PrefsReader {
    private val xsp: XSharedPreferences = XSharedPreferences(packageName, prefsName)

    override fun hasFileChanged(): Boolean {
        return runCatching { xsp.hasFileChanged() }.getOrDefault(true)
    }

    override fun reload() {
        runCatching { xsp.reload() }
    }

    override fun readConfig(fallback: MockConfig): MockConfig {
        return ConfigSyncSerializer.fromPrefs(xsp as SharedPreferences, fallback)
    }
}

interface ProviderConfigFetcher {
    fun fetchGlobalConfig(context: Context): Bundle?
}

object AndroidProviderConfigFetcher : ProviderConfigFetcher {
    override fun fetchGlobalConfig(context: Context): Bundle? {
        return try {
            // arg = null 以获取完整全局配置（含全局 enabled 与完整 targetPackages），避免包级裁剪
            context.contentResolver?.call(
                IpcProtocol.CONTENT_URI,
                IpcProtocol.METHOD_GET_CONFIG,
                null,
                null
            )
        } catch (_: Throwable) {
            // 兜底 Android 11+ <queries> 限制引起的 SecurityException / IllegalArgumentException
            null
        }
    }
}

class HookConfigStore internal constructor(
    private val prefsReaderFactory: () -> PrefsReader? = { runCatching { XposedPrefsReader() }.getOrNull() },
    private val providerFetcher: ProviderConfigFetcher = AndroidProviderConfigFetcher,
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val logger: (String, Throwable?) -> Unit = { msg, err -> XposedHookBackend.log(msg, err) },
    private val throttleMs: Long = DEFAULT_XSP_THROTTLE_MS
) : ConfigSnapshotProvider {

    private val configLock = Any()
    private val prefsLock = Any()

    @Volatile
    private var snapshot: MockConfig = MockConfig.DEFAULT

    @Volatile
    private var prefsReader: PrefsReader? = null

    @Volatile
    private var hasEverLoadedL1: Boolean = false

    private val lastXspCheckMs = AtomicLong(0L)
    private val receiverRegistered = AtomicBoolean(false)
    private val l3Attempted = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(MockConfig) -> Unit>()

    /**
     * 纯内存快照读取（无任何 I/O 或 IPC），专供 system_server 回调与纯缓存检查使用。
     */
    fun peekCurrentConfig(): MockConfig = snapshot

    /**
     * 普通应用进程内安全委派给带 400ms 节流的 L1 XSharedPreferences 热刷新逻辑，
     * 确保无 L2 广播或进程冻结时仍能持续感知配置热更新。
     */
    override fun currentConfig(): MockConfig {
        return runCatching {
            getConfig(LocationHook.currentAppContext, forceRefresh = false)
        }.getOrElse { snapshot }
    }

    override fun isActiveFor(packageName: String): Boolean {
        return currentConfig().isEnabledForPackage(packageName)
    }

    fun addListener(listener: (MockConfig) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun getConfig(context: Context? = null, forceRefresh: Boolean = false): MockConfig {
        val now = elapsedRealtimeProvider().coerceAtLeast(0L)
        val lastCheck = lastXspCheckMs.get()
        val due = forceRefresh || !hasEverLoadedL1 || (now - lastCheck >= throttleMs)
        if (due && (forceRefresh || lastXspCheckMs.compareAndSet(lastCheck, now))) {
            reloadFromL1(forceRefresh)
        }

        if (context != null &&
            snapshot.version == 0L &&
            snapshot.updatedAtMs == 0L &&
            l3Attempted.compareAndSet(false, true)
        ) {
            fetchFromL3ProviderSafe(context)
        }
        return snapshot
    }

    fun reloadFromL1(forceReload: Boolean = false): MockConfig {
        val loaded = synchronized(prefsLock) {
            val reader = prefsReader ?: prefsReaderFactory()?.also { prefsReader = it } ?: return@synchronized null
            val firstLoad = !hasEverLoadedL1
            val changed = try {
                firstLoad || forceReload || reader.hasFileChanged()
            } catch (_: Throwable) {
                true
            }
            if (!changed) {
                return@synchronized null
            }
            runCatching { reader.reload() }
            hasEverLoadedL1 = true
            runCatching { reader.readConfig(snapshot) }.getOrNull()
        }
        if (loaded != null) {
            applyIfNewer(loaded)
        }
        return snapshot
    }

    fun fetchFromL3ProviderSafe(context: Context): Boolean {
        return try {
            val bundle = providerFetcher.fetchGlobalConfig(context) ?: return false
            val ok = bundle.getBoolean(IpcProtocol.RESULT_OK, false)
            if (!ok) return false
            val parsed = parseBundleSafely(bundle, snapshot)
            applyIfNewer(parsed)
        } catch (t: Throwable) {
            logger("L3 LocationConfigProvider fetch ignored", t)
            false
        }
    }

    fun onBroadcastReceived(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != null && action != IpcProtocol.ACTION_CONFIG_CHANGED) {
            return false
        }
        val extras = intent.extras ?: return false
        val parsed = parseBundleSafely(extras, snapshot)
        return applyIfNewer(parsed)
    }

    fun registerConfigReceiverOnce(context: Context, onChanged: (MockConfig) -> Unit = {}) {
        addListener(onChanged)
        if (!receiverRegistered.compareAndSet(false, true)) {
            return
        }
        val targetContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onBroadcastReceived(intent)
            }
        }
        val filter = IntentFilter(IpcProtocol.ACTION_CONFIG_CHANGED)
        val registered = registerReceiverViaReflection(targetContext, receiver, filter)
        if (!registered) {
            receiverRegistered.set(false)
        }
    }

    private fun registerReceiverViaReflection(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter
    ): Boolean {
        val sdkInt = sdkIntProvider()
        if (sdkInt >= 33) {
            try {
                val method = Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Int::class.javaPrimitiveType
                )
                // Context.RECEIVER_EXPORTED == 2，无 AndroidX 运行时依赖
                method.invoke(context, receiver, filter, RECEIVER_EXPORTED_FLAG)
                return true
            } catch (_: Throwable) {
                // 降级尝试 2 参数重载
            }
        }
        return try {
            val method = Context::class.java.getMethod(
                "registerReceiver",
                BroadcastReceiver::class.java,
                IntentFilter::class.java
            )
            method.invoke(context, receiver, filter)
            true
        } catch (t: Throwable) {
            logger("L2 broadcast receiver registration skipped", t)
            false
        }
    }

    fun applyIfNewer(candidate: MockConfig): Boolean {
        val safeCandidate = candidate.sanitized(snapshot)
        var changed = false
        synchronized(configLock) {
            val current = snapshot
            if (shouldAcceptCandidate(current, safeCandidate)) {
                snapshot = safeCandidate
                changed = (current != safeCandidate)
            }
        }
        if (changed) {
            for (listener in listeners) {
                runCatching { listener(safeCandidate) }
            }
        }
        return changed
    }

    private fun shouldAcceptCandidate(current: MockConfig, candidate: MockConfig): Boolean {
        if (current.version == 0L && current.updatedAtMs == 0L && candidate != current) {
            return true
        }
        if (candidate.updatedAtMs > current.updatedAtMs) {
            return true
        }
        if (candidate.updatedAtMs < current.updatedAtMs) {
            return false
        }
        // updatedAtMs 相等时，比较版本号并处理 Long.MAX_VALUE - 1L -> 1L 双向回绕防护
        val wrappedBackward = (candidate.version - current.version) > VERSION_WRAP_THRESHOLD
        if (wrappedBackward) {
            return false
        }
        val wrappedForward = (current.version - candidate.version) > VERSION_WRAP_THRESHOLD
        if (wrappedForward || candidate.version > current.version) {
            return true
        }
        if (candidate.version == current.version && candidate != current) {
            return true
        }
        return false
    }

    private fun parseBundleSafely(bundle: Bundle, fallback: MockConfig): MockConfig {
        val parsed = ConfigSyncSerializer.fromBundle(bundle, fallback)
        return if (bundle.containsKey(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED)) {
            val globalEnabled = runCatching {
                bundle.getBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, parsed.enabled)
            }.getOrDefault(parsed.enabled)
            parsed.copy(enabled = globalEnabled).sanitized(fallback)
        } else {
            parsed
        }
    }

    fun resetForTest(initialConfig: MockConfig = MockConfig.DEFAULT) {
        synchronized(configLock) {
            snapshot = initialConfig.sanitized()
        }
        synchronized(prefsLock) {
            prefsReader = null
            hasEverLoadedL1 = false
        }
        lastXspCheckMs.set(0L)
        receiverRegistered.set(false)
        l3Attempted.set(false)
        listeners.clear()
    }

    companion object {
        const val MOCKLOC_PACKAGE = "com.mockloc"
        const val DEFAULT_XSP_THROTTLE_MS = 400L
        private const val RECEIVER_EXPORTED_FLAG = 2
        private const val VERSION_WRAP_THRESHOLD = 1_000_000L

        val shared: HookConfigStore = HookConfigStore()
    }
}
