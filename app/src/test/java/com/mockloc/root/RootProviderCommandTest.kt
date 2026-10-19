package com.mockloc.root

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import com.mockloc.hook.cleaner.TestableMockLocation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * Milestone 3 自动化单元测试套件：
 * 验证 ShellUtil 命令构造与执行、RootProviderManager 测试提供者生命周期管理及 EnvironmentDetector 状态模型。
 */
class RootProviderCommandTest {

    private val originalExecutor = ShellUtil.commandExecutor

    @Before
    fun setUp() {
        ShellUtil.setCommandExecutorForTesting(originalExecutor)
        EnvironmentDetector.setXposedActiveForTesting(null)
    }

    @After
    fun tearDown() {
        ShellUtil.setCommandExecutorForTesting(originalExecutor)
        EnvironmentDetector.setXposedActiveForTesting(null)
    }

    // =========================================================================
    // 1. ShellUtil.buildAppOpsCommand 测试 (有效包名与防命令注入)
    // =========================================================================

    @Test
    fun testBuildAppOpsCommandValidPackages() {
        assertEquals(
            "appops set com.mockloc android:mock_location allow",
            ShellUtil.buildAppOpsCommand("com.mockloc")
        )
        assertEquals(
            "appops set com.tencent.mm android:mock_location allow",
            ShellUtil.buildAppOpsCommand("com.tencent.mm")
        )
        assertEquals(
            "appops set com.autonavi.minimap android:mock_location allow",
            ShellUtil.buildAppOpsCommand("com.autonavi.minimap")
        )
        assertEquals(
            "appops set org.osmdroid.demo_app123 android:mock_location allow",
            ShellUtil.buildAppOpsCommand("org.osmdroid.demo_app123")
        )
        // 验证自动去除首尾空白字符
        assertEquals(
            "appops set com.example.service android:mock_location allow",
            ShellUtil.buildAppOpsCommand("   com.example.service  \t ")
        )
    }

    @Test
    fun testBuildAppOpsCommandRejectsInvalidPackages() {
        // 空值或纯空格
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("   ")
        }

