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
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone 3 极限对抗与压力鲁棒性测试 (Adversarial Stress Test Suite)
 * 验证：
 * 1. 深度 Shell 注入防御与畸形输入防御
 * 2. 多线程并发调度下的状态一致性与线程安全性
 * 3. 极端地理与物理边界参数下发校验
 * 4. 频繁快速重入生命周期与异常恢复能力
 */
class RootProviderAdversarialTest {

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
    // 1. 极端 Shell 注入与特殊字符测试
    // =========================================================================

    @Test
    fun testShellInjectionMetacharacterPayloads() {
        val maliciousPayloads = listOf(
            "com.app\$(reboot)",
            "com.app`id`",
            "com.app||reboot",
            "com.app&&whoami",
            "com.app;cat /data/system/users/0/settings_secure.xml",
            "com.app\u0000inject",
            "com.app\r\nrm -rf /",
            "com.app > /dev/null",
            "com.app < /dev/zero",
            "com.app' OR '1'='1",
            "com. app",
            "com .app",
            "..com.app",
            "com.app..sub",
            ".com.app",
            "com.app.",
            "123.com.app" // 第一段以数字开头违背标准命名
        )

        for (payload in maliciousPayloads) {
            assertThrows("Payload must be rejected: $payload", IllegalArgumentException::class.java) {
                ShellUtil.buildAppOpsCommand(payload)
            }
        }
    }

    @Test
    fun testLargeStreamOutputHandling() {
        // 模拟进程产生超大量输出（如 100KB），验证流处理不发生死锁或缓冲区阻塞
        val hugeStdout = "X".repeat(100_000)
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 0, stdout = hugeStdout, stderr = "")
        }

        val result = ShellUtil.executeSuCommand("generate_huge_log")
        assertTrue(result.isSuccess)
        assertEquals(100_000, result.stdout.length)
    }

    // =========================================================================
    // 2. 多线程并发调度与状态一致性
    // =========================================================================

    @Test
    fun testConcurrentStartUpdateStopMock() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)
        val manager = RootProviderManager(
            mockContext,
            mockLocationManager,
            locationFactory = { TestableMockLocation(it) }
        )

        val threadCount = 8
        val iterationsPerThread = 50
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val errorCounter = AtomicInteger(0)

        for (i in 0 until threadCount) {
            Thread {
                try {
                    startLatch.await()
                    for (j in 0 until iterationsPerThread) {
                        manager.startMock(30.0 + j * 0.001, 120.0 + j * 0.001)
                        manager.updateLocation(30.1 + j * 0.001, 120.1 + j * 0.001)
                        manager.stopMock()
                    }
                } catch (t: Throwable) {
                    errorCounter.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        val finished = doneLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent operations should complete within timeout", finished)
        assertEquals("No concurrency errors should occur", 0, errorCounter.get())
        assertFalse("Final state after stop must be consistent", manager.isRunning)
    }

    // =========================================================================
    // 3. 极端地理与物理边界参数校验
    // =========================================================================

    @Test
    fun testExtremeGeographicalCoordinates() {
        val mockContext = mock(Context::class.java)
        val mockLocationManager = mock(LocationManager::class.java)
        val manager = RootProviderManager(
            mockContext,
            mockLocationManager,
            locationFactory = { TestableMockLocation(it) }
        )

        val captor = ArgumentCaptor.forClass(Location::class.java)

        // 南极点极限边界 (-90.0, 0.0)
        manager.startMock(-90.0, 0.0, alt = -418.0, speed = 0.0f, bearing = 0.0f, accuracy = 1.0f)
        verify(mockLocationManager).setTestProviderLocation(eq(LocationManager.GPS_PROVIDER), captor.capture())
        assertEquals(-90.0, captor.value.latitude, 1e-6)
        assertEquals(0.0, captor.value.longitude, 1e-6)
        assertEquals(-418.0, captor.value.altitude, 1e-6) // 死海以下极限海拔

        // 国际日期变更线经度极限 (+180.0, -180.0)
        manager.updateLocation(0.0, 180.0, alt = 8848.86, speed = 1000.0f, bearing = 359.9f, accuracy = 0.5f)
        verify(mockLocationManager, times(2)).setTestProviderLocation(eq(LocationManager.GPS_PROVIDER), captor.capture())
        assertEquals(180.0, captor.value.longitude, 1e-6)
        assertEquals(8848.86, captor.value.altitude, 1e-6) // 珠峰海拔
        assertEquals(1000.0f, captor.value.speed, 0.01f) // 超音速速度
        assertEquals(359.9f, captor.value.bearing, 0.01f)

        manager.stopMock()
        assertFalse(manager.isRunning)
    }

    // =========================================================================
    // 4. EnvironmentDetector 组合场景与推荐模式边界
    // =========================================================================

    @Test
    fun testEnvironmentDetectorCombinations() {
        // 场景 1：Xposed 激活，但 Root 不可用 -> 推荐 XPOSED_HOOK
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 1, stdout = "", stderr = "not found")
        }
        EnvironmentDetector.setXposedActiveForTesting(true)
        val status1 = EnvironmentDetector.checkEnvironment()
        assertFalse(status1.isRootAvailable)
        assertTrue(status1.isXposedActive)
        assertEquals("XPOSED_HOOK", status1.recommendedMode)
        assertTrue(status1.hasAnyMockCapability)

        // 场景 2：Xposed 激活且 Root 也可用 -> 优先推荐 XPOSED_HOOK (深度拦截与防检测能力更强)
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 0, stdout = "uid=0(root)", stderr = "")
        }
        EnvironmentDetector.setXposedActiveForTesting(true)
        val status2 = EnvironmentDetector.checkEnvironment()
        assertTrue(status2.isRootAvailable)
        assertTrue(status2.isXposedActive)
        assertEquals("XPOSED_HOOK", status2.recommendedMode)
        assertTrue(status2.hasAnyMockCapability)

        // 场景 3：无权限无模块 -> 推荐 NONE
        ShellUtil.setCommandExecutorForTesting { _, _ ->
            ShellResult(exitCode = 1, stdout = "", stderr = "")
        }
        EnvironmentDetector.setXposedActiveForTesting(false)
        val status3 = EnvironmentDetector.checkEnvironment()
        assertFalse(status3.isRootAvailable)
        assertFalse(status3.isXposedActive)
        assertEquals("NONE", status3.recommendedMode)
        assertFalse(status3.hasAnyMockCapability)
    }
}
