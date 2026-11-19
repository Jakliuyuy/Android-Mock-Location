package com.mockloc.root

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import com.mockloc.R
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class EnvironmentDetectorTest {

    private class StubShellExecutor(
        private val rootAvailable: Boolean,
        private val rootProvider: String
    ) : ShellExecutor {
        override suspend fun execRoot(command: String, timeoutMs: Long): ShellResult {
            return if (rootAvailable) ShellResult(0, "uid=0(root)", "") else ShellResult(1, "", "denied")
        }

        override suspend fun execShell(command: String, timeoutMs: Long): ShellResult {
            return ShellResult(0, "", "")
        }

        override suspend fun hasRootAccess(timeoutMs: Long): Boolean = rootAvailable

        override suspend fun detectRootProvider(timeoutMs: Long): String = rootProvider
    }

    @Test
    fun `detectEnvironment reports Magisk KernelSU APatch and installed scope count`() = runTest {
        val scopeArray = arrayOf(
            "com.mockloc",
            "com.autonavi.minimap",
            "com.tencent.mm",
            "com.baidu.BaiduMap"
        )
        val resources = mock<Resources> {
            on { getStringArray(R.array.xposed_scope) } doReturn scopeArray
        }
        val pm = mock<PackageManager>()
        val context = mock<Context> {
            on { getResources() } doReturn resources
            on { getPackageManager() } doReturn pm
            on { checkPermission(any(), any(), any()) } doReturn PackageManager.PERMISSION_GRANTED
        }

        val installed = setOf("com.mockloc", "com.tencent.mm")
        val status = EnvironmentDetector.detectEnvironment(
            context = context,
            shellExecutor = StubShellExecutor(rootAvailable = true, rootProvider = "Magisk"),
            sdkInt = 35,
            packageInstalledChecker = { _, pkg, _ -> pkg in installed }
        )

        assertTrue(status.isRootAvailable)
        assertEquals("Magisk", status.rootProviderName)
        assertTrue(status.hasLocationPermission)
        assertTrue(status.hasNotificationPermission)
        assertEquals(2, status.recommendedScopeInstalledCount)
        assertEquals(4, status.recommendedScopeTotalCount)
        assertFalse(status.isLSPosedActive)
        assertEquals(0, status.xposedApiVersion)
    }

    @Test
    fun `detectEnvironment handles no root and missing permissions safely`() = runTest {
        val context = mock<Context> {
            on { checkPermission(any(), any(), any()) } doReturn PackageManager.PERMISSION_DENIED
        }

        val throwingExecutor = object : ShellExecutor {
            override suspend fun execRoot(command: String, timeoutMs: Long) =
                throw RuntimeException("su unavailable")
            override suspend fun execShell(command: String, timeoutMs: Long) =
                ShellResult(-1, "", "err")
            override suspend fun hasRootAccess(timeoutMs: Long): Boolean =
                throw RuntimeException("timeout")
            override suspend fun detectRootProvider(timeoutMs: Long): String =
                throw RuntimeException("probe crash")
        }

        val status = EnvironmentDetector.detectEnvironment(
            context = context,
            shellExecutor = throwingExecutor,
            sdkInt = 34
        )

        assertFalse(status.isRootAvailable)
        assertEquals("None", status.rootProviderName)
        assertFalse(status.hasLocationPermission)
        assertFalse(status.hasNotificationPermission)
        // 即使 resources 为 null 也有内置 10 项推荐包名兜底
        assertEquals(10, status.recommendedScopeTotalCount)
        assertEquals(0, status.recommendedScopeInstalledCount)
    }

    @Test
    fun `detectEnvironment recognizes KernelSU and APatch providers`() = runTest {
        val context = mock<Context> {
            on { checkPermission(any(), any(), any()) } doReturn PackageManager.PERMISSION_DENIED
        }

        val ksuStatus = EnvironmentDetector.detectEnvironment(
            context = context,
            shellExecutor = StubShellExecutor(rootAvailable = true, rootProvider = "KernelSU"),
            sdkInt = 35
        )
        assertTrue(ksuStatus.isRootAvailable)
        assertEquals("KernelSU", ksuStatus.rootProviderName)

        val apatchStatus = EnvironmentDetector.detectEnvironment(
            context = context,
            shellExecutor = StubShellExecutor(rootAvailable = true, rootProvider = "APatch"),
            sdkInt = 36
        )
        assertTrue(apatchStatus.isRootAvailable)
        assertEquals("APatch", apatchStatus.rootProviderName)
    }

    @Test
    fun `detectEnvironment falls back to PackageManager getPackageInfo safely`() = runTest {
        val pm = mock<PackageManager> {
            on { getPackageInfo(org.mockito.kotlin.eq("com.mockloc"), any<Int>()) } doReturn PackageInfo()
            on { getPackageInfo(org.mockito.kotlin.eq("com.tencent.mm"), any<Int>()) } doReturn PackageInfo()
        }
        val context = mock<Context> {
            on { packageManager } doReturn pm
            on { checkPermission(any(), any(), any()) } doReturn PackageManager.PERMISSION_GRANTED
        }

        val status = EnvironmentDetector.detectEnvironment(
            context = context,
            shellExecutor = StubShellExecutor(rootAvailable = false, rootProvider = "None"),
            sdkInt = 31
        )
        assertEquals(2, status.recommendedScopeInstalledCount)
    }

    @Test
    fun `detectEnvironment returns isRootAvailable false and None when detectRootProvider leaks name without root access`() = runTest {
        val context = mock<Context> {
            on { checkPermission(any(), any(), any()) } doReturn PackageManager.PERMISSION_GRANTED
        }
        val status = EnvironmentDetector.detectEnvironment(
            context = context,
            shellExecutor = StubShellExecutor(rootAvailable = false, rootProvider = "Magisk"),
            sdkInt = 35
        )
        assertFalse(status.isRootAvailable)
        assertEquals("None", status.rootProviderName)
    }
}
