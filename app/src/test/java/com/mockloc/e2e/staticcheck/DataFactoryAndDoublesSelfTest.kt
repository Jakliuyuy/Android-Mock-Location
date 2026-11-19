package com.mockloc.e2e.staticcheck

import android.content.Context
import com.mockloc.e2e.factory.E2eFakeLocation
import com.mockloc.e2e.factory.FakeGeocodingHttpRequester
import com.mockloc.e2e.factory.FakeShellExecutor
import com.mockloc.e2e.factory.FakeShellResult
import com.mockloc.e2e.factory.InMemoryPreferencesOpener
import com.mockloc.e2e.factory.InMemorySharedPreferences
import com.mockloc.e2e.factory.StatefulFakeBundle
import com.mockloc.e2e.factory.TestConfigPayloads
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.TestEnvironmentPresets
import com.mockloc.e2e.factory.TestRouteSpecs
import com.mockloc.e2e.infra.WorkspacePathResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DataFactoryAndDoublesSelfTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `WorkspacePathResolver locates project root from root, app module, and nested paths`() {
        val root = WorkspacePathResolver.resolveProjectRoot()
        assertTrue(File(root, "settings.gradle.kts").isFile)
        assertTrue(File(root, "app/src/main/AndroidManifest.xml").isFile)

        val appDir = WorkspacePathResolver.resolveAppDir()
        assertEquals(File(root, "app").canonicalPath, appDir.canonicalPath)

        val fromApp = WorkspacePathResolver.resolveProjectRoot(appDir)
        assertEquals(root.canonicalPath, fromApp.canonicalPath)

        val nested = File(root, "app/src/main/java/com/mockloc")
        val fromNested = WorkspacePathResolver.resolveProjectRoot(nested)
        assertEquals(root.canonicalPath, fromNested.canonicalPath)

        val isolated = tempFolder.newFolder("not_a_project")
        assertNull(WorkspacePathResolver.tryResolveProjectRoot(isolated))
    }

    @Test
    fun `TestCoordinates, TestRouteSpecs, TestConfigPayloads, and Presets are complete and consistent`() {
        assertEquals(39.9087, TestCoordinates.BEIJING_TIANANMEN.latitude, 1e-6)
        assertEquals(3650.0, TestCoordinates.LHASA_POTALA.altitude, 1e-6)

        assertTrue(TestRouteSpecs.EMPTY_ROUTE.waypoints.isEmpty())
        assertEquals(1, TestRouteSpecs.SINGLE_POINT_ROUTE.waypoints.size)
        assertEquals(3, TestRouteSpecs.BEIJING_COMMUTE_3WP.waypoints.size)
        assertEquals(500, TestRouteSpecs.LARGE_500WP_ROUTE.waypoints.size)

        val largeWhitelist = TestConfigPayloads.createLargeWhitelistConfig(5000)
        assertEquals(5000, largeWhitelist.targetPackages.size)
        assertTrue(TestConfigPayloads.CORRUPTED_JSON_PAYLOADS.size >= 5)
        assertTrue(TestConfigPayloads.OUT_OF_RANGE_RAW_MAPS.size >= 5)
        assertTrue(TestConfigPayloads.INJECTION_PACKAGE_NAMES.contains("com.app; rm -rf /"))

        assertTrue(TestEnvironmentPresets.ENV_DUAL_READY.isLSPosedActive)
        assertTrue(TestEnvironmentPresets.ENV_DUAL_READY.isRootAvailable)
        assertFalse(TestEnvironmentPresets.ENV_NO_LOCATION_PERM.hasFineLocationPermission)
        assertFalse(TestEnvironmentPresets.ENV_NO_NOTIFICATION_PERM.hasNotificationPermission)
    }

    @Test
    fun `InMemorySharedPreferences supports concurrent reads writes, listeners, and type corruption injection`() {
        val prefs = InMemorySharedPreferences()
        val changeCount = AtomicInteger(0)
        prefs.registerOnSharedPreferenceChangeListener { _, _ -> changeCount.incrementAndGet() }

        prefs.edit()
            .putString("mode", "LSPOSED_ONLY")
            .putFloat("accuracy", 2.5f)
            .putLong("version", 42L)
            .putBoolean("enabled", true)
            .putStringSet("pkgs", mutableSetOf("com.tencent.mm", "com.autonavi.minimap"))
            .apply()

        assertEquals("LSPOSED_ONLY", prefs.getString("mode", ""))
        assertEquals(2.5f, prefs.getFloat("accuracy", 0f), 0f)
        assertEquals(42L, prefs.getLong("version", 0L))
        assertTrue(prefs.getBoolean("enabled", false))
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), prefs.getStringSet("pkgs", mutableSetOf()))
        assertTrue(changeCount.get() >= 5)

        // 注入类型损坏键应按 SharedPreferences 契约抛出 ClassCastException 以便测容错分支
        prefs.injectRawValue("accuracy", "corrupted_string")
        var caughtCast = false
        try {
            prefs.getFloat("accuracy", 1.0f)
        } catch (_: ClassCastException) {
            caughtCast = true
        }
        assertTrue("Expected ClassCastException on type-corrupted entry", caughtCast)

        // 多线程并发压测
        val threads = 12
        val iterations = 100
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        for (t in 0 until threads) {
            pool.submit {
                try {
                    for (i in 0 until iterations) {
                        prefs.edit()
                            .putLong("thread_$t", i.toLong())
                            .putStringSet("pkgs", mutableSetOf("pkg.$t.$i"))
                            .apply()
                        prefs.getStringSet("pkgs", mutableSetOf())
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `InMemoryPreferencesOpener simulates MODE_WORLD_READABLE SecurityException fallback`() {
        val opener = InMemoryPreferencesOpener(throwOnWorldReadable = true)
        var secThrown = false
        try {
            opener.open("mockloc_xposed_config", Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            secThrown = true
        }
        assertTrue(secThrown)

        val fallbackPrefs = opener.open("mockloc_xposed_config", Context.MODE_PRIVATE)
        fallbackPrefs.edit().putBoolean("enabled", true).apply()
        assertTrue(fallbackPrefs.getBoolean("enabled", false))
        assertEquals(listOf(Context.MODE_WORLD_READABLE, Context.MODE_PRIVATE), opener.requestedModes)
    }

    @Test
    fun `StatefulFakeBundle stores and retrieves typed entries accurately`() {
        val bundle = StatefulFakeBundle()
        assertTrue(bundle.isEmpty())

        bundle.putDouble("lat", 31.2397)
        bundle.putDouble("lon", 121.4998)
        bundle.putFloat("speed", 4.5f)
        bundle.putBoolean("enabled", true)
        bundle.putLong("version", 10L)
        bundle.putStringArrayList("targetPackages", arrayListOf("com.tencent.mm", "com.autonavi.minimap"))

        assertEquals(31.2397, bundle.getDouble("lat"), 1e-9)
        assertEquals(121.4998, bundle.getDouble("lon"), 1e-9)
        assertEquals(4.5f, bundle.getFloat("speed"), 0f)
        assertTrue(bundle.getBoolean("enabled"))
        assertEquals(10L, bundle.getLong("version"))
        assertEquals(arrayListOf("com.tencent.mm", "com.autonavi.minimap"), bundle.getStringArrayList("targetPackages"))

        bundle.remove("speed")
        assertFalse(bundle.containsKey("speed"))
        bundle.clear()
        assertTrue(bundle.isEmpty())
    }

    @Test
    fun `FakeShellExecutor and FakeGeocodingHttpRequester handle success, error, timeout, and exceptions`() = runBlocking {
        val shell = FakeShellExecutor()
        shell.stubCommandPrefix("id", FakeShellResult(exitCode = 0, stdout = "uid=0(root) gid=0(root)"))
        shell.stubCommand(Regex("appops set"), FakeShellResult(exitCode = 137, stderr = "Permission denied"))
        shell.stubTimeout(Regex("hang_cmd"), delayMs = 200L)
        shell.stubException(Regex("missing_su"), IOException("No such file"))

        val idRes = shell.execute("id")
        assertTrue(idRes.isSuccess)
        assertTrue(idRes.stdout.contains("uid=0"))

        val denied = shell.execute("appops set com.mockloc android:mock_location allow")
        assertFalse(denied.isSuccess)
        assertEquals(137, denied.exitCode)

        val timedOut = shell.execute("hang_cmd", timeoutMs = 20L)
        assertTrue(timedOut.timedOut)
        assertFalse(timedOut.isSuccess)

        var ioCaught = false
        try {
            shell.execute("missing_su")
        } catch (_: IOException) {
            ioCaught = true
        }
        assertTrue(ioCaught)
        assertEquals(4, shell.executedCommands.size)

        val http = FakeGeocodingHttpRequester()
        http.stubSuccess(Regex("tiananmen"), """{"status":"1","pois":[{"name":"天安门"}]}""")
        http.stubHttpError(Regex("forbidden"), statusCode = 403, body = "Forbidden")
        http.stubTimeout(Regex("slow_endpoint"))

        val ok = http.request("https://restapi.example.cn/search?q=tiananmen")
        assertEquals(200, ok.statusCode)
        assertTrue(ok.body.contains("天安门"))

        val err = http.request("https://restapi.example.cn/forbidden")
        assertEquals(403, err.statusCode)

        var timeoutCaught = false
        try {
            http.request("https://restapi.example.cn/slow_endpoint")
        } catch (_: SocketTimeoutException) {
            timeoutCaught = true
        }
        assertTrue(timeoutCaught)
        assertEquals(3, http.requestCount.get())
    }

    @Test
    fun `E2eFakeLocation exposes and clears mFieldsMask bit 4, mIsFromMockProvider, and extras`() {
        val loc = E2eFakeLocation(latitude = 39.9087, longitude = 116.3975)
        assertTrue(loc.isFromMockProvider())
        assertTrue(loc.isMock())
        assertTrue((loc.mFieldsMask and E2eFakeLocation.HAS_MOCK_PROVIDER_MASK) != 0)
        assertEquals(true, loc.extras["mockLocation"])

        loc.clearMockMarkersForTestReference()
        assertFalse(loc.isFromMockProvider())
        assertFalse(loc.isMock())
        assertFalse(loc.mIsFromMockProvider)
        assertEquals(0, loc.mFieldsMask and E2eFakeLocation.HAS_MOCK_PROVIDER_MASK)
        // 其余有效位（海拔/速度/航向/精度）应保留
        assertTrue((loc.mFieldsMask and E2eFakeLocation.HAS_ALTITUDE_MASK) != 0)
        assertFalse(loc.extras.containsKey("mockLocation"))
        assertFalse(loc.extras.containsKey("mock"))
        assertFalse(loc.extras.containsKey("isFromMockProvider"))
        assertNotNull(loc.extras["satellites"])
        assertEquals(12, loc.extras["satellites"])
    }

    @Test
    fun `InMemorySharedPreferences prevents torn reads on clear-put-commit, skips identical-value notifications, and bridges Context`() {
        val prefs = InMemorySharedPreferences()
        val changedKeys = java.util.Collections.synchronizedList(mutableListOf<String>())
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key != null) changedKeys.add(key)
        }

        // 验证同值写入不触发冗余回调
        prefs.edit().putInt("k1", 100).commit()
        assertEquals(listOf("k1"), changedKeys.toList())
        changedKeys.clear()
        prefs.edit().putInt("k1", 100).commit()
        assertTrue("Re-committing identical value must not fire listener", changedKeys.isEmpty())

        // 4 写 + 12 读线程压测 clear()+put()+commit() 原子性，确保 0 torn reads
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val tornReads = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(16)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(16)

        for (w in 0 until 4) {
            pool.submit {
                try {
                    startLatch.await()
                    var seq = 1
                    while (running.get()) {
                        prefs.edit()
                            .clear()
                            .putInt("k1", seq)
                            .putInt("k2", seq)
                            .commit()
                        seq++
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        for (r in 0 until 12) {
            pool.submit {
                try {
                    startLatch.await()
                    while (running.get()) {
                        val v = prefs.getInt("k1", -1)
                        if (v == -1) {
                            tornReads.incrementAndGet()
                        }
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        Thread.sleep(300L)
        running.set(false)
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(0, errors.get())
        assertEquals("Expected zero torn reads during clear+put+commit", 0, tornReads.get())

        // 验证 asMockContext()
        val opener = InMemoryPreferencesOpener(throwOnWorldReadable = true)
        val mockCtx = opener.asMockContext("com.mockloc")
        assertEquals("com.mockloc", mockCtx.packageName)
        var secCaught = false
        try {
            @Suppress("DEPRECATION")
            mockCtx.getSharedPreferences("cfg", Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            secCaught = true
        }
        assertTrue(secCaught)
        val privatePrefs = mockCtx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        privatePrefs.edit().putString("host", "ok").apply()
        assertEquals("ok", privatePrefs.getString("host", null))
    }

    @Test
    fun `StatefulFakeBundle supports nested Bundle, StringSet, strict numeric types, concurrency, and Android Bundle bridge`() {
        val nested = StatefulFakeBundle().apply { putString("inner", "val") }
        val bundle = StatefulFakeBundle().apply {
            putInt("intKey", 42)
            putLong("longKey", 99L)
            putFloat("floatKey", 1.5f)
            putDouble("doubleKey", 39.9087)
            putStringSet("pkgSet", setOf("com.tencent.mm", "com.autonavi.minimap"))
            putBundle("nested", nested)
            putString("nullKey", null)
        }

        // 严格类型校验：禁止跨数字类型隐式强转
        assertEquals(0.0, bundle.getDouble("intKey", 0.0), 0.0)
        assertEquals(0, bundle.getInt("doubleKey", 0))
        assertEquals(0f, bundle.getFloat("doubleKey", 0f), 0f)
        assertEquals(0L, bundle.getLong("intKey", 0L))
        assertEquals(42, bundle.getInt("intKey", 0))
        assertEquals(39.9087, bundle.getDouble("doubleKey", 0.0), 1e-9)
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), bundle.getStringSet("pkgSet"))
        assertEquals("val", bundle.getBundle("nested")?.getString("inner"))
        assertTrue(bundle.containsKey("nullKey"))
        assertNull(bundle.getString("nullKey", null))

        // 8 线程并发写入与读取 keySet()/toMap() 无 ConcurrentModificationException
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        val errors = AtomicInteger(0)
        for (t in 0 until 8) {
            pool.submit {
                try {
                    for (i in 0 until 150) {
                        bundle.putString("k_${t}_$i", "v_$i")
                        bundle.keySet().size
                        bundle.toMap().size
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(0, errors.get())

        // asAndroidBundle() 桥接测试
        val androidBundle = bundle.asAndroidBundle()
        assertEquals(42, androidBundle.getInt("intKey"))
        assertEquals(39.9087, androidBundle.getDouble("doubleKey"), 1e-9)
        assertEquals("val", androidBundle.getBundle("nested")?.getString("inner"))
        androidBundle.putString("fromAndroid", "hello")
        assertEquals("hello", bundle.getString("fromAndroid"))
        androidBundle.remove("intKey")
        assertFalse(bundle.containsKey("intKey"))
    }

    @Test
    fun `E2eFakeLocation asAndroidLocation bridges Location contract and syncs extras removal`() {
        val fake = E2eFakeLocation(
            provider = "gps",
            latitude = 39.9087,
            longitude = 116.3975,
            altitude = 44.0,
            accuracy = 2.5f,
            bearing = 90f,
            speed = 1.4f
        )
        val androidLoc = fake.asAndroidLocation()
        assertEquals(39.9087, androidLoc.latitude, 1e-9)
        assertEquals(116.3975, androidLoc.longitude, 1e-9)
        assertEquals(44.0, androidLoc.altitude, 1e-9)
        assertTrue(androidLoc.isMock)
        @Suppress("DEPRECATION")
        assertTrue(androidLoc.isFromMockProvider)

        androidLoc.isMock = false
        androidLoc.extras?.remove("mockLocation")
        androidLoc.extras?.remove("mock")
        androidLoc.extras?.remove("isFromMockProvider")

        assertFalse(fake.isMock())
        assertFalse(fake.isFromMockProvider())
        assertFalse(fake.extras.containsKey("mockLocation"))
        assertFalse(fake.extras.containsKey("mock"))
        assertFalse(fake.extras.containsKey("isFromMockProvider"))
        assertEquals(12, fake.extras["satellites"])
    }

    @Test
    fun `InMemorySharedPreferences commit dispatches listeners outside lock preventing AB-BA deadlock`() {
        val prefs = InMemorySharedPreferences()
        val externalLock = Any()
        val errors = AtomicInteger(0)
        val callbackHits = AtomicInteger(0)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)

        // 监听器内部获取 externalLock，并跨线程读取 prefs
        val readerExec = Executors.newSingleThreadExecutor()
        prefs.registerOnSharedPreferenceChangeListener { sp, _ ->
            synchronized(externalLock) {
                callbackHits.incrementAndGet()
                val fut = readerExec.submit<Int> { sp.getInt("k", -1) }
                val readVal = fut.get(2, TimeUnit.SECONDS)
                if (readVal < 0) errors.incrementAndGet()
            }
        }

        // 先做一次确定性单写同步验证，确认回调中跨线程读 prefs 不死锁
        prefs.edit().putInt("k", 1).commit()
        assertEquals(1, callbackHits.get())
        assertEquals(0, errors.get())

        // 3 线程（写线程 + 持 externalLock 读线程 + 持 externalLock 写线程）AB-BA 死锁压测
        val pool = Executors.newFixedThreadPool(3)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(3)

        // 线程 1：持续 commit 触发 listener -> externalLock
        pool.submit {
            try {
                startLatch.await()
                var seq = 2
                while (running.get()) {
                    prefs.edit().putInt("k", seq++).commit()
                }
            } catch (_: Throwable) {
                errors.incrementAndGet()
            } finally {
                doneLatch.countDown()
            }
        }
        // 线程 2：持有 externalLock 读取 prefs
        pool.submit {
            try {
                startLatch.await()
                while (running.get()) {
                    synchronized(externalLock) {
                        val v = prefs.getInt("k", -1)
                        if (v < 1) errors.incrementAndGet()
                    }
                }
            } catch (_: Throwable) {
                errors.incrementAndGet()
            } finally {
                doneLatch.countDown()
            }
        }
        // 线程 3：持有 externalLock 并发写入 prefs
        pool.submit {
            try {
                startLatch.await()
                var seq = 10_000
                while (running.get()) {
                    synchronized(externalLock) {
                        prefs.edit().putInt("k", seq++).commit()
                    }
                }
            } catch (_: Throwable) {
                errors.incrementAndGet()
            } finally {
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        Thread.sleep(350L)
        running.set(false)
        assertTrue("Deadlock detected: worker threads did not finish in time", doneLatch.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("Executor pool did not terminate cleanly", pool.awaitTermination(5, TimeUnit.SECONDS))
        readerExec.shutdown()
        assertTrue("Reader executor did not terminate cleanly", readerExec.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
        assertTrue(callbackHits.get() > 1)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `StatefulFakeBundle and E2eFakeLocation support nested Bundle, putAll, null keys, and bidirectional extras sync`() {
        // 1. StatefulFakeBundle.asAndroidBundle() 嵌套代理、putAll 与 null key 防护
        val childFake = StatefulFakeBundle().apply {
            putInt("nestedInt", 77)
            putDouble("nestedDouble", 12.34)
            putBoolean("nestedBool", true)
            putString("nestedStr", "ok")
        }
        val sourceAndroid = StatefulFakeBundle().apply {
            putString("name", "mockloc")
            putInt("ver", 3)
            putStringSet("pkgs", setOf("com.tencent.mm", "com.autonavi.minimap"))
            putBundle("nested", childFake)
        }.asAndroidBundle()

        // "get" 与 "getBundle" 均返回 Bundle 代理而非暴露内部 StatefulFakeBundle
        val gotViaGet = sourceAndroid.get("nested")
        assertTrue("get(\"nested\") must return android.os.Bundle proxy", gotViaGet is android.os.Bundle)
        assertEquals(77, (gotViaGet as android.os.Bundle).getInt("nestedInt"))
        assertTrue(sourceAndroid.getBundle("nested") === gotViaGet)

        val targetFake = StatefulFakeBundle()
        val targetAndroid = targetFake.asAndroidBundle()
        targetAndroid.putAll(sourceAndroid)

        assertEquals("mockloc", targetAndroid.getString("name"))
        assertEquals(3, targetAndroid.getInt("ver"))
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), targetFake.getStringSet("pkgs"))
        val copiedNested = targetAndroid.getBundle("nested")
        assertNotNull(copiedNested)
        assertEquals(77, copiedNested?.getInt("nestedInt"))
        assertEquals(12.34, copiedNested?.getDouble("nestedDouble") ?: 0.0, 1e-9)
        assertEquals(true, copiedNested?.getBoolean("nestedBool"))
        assertEquals("ok", copiedNested?.getString("nestedStr"))

        // null key 安全不抛 NPE
        assertNull(targetAndroid.getString(null))
        assertEquals("fallback", targetAndroid.getString(null, "fallback"))
        assertEquals(7, targetAndroid.getInt(null, 7))
        assertEquals(0L, targetAndroid.getLong(null, 0L))
        assertFalse(targetAndroid.containsKey(null))
        assertNull(targetAndroid.get(null))
        assertNull(targetAndroid.getBundle(null))
        targetAndroid.putString(null, "ignored")
        targetAndroid.remove(null)

        // 2. E2eFakeLocation.asAndroidLocation() 双向同步 extras 与 setExtras 递归嵌套 Bundle
        val fakeLoc = E2eFakeLocation(latitude = 31.2397, longitude = 121.4998)
        val androidLoc = fakeLoc.asAndroidLocation()

        // 桥接后直接修改 fakeLoc.extras 不应被 getExtras() 覆盖抹除
        fakeLoc.extras["afterBridge"] = "yes"
        val bridgedExtras = androidLoc.extras
        assertNotNull(bridgedExtras)
        assertEquals("yes", bridgedExtras?.getString("afterBridge"))
        assertEquals("yes", fakeLoc.extras["afterBridge"])

        // setExtras(Bundle) 应递归保留嵌套 Bundle 及全类型字段
        val deepChild = StatefulFakeBundle().apply { putString("deepKey", "deepVal") }
        val newParentFake = StatefulFakeBundle().apply {
            putInt("topInt", 9)
            putBundle("nested", StatefulFakeBundle().apply {
                putInt("innerInt", 88)
                putDouble("innerDouble", 3.14)
                putBoolean("innerBool", true)
                putStringSet("innerSet", setOf("a", "b"))
                putBundle("deep", deepChild)
            })
        }
        androidLoc.extras = newParentFake.asAndroidBundle()

        val readBack = androidLoc.extras
        assertNotNull(readBack)
        assertEquals(9, readBack?.getInt("topInt"))
        val readNested = readBack?.getBundle("nested")
        assertNotNull(readNested)
        assertEquals(88, readNested?.getInt("innerInt"))
        assertEquals(3.14, readNested?.getDouble("innerDouble") ?: 0.0, 1e-9)
        assertEquals(true, readNested?.getBoolean("innerBool"))
        assertEquals("deepVal", readNested?.getBundle("deep")?.getString("deepKey"))
        assertTrue(fakeLoc.extras["nested"] is StatefulFakeBundle)
        assertEquals(88, (fakeLoc.extras["nested"] as StatefulFakeBundle).getInt("innerInt"))
        assertEquals(setOf("a", "b"), (fakeLoc.extras["nested"] as StatefulFakeBundle).getStringSet("innerSet"))
    }
}
