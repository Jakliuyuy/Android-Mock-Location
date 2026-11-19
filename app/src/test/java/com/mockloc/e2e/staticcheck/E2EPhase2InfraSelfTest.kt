package com.mockloc.e2e.staticcheck

import com.mockloc.data.config.SafePrefs
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.geocoding.DomesticGeocodingService
import com.mockloc.domain.geocoding.OfflineFallbackGeocodingService
import com.mockloc.e2e.factory.ByteFieldsMaskLocationStub
import com.mockloc.e2e.factory.ChildHiddenFieldsLocationStub
import com.mockloc.e2e.factory.E2eHookBackend
import com.mockloc.e2e.factory.E2eLbsSdkDoubles
import com.mockloc.e2e.factory.FakeGeocodingHttpRequester
import com.mockloc.e2e.factory.FakeShellExecutor
import com.mockloc.e2e.factory.FakeShellResult
import com.mockloc.e2e.factory.FakeTestProviderBackend
import com.mockloc.e2e.factory.HiddenFieldsLocationStub
import com.mockloc.e2e.factory.InMemoryPreferencesOpener
import com.mockloc.e2e.factory.TestConfigPayloads
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.TestRouteSpecs
import com.mockloc.e2e.factory.XposedParamFactory
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toCruiseConfig
import com.mockloc.e2e.factory.toGeoPoint
import com.mockloc.e2e.factory.toGeocodingHttpClient
import com.mockloc.e2e.factory.toMockConfig
import com.mockloc.e2e.factory.toPlannedRoute
import com.mockloc.e2e.factory.toShellExecutor
import com.mockloc.e2e.factory.withMainDispatcher
import com.mockloc.hook.MethodHookHandler
import com.mockloc.hook.cleaner.MockLocationCleaner
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.RootProviderManager
import com.mockloc.service.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E2EPhase2InfraSelfTest {

    private open class ParentTarget {
        open fun ping(x: Int): Int = x + 1
    }

    private class ChildTarget : ParentTarget()

    private interface SampleIface {
        fun callMe()
    }

    @Test
    fun `E2ETestMappers convert specs and adapt ShellExecutor, GeocodingHttpClient, MainDispatcher, and Context`() {
        val gp = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()
        assertEquals(39.9087, gp.latitude, 1e-6)

        val route = TestRouteSpecs.BEIJING_COMMUTE_3WP.toPlannedRoute()
        assertEquals(3, route.waypoints.size)
        assertEquals(4.5f, route.waypoints[1].targetSpeedMps!!, 1e-6f)

        val cruiseCfg = TestRouteSpecs.CLOSED_LOOP_4WP.toCruiseConfig(speedMps = 6.0)
        assertEquals(LoopMode.LOOP, cruiseCfg.loopMode)
        assertEquals(6.0, cruiseCfg.speedMps, 1e-6)

        val mockCfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig()
        assertTrue(mockCfg.enabled)
        assertTrue(mockCfg.isEnabledForPackage("com.tencent.mm"))

        val fakeShell = FakeShellExecutor().apply {
            stubCommand(Regex("^id$"), FakeShellResult(0, "uid=0(root) gid=0(root)"))
            stubCommand(Regex("su -v"), FakeShellResult(0, "27.0:MAGISKSU"))
        }
        val opener = InMemoryPreferencesOpener(throwOnWorldReadable = true)
        val ctx = buildTestAppContext(
            prefsOpener = opener,
            hasFineLocation = true,
            hasCoarseLocation = false,
            hasNotification = true,
            installedPackages = setOf("com.tencent.mm", "com.autonavi.minimap"),
            xposedScopeArray = arrayOf("com.mockloc", "com.tencent.mm", "com.autonavi.minimap")
        )

        val prefsRes = SafePrefs.open(ctx)
        assertFalse(prefsRes.isWorldReadable)
        assertTrue(PermissionChecker.check(ctx, sdkInt = 34).canStartForegroundLocation)

        runBlocking {
            val env = EnvironmentDetector.detectEnvironment(
                context = ctx,
                shellExecutor = fakeShell.toShellExecutor(),
                sdkInt = 35
            )
            assertTrue(env.isRootAvailable)
            assertEquals("Magisk", env.rootProviderName)
            assertEquals(2, env.recommendedScopeInstalledCount)
            assertEquals(3, env.recommendedScopeTotalCount)

            val fakeHttp = FakeGeocodingHttpRequester().apply {
                stubTimeout(Regex(".*"))
            }
            val geoSvc = DomesticGeocodingService(
                httpClient = fakeHttp.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )
            val searchRes = geoSvc.search("东方明珠")
            assertTrue(searchRes.isSuccess)
            assertTrue(searchRes.getOrThrow().isNotEmpty())
        }

        val mainThreadName = withMainDispatcher {
            runBlocking {
                withContext(Dispatchers.Main) {
                    Thread.currentThread().name
                }
            }
        }
        assertTrue(mainThreadName.startsWith("E2E-MainDispatcher"))
    }

    @Test
    fun `E2eHookBackend supports declaredOnly mode, interface hook tracking, and XposedParamFactory`() {
        val backend = E2eHookBackend(declaredOnly = true)
        assertEquals(0, backend.hookAllMethods(ChildTarget::class.java, "ping", before = MethodHookHandler {}))

        val parentMethod = ParentTarget::class.java.getDeclaredMethod("ping", Int::class.javaPrimitiveType)
        assertTrue(
            backend.hookMethod(
                parentMethod,
                before = MethodHookHandler { inv ->
                    inv.args[0] = (inv.args[0] as Int) * 10
                }
            )
        )
        val childInstance = ChildTarget()
        val res = backend.invokeMethod(
            clazz = ChildTarget::class.java,
            methodName = "ping",
            thisObject = childInstance,
            args = arrayOf(3),
            originalImpl = { a -> childInstance.ping(a[0] as Int) }
        )
        assertFalse(res.earlyReturned)
        assertEquals(31, res.result)

        backend.hookAllMethods(SampleIface::class.java, "callMe", before = MethodHookHandler {})
        assertTrue(backend.hasInterfaceHookAllMethods())
        assertEquals(1, backend.interfaceHookAllMethodsRecords().size)

        val lp = XposedParamFactory.createLoadPackageParam("com.tencent.mm", javaClass.classLoader)
        assertEquals("com.tencent.mm", lp.packageName)
        assertNotNull(lp.classLoader)

        val mh = XposedParamFactory.createMethodHookParam(method = parentMethod, thisObject = childInstance, args = arrayOf(5))
        assertEquals(parentMethod, mh.method)
        assertEquals(5, mh.args[0])
    }

    @Test
    fun `E2eLbsSdkDoubles, HiddenFieldsStubs, and FakeTestProviderBackend satisfy contracts`() {
        val backend = E2eHookBackend()
        E2eLbsSdkDoubles.registerAll(backend)
        assertNotNull(backend.findClassIfExists("com.tencent.map.geolocation.TencentLocationManager", null))
        assertNotNull(backend.findClassIfExists("com.amap.api.location.AMapLocationClient", null))
        assertNotNull(backend.findClassIfExists("com.baidu.location.LocationClient", null))
        assertNotNull(backend.findClassIfExists("com.google.android.gms.location.FusedLocationProviderClient", null))

        val hiddenStub = HiddenFieldsLocationStub()
        MockLocationCleaner.cleanHiddenFields(hiddenStub)
        assertEquals(0, hiddenStub.mFieldsMask and 16)
        assertEquals(15, hiddenStub.mFieldsMask and 15)
        assertFalse(hiddenStub.mIsFromMockProvider)

        val childHiddenStub = ChildHiddenFieldsLocationStub()
        MockLocationCleaner.cleanHiddenFields(childHiddenStub)
        assertEquals(0, childHiddenStub.mFieldsMask and 16)
        assertFalse(childHiddenStub.mIsFromMockProvider)

        val byteStub = ByteFieldsMaskLocationStub()
        assertEquals(0b11111.toByte(), byteStub.mFieldsMask)

        val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = true)
        val manager = RootProviderManager(
            context = buildTestAppContext(),
            shellExecutor = FakeShellExecutor().toShellExecutor(),
            backendFactory = { providerBackend },
            sdkIntProvider = { 35 }
        )
        runBlocking {
            val startRes = manager.startOrUpdate(39.9087, 116.3975, 44.0, 3.0f, 90f, 1.5f)
            assertTrue(startRes.isSuccess)
            assertEquals(setOf("gps", "network", "fused"), providerBackend.registeredProviders.keys)
            val stopRes = manager.stop()
            assertTrue(stopRes.isSuccess)
            assertTrue(providerBackend.registeredProviders.isEmpty())
        }
    }
}
