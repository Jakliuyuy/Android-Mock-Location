package com.mockloc.data.favorite

import android.content.SharedPreferences
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.FavoriteLocation
import com.mockloc.domain.model.GeoPoint
import com.mockloc.ipc.IpcProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FavoriteRepositoryTest {

    private class ThreadSafeTestPrefs : SharedPreferences {
        private val lock = Any()
        private val map = LinkedHashMap<String, Any?>()
        val failNextCommit = AtomicBoolean(false)
        val alwaysFailCommit = AtomicBoolean(false)
        val throwOnCommit = AtomicBoolean(false)

        fun putRaw(key: String, value: Any?) {
            synchronized(lock) {
                map[key] = value
            }
        }

        override fun getAll(): MutableMap<String, *> = synchronized(lock) { LinkedHashMap(map) }

        override fun getString(key: String?, defValue: String?): String? = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as String?
        }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(lock) {
                if (key == null || !map.containsKey(key)) defValues
                else (map[key] as? Set<String>)?.let { HashSet(it) } ?: defValues
            }

        override fun getInt(key: String?, defValue: Int): Int = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Int
        }

        override fun getLong(key: String?, defValue: Long): Long = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Long
        }

        override fun getFloat(key: String?, defValue: Float): Float = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Float
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Boolean
        }

        override fun contains(key: String?): Boolean = synchronized(lock) {
            key != null && map.containsKey(key)
        }

        override fun edit(): SharedPreferences.Editor = EditorImpl()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.let { HashSet(it) }
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = REMOVED }
            override fun clear() = apply { clearFlag = true }

            override fun commit(): Boolean {
                if (throwOnCommit.get()) {
                    throw RuntimeException("simulated commit exception")
                }
                if (alwaysFailCommit.get() || failNextCommit.compareAndSet(true, false)) {
                    return false
                }
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() = synchronized(lock) {
                if (clearFlag) map.clear()
                for ((k, v) in pending) {
                    if (v === REMOVED) map.remove(k) else map[k] = v
                }
            }
        }

        private object REMOVED
    }

    @Test
    fun `TC-F1 first init populates 5 valid WGS84 defaults and syncs StateFlow`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()
        val clock = AtomicLong(1_700_000_000_000L)
        val repo = FavoriteRepository(
            prefs = prefs,
            ioDispatcher = dispatcher,
            ioScope = TestScope(dispatcher),
            timeProvider = { clock.get() }
        )

        val all = repo.getAll()
        assertEquals(5, all.size)
        assertTrue(prefs.getBoolean(FavoriteRepository.KEY_INITIALIZED, false))
        assertEquals(all, repo.observeFavorites().value)
        for (fav in all) {
            assertTrue(fav.id.isNotBlank())
            assertTrue(fav.name.isNotBlank())
            assertTrue(GeoPoint.isValid(fav.latitude, fav.longitude))
            assertEquals(CoordinateType.WGS84, fav.coordinateType)
        }
    }

    @Test
    fun `TC-F2 user clear or remove all does not resurrect defaults after cold restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()

        val repo1 = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val initial = repo1.getAll()
        assertEquals(5, initial.size)

        // 逐条删除全部默认地点
        for (item in initial) {
            assertTrue(repo1.remove(item.id))
        }
        assertTrue(repo1.getAll().isEmpty())
        assertTrue(prefs.getBoolean(FavoriteRepository.KEY_INITIALIZED, false))

        // 冷启重建仓库
        val repo2 = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue("Deleted favorites must not resurrect on restart", repo2.getAll().isEmpty())

        // 新增后使用 clearAll 清空再冷启
        assertTrue(repo2.add("测试点", "测试地址", 30.0, 120.0).isSuccess)
        assertTrue(repo2.clearAll())
        val repo3 = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo3.getAll().isEmpty())

        // 显式 resetToDefaults 可恢复
        val reset = repo3.resetToDefaults().getOrThrow()
        assertEquals(5, reset.size)
    }

    @Test
    fun `TC-F3 CRUD update all branches and search`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()
        val idSeq = AtomicInteger(1)
        val clock = AtomicLong(1_700_000_000_000L)

        val repo = FavoriteRepository(
            prefs = prefs,
            ioDispatcher = dispatcher,
            ioScope = TestScope(dispatcher),
            timeProvider = { clock.incrementAndGet() },
            idGenerator = { "fav_${idSeq.getAndIncrement()}" }
        )
        repo.ensureInitialized()

        val added = repo.add(
            name = "中关村软件园",
            address = "北京市海淀区东北旺西路8号",
            latitude = 40.0489,
            longitude = 116.2983,
            altitude = 52.0
        ).getOrThrow()
        assertEquals("fav_1", added.id)
        assertEquals("中关村软件园", repo.getById(added.id)?.name)

        // 1) 仅重命名，保留地址与坐标及创建时间
        val renamedOnly = repo.update(id = added.id, name = "百度科技园").getOrThrow()
        assertEquals("百度科技园", renamedOnly.name)
        assertEquals("北京市海淀区东北旺西路8号", renamedOnly.address)
        assertEquals(40.0489, renamedOnly.latitude, 1e-7)
        assertEquals(116.2983, renamedOnly.longitude, 1e-7)
        assertEquals(added.createdAtMs, renamedOnly.createdAtMs)

        // 2) 重命名 + 修改地址，保留坐标
        val renamedWithAddr = repo.update(
            id = added.id,
            name = "海淀软件园",
            address = "北京市海淀区上地十街"
        ).getOrThrow()
        assertEquals("海淀软件园", renamedWithAddr.name)
        assertEquals("北京市海淀区上地十街", renamedWithAddr.address)
        assertEquals(40.0489, renamedWithAddr.latitude, 1e-7)

        // 3) 同时修改坐标
        val updatedWithPoint = repo.update(
            id = added.id,
            name = "浦东张江",
            address = "上海市浦东新区张江高科",
            point = GeoPoint(31.2030, 121.6020, 12.0)
        ).getOrThrow()
        assertEquals("浦东张江", updatedWithPoint.name)
        assertEquals("上海市浦东新区张江高科", updatedWithPoint.address)
        assertEquals(31.2030, updatedWithPoint.latitude, 1e-7)
        assertEquals(121.6020, updatedWithPoint.longitude, 1e-7)

        // 4) 失败路径：不存在 id / 空名称
        assertTrue(repo.update("non_existent", "名称").isFailure)
        assertTrue(repo.update(added.id, "   ").isFailure)

        // 5) search 查询
        val searchRes = repo.search("浦东 张江")
        assertEquals(1, searchRes.size)
        assertEquals(added.id, searchRes[0].id)
        assertEquals(repo.getAll(), repo.search("   "))

        // 6) 冷启重读持久化验证
        val reloaded = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val fetched = reloaded.getById(added.id)
        assertNotNull(fetched)
        assertEquals("浦东张江", fetched!!.name)
        assertEquals(31.2030, fetched.latitude, 1e-7)
    }

    @Test
    fun `TC-F4 markUsed increments stats sorts to top and persists across restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()
        val clock = AtomicLong(1_700_000_000_000L)

        val repo = FavoriteRepository(
            prefs = prefs,
            ioDispatcher = dispatcher,
            ioScope = TestScope(dispatcher),
            timeProvider = { clock.addAndGet(1000L) }
        )
        val initial = repo.getAll()
        val lastItem = initial.last()

        assertTrue(repo.markUsed(lastItem.id))
        assertTrue(repo.markUsed(lastItem.id))
        assertFalse(repo.markUsed("missing_id"))

        val stats = repo.getUsageStats(lastItem.id)
        assertNotNull(stats)
        assertEquals(2, stats!!.useCount)
        assertTrue(stats.lastUsedAtMs > 0L)
        assertEquals(lastItem.id, repo.getAll().first().id)
        assertEquals(2, repo.observeUsageStats().value[lastItem.id]?.useCount)

        val restarted = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertEquals(lastItem.id, restarted.getAll().first().id)
        assertEquals(2, restarted.getUsageStats(lastItem.id)?.useCount)
    }

    @Test
    fun `TC-F5 deduplication and strict bounds validation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()
        val repo = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val baseCount = repo.getAll().size

        // 同名且距离 < 1e-4° 触发去重合并，数量不变
        val dupNear = repo.add("北京天安门", "更新后地址", 39.90872, 116.39752).getOrThrow()
        assertEquals("default_beijing_tiananmen", dupNear.id)
        assertEquals(baseCount, repo.getAll().size)

        // 极近坐标 (< 1e-6°) 即使改名也触发去重合并
        val dupExactCoord = repo.add("天安门中心点", "", 39.9087202, 116.3975202).getOrThrow()
        assertEquals("default_beijing_tiananmen", dupExactCoord.id)
        assertEquals(baseCount, repo.getAll().size)

        // 非法名称 / 越界 / NaN / Infinity 拦截，且不污染存储
        val badInputs = listOf(
            FavoriteLocation("b1", "  ", "", 39.9, 116.4),
            FavoriteLocation("b2", "北极越界", "", 90.001, 116.4),
            FavoriteLocation("b3", "南极越界", "", -90.001, 116.4),
            FavoriteLocation("b4", "经度越界", "", 39.9, 180.001),
            FavoriteLocation("b5", "经度负越界", "", 39.9, -180.001),
            FavoriteLocation("b6", "NaN坐标", "", Double.NaN, 116.4),
            FavoriteLocation("b7", "Inf坐标", "", 39.9, Double.POSITIVE_INFINITY)
        )
        for (bad in badInputs) {
            assertTrue("Input ${bad.id} must be rejected", repo.add(bad).isFailure)
        }
        assertEquals(baseCount, repo.getAll().size)

        // commit 失败时不更新内存
        prefs.failNextCommit.set(true)
        assertTrue(repo.add("写盘失败测试", "", 28.0, 112.0).isFailure)
        assertNull(repo.getAll().firstOrNull { it.name == "写盘失败测试" })
    }

    @Test
    fun `TC-F6 corrupted JSON type mismatch tolerance and isolation from MockConfig`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // 1) 已初始化但混入多条坏记录 + 1 条正常记录
        val mixedPrefs = ThreadSafeTestPrefs().apply {
            putRaw(FavoriteRepository.KEY_INITIALIZED, true)
            putRaw(
                FavoriteRepository.KEY_FAVORITES_JSON,
                """
                [
                  {"name":"缺少ID","latitude":39.9,"longitude":116.4},
                  {"id":"bad_lat","name":"越界纬度","latitude":999.0,"longitude":116.4},
                  {"id":"bad_nan","name":"NaN字符串","latitude":"NaN","longitude":116.4},
                  {"id":"valid_1","name":"合法地点","address":"测试地址","latitude":31.23,"longitude":121.47},
                  "not_an_object"
                ]
                """.trimIndent()
            )
        }
        val mixedRepo = FavoriteRepository(prefs = mixedPrefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val loaded = mixedRepo.getAll()
        assertEquals(1, loaded.size)
        assertEquals("valid_1", loaded[0].id)

        // 2) ClassCastException 类型损坏容错
        val ccePrefs = ThreadSafeTestPrefs().apply {
            putRaw(FavoriteRepository.KEY_INITIALIZED, true)
            putRaw(FavoriteRepository.KEY_FAVORITES_JSON, 12345)
        }
        val cceRepo = FavoriteRepository(prefs = ccePrefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(cceRepo.getAll().isEmpty())

        // 3) 与 MockConfigRepository (mockloc_xposed_config) 完全隔离
        val configPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs, ioScope = TestScope(dispatcher))
        val beforeVersion = configRepo.currentConfig.version
        val favPrefs = ThreadSafeTestPrefs()
        val favRepo = FavoriteRepository(prefs = favPrefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        favRepo.add("隔离测试点", "测试", 35.0, 110.0)
        assertEquals(beforeVersion, configRepo.currentConfig.version)
        assertFalse(configPrefs.contains(FavoriteRepository.KEY_FAVORITES_JSON))
        assertFalse(favPrefs.contains(IpcProtocol.KEY_LATITUDE))
    }

    @Test
    fun `TC-F7 high concurrency stress test preserves memory and disk consistency`() = runBlocking {
        val prefs = ThreadSafeTestPrefs()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seq = AtomicInteger(0)
        val repo = FavoriteRepository(
            prefs = prefs,
            ioDispatcher = Dispatchers.IO,
            ioScope = ioScope
        )
        repo.ensureInitialized()

        val jobs = (1..16).map { workerIdx ->
            async(Dispatchers.IO) {
                for (round in 1..20) {
                    val idx = seq.incrementAndGet()
                    val lat = 20.0 + (idx % 30) * 0.1 + workerIdx * 0.001
                    val lon = 100.0 + (idx % 30) * 0.1 + round * 0.001
                    val added = repo.add("并发点_$idx", "地址_$idx", lat, lon).getOrNull()
                    if (added != null) {
                        if (round % 3 == 0) {
                            repo.markUsed(added.id)
                        }
                        if (round % 5 == 0) {
                            repo.update(added.id, "更新_${added.name}")
                        }
                        if (round % 7 == 0) {
                            repo.remove(added.id)
                        }
                    }
                    repo.search("并发点")
                    // 并发迭代观察流快照，验证不发生 ConcurrentModificationException
                    repo.observeFavorites().value.size
                }
            }
        }
        jobs.awaitAll()

        val finalSnapshot = repo.getAll()
        assertEquals(finalSnapshot, repo.observeFavorites().value)

        // 冷启重读磁盘必须与内存快照 100% 一致
        val reloaded = FavoriteRepository(prefs = prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
        assertEquals(finalSnapshot, reloaded.getAll())
        assertEquals(MockConfig.DEFAULT_LATITUDE, MockConfig.DEFAULT_LATITUDE, 1e-6)
    }

    @Test
    fun `TC-F8 cold start commit failure keeps empty StateFlow and self-heals when storage recovers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // 1) 默认初始化分支 commit 返回 false -> 内存/StateFlow 均为空且未标记 initialized，恢复后自愈
        val prefs1 = ThreadSafeTestPrefs().apply { alwaysFailCommit.set(true) }
        val repo1 = FavoriteRepository(prefs = prefs1, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo1.getAll().isEmpty())
        assertTrue(repo1.observeFavorites().value.isEmpty())
        assertFalse(prefs1.contains(FavoriteRepository.KEY_INITIALIZED))
        assertNull(prefs1.getString(FavoriteRepository.KEY_FAVORITES_JSON, null))

        prefs1.alwaysFailCommit.set(false)
        val healed1 = repo1.getAll()
        assertEquals(5, healed1.size)
        assertEquals(healed1, repo1.observeFavorites().value)
        assertTrue(prefs1.getBoolean(FavoriteRepository.KEY_INITIALIZED, false))
        assertNotNull(prefs1.getString(FavoriteRepository.KEY_FAVORITES_JSON, null))

        // 2) 迁移分支 commit 抛 RuntimeException -> 内存/StateFlow 为空，恢复后完成迁移自愈
        val prefs2 = ThreadSafeTestPrefs().apply {
            putRaw(
                FavoriteRepository.KEY_FAVORITES_JSON,
                """[{"id":"mig_1","name":"迁移点","latitude":39.9,"longitude":116.4}]"""
            )
            throwOnCommit.set(true)
        }
        val repo2 = FavoriteRepository(prefs = prefs2, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo2.getAll().isEmpty())
        assertTrue(repo2.observeFavorites().value.isEmpty())
        assertFalse(prefs2.getBoolean(FavoriteRepository.KEY_INITIALIZED, false))

        prefs2.throwOnCommit.set(false)
        val healed2 = repo2.getAll()
        assertEquals(1, healed2.size)
        assertEquals("mig_1", healed2[0].id)
        assertTrue(prefs2.getBoolean(FavoriteRepository.KEY_INITIALIZED, false))

        // 3) 损坏恢复分支 commit 失败 -> 保持未自愈，恢复后清空损坏 JSON
        val prefs3 = ThreadSafeTestPrefs().apply {
            putRaw(FavoriteRepository.KEY_INITIALIZED, true)
            putRaw(FavoriteRepository.KEY_FAVORITES_JSON, "{not_json_array")
            alwaysFailCommit.set(true)
        }
        val repo3 = FavoriteRepository(prefs = prefs3, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo3.getAll().isEmpty())
        assertEquals("{not_json_array", prefs3.getString(FavoriteRepository.KEY_FAVORITES_JSON, null))

        prefs3.alwaysFailCommit.set(false)
        assertTrue(repo3.getAll().isEmpty())
        assertEquals("[]", prefs3.getString(FavoriteRepository.KEY_FAVORITES_JSON, null))
    }

    @Test
    fun `TC-F9 add by existing id rejects same-place conflict with another record and keeps invariant`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs().apply {
            putRaw(FavoriteRepository.KEY_INITIALIZED, true)
            putRaw(FavoriteRepository.KEY_FAVORITES_JSON, "[]")
        }
        val repo = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        repo.ensureInitialized()

        val locA = FavoriteLocation(id = "id_a", name = "地点A", latitude = 30.0, longitude = 120.0, createdAtMs = 1000L)
        val locB = FavoriteLocation(id = "id_b", name = "地点B", latitude = 40.0, longitude = 110.0, createdAtMs = 2000L)
        assertTrue(repo.add(locA).isSuccess)
        assertTrue(repo.add(locB).isSuccess)

        // 尝试以已有 id_b 覆写为与 id_a 极近/相同坐标：必须被拒绝，不允许出现同坐标双记录
        val conflictResult = repo.add(
            FavoriteLocation(
                id = "id_b",
                name = "B变成A坐标",
                latitude = 30.0,
                longitude = 120.0
            )
        )
        assertTrue("Updating existing id onto another record's coordinates must fail", conflictResult.isFailure)
        assertTrue(conflictResult.exceptionOrNull() is IllegalArgumentException)

        // 同名且近距离 (< 1e-4°) 跨 ID 冲突同样拒绝
        val nearNameConflict = repo.add(
            FavoriteLocation(
                id = "id_b",
                name = "地点A",
                latitude = 30.00005,
                longitude = 120.00005
            )
        )
        assertTrue(nearNameConflict.isFailure)

        val all = repo.getAll()
        assertEquals(2, all.size)
        val bAfter = repo.getById("id_b")!!
        assertEquals(40.0, bAfter.latitude, 1e-7)
        assertEquals(110.0, bAfter.longitude, 1e-7)
    }

    @Test
    fun `TC-F10 optNonBlankString rejects non-string JSON types and update point sets coordinateType to WGS84`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs().apply {
            putRaw(FavoriteRepository.KEY_INITIALIZED, true)
            putRaw(
                FavoriteRepository.KEY_FAVORITES_JSON,
                """
                [
                  {"id":"bad_bool_name","name":true,"latitude":39.9,"longitude":116.4},
                  {"id":"bad_num_name","name":12345,"latitude":39.9,"longitude":116.4},
                  {"id":12345,"name":"数字ID","latitude":39.9,"longitude":116.4},
                  {"id":"good_gcj","name":"合法记录","latitude":31.23,"longitude":121.47,"coordinateType":"GCJ02"}
                ]
                """.trimIndent()
            )
        }
        val repo = FavoriteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val loaded = repo.getAll()
        assertEquals(1, loaded.size)
        assertEquals("good_gcj", loaded[0].id)
        assertEquals(CoordinateType.GCJ02, loaded[0].coordinateType)

        // 仅改名不传 point 时保持原有 coordinateType
        val renamed = repo.update(id = "good_gcj", name = "合法记录改名").getOrThrow()
        assertEquals(CoordinateType.GCJ02, renamed.coordinateType)

        // 传入新 WGS84 point 时同步将 coordinateType 设为 WGS84
        val updatedPoint = repo.update(
            id = "good_gcj",
            name = "合法记录新坐标",
            point = GeoPoint(31.24, 121.48)
        ).getOrThrow()
        assertEquals(CoordinateType.WGS84, updatedPoint.coordinateType)
    }

    @Test
    fun `TC-F11 add by existing id rejects cross-id conflict regardless of record ordering`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = AtomicLong(5000L)

        // 场景1：A 创建时间更晚（3000L > 1000L），天然排在 B 之前（索引 0 为 A）
        val prefsOrderByCreated = ThreadSafeTestPrefs().apply {
            putRaw(FavoriteRepository.KEY_INITIALIZED, true)
            putRaw(FavoriteRepository.KEY_FAVORITES_JSON, "[]")
        }
        val repo1 = FavoriteRepository(
            prefs = prefsOrderByCreated,
            ioDispatcher = dispatcher,
            ioScope = TestScope(dispatcher),
            timeProvider = { clock.incrementAndGet() }
        )
        repo1.ensureInitialized()

        val locA = FavoriteLocation(
            id = "id_a",
            name = "地点A",
            address = "A原地址",
            latitude = 30.0,
            longitude = 120.0,
            createdAtMs = 3000L
        )
        val locB = FavoriteLocation(
            id = "id_b",
            name = "地点B",
            address = "B原地址",
            latitude = 40.0,
            longitude = 110.0,
            createdAtMs = 1000L
        )
        assertTrue(repo1.add(locA).isSuccess)
        assertTrue(repo1.add(locB).isSuccess)
        assertEquals(listOf("id_a", "id_b"), repo1.getAll().map { it.id })

        val exactConflict1 = repo1.add(
            FavoriteLocation(
                id = "id_b",
                name = "B改名",
                address = "篡改地址",
                latitude = 30.0,
                longitude = 120.0
            )
        )
        assertTrue(exactConflict1.isFailure)
        assertTrue(exactConflict1.exceptionOrNull() is IllegalArgumentException)

        val nearNameConflict1 = repo1.add(
            FavoriteLocation(
                id = "id_b",
                name = "地点A",
                address = "篡改地址",
                latitude = 30.00005,
                longitude = 120.00005
            )
        )
        assertTrue(nearNameConflict1.isFailure)
        assertTrue(nearNameConflict1.exceptionOrNull() is IllegalArgumentException)

        assertEquals(2, repo1.getAll().size)
        val aAfter1 = repo1.getById("id_a")!!
        assertEquals("地点A", aAfter1.name)
        assertEquals("A原地址", aAfter1.address)
        assertEquals(30.0, aAfter1.latitude, 1e-7)
        val bAfter1 = repo1.getById("id_b")!!
        assertEquals("地点B", bAfter1.name)
        assertEquals("B原地址", bAfter1.address)
        assertEquals(40.0, bAfter1.latitude, 1e-7)
        assertEquals(110.0, bAfter1.longitude, 1e-7)

        // 场景2：A 最初创建时间更早（1000L < 2000L），但调用 markUsed("id_a") 后置顶到索引 0
        val prefsOrderByMarkUsed = ThreadSafeTestPrefs().apply {
            putRaw(FavoriteRepository.KEY_INITIALIZED, true)
            putRaw(FavoriteRepository.KEY_FAVORITES_JSON, "[]")
        }
        val repo2 = FavoriteRepository(
            prefs = prefsOrderByMarkUsed,
            ioDispatcher = dispatcher,
            ioScope = TestScope(dispatcher),
            timeProvider = { clock.incrementAndGet() }
        )
        repo2.ensureInitialized()

        assertTrue(repo2.add(locA.copy(createdAtMs = 1000L)).isSuccess)
        assertTrue(repo2.add(locB.copy(createdAtMs = 2000L)).isSuccess)
        assertEquals(listOf("id_b", "id_a"), repo2.getAll().map { it.id })

        assertTrue(repo2.markUsed("id_a"))
        assertEquals(listOf("id_a", "id_b"), repo2.getAll().map { it.id })

        val exactConflict2 = repo2.add(
            FavoriteLocation(
                id = "id_b",
                name = "B改名",
                address = "篡改地址",
                latitude = 30.0,
                longitude = 120.0
            )
        )
        assertTrue(exactConflict2.isFailure)

        val nearNameConflict2 = repo2.add(
            FavoriteLocation(
                id = "id_b",
                name = "地点A",
                latitude = 30.00005,
                longitude = 120.00005
            )
        )
        assertTrue(nearNameConflict2.isFailure)

        val aAfter2 = repo2.getById("id_a")!!
        assertEquals("地点A", aAfter2.name)
        assertEquals("A原地址", aAfter2.address)
        val bAfter2 = repo2.getById("id_b")!!
        assertEquals("地点B", bAfter2.name)
        assertEquals(40.0, bAfter2.latitude, 1e-7)
        assertEquals(110.0, bAfter2.longitude, 1e-7)
    }
}
