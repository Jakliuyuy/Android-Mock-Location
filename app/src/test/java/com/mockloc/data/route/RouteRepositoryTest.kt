package com.mockloc.data.route

import android.content.SharedPreferences
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.CruiseEngine
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
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

class RouteRepositoryTest {

    private class ThreadSafeTestPrefs : SharedPreferences {
        private val lock = Any()
        private val map = LinkedHashMap<String, Any?>()
        val failNextCommit = AtomicBoolean(false)
        val alwaysFailCommit = AtomicBoolean(false)
        val throwOnCommit = AtomicBoolean(false)

        fun putRaw(key: String, value: Any?) = synchronized(lock) { map[key] = value }

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
        override fun contains(key: String?): Boolean = synchronized(lock) { key != null && map.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

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
                    throw RuntimeException("simulated route commit exception")
                }
                if (alwaysFailCommit.get() || failNextCommit.compareAndSet(true, false)) return false
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

    private fun sampleRoute(id: String = "r_custom", name: String = "测试路线"): PlannedRoute {
        return PlannedRoute(
            id = id,
            name = name,
            waypoints = listOf(
                Waypoint(GeoPoint(39.9087, 116.3975), "起点", 1.0, 2.5f),
                Waypoint(GeoPoint(39.9163, 116.3972), "终点", 0.0, null)
            ),
            createdAtMs = 1_700_000_000_000L
        )
    }

