package com.mockloc.hook

import android.content.Context
import android.content.Intent
import com.mockloc.data.config.MockConfig
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import com.mockloc.testing.createStatefulMockBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class IpcHookSyncClientTest {

    @Test
    fun `L1 XSharedPreferences throttles reload when file unchanged and reloads hot updates`() {
        val nowMs = AtomicLong(1_000L)
        val initialCfg = MockConfig(
            enabled = true,
            latitude = 39.9087,
            longitude = 116.3975,
            targetPackages = setOf("com.tencent.mm"),
            updatedAtMs = 100L,
            version = 1L
        )
        val fakeReader = FakePrefsReader(initialCfg)
        val store = HookConfigStore(
            prefsReaderFactory = { fakeReader },
            elapsedRealtimeProvider = { nowMs.get() },
            throttleMs = 400L
        )

        // 首次冷加载
        val first = store.getConfig()
        assertEquals(39.9087, first.latitude, 1e-6)
        assertEquals(1, fakeReader.reloadCount)
        assertTrue(store.isActiveFor("com.tencent.mm"))

        // 节流窗口内且无变化不触发额外 reload
        nowMs.set(1_200L)
        store.getConfig()
        assertEquals(1, fakeReader.reloadCount)

        // 超过节流窗口但文件未变更，也不触发 reload
        nowMs.set(1_600L)
        store.getConfig()
        assertEquals(1, fakeReader.reloadCount)

        // 配置热更新并标记文件已变更（彻底废除一次性 isConfigSynced 锁死）
        val updatedCfg = initialCfg.copy(
            latitude = 31.2304,
            longitude = 121.4737,
            updatedAtMs = 200L,
            version = 2L
        )
        fakeReader.writeConfig(updatedCfg, markChanged = true)
        nowMs.set(2_100L)

        val refreshed = store.getConfig()
        assertEquals(2, fakeReader.reloadCount)
        assertEquals(31.2304, refreshed.latitude, 1e-6)
        assertEquals(121.4737, refreshed.longitude, 1e-6)
    }

    @Test
    fun `L2 explicit broadcast hot update overrides older L1 state and notifies listeners`() {
        val store = HookConfigStore(
            prefsReaderFactory = { null },
            elapsedRealtimeProvider = { 1000L }
        )
        var listenerTriggered = false
        store.addListener { cfg ->
            if (cfg.latitude == 22.5431) listenerTriggered = true
        }

        val bundle = ConfigSyncSerializer.toBundle(
            MockConfig(
                enabled = true,
                latitude = 22.5431,
                longitude = 114.0579,
                targetPackages = setOf("com.autonavi.minimap"),
                updatedAtMs = 500L,
                version = 3L
            ),
            createStatefulMockBundle()
        )
        val intent = mock<Intent> {
            on { action } doReturn IpcProtocol.ACTION_CONFIG_CHANGED
            on { extras } doReturn bundle
        }

        assertTrue(store.onBroadcastReceived(intent))
        assertTrue(listenerTriggered)
        assertEquals(22.5431, store.currentConfig().latitude, 1e-6)
        assertTrue(store.isActiveFor("com.autonavi.minimap"))

        // 旧版本广播不得回退新配置
        val staleBundle = ConfigSyncSerializer.toBundle(
            MockConfig(
                enabled = false,
                latitude = 10.0,
                longitude = 10.0,
                updatedAtMs = 400L,
                version = 2L
            ),
            createStatefulMockBundle()
        )
        val staleIntent = mock<Intent> {
            on { action } doReturn IpcProtocol.ACTION_CONFIG_CHANGED
            on { extras } doReturn staleBundle
        }
        assertFalse(store.onBroadcastReceived(staleIntent))
        assertEquals(22.5431, store.currentConfig().latitude, 1e-6)
    }

    @Test
    fun `L3 LocationConfigProvider fallback recovers global config and handles queries SecurityException`() {
        val bundle = ConfigSyncSerializer.toBundle(
            MockConfig(
                enabled = true,
                latitude = 30.5728,
                longitude = 104.0668,
                targetPackages = setOf("com.sankuai.meituan"),
                updatedAtMs = 999L,
                version = 5L
            ),
            createStatefulMockBundle()
        ).apply {
            putBoolean(IpcProtocol.RESULT_OK, true)
            putBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, true)
        }

        val store = HookConfigStore(
            prefsReaderFactory = { null },
            providerFetcher = object : ProviderConfigFetcher {
                override fun fetchGlobalConfig(context: Context) = bundle
            }
        )
        val ctx = mock<Context>()
        val cfg = store.getConfig(ctx)
        assertEquals(30.5728, cfg.latitude, 1e-6)
        assertTrue(store.isActiveFor("com.sankuai.meituan"))
        assertFalse(store.isActiveFor("com.other.app"))

        // Android 11+ <queries> 抛 SecurityException 时安全降级不崩溃
        val throwingStore = HookConfigStore(
            prefsReaderFactory = { null },
            providerFetcher = object : ProviderConfigFetcher {
                override fun fetchGlobalConfig(context: Context): android.os.Bundle? {
                    throw SecurityException("Failed to find provider info for com.mockloc.configprovider")
                }
            }
        )
        val safeCfg = throwingStore.getConfig(ctx)
        assertFalse(safeCfg.enabled)
    }

    @Test
    fun `version wrap-around from Long MAX_VALUE - 1 to 1 is accepted monotonically`() {
        val store = HookConfigStore(prefsReaderFactory = { null })
        val maxCfg = MockConfig(
            enabled = true,
            latitude = 39.0,
            longitude = 116.0,
            updatedAtMs = 1000L,
            version = Long.MAX_VALUE - 1L
        )
        assertTrue(store.applyIfNewer(maxCfg))

        // 同毫秒下发生版本回绕到 1L，必须识别为新版本而非旧版本
        val wrappedCfg = maxCfg.copy(
            latitude = 40.0,
            version = 1L
        )
        assertTrue(store.applyIfNewer(wrappedCfg))
        assertEquals(40.0, store.currentConfig().latitude, 1e-6)

        // 回绕后到达的迟到旧包（Long.MAX_VALUE - 1L）必须被对称反向回绕逻辑拒收
        assertFalse(store.applyIfNewer(maxCfg.copy(latitude = 39.0)))
        assertEquals(40.0, store.peekCurrentConfig().latitude, 1e-6)
        assertEquals(1L, store.peekCurrentConfig().version)
    }

    @Test
    fun `currentConfig and isActiveFor delegate to throttled L1 XSharedPreferences without L2 broadcast`() {
        val nowMs = AtomicLong(1_000L)
        val initialCfg = MockConfig(
            enabled = false,
            latitude = 39.9087,
            longitude = 116.3975,
            targetPackages = setOf("com.tencent.mm"),
            updatedAtMs = 100L,
            version = 1L
        )
        val fakeReader = FakePrefsReader(initialCfg)
        val store = HookConfigStore(
            prefsReaderFactory = { fakeReader },
            elapsedRealtimeProvider = { nowMs.get() },
            throttleMs = 400L
        )

        assertFalse(store.isActiveFor("com.tencent.mm"))
        assertEquals(1, fakeReader.reloadCount)

        // 无 L2 广播下更新 L1 文件
        fakeReader.writeConfig(
            initialCfg.copy(enabled = true, latitude = 31.2304, updatedAtMs = 200L, version = 2L),
            markChanged = true
        )

        // 400ms 窗口内节流不触发 reload
        nowMs.set(1_200L)
        assertFalse(store.isActiveFor("com.tencent.mm"))
        assertEquals(1, fakeReader.reloadCount)

        // 超过 400ms 窗口后，currentConfig() / isActiveFor() 自动完成 L1 热刷新
        nowMs.set(1_500L)
        assertTrue(store.isActiveFor("com.tencent.mm"))
        assertEquals(31.2304, store.currentConfig().latitude, 1e-6)
        assertEquals(2, fakeReader.reloadCount)
    }

    @Test
    fun `concurrent L1 reload and L2 updates never regress to stale version`() {
        val fakeReader = FakePrefsReader(MockConfig.DEFAULT)
        val store = HookConfigStore(
            prefsReaderFactory = { fakeReader },
            throttleMs = 0L
        )
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(40)

        for (i in 1..40) {
            pool.execute {
                try {
                    val cfg = MockConfig(
                        enabled = true,
                        latitude = 30.0 + i * 0.001,
                        longitude = 120.0,
                        targetPackages = setOf("com.tencent.mm"),
                        updatedAtMs = i.toLong() * 10L,
                        version = i.toLong()
                    )
                    store.applyIfNewer(cfg)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(40L, store.currentConfig().version)
        assertEquals(30.04, store.currentConfig().latitude, 1e-6)
    }
}
