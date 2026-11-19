package com.mockloc.hook

import android.app.AppOpsManager
import android.location.Location
import android.provider.Settings
import com.mockloc.hook.cleaner.MockLocationCleaner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockLocationCleanerTest {

    // 模拟带隐藏字段 mFieldsMask (bit 4 = 16) 与 mIsFromMockProvider 的平台承载对象
    private class HiddenFieldsLocationCarrier(
        @Suppress("unused") var mFieldsMask: Int,
        @Suppress("unused") var mIsFromMockProvider: Boolean
    )

    @Before
    fun setUp() {
        MockLocationCleaner.clearForTest()
    }

    @After
    fun tearDown() {
        MockLocationCleaner.clearForTest()
    }

    @Test
    fun `cleanHiddenFields clears bit 4 of mFieldsMask without clearing other bits and clears mIsFromMockProvider`() {
        // bit 0 (1) | bit 1 (2) | bit 4 (16) = 19
        val carrier = HiddenFieldsLocationCarrier(mFieldsMask = 19, mIsFromMockProvider = true)
        MockLocationCleaner.cleanHiddenFields(carrier)

        assertEquals(3, carrier.mFieldsMask)
        assertFalse(carrier.mIsFromMockProvider)
    }

    @Test
    fun `cleanLocation and sanitize strip mock extras while preserving legitimate extras`() {
        assertNull(MockLocationCleaner.cleanLocation(null))
        assertNull(MockLocationCleaner.sanitize(null))

        val (extras, map) = createStatefulExtrasBundle(
            mapOf(
                "mockLocation" to true,
                "isMock" to true,
                "fake" to "1",
                "spoof" to "yes",
                "mock_location" to true,
                "satellites" to "12"
            )
        )
        val loc = createStatefulMockLocation(
            initialProvider = "gps",
            initialMock = true,
            initialExtras = extras
        )

        MockLocationCleaner.sanitize(loc)

        assertFalse(loc.isMock)
        assertFalse(map.containsKey("mockLocation"))
        assertFalse(map.containsKey("isMock"))
        assertFalse(map.containsKey("fake"))
        assertFalse(map.containsKey("spoof"))
        assertFalse(map.containsKey("mock_location"))
        assertEquals("12", map["satellites"])
    }

    @Test
    fun `installed hooks mask isMock Settings Secure and AppOpsManager including 4-arg overload when active`() {
        val backend = FakeHookBackend()
        var active = true
        MockLocationCleaner.installHooks(
            classLoader = javaClass.classLoader,
            backend = backend,
            sdkInt = 36
        ) { active }

        val loc = createStatefulMockLocation(initialMock = true)
        val isMockInv = backend.invokeSimulated(loc, Location::class.java, "isMock") { true }
        assertEquals(false, isMockInv.result)

        val isFromMockInv = backend.invokeSimulated(loc, Location::class.java, "isFromMockProvider") { true }
        assertEquals(false, isFromMockInv.result)

        val setMockInv = backend.invokeSimulated(loc, Location::class.java, "setMock", true)
        assertEquals(false, setMockInv.args[0])

        val secureStringInv = backend.invokeSimulated(
            null,
            Settings.Secure::class.java,
            "getString",
            null,
            "mock_location"
        )
        assertEquals("0", secureStringInv.result)

        val secureIntInv = backend.invokeSimulated(
            null,
            Settings.Secure::class.java,
            "getInt",
            null,
            "mock_location",
            1
        )
        assertEquals(0, secureIntInv.result)

        // 非 mock_location 键必须透传
        val androidIdInv = backend.invokeSimulated(
            null,
            Settings.Secure::class.java,
            "getString",
            null,
            "android_id"
        ) { "real-device-id" }
        assertEquals("real-device-id", androidIdInv.result)

        // AppOpsManager 3 参及 android-36 4 参重载对 OPSTR_MOCK_LOCATION 返回 MODE_IGNORED
        val checkOp3Inv = backend.invokeSimulated(
            null,
            AppOpsManager::class.java,
            "checkOpNoThrow",
            "android:mock_location",
            10001,
            "com.tencent.mm"
        ) { AppOpsManager.MODE_ALLOWED }
        assertEquals(AppOpsManager.MODE_IGNORED, checkOp3Inv.result)

        val checkOp4Inv = backend.invokeSimulated(
            null,
            AppOpsManager::class.java,
            "checkOpNoThrow",
            "android:mock_location",
            10001,
            "com.tencent.mm",
            "attributionTag"
        ) { AppOpsManager.MODE_ALLOWED }
        assertEquals(AppOpsManager.MODE_IGNORED, checkOp4Inv.result)

        val isOpActiveInv = backend.invokeSimulated(
            null,
            AppOpsManager::class.java,
            "isOpActive",
            "android:mock_location",
            10001,
            "com.tencent.mm"
        ) { true }
        assertEquals(false, isOpActiveInv.result)

        // 非白名单或关闭开关时严格透传原值
        active = false
        val passthroughMock = backend.invokeSimulated(loc, Location::class.java, "isMock") { true }
        assertEquals(true, passthroughMock.result)

        val passthroughAppOps = backend.invokeSimulated(
            null,
            AppOpsManager::class.java,
            "checkOpNoThrow",
            "android:mock_location",
            10001,
            "com.other.app"
        ) { AppOpsManager.MODE_ALLOWED }
        assertEquals(AppOpsManager.MODE_ALLOWED, passthroughAppOps.result)
    }
}