    @Test
    fun `TC-R1 first init creates 3 valid sample routes compatible with CruiseEngine`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()
        val repo = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))

        val loaded = repo.getAllRoutes()
        assertEquals(3, loaded.size)
        assertEquals(repo.getDefaultSampleRoutes().map { it.id }, loaded.map { it.id })
        assertTrue(prefs.getBoolean(RouteRepository.KEY_INITIALIZED, false))

        val engine = CruiseEngine()
        for (route in loaded) {
            assertTrue(route.waypoints.size >= 3)
            assertTrue(route.isValid())
            assertTrue(route.totalDistanceMeters() > 100.0)
            engine.loadRoute(route, CruiseConfig(speedMps = 5.0))
            engine.start()
            assertEquals(CruiseEngine.State.RUNNING, engine.state)
            val step = engine.advanceStep(1.0)
            assertTrue(GeoPoint.isValid(step.point.latitude, step.point.longitude))
            engine.stop()
        }
    }

    @Test
    fun `TC-R2 deleting or clearing all routes does not resurrect defaults on restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()

        val repo1 = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val initial = repo1.getAllRoutes()
        assertEquals(3, initial.size)

        for (route in initial) {
            assertTrue(repo1.deleteRoute(route.id))
        }
        assertTrue(repo1.getAllRoutes().isEmpty())

        val repo2 = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue("Deleted routes must stay empty after restart", repo2.getAllRoutes().isEmpty())

        assertTrue(repo2.saveRoute(sampleRoute("r1", "新路线")).isSuccess)
        assertTrue(repo2.clearAllRoutes())
        val repo3 = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo3.getAllRoutes().isEmpty())

        val reset = repo3.resetToDefaultSampleRoutes().getOrThrow()
        assertEquals(3, reset.size)
    }

    @Test
    fun `TC-R3 CRUD save update rename delete`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()
        val repo = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        repo.ensureInitialized()

        val saved = repo.saveRoute(sampleRoute("route_a", "清晨跑步")).getOrThrow()
        assertEquals("route_a", saved.id)
        assertEquals(4, repo.getAllRoutes().size)
        assertEquals("清晨跑步", repo.getRouteById("route_a")?.name)

        // renameRoute 保留航点和 createdAtMs
        val renamed = repo.renameRoute("route_a", "晚间慢跑").getOrThrow()
        assertEquals("晚间慢跑", renamed.name)
        assertEquals(saved.waypoints.size, renamed.waypoints.size)
        assertEquals(saved.createdAtMs, renamed.createdAtMs)

        // updateRoute 替换航点
        val modified = renamed.copy(
            waypoints = renamed.waypoints + Waypoint(GeoPoint(39.9200, 116.4000), "加点", 0.5, 3.0f)
        )
        val updated = repo.updateRoute(modified).getOrThrow()
        assertEquals(3, updated.waypoints.size)

        // 冷启重读
        val restarted = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val reloaded = restarted.getRouteById("route_a")
        assertNotNull(reloaded)
        assertEquals("晚间慢跑", reloaded!!.name)
        assertEquals(3, reloaded.waypoints.size)

        // 失败路径
        assertTrue(repo.updateRoute(sampleRoute("missing", "不存在")).isFailure)
        assertTrue(repo.renameRoute("route_a", "   ").isFailure)
        assertFalse(repo.deleteRoute("missing"))
    }

    @Test
    fun `TC-R4 strict validation blocks empty routes invalid speeds and handles 500 waypoints`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs()
        val repo = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))

        // 空航点拦截
        assertTrue(repo.saveRoute(PlannedRoute(id = "empty", name = "空", waypoints = emptyList())).isFailure)

        // 空名称 / 超长名称拦截
        assertTrue(repo.saveRoute(sampleRoute("blank_name", "   ")).isFailure)
        assertTrue(repo.saveRoute(sampleRoute("long_name", "A".repeat(101))).isFailure)

        // 非法速度拦截: 0f, NaN, 超限 500f (注意 null 合法)
        val zeroSpeed = PlannedRoute(
            id = "s0",
            name = "零速",
            waypoints = listOf(Waypoint(GeoPoint(39.9, 116.4), targetSpeedMps = 0f))
        )
        val overSpeed = PlannedRoute(
            id = "s500",
            name = "超速",
            waypoints = listOf(Waypoint(GeoPoint(39.9, 116.4), targetSpeedMps = 500f))
        )
        assertTrue(repo.saveRoute(zeroSpeed).isFailure)
        assertTrue(repo.saveRoute(overSpeed).isFailure)

        // 500 航点大规模路线正常保存并恢复
        val largeWaypoints = (0 until 500).map { i ->
            Waypoint(
                point = GeoPoint(39.90 + i * 0.0001, 116.39 + i * 0.0001),
                name = "WP_$i",
                dwellTimeSeconds = 0.0,
                targetSpeedMps = if (i % 2 == 0) null else 3.5f
            )
        }
        val largeRoute = PlannedRoute(id = "large_500", name = "500航点长路线", waypoints = largeWaypoints)
        assertTrue(repo.saveRoute(largeRoute).isSuccess)

        val restarted = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val reloadedLarge = restarted.getRouteById("large_500")
        assertNotNull(reloadedLarge)
        assertEquals(500, reloadedLarge!!.waypoints.size)

        // 超过 1000 航点上限拦截
        val tooMany = (0..1000).map { i ->
            Waypoint(GeoPoint(30.0 + i * 0.00001, 120.0))
        }
        assertTrue(repo.saveRoute(PlannedRoute(id = "too_many", name = "超限", waypoints = tooMany)).isFailure)

        // commit 失败保护
        prefs.failNextCommit.set(true)
        assertTrue(repo.saveRoute(sampleRoute("commit_fail", "失败路线")).isFailure)
        assertNull(repo.getRouteById("commit_fail"))
    }

    @Test
    fun `TC-R5 corrupted JSON skips bad routes and recovers cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mixedPrefs = ThreadSafeTestPrefs().apply {
            putRaw(RouteRepository.KEY_INITIALIZED, true)
            putRaw(
                RouteRepository.KEY_ROUTES_JSON,
                """
                [
                  {"id":"empty_wps","name":"空航点","waypoints":[]},
                  {
                    "id":"bad_lat_wp",
                    "name":"含越界航点",
                    "waypoints":[
                      {"name":"坏点","dwellTimeSeconds":0,"point":{"latitude":999.0,"longitude":116.4}}
                    ]
                  },
                  {
                    "id":"bad_speed_wp",
                    "name":"含负速度航点",
                    "waypoints":[
                      {"name":"坏速","dwellTimeSeconds":0,"targetSpeedMps":-5.0,"point":{"latitude":39.9,"longitude":116.4}}
                    ]
                  },
                  {
                    "id":"good_route",
                    "name":"完整合法路线",
                    "createdAtMs":1700000000000,
                    "waypoints":[
                      {"name":"P1","dwellTimeSeconds":1.0,"targetSpeedMps":2.0,"point":{"latitude":39.9087,"longitude":116.3975,"altitude":44.0,"accuracy":5.0,"timestamp":1700000000000}},
                      {"name":"P2","dwellTimeSeconds":0.0,"targetSpeedMps":null,"point":{"latitude":39.9163,"longitude":116.3972,"altitude":45.0,"accuracy":5.0,"timestamp":1700000000000}}
                    ]
                  }
                ]
                """.trimIndent()
            )
        }
        val repo = RouteRepository(prefs = mixedPrefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val routes = repo.getAllRoutes()
        assertEquals(1, routes.size)
        assertEquals("good_route", routes[0].id)
        assertEquals(2, routes[0].waypoints.size)
        assertNull(routes[0].waypoints[1].targetSpeedMps)

        // 顶层彻底损坏 + ClassCastException
        val brokenPrefs = ThreadSafeTestPrefs().apply {
            putRaw(RouteRepository.KEY_INITIALIZED, true)
            putRaw(RouteRepository.KEY_ROUTES_JSON, 98765)
        }
        val brokenRepo = RouteRepository(prefs = brokenPrefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(brokenRepo.getAllRoutes().isEmpty())
    }

    @Test
    fun `TC-R6 high concurrency stress test maintains memory and disk consistency`() = runBlocking {
        val prefs = ThreadSafeTestPrefs()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seq = AtomicInteger(0)
        val repo = RouteRepository(prefs = prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
        repo.ensureInitialized()

        val jobs = (1..12).map {
            async(Dispatchers.IO) {
                for (round in 1..20) {
                    val idx = seq.incrementAndGet()
                    val route = PlannedRoute(
                        id = "conc_$idx",
                        name = "并发路线_$idx",
                        waypoints = listOf(
                            Waypoint(GeoPoint(31.2 + (idx % 10) * 0.01, 121.4), "A", 0.5, 2.0f),
                            Waypoint(GeoPoint(31.3 + (idx % 10) * 0.01, 121.5), "B", 0.0, 3.0f)
                        )
                    )
                    repo.saveRoute(route)
                    if (round % 3 == 0) repo.renameRoute("conc_$idx", "重命名_$idx")
                    if (round % 6 == 0) repo.deleteRoute("conc_$idx")
                    repo.observeRoutes().value.size
                }
            }
        }
        jobs.awaitAll()

        val current = repo.getAllRoutes()
        assertEquals(current, repo.observeRoutes().value)

        val reloaded = RouteRepository(prefs = prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
        assertEquals(current, reloaded.getAllRoutes())
    }

    @Test
    fun `TC-R7 cold start commit failure keeps empty StateFlow and self-heals when storage recovers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // 1) 默认初始化分支 commit 返回 false -> 内存/StateFlow 均为空且未标记 initialized，恢复后自愈
        val prefs1 = ThreadSafeTestPrefs().apply { alwaysFailCommit.set(true) }
        val repo1 = RouteRepository(prefs = prefs1, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo1.getAllRoutes().isEmpty())
        assertTrue(repo1.observeRoutes().value.isEmpty())
        assertFalse(prefs1.contains(RouteRepository.KEY_INITIALIZED))
        assertNull(prefs1.getString(RouteRepository.KEY_ROUTES_JSON, null))

        prefs1.alwaysFailCommit.set(false)
        val healed1 = repo1.getAllRoutes()
        assertEquals(3, healed1.size)
        assertEquals(healed1, repo1.observeRoutes().value)
        assertTrue(prefs1.getBoolean(RouteRepository.KEY_INITIALIZED, false))
        assertNotNull(prefs1.getString(RouteRepository.KEY_ROUTES_JSON, null))

        // 2) 迁移分支 commit 抛异常 -> 内存/StateFlow 为空，恢复后完成迁移自愈
        val prefs2 = ThreadSafeTestPrefs().apply {
            putRaw(
                RouteRepository.KEY_ROUTES_JSON,
                """
                [
                  {
                    "id":"mig_r1",
                    "name":"迁移路线",
                    "waypoints":[{"name":"P1","dwellTimeSeconds":0,"point":{"latitude":39.9,"longitude":116.4}}]
                  }
                ]
                """.trimIndent()
            )
            throwOnCommit.set(true)
        }
        val repo2 = RouteRepository(prefs = prefs2, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo2.getAllRoutes().isEmpty())
        assertTrue(repo2.observeRoutes().value.isEmpty())
        assertFalse(prefs2.getBoolean(RouteRepository.KEY_INITIALIZED, false))

        prefs2.throwOnCommit.set(false)
        val healed2 = repo2.getAllRoutes()
        assertEquals(1, healed2.size)
        assertEquals("mig_r1", healed2[0].id)
        assertTrue(prefs2.getBoolean(RouteRepository.KEY_INITIALIZED, false))

        // 3) 损坏恢复分支 commit 失败 -> 保持未清理，恢复后写空修复
        val prefs3 = ThreadSafeTestPrefs().apply {
            putRaw(RouteRepository.KEY_INITIALIZED, true)
            putRaw(RouteRepository.KEY_ROUTES_JSON, "not_json_array")
            alwaysFailCommit.set(true)
        }
        val repo3 = RouteRepository(prefs = prefs3, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        assertTrue(repo3.getAllRoutes().isEmpty())
        assertEquals("not_json_array", prefs3.getString(RouteRepository.KEY_ROUTES_JSON, null))

        prefs3.alwaysFailCommit.set(false)
        assertTrue(repo3.getAllRoutes().isEmpty())
        assertEquals("[]", prefs3.getString(RouteRepository.KEY_ROUTES_JSON, null))
    }

    @Test
    fun `TC-R8 optNonBlankString rejects boolean and numeric JSON values for route id or name`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = ThreadSafeTestPrefs().apply {
            putRaw(RouteRepository.KEY_INITIALIZED, true)
            putRaw(
                RouteRepository.KEY_ROUTES_JSON,
                """
                [
                  {
                    "id":"bad_bool_name",
                    "name":true,
                    "waypoints":[{"name":"P1","dwellTimeSeconds":0,"point":{"latitude":39.9,"longitude":116.4}}]
                  },
                  {
                    "id":"bad_num_name",
                    "name":12345,
                    "waypoints":[{"name":"P1","dwellTimeSeconds":0,"point":{"latitude":39.9,"longitude":116.4}}]
                  },
                  {
                    "id":999,
                    "name":"数字ID路线",
                    "waypoints":[{"name":"P1","dwellTimeSeconds":0,"point":{"latitude":39.9,"longitude":116.4}}]
                  },
                  {
                    "id":"good_route",
                    "name":"合法路线",
                    "waypoints":[{"name":"P1","dwellTimeSeconds":0,"point":{"latitude":39.9087,"longitude":116.3975}}]
                  }
                ]
                """.trimIndent()
            )
        }
        val repo = RouteRepository(prefs = prefs, ioDispatcher = dispatcher, ioScope = TestScope(dispatcher))
        val routes = repo.getAllRoutes()
        assertEquals(1, routes.size)
        assertEquals("good_route", routes[0].id)
        assertEquals("合法路线", routes[0].name)
    }
}