        // 命令注入注入字符：分号
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("com.mockloc; rm -rf /")
        }

        // 命令注入注入字符：管道符
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("com.mockloc | cat /etc/passwd")
        }

        // 包含空格
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("com.mockloc app")
        }

        // 重定向符
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("com.mockloc > /sdcard/leak.txt")
        }

        // 换行符
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("com.mockloc\nreboot")
        }

        // 非法字符：单引号或反引号
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("com.mockloc'`id`'")
        }

        // 仅一段无点
        assertThrows(IllegalArgumentException::class.java) {
            ShellUtil.buildAppOpsCommand("invalidpackagename")
        }
    }

    // =========================================================================
    // 2. ShellUtil 命令执行与 Su 检测测试
    // =========================================================================

    @Test
    fun testShellUtilExecutionSuccess() {
        ShellUtil.setCommandExecutorForTesting { cmd, _ ->
            assertEquals(listOf("su", "-c", "echo hello"), cmd)
            ShellResult(exitCode = 0, stdout = "hello", stderr = "")
        }

        val result = ShellUtil.executeSuCommand("echo hello")
        assertTrue(result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun testShellUtilExecutionFailure() {
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 127, stdout = "", stderr = "su: not found")
        }

        val result = ShellUtil.executeSuCommand("some_cmd")
        assertFalse(result.isSuccess)
        assertEquals(127, result.exitCode)
        assertEquals("su: not found", result.stderr)
    }

    @Test
    fun testShellUtilCheckSuAvailableTrue() {
        ShellUtil.setCommandExecutorForTesting { cmd, _ ->
            assertEquals(listOf("su", "-c", "id"), cmd)
            ShellResult(exitCode = 0, stdout = "uid=0(root) gid=0(root) groups=0(root)", stderr = "")
        }

        assertTrue("Root should be available when id returns uid=0", ShellUtil.checkSuAvailable())
    }

    @Test
    fun testShellUtilCheckSuAvailableFalseWhenExitNonZero() {
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 1, stdout = "", stderr = "Permission denied")
        }

        assertFalse("Root should be unavailable on non-zero exit", ShellUtil.checkSuAvailable())
    }

    @Test
    fun testShellUtilCheckSuAvailableFalseWhenExceptionThrown() {
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            throw RuntimeException("Process failed to start")
        }

        assertFalse("Root should be gracefully reported as unavailable on error", ShellUtil.checkSuAvailable())
    }

    @Test
    fun testShellUtilGrantMockLocationPermissionSuccessAndFailure() {
        var recordedCommand: String? = null
        ShellUtil.setCommandExecutorForTesting { cmd, _ ->
            recordedCommand = cmd[2]
            ShellResult(exitCode = 0, stdout = "", stderr = "")
        }

        val success = ShellUtil.grantMockLocationPermission("com.target.app")
        assertTrue(success)
        assertEquals("appops set com.target.app android:mock_location allow", recordedCommand)

        // 验证 PROJECT.md 契约别名
        val aliasSuccess = ShellUtil.grantMockPermissionWithSu("com.target.app")
        assertTrue(aliasSuccess)

        // 模拟执行失败
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 1, stdout = "", stderr = "Error")
        }
        val failure = ShellUtil.grantMockLocationPermission("com.target.app")
        assertFalse(failure)
    }

    @Test
    fun testDefaultCommandExecutorHandlesNonExistentProgram() {
        val executor = DefaultCommandExecutor()
        // 执行一个绝对不存在的命令，验证安全捕获而不是崩溃
        val result = executor.execute(listOf("non_existent_binary_xyz_12345"), 1000L)
        assertEquals(-1, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue(result.stderr.isNotEmpty())
    }

    // =========================================================================
    // 3. RootProviderManager 生命周期与状态转换测试
    // =========================================================================

    @Test
    fun testRootProviderManagerCompleteLifecycle() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)

        val manager = RootProviderManager(
            context = mockContext,
            locationManager = mockLocationManager,
            locationFactory = { TestableMockLocation(it) }
        )

        assertFalse("Initially should not be running", manager.isRunning)

        val targetLat = 39.9042
        val targetLng = 116.4074
        val targetAlt = 50.0
        val targetSpeed = 4.5f
        val targetBearing = 120.0f
        val targetAccuracy = 2.5f

        // 1. 启动 Mock
        manager.startMock(
            lat = targetLat,
            lng = targetLng,
            alt = targetAlt,
            speed = targetSpeed,
            bearing = targetBearing,
            accuracy = targetAccuracy
        )

        assertTrue("Manager should be running after startMock", manager.isRunning)

        // 验证先清理旧 provider
        verify(mockLocationManager).removeTestProvider(LocationManager.GPS_PROVIDER)

        // 验证注册 test provider
        verify(mockLocationManager).addTestProvider(
            eq(LocationManager.GPS_PROVIDER),
            eq(false), eq(false), eq(false), eq(false),
            eq(true), eq(true), eq(true),
            eq(Criteria.POWER_LOW),
            eq(Criteria.ACCURACY_FINE)
        )

        // 验证启用 provider
        verify(mockLocationManager).setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)

        // 验证下发初始位置
        val locationCaptor = ArgumentCaptor.forClass(Location::class.java)
        verify(mockLocationManager, times(1)).setTestProviderLocation(
            eq(LocationManager.GPS_PROVIDER),
            locationCaptor.capture()
        )

        val capturedLoc = locationCaptor.value
        assertNotNull(capturedLoc)
        assertEquals(targetLat, capturedLoc.latitude, 1e-6)
        assertEquals(targetLng, capturedLoc.longitude, 1e-6)
        assertEquals(targetAlt, capturedLoc.altitude, 1e-6)
        assertEquals(targetSpeed, capturedLoc.speed, 0.01f)
        assertEquals(targetBearing, capturedLoc.bearing, 0.01f)
        assertEquals(targetAccuracy, capturedLoc.accuracy, 0.01f)

        // 2. 连续更新位置
        val updateLat = 31.2304
        val updateLng = 121.4737
        manager.updateLocation(
            lat = updateLat,
            lng = updateLng,
            alt = 12.0,
            speed = 8.0f,
            bearing = 45.0f,
            accuracy = 1.8f
        )

        verify(mockLocationManager, times(2)).setTestProviderLocation(
            eq(LocationManager.GPS_PROVIDER),
            locationCaptor.capture()
        )
        val secondLoc = locationCaptor.value
        assertEquals(updateLat, secondLoc.latitude, 1e-6)
        assertEquals(updateLng, secondLoc.longitude, 1e-6)

        // 3. 停止 Mock
        manager.stopMock()
        assertFalse("Manager should not be running after stopMock", manager.isRunning)
        verify(mockLocationManager).setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        // removeTestProvider 一共被调用 2 次（start 清理一次，stop 一次）
        verify(mockLocationManager, times(2)).removeTestProvider(LocationManager.GPS_PROVIDER)
    }

    @Test
    fun testRootProviderManagerUpdateWhenNotRunningDoesNothing() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)
        val manager = RootProviderManager(mockContext, mockLocationManager)

        manager.updateLocation(39.9, 116.4)
        verify(mockLocationManager, never()).setTestProviderLocation(any(), any())
    }

    @Test
    fun testRootProviderManagerStopWhenNotRunningDoesNothing() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)
        val manager = RootProviderManager(mockContext, mockLocationManager)

        manager.stopMock()
        verify(mockLocationManager, never()).setTestProviderEnabled(any(), anyBoolean())
        verify(mockLocationManager, never()).removeTestProvider(any())
    }

    @Test
    fun testRootProviderManagerSecurityExceptionHandling() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)

        doThrow(SecurityException("Permission ACCESS_MOCK_LOCATION denied"))
            .`when`(mockLocationManager).addTestProvider(
                any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt()
            )

        val manager = RootProviderManager(mockContext, mockLocationManager)

        assertThrows(SecurityException::class.java) {
            manager.startMock(39.9, 116.4)
        }

        assertFalse("isRunning must remain false when SecurityException occurs", manager.isRunning)
    }

    @Test
    fun testRootProviderManagerToleratesStaleProviderRemovalFailure() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)

        // 模拟清理旧 provider 时抛出 IllegalArgumentException (例如 provider 从未添加过)
        doThrow(IllegalArgumentException("Provider does not exist"))
            .`when`(mockLocationManager).removeTestProvider(LocationManager.GPS_PROVIDER)

        val manager = RootProviderManager(mockContext, mockLocationManager)

        // 应该忽略该异常，继续正常注册
        manager.startMock(39.9, 116.4)
        assertTrue(manager.isRunning)
        verify(mockLocationManager).addTestProvider(
            eq(LocationManager.GPS_PROVIDER),
            anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
            anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt()
        )
    }

    @Test
    fun testRootProviderManagerDefaultAccuracyFallback() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)
        val manager = RootProviderManager(
            mockContext,
            mockLocationManager,
            locationFactory = { TestableMockLocation(it) }
        )

        // 传入非法精度 0.0f
        manager.startMock(39.9, 116.4, accuracy = 0.0f)
        val captor = ArgumentCaptor.forClass(Location::class.java)
        verify(mockLocationManager).setTestProviderLocation(eq(LocationManager.GPS_PROVIDER), captor.capture())
        assertEquals(3.0f, captor.value.accuracy, 0.01f)

        // 传入负数精度 -5.0f
        manager.updateLocation(39.9, 116.4, accuracy = -5.0f)
        verify(mockLocationManager, times(2)).setTestProviderLocation(eq(LocationManager.GPS_PROVIDER), captor.capture())
        assertEquals(3.0f, captor.value.accuracy, 0.01f)
    }

    // =========================================================================
    // 4. EnvironmentDetector 状态模型测试
    // =========================================================================

    @Test
    fun testEnvironmentDetectorBothRootAndXposedAvailable() {
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 0, stdout = "uid=0(root)", stderr = "")
        }
        EnvironmentDetector.setXposedActiveForTesting(true)

        val status = EnvironmentDetector.checkEnvironment()
        assertTrue(status.isRootAvailable)
        assertTrue(status.isXposedActive)
        assertTrue(status.hasAnyMockCapability)
        assertEquals("XPOSED_HOOK", status.recommendedMode)
        assertTrue(status.summary.contains("Xposed Hook Mode"))
    }

    @Test
    fun testEnvironmentDetectorRootOnlyAvailable() {
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 0, stdout = "uid=0(root)", stderr = "")
        }
        EnvironmentDetector.setXposedActiveForTesting(false)

        val status = EnvironmentDetector.checkEnvironment()
        assertTrue(status.isRootAvailable)
        assertFalse(status.isXposedActive)
        assertTrue(status.hasAnyMockCapability)
        assertEquals("ROOT_PROVIDER", status.recommendedMode)
        assertTrue(status.summary.contains("Root Native Provider Mode"))
    }

    @Test
    fun testEnvironmentDetectorNeitherAvailable() {
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 1, stdout = "", stderr = "su not found")
        }
        EnvironmentDetector.setXposedActiveForTesting(false)

        val status = EnvironmentDetector.checkEnvironment()
        assertFalse(status.isRootAvailable)
        assertFalse(status.isXposedActive)
        assertFalse(status.hasAnyMockCapability)
        assertEquals("NONE", status.recommendedMode)
        assertTrue(status.summary.contains("Unprivileged"))
    }
}
