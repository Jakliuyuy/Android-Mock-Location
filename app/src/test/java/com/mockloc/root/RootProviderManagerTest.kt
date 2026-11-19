package com.mockloc.root

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class RootProviderManagerTest {

    private class FakeTestProviderBackend : RootProviderManager.TestProviderBackend {
        val addCalls = mutableListOf<Pair<String, Int>>()
        val enabledCalls = mutableListOf<Pair<String, Boolean>>()
        val pushedLocations = mutableListOf<PushedLoc>()
        val removedProviders = mutableListOf<String>()
        var hasAppOp: Boolean = true
        var failSecurityOnAddFor: Set<String> = emptySet()
        var failProviders: Set<String> = emptySet()
        var failOnSetEnabledFor: Set<String> = emptySet()
        var throwOnceIllegalArgOnPushFor: MutableSet<String> = mutableSetOf()

        data class PushedLoc(
            val provider: String,
            val lat: Double,
            val lon: Double,
            val alt: Double,
            val acc: Float,
            val bearing: Float,
            val speed: Float
        )

        override fun addTestProvider(provider: String, sdkInt: Int) {
            if (provider in failSecurityOnAddFor) {
                throw SecurityException("not allowed to perform MOCK_LOCATION")
            }
            if (provider in failProviders) {
                throw IllegalArgumentException("provider unsupported: $provider")
            }
            addCalls.add(provider to sdkInt)
        }

        override fun setTestProviderEnabled(provider: String, enabled: Boolean) {
            if (enabled && provider in failOnSetEnabledFor) {
                throw IllegalStateException("ROM failed setTestProviderEnabled for $provider")
            }
            enabledCalls.add(provider to enabled)
        }

        override fun setTestProviderLocation(
            provider: String,
            latitude: Double,
            longitude: Double,
            altitude: Double,
            accuracy: Float,
            bearing: Float,
            speed: Float
        ) {
            if (throwOnceIllegalArgOnPushFor.remove(provider)) {
                throw IllegalArgumentException("provider evicted")
            }
            pushedLocations.add(PushedLoc(provider, latitude, longitude, altitude, accuracy, bearing, speed))
        }

        override fun removeTestProvider(provider: String) {
            removedProviders.add(provider)
        }

        override fun hasMockLocationAppOp(packageName: String, uid: Int, sdkInt: Int): Boolean = hasAppOp
    }

    private class RecordingShellExecutor(
        private val succeedOnCommandSubstring: String? = "appops set"
    ) : ShellExecutor {
        val executedRootCommands = mutableListOf<String>()

        override suspend fun execRoot(command: String, timeoutMs: Long): ShellResult {
            executedRootCommands.add(command)
            val ok = succeedOnCommandSubstring != null && command.contains(succeedOnCommandSubstring)
            return if (ok) ShellResult(0, "", "") else ShellResult(1, "", "permission denied")
        }

        override suspend fun execShell(command: String, timeoutMs: Long): ShellResult = ShellResult(0, "", "")
        override suspend fun hasRootAccess(timeoutMs: Long): Boolean = succeedOnCommandSubstring != null
        override suspend fun detectRootProvider(timeoutMs: Long): String = "Magisk"
    }

    private fun mockContext(pkg: String = "com.mockloc"): Context = mock {
        on { packageName } doReturn pkg
        on { applicationContext } doReturn null
    }

    @Test
    fun `ensureMockPermission rejects invalid package name against injection`() = runTest {
        val shell = RecordingShellExecutor()
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = shell,
            backendFactory = { FakeTestProviderBackend() }
        )

        val res = manager.ensureMockPermission("com.app; rm -rf /")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalArgumentException)
        assertTrue(shell.executedRootCommands.isEmpty())
    }

    @Test
    fun `ensureMockPermission falls back across appops user commands`() = runTest {
        val backend = FakeTestProviderBackend().apply { hasAppOp = false }
        val shell = RecordingShellExecutor(succeedOnCommandSubstring = "appops set com.mockloc")
        val manager = RootProviderManager(
            context = mockContext("com.mockloc"),
            shellExecutor = shell,
            backendFactory = { backend },
            userIdProvider = { 10 }
        )

        val res = manager.ensureMockPermission("com.mockloc")
        assertTrue(res.isSuccess)
        assertEquals(3, shell.executedRootCommands.size)
        assertTrue(shell.executedRootCommands[0].contains("--user 10"))
        assertTrue(shell.executedRootCommands[1].contains("--user current"))
        assertEquals("appops set com.mockloc android:mock_location allow", shell.executedRootCommands[2])
    }

    @Test
    fun `ensureMockPermission returns failure when all appops commands fail`() = runTest {
        val backend = FakeTestProviderBackend().apply { hasAppOp = false }
        val shell = RecordingShellExecutor(succeedOnCommandSubstring = null)
        val manager = RootProviderManager(
            context = mockContext("com.mockloc"),
            shellExecutor = shell,
            backendFactory = { backend }
        )

        val res = manager.ensureMockPermission("com.mockloc")
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `startOrUpdate registers gps network fused once and updates subsequent coordinates`() = runTest {
        val backend = FakeTestProviderBackend()
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = RecordingShellExecutor(),
            backendFactory = { backend },
            sdkIntProvider = { 35 }
        )

        val r1 = manager.startOrUpdate(39.9087, 116.3975, 50.0, 3.0f, 90f, 5.0f)
        val r2 = manager.startOrUpdate(39.9090, 116.3980, 52.0, 3.5f, 95f, 6.0f)

        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        // 三个 Provider 各 add 一次，并伴随 sdkInt=35
        assertEquals(
            listOf("gps" to 35, "network" to 35, "fused" to 35),
            backend.addCalls
        )
        // 推送 2 轮 * 3 路 = 6 次坐标
        assertEquals(6, backend.pushedLocations.size)
        assertEquals(39.9090, backend.pushedLocations.last().lat, 1e-6)
    }

    @Test
    fun `startOrUpdate recovers from SecurityException via ensureMockPermission`() = runTest {
        val backend = FakeTestProviderBackend().apply {
            hasAppOp = false
            failSecurityOnAddFor = setOf("gps")
        }
        val shell = object : ShellExecutor {
            override suspend fun execRoot(command: String, timeoutMs: Long): ShellResult {
                backend.failSecurityOnAddFor = emptySet()
                backend.hasAppOp = true
                return ShellResult(0, "", "")
            }

            override suspend fun execShell(command: String, timeoutMs: Long) = ShellResult(0, "", "")
            override suspend fun hasRootAccess(timeoutMs: Long) = true
            override suspend fun detectRootProvider(timeoutMs: Long) = "Magisk"
        }

        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = shell,
            backendFactory = { backend },
            sdkIntProvider = { 30 }
        )

        val res = manager.startOrUpdate(31.2304, 121.4737, 10.0, 3.0f, 0f, 0f)
        assertTrue(res.isSuccess)
        assertEquals(listOf("gps" to 30, "network" to 30, "fused" to 30), backend.addCalls)
    }

    @Test
    fun `startOrUpdate returns explicit failure when core gps provider fails`() = runTest {
        val backend = FakeTestProviderBackend().apply {
            failProviders = setOf("gps", "network", "fused")
        }
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = RecordingShellExecutor(),
            backendFactory = { backend }
        )

        val res = manager.startOrUpdate(31.2304, 121.4737, 10.0, 3.0f, 0f, 0f)
        assertTrue(res.isFailure)
    }

    @Test
    fun `startOrUpdate tolerates secondary fused failure when gps succeeds`() = runTest {
        val backend = FakeTestProviderBackend().apply {
            failProviders = setOf("fused")
        }
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = RecordingShellExecutor(),
            backendFactory = { backend }
        )

        val res = manager.startOrUpdate(31.2304, 121.4737, 10.0, 3.0f, 0f, 0f)
        assertTrue(res.isSuccess)
        assertEquals(2, backend.pushedLocations.size)
    }

    @Test
    fun `startOrUpdate rejects invalid coordinates immediately`() = runTest {
        val backend = FakeTestProviderBackend()
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = RecordingShellExecutor(),
            backendFactory = { backend }
        )

        assertTrue(manager.startOrUpdate(Double.NaN, 116.0, 0.0, 3.0f, 0f, 0f).isFailure)
        assertTrue(manager.startOrUpdate(95.0, 116.0, 0.0, 3.0f, 0f, 0f).isFailure)
        assertTrue(backend.pushedLocations.isEmpty())
    }

    @Test
    fun `stop cleans up providers and concurrent start and stop remain consistent`() = runTest {
        val backend = FakeTestProviderBackend()
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = RecordingShellExecutor(),
            backendFactory = { backend }
        )

        val jobs = (1..10).map { i ->
            async {
                if (i % 2 == 0) {
                    manager.startOrUpdate(30.0 + i * 0.01, 120.0, 10.0, 3.0f, 0f, 0f)
                } else {
                    manager.stop()
                }
            }
        }
        jobs.awaitAll()

        val stopRes = manager.stop()
        assertTrue(stopRes.isSuccess)
        assertTrue(backend.removedProviders.containsAll(listOf("gps", "network", "fused")))
    }

    @Test
    fun `startOrUpdate rolls back gps provider atomically when network fails with SecurityException`() = runTest {
        val backend = FakeTestProviderBackend().apply {
            hasAppOp = false
            failSecurityOnAddFor = setOf("network")
        }
        val shell = RecordingShellExecutor(succeedOnCommandSubstring = null)
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = shell,
            backendFactory = { backend },
            sdkIntProvider = { 35 }
        )

        val res = manager.startOrUpdate(39.9087, 116.3975, 50.0, 3.0f, 90f, 5.0f)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is SecurityException)
        assertTrue(backend.removedProviders.contains("gps"))
        assertTrue(backend.enabledCalls.contains("gps" to false))
    }

    @Test
    fun `startOrUpdate rolls back secondary providers when gps provider fails`() = runTest {
        val backend = FakeTestProviderBackend().apply {
            failProviders = setOf("gps")
        }
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = RecordingShellExecutor(),
            backendFactory = { backend },
            sdkIntProvider = { 35 }
        )

        val res = manager.startOrUpdate(39.9087, 116.3975, 50.0, 3.0f, 90f, 5.0f)
        assertTrue(res.isFailure)
        assertTrue(backend.removedProviders.containsAll(listOf("network", "fused")))
    }

    @Test
    fun `pushSingleProvider cleans up newly added provider when setTestProviderEnabled throws`() = runTest {
        val backend = FakeTestProviderBackend().apply {
            failOnSetEnabledFor = setOf("gps")
        }
        val manager = RootProviderManager(
            context = mockContext(),
            shellExecutor = RecordingShellExecutor(),
            backendFactory = { backend },
            sdkIntProvider = { 35 }
        )

        val res = manager.startOrUpdate(39.9087, 116.3975, 50.0, 3.0f, 90f, 5.0f)
        assertTrue(res.isFailure)
        assertEquals(2, backend.removedProviders.count { it == "gps" })
        assertTrue(backend.enabledCalls.contains("gps" to false))
    }
}
