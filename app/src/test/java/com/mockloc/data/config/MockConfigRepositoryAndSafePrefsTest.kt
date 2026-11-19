package com.mockloc.data.config

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.domain.model.MockMode
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import com.mockloc.testing.InMemorySharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class MockConfigRepositoryAndSafePrefsTest {

    @Test
    fun `SafePrefs uses world readable when permitted and falls back to MODE_PRIVATE on SecurityException`() {
        val mockCtx = mock<Context>()
        val worldPrefs = InMemorySharedPreferences()
        val privatePrefs = InMemorySharedPreferences()

        val worldResult = SafePrefs.open(mockCtx, IpcProtocol.PREFS_NAME) { _, _, mode ->
            assertEquals(SafePrefs.MODE_WORLD_READABLE_COMPAT, mode)
            worldPrefs
        }
        assertTrue(worldResult.isWorldReadable)
        assertEquals(worldPrefs, worldResult.prefs)

        val fallbackResult = SafePrefs.open(mockCtx, IpcProtocol.PREFS_NAME) { _, _, mode ->
            if (mode == SafePrefs.MODE_WORLD_READABLE_COMPAT) {
                throw SecurityException("MODE_WORLD_READABLE no longer supported")
            }
            assertEquals(Context.MODE_PRIVATE, mode)
            privatePrefs
        }
        assertFalse(fallbackResult.isWorldReadable)
        assertEquals(privatePrefs, fallbackResult.prefs)
    }

    @Test
    fun `MockConfigRepository recovers persisted state on cold start`() {
        val prefs = InMemorySharedPreferences()
        val persisted = MockConfig(
            enabled = true,
            mode = MockMode.ROOT_ONLY,
            latitude = 31.230416,
            longitude = 121.473701,
            targetPackages = setOf("com.autonavi.minimap"),
            version = 7L
        )
        ConfigSyncSerializer.writeToPrefs(prefs.edit(), persisted).commit()

        val repo = MockConfigRepository(prefs = prefs, isWorldReadable = true)
        assertTrue(repo.currentConfig.enabled)
        assertEquals(MockMode.ROOT_ONLY, repo.currentConfig.mode)
        assertEquals(31.230416, repo.currentConfig.latitude, 1e-12)
        assertEquals(121.473701, repo.currentConfig.longitude, 1e-12)
        assertEquals(setOf("com.autonavi.minimap"), repo.currentConfig.targetPackages)
    }

    @Test
    fun `MockConfigRepository debounces frequent updates and commits immediate updates at once`() = runTest {
        val prefs = InMemorySharedPreferences()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ioScope = TestScope(dispatcher)
        var now = 1000L

        val repo = MockConfigRepository(
            prefs = prefs,
            ioScope = ioScope,
            debounceMs = 300L,
            timeProvider = { now }
        )

        // 连续两次防抖更新
        repo.updateDebounced { it.copy(enabled = true, latitude = 30.0) }
        now += 100L
        repo.updateDebounced { it.copy(latitude = 30.5) }

        // 尚未超过 300ms：内存 StateFlow 已更新，但尚未写盘
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(30.5, repo.configFlow.value.latitude, 1e-9)
        assertEquals(0, prefs.commitCount)

        // 超过 300ms 后：合并为 1 次 commit 落盘
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(1, prefs.commitCount)
        assertEquals(30.5, ConfigSyncSerializer.fromPrefs(prefs).latitude, 1e-9)

        // 再发起一次防抖，但在落盘前执行 immediate：应取消旧防抖并立刻写盘，不会被旧数据覆盖
        repo.updateDebounced { it.copy(latitude = 31.0) }
        repo.updateImmediate { it.copy(enabled = false, latitude = 32.0) }
        assertEquals(2, prefs.commitCount)

        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, prefs.commitCount)
        val finalPersisted = ConfigSyncSerializer.fromPrefs(prefs)
        assertFalse(finalPersisted.enabled)
        assertEquals(32.0, finalPersisted.latitude, 1e-9)
    }

    @Test
    fun `real Dispatchers IO slow commit race never allows stale debounce to overwrite updateImmediate or newer debounce`() = runBlocking {
        // 场景 2b：慢速旧防抖任务阻塞在 commit()，updateImmediate 并发提交最新状态
        val firstCommitEntered = CountDownLatch(1)
        val releaseFirstCommit = CountDownLatch(1)
        val commitSeq = AtomicInteger(0)
        val backing = BlockingCommitSharedPreferences(
            onBeforeCommit = {
                if (commitSeq.incrementAndGet() == 1) {
                    firstCommitEntered.countDown()
                    releaseFirstCommit.await(5, TimeUnit.SECONDS)
                }
            }
        )

        val scope2b = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = MockConfigRepository(
                prefs = backing,
                ioScope = scope2b,
                debounceMs = 15L
            )
            repo.updateDebounced { it.copy(enabled = true, latitude = 10.0) }
            assertTrue("Old debounce task did not enter commit in time", firstCommitEntered.await(5, TimeUnit.SECONDS))

            val immediateDone = CountDownLatch(1)
            thread(name = "immediate-updater") {
                repo.updateImmediate { it.copy(enabled = false, latitude = 20.0) }
                immediateDone.countDown()
            }
            // 留出竞态窗口确认 updateImmediate 等待写盘锁，不会被旧任务反超
            Thread.sleep(40L)
            releaseFirstCommit.countDown()
            assertTrue("updateImmediate did not finish in time", immediateDone.await(5, TimeUnit.SECONDS))
            repo.flushPendingSync()

            assertFalse(repo.currentConfig.enabled)
            assertEquals(20.0, repo.currentConfig.latitude, 1e-9)
            val persisted2b = ConfigSyncSerializer.fromPrefs(backing)
            assertFalse("Stale debounce overwrote enabled=false on disk!", persisted2b.enabled)
            assertEquals("Stale debounce overwrote latitude=20.0 on disk!", 20.0, persisted2b.latitude, 1e-9)
        } finally {
            scope2b.cancel()
        }

        // 场景 E4：连续防抖写盘乱序竞争（旧防抖慢速提交期间发起新防抖）
        val e4FirstEntered = CountDownLatch(1)
        val e4ReleaseFirst = CountDownLatch(1)
        val e4CommitSeq = AtomicInteger(0)
        val e4Prefs = BlockingCommitSharedPreferences(
            onBeforeCommit = {
                if (e4CommitSeq.incrementAndGet() == 1) {
                    e4FirstEntered.countDown()
                    e4ReleaseFirst.await(5, TimeUnit.SECONDS)
                }
            }
        )
        val scopeE4 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repoE4 = MockConfigRepository(
                prefs = e4Prefs,
                ioScope = scopeE4,
                debounceMs = 15L
            )
            repoE4.updateDebounced { it.copy(enabled = true, latitude = 11.11) }
            assertTrue("E4 first debounce did not enter commit in time", e4FirstEntered.await(5, TimeUnit.SECONDS))

            repoE4.updateDebounced { it.copy(enabled = false, latitude = 22.22) }
            Thread.sleep(40L)
            e4ReleaseFirst.countDown()
            repoE4.flushPendingSync()
            Thread.sleep(50L)

            assertFalse(repoE4.currentConfig.enabled)
            assertEquals(22.22, repoE4.currentConfig.latitude, 1e-9)
            val persistedE4 = ConfigSyncSerializer.fromPrefs(e4Prefs)
            assertFalse("Older debounce overwrote newer debounce enabled=false!", persistedE4.enabled)
            assertEquals("Older debounce overwrote newer debounce latitude=22.22!", 22.22, persistedE4.latitude, 1e-9)
        } finally {
            scopeE4.cancel()
        }
    }

    @Test
    fun `corrupted Long MAX_VALUE persisted version is clamped so subsequent updates still persist`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val corruptedMax = MockConfig(
            enabled = true,
            latitude = 39.9087,
            longitude = 116.3975,
            version = Long.MAX_VALUE
        )
        ConfigSyncSerializer.writeToPrefs(prefs.edit(), corruptedMax).commit()

        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = MockConfigRepository(prefs = prefs, ioScope = ioScope, debounceMs = 10L)
            assertEquals(1L, repo.currentConfig.version)

            val afterImmediate = repo.updateImmediate { it.copy(enabled = false, latitude = 31.2304) }
            assertEquals(2L, afterImmediate.version)
            val diskAfterImmediate = ConfigSyncSerializer.fromPrefs(prefs)
            assertFalse(diskAfterImmediate.enabled)
            assertEquals(31.2304, diskAfterImmediate.latitude, 1e-9)
            assertEquals(2L, diskAfterImmediate.version)

            val afterDebounced = repo.updateDebounced { it.copy(enabled = true, latitude = 22.5431) }
            assertEquals(3L, afterDebounced.version)
            repo.flushPendingSync()
            val diskAfterDebounced = ConfigSyncSerializer.fromPrefs(prefs)
            assertTrue(diskAfterDebounced.enabled)
            assertEquals(22.5431, diskAfterDebounced.latitude, 1e-9)
            assertEquals(3L, diskAfterDebounced.version)
        } finally {
            ioScope.cancel()
        }
    }

    private class BlockingCommitSharedPreferences(
        private val onBeforeCommit: () -> Unit
    ) : SharedPreferences {
        private val delegate = InMemorySharedPreferences()
        private val storeLock = Any()

        override fun getAll(): MutableMap<String, *> = synchronized(storeLock) { delegate.all }
        override fun getString(key: String?, defValue: String?): String? = synchronized(storeLock) {
            delegate.getString(key, defValue)
        }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(storeLock) {
            delegate.getStringSet(key, defValues)
        }
        override fun getInt(key: String?, defValue: Int): Int = synchronized(storeLock) {
            delegate.getInt(key, defValue)
        }
        override fun getLong(key: String?, defValue: Long): Long = synchronized(storeLock) {
            delegate.getLong(key, defValue)
        }
        override fun getFloat(key: String?, defValue: Float): Float = synchronized(storeLock) {
            delegate.getFloat(key, defValue)
        }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(storeLock) {
            delegate.getBoolean(key, defValue)
        }
        override fun contains(key: String?): Boolean = synchronized(storeLock) { delegate.contains(key) }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            synchronized(storeLock) { delegate.registerOnSharedPreferenceChangeListener(listener) }
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            synchronized(storeLock) { delegate.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        override fun edit(): SharedPreferences.Editor {
            val ops = mutableListOf<(SharedPreferences.Editor) -> Unit>()
            return object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                    ops.add { it.putString(key, value) }
                }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                    val snapshot = values?.let { HashSet(it) }
                    ops.add { it.putStringSet(key, snapshot) }
                }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                    ops.add { it.putInt(key, value) }
                }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                    ops.add { it.putLong(key, value) }
                }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                    ops.add { it.putFloat(key, value) }
                }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                    ops.add { it.putBoolean(key, value) }
                }
                override fun remove(key: String?): SharedPreferences.Editor = apply {
                    ops.add { it.remove(key) }
                }
                override fun clear(): SharedPreferences.Editor = apply {
                    ops.add { it.clear() }
                }
                override fun commit(): Boolean {
                    onBeforeCommit()
                    synchronized(storeLock) {
                        val editor = delegate.edit()
                        ops.forEach { it(editor) }
                        return editor.commit()
                    }
                }
                override fun apply() {
                    commit()
                }
            }
        }
    }
}
