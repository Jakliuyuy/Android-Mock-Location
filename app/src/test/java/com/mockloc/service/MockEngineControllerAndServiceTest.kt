package com.mockloc.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.MotionSimulator
import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import com.mockloc.testing.InMemorySharedPreferences
import com.mockloc.testing.createStatefulMockBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MockEngineControllerAndServiceTest {

    @Test
    fun `missing location permission blocks startForeground and transitions to Error without crashing`() = runTest {
        val prefs = InMemorySharedPreferences()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        var foregroundStartCalls = 0
        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            permissionProvider = {
                PermissionChecker.evaluateStartForegroundEligibility(
                    hasFineLocation = false,
                    hasCoarseLocation = false,
                    hasNotification = true
                )
            },
            broadcastDispatcher = { _, _ -> },
            foregroundLauncher = { start ->
                if (start) foregroundStartCalls++
                true
            },
            scope = backgroundScope
        )

        val controller = MockEngineController(
            coordinator = coordinator,
            permissionCheck = {
                PermissionChecker.evaluateStartForegroundEligibility(
                    hasFineLocation = false,
                    hasCoarseLocation = false,
                    hasNotification = true
                )
            },
            dispatcher = dispatcher
        )

        try {
            controller.startSingleMock(GeoPoint(39.9087, 116.3975), MockConfig())
            runCurrent()

            assertEquals(0, foregroundStartCalls)
            assertTrue(controller.runtimeState.value.status is MockRunStatus.Error)
            assertFalse(repo.currentConfig.enabled)

            // 传入空路线也应安全进入 Error，不抛异常、不启动前台服务
            val permittedCoordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = {
                    PermissionChecker.evaluateStartForegroundEligibility(true, true, true)
                },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { start ->
                    if (start) foregroundStartCalls++
                    true
                },
                scope = backgroundScope
            )
            permittedCoordinator.startCruise(PlannedRoute(), CruiseConfig(), MockConfig())
            runCurrent()
            assertEquals(0, foregroundStartCalls)
            assertTrue(permittedCoordinator.runtimeState.value.status is MockRunStatus.Error)
        } finally {
            controller.stopMock()
            controller.release()
        }
    }

    @Test
    fun `single mock pulses update repository dispatch explicit broadcasts and obey IMMUTABLE PendingIntent flags`() = runTest {
        val prefs = InMemorySharedPreferences()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        val dispatchedPackages = mutableListOf<String>()
        var foregroundActive = false
        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            permissionProvider = {
                PermissionChecker.evaluateStartForegroundEligibility(true, true, true)
            },
            broadcastDispatcher = { _, packages ->
                dispatchedPackages.addAll(packages)
            },
            foregroundLauncher = { start ->
                foregroundActive = start
                true
            },
            scope = backgroundScope,
            tickIntervalMs = 1000L,
            motionSimulator = MotionSimulator(Random(11))
        )
        val controller = MockEngineController(
            coordinator = coordinator,
            permissionCheck = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            dispatcher = dispatcher
        )

        try {
            val config = MockConfig(
                mode = MockMode.LSPOSED_ONLY,
                jitterEnabled = true,
                jitterRadiusMeters = 3.0,
                targetPackages = setOf("com.tencent.mm", "com.autonavi.minimap")
            )
            controller.startSingleMock(GeoPoint(39.9087, 116.3975), config)
            runCurrent()

            assertTrue(foregroundActive)
            assertTrue(controller.runtimeState.value.status is MockRunStatus.SingleRunning)
            assertTrue(repo.currentConfig.enabled)
            assertEquals(listOf("com.tencent.mm", "com.autonavi.minimap"), dispatchedPackages)

            // 验证 PendingIntent 标志与显式包名 Intent 构造满足 Android 14-17 铁律
            assertTrue(
                (MockLocationForegroundService.PENDING_INTENT_FLAGS and PendingIntent.FLAG_IMMUTABLE) != 0
            )
            val spec = MockLocationForegroundService.buildConfigBroadcastSpec(
                repo.currentConfig,
                "com.tencent.mm"
            )
            assertEquals(IpcProtocol.ACTION_CONFIG_CHANGED, spec.action)
            assertEquals("com.tencent.mm", spec.targetPackage)

            val mockIntent = mock<Intent>()
            var capturedAction: String? = null
            MockLocationForegroundService.buildConfigBroadcastIntent(
                config = repo.currentConfig,
                targetPackage = "com.tencent.mm",
                intentFactory = { action ->
                    capturedAction = action
                    mockIntent
                },
                bundleFactory = { createStatefulMockBundle() }
            )
            assertEquals(IpcProtocol.ACTION_CONFIG_CHANGED, capturedAction)
            verify(mockIntent).setPackage("com.tencent.mm")
            verify(mockIntent).putExtras(any<Bundle>())

            // 推进 2 秒，验证脉冲持续驱动并同步
            advanceTimeBy(2100L)
            runCurrent()
            assertTrue(dispatchedPackages.size >= 6)

            controller.stopMock()
            runCurrent()
            assertFalse(foregroundActive)
            assertTrue(controller.runtimeState.value.status is MockRunStatus.Idle)
            assertFalse(repo.currentConfig.enabled)
        } finally {
            controller.stopMock()
            controller.release()
        }
    }

    @Test
    fun `ONE_WAY cruise completes cleanly exits loop with zero idle ticks afterwards and supports restart`() = runTest {
        val prefs = InMemorySharedPreferences()
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        var tickDispatchCount = 0
        var foregroundActive = false
        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            permissionProvider = {
                PermissionChecker.evaluateStartForegroundEligibility(true, true, true)
            },
            broadcastDispatcher = { _, _ -> tickDispatchCount++ },
            foregroundLauncher = { start ->
                foregroundActive = start
                true
            },
            scope = backgroundScope,
            tickIntervalMs = 1000L,
            motionSimulator = MotionSimulator(Random(5))
        )

        try {
            val p0 = GeoPoint(39.9000, 116.4000)
            val p1 = SpatialMath.offsetByMeters(p0, northMeters = 50.0, eastMeters = 0.0)
            val route = PlannedRoute(
                id = "oneway-test",
                name = "短途单向",
                waypoints = listOf(Waypoint(p0), Waypoint(p1))
            )
            val cruiseCfg = CruiseConfig(
                speedMps = 30.0,
                loopMode = LoopMode.ONE_WAY,
                enableSpeedNoise = false,
                enableMicroDrift = false
            )

            coordinator.startCruise(
                route,
                cruiseCfg,
                MockConfig(mode = MockMode.LSPOSED_ONLY, targetPackages = setOf("com.tencent.mm"))
            )
            runCurrent()
            assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)

            // 50m @ 30m/s 在 2 秒时到达终点并触发 COMPLETED 收尾
            advanceTimeBy(2500L)
            runCurrent()
            assertEquals(MockRunStatus.Idle, coordinator.runtimeState.value.status)
            assertEquals(1.0f, coordinator.runtimeState.value.progressFraction, 1e-5f)
            assertFalse(foregroundActive)
            assertFalse(repo.currentConfig.enabled)

            val countAtCompletion = tickDispatchCount

            // 再推进 60 秒：必须产生 0 次多余空转 tick！
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(countAtCompletion, tickDispatchCount)

            // 支持再次干净重启巡航
            coordinator.startCruise(route, cruiseCfg, MockConfig(mode = MockMode.LSPOSED_ONLY))
            runCurrent()
            assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)
            coordinator.stopMock()
            runCurrent()
        } finally {
            coordinator.stopMock()
        }
    }

    @Test
    fun `ROOT_ONLY mode transitions to Error when RootLocationDriver fails`() = runTest {
        val prefs = InMemorySharedPreferences()
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        var rootStopped = false
        val failingRootDriver = object : RootLocationDriver {
            override suspend fun startOrUpdate(
                latitude: Double,
                longitude: Double,
                altitude: Double,
                accuracy: Float,
                bearing: Float,
                speed: Float
            ): Result<Unit> = Result.failure(SecurityException("mock_location AppOps denied"))

            override suspend fun stop(): Result<Unit> {
                rootStopped = true
                return Result.success(Unit)
            }
        }

        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            rootDriver = failingRootDriver,
            permissionProvider = {
                PermissionChecker.evaluateStartForegroundEligibility(true, true, true)
            },
            broadcastDispatcher = { _, _ -> },
            foregroundLauncher = { true },
            scope = backgroundScope,
            tickIntervalMs = 1000L
        )

        try {
            coordinator.startSingleMock(
                GeoPoint(39.9087, 116.3975),
                MockConfig(mode = MockMode.ROOT_ONLY)
            )
            advanceTimeBy(500L)
            runCurrent()

            val status = coordinator.runtimeState.value.status
            assertTrue("Expected Error status on Root failure, got $status", status is MockRunStatus.Error)
            assertTrue(rootStopped)
            assertFalse(repo.currentConfig.enabled)
        } finally {
            coordinator.stopMock()
        }
    }

    @Test
    fun `foreground notification sync updates presentation and actions across Idle SingleRunning CruiseRunning CruisePaused and Idle`() = runTest {
        val prefs = InMemorySharedPreferences()
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        var foregroundActive = false
        val updates = mutableListOf<MockLocationForegroundService.NotificationPresentation>()
        var idleCleanupCalls = 0
        lateinit var notificationSync: MockLocationForegroundService.ServiceNotificationSync

        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            broadcastDispatcher = { _, _ -> },
            foregroundLauncher = { start ->
                foregroundActive = start
                if (start) {
                    notificationSync.resetOnForegroundStarted(MockLocationForegroundService.buildNotificationPresentation(
                        com.mockloc.domain.model.MockRuntimeState()
                    ).let { com.mockloc.domain.model.MockRuntimeState() })
                }
                true
            },
            scope = backgroundScope,
            tickIntervalMs = 1000L,
            motionSimulator = MotionSimulator(Random(7))
        )

        notificationSync = MockLocationForegroundService.ServiceNotificationSync(
            isForegroundActive = { foregroundActive },
            onUpdateNotification = { _, presentation -> updates.add(presentation) },
            onIdleCleanup = {
                idleCleanupCalls++
                foregroundActive = false
            }
        )

        val collectJob = backgroundScope.launch {
            coordinator.runtimeState.collect { state ->
                notificationSync.onStateChanged(state)
            }
        }

        try {
            runCurrent()
            // 初始 Idle 不触发更新也不触发清理
            assertEquals(0, updates.size)
            assertEquals(0, idleCleanupCalls)

            // 1. 启动单点模拟 -> 刷新为 SingleRunning
            coordinator.startSingleMock(
                GeoPoint(39.9087, 116.3975),
                MockConfig(mode = MockMode.LSPOSED_ONLY, jitterEnabled = true, jitterRadiusMeters = 1.5)
            )
            runCurrent()
            assertEquals(1, updates.size)
            assertTrue(updates.last().contentText.contains("单点模拟运行中"))
            assertFalse(updates.last().showPauseAction)
            assertFalse(updates.last().showResumeAction)
            assertTrue(updates.last().showStopAction)

            // 推进 3 秒微小抖动（< 0.0001°）：不应刷屏重复通知
            advanceTimeBy(3100L)
            runCurrent()
            assertEquals(1, updates.size)

            // 2. 切换至多航点巡航 -> 刷新为 CruiseRunning 且带暂停按钮
            val p0 = GeoPoint(39.9000, 116.4000)
            val p1 = SpatialMath.offsetByMeters(p0, northMeters = 100.0, eastMeters = 0.0)
            val route = PlannedRoute(
                id = "notif-route",
                name = "通知测试路线",
                waypoints = listOf(Waypoint(p0), Waypoint(p1))
            )
            coordinator.startCruise(
                route,
                CruiseConfig(speedMps = 10.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false),
                MockConfig(mode = MockMode.LSPOSED_ONLY)
            )
            runCurrent()
            assertEquals(2, updates.size)
            assertTrue(updates.last().contentText.contains("轨迹巡航运行中"))
            assertTrue(updates.last().showPauseAction)
            assertFalse(updates.last().showResumeAction)

            // 3. 暂停巡航 -> 刷新为 CruisePaused 且带继续按钮
            coordinator.pauseCruise()
            runCurrent()
            assertEquals(3, updates.size)
            assertTrue(updates.last().contentText.contains("轨迹巡航已暂停"))
            assertFalse(updates.last().showPauseAction)
            assertTrue(updates.last().showResumeAction)

            // 4. 恢复巡航 -> 刷新为 CruiseRunning 且恢复暂停按钮
            coordinator.resumeCruise()
            runCurrent()
            assertEquals(4, updates.size)
            assertTrue(updates.last().contentText.contains("轨迹巡航运行中"))
            assertTrue(updates.last().showPauseAction)
            assertFalse(updates.last().showResumeAction)

            // 5. 停止模拟 -> 回到 Idle 并触发清理
            coordinator.stopMock()
            runCurrent()
            assertEquals(1, idleCleanupCalls)
            assertFalse(foregroundActive)
        } finally {
            collectJob.cancel()
            coordinator.stopMock()
        }
    }

    @Test
    fun `stopMock during bindService window cancels orphan foreground notification upon onServiceConnected`() = runTest {
        val prefs = InMemorySharedPreferences()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        var foregroundActive = false
        var serviceStartedCount = 0
        var serviceStoppedCount = 0
        var capturedConnection: ServiceConnection? = null

        val mockContext = mock<Context>()
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        doAnswer { inv ->
            capturedConnection = inv.getArgument(1)
            true
        }.whenever(mockContext).bindService(any(), any(), any<Int>())

        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            broadcastDispatcher = { _, _ -> },
            foregroundLauncher = { start ->
                foregroundActive = start
                true
            },
            scope = backgroundScope,
            tickIntervalMs = 1000L
        )

        val mockService = mock<MockLocationForegroundService>()
        whenever(mockService.getCoordinator()).thenReturn(coordinator)
        var serviceSelfStopped = false
        doAnswer {
            foregroundActive = false
            serviceSelfStopped = true
            Unit
        }.whenever(mockService).stopForegroundAndSelf()

        val mockBinder = mock<MockLocationForegroundService.LocalBinder>()
        whenever(mockBinder.getService()).thenReturn(mockService)

        val controller = MockEngineController(
            context = mockContext,
            permissionCheck = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            serviceStarter = {
                serviceStartedCount++
                foregroundActive = true
            },
            dispatcher = dispatcher,
            serviceStopper = {
                serviceStoppedCount++
            },
            leaseCheck = { true }
        )

        try {
            // 1. 发起 startSingleMock，此时尚未触发 onServiceConnected
            controller.startSingleMock(
                GeoPoint(39.9087, 116.3975),
                MockConfig(mode = MockMode.LSPOSED_ONLY)
            )
            assertEquals(1, serviceStartedCount)
            assertTrue(foregroundActive)
            assertTrue(capturedConnection != null)

            // 2. 在绑定完成窗口期立刻调用 stopMock()
            controller.stopMock()
            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)

            // 3. 延迟触发 onServiceConnected：必须立即调用 coordinator.stopMock() 并清理前台服务
            capturedConnection!!.onServiceConnected(
                ComponentName("com.mockloc", MockLocationForegroundService::class.java.name),
                mockBinder
            )
            runCurrent()
            advanceTimeBy(3000L)
            runCurrent()

            assertFalse(foregroundActive)
            assertTrue(serviceSelfStopped)
            assertEquals(1, serviceStoppedCount)
            assertEquals(MockRunStatus.Idle, coordinator.runtimeState.value.status)
            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)
            assertFalse(repo.currentConfig.enabled)

            // 4. 之后重新发起单点模拟仍能正常启动
            controller.startSingleMock(
                GeoPoint(31.2304, 121.4737),
                MockConfig(mode = MockMode.LSPOSED_ONLY)
            )
            runCurrent()
            assertTrue(foregroundActive)
            assertTrue(controller.runtimeState.value.status is MockRunStatus.SingleRunning)
            assertTrue(repo.currentConfig.enabled)
        } finally {
            controller.stopMock()
            controller.release()
        }
    }

    @Test
    fun `coordinator enforces updateSingleTarget state guard and broadcasts updateSpeed without corrupting paused speed`() = runTest {
        val prefs = InMemorySharedPreferences()
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        val broadcastSnapshots = mutableListOf<MockConfig>()
        val rootUpdatedPoints = mutableListOf<Pair<Double, Double>>()
        val fakeRootDriver = object : RootLocationDriver {
            override suspend fun startOrUpdate(
                latitude: Double,
                longitude: Double,
                altitude: Double,
                accuracy: Float,
                bearing: Float,
                speed: Float
            ): Result<Unit> {
                rootUpdatedPoints.add(latitude to longitude)
                return Result.success(Unit)
            }

            override suspend fun stop(): Result<Unit> = Result.success(Unit)
        }

        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            rootDriver = fakeRootDriver,
            permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            broadcastDispatcher = { cfg, _ -> broadcastSnapshots.add(cfg) },
            foregroundLauncher = { true },
            scope = backgroundScope,
            tickIntervalMs = 1000L,
            motionSimulator = MotionSimulator(Random(3))
        )

        try {
            // 1. Idle 状态下 updateSingleTarget 应安全忽略，不修改配置或状态
            val initialLat = repo.currentConfig.latitude
            coordinator.updateSingleTarget(GeoPoint(31.2304, 121.4737))
            runCurrent()
            assertEquals(MockRunStatus.Idle, coordinator.runtimeState.value.status)
            assertEquals(initialLat, repo.currentConfig.latitude, 1e-9)
            assertTrue(broadcastSnapshots.isEmpty())

            // 2. SingleRunning 状态下 updateSingleTarget 生效并立即异步推送 rootDriver 与广播
            coordinator.startSingleMock(
                GeoPoint(39.9087, 116.3975),
                MockConfig(mode = MockMode.AUTO, targetPackages = setOf("com.tencent.mm"))
            )
            runCurrent()
            val countBeforeUpdate = broadcastSnapshots.size

            val hotTarget = GeoPoint(39.9163, 116.3972)
            coordinator.updateSingleTarget(hotTarget)
            runCurrent()

            assertEquals(hotTarget, coordinator.runtimeState.value.currentPoint)
            assertEquals(hotTarget.latitude, repo.currentConfig.latitude, 1e-9)
            assertTrue(broadcastSnapshots.size > countBeforeUpdate)
            assertEquals(hotTarget.latitude, broadcastSnapshots.last().latitude, 1e-9)
            assertTrue(
                "Root driver should receive hot-updated target immediately",
                rootUpdatedPoints.any { kotlin.math.abs(it.first - hotTarget.latitude) < 1e-6 }
            )

            // 3. CruiseRunning 与 CruisePaused 下 updateSingleTarget 应被忽略，updateSpeed 广播且保持暂停速度为 0.0
            val p0 = GeoPoint(39.9000, 116.4000)
            val p1 = SpatialMath.offsetByMeters(p0, northMeters = 500.0, eastMeters = 0.0)
            val route = PlannedRoute(
                id = "speed-guard-route",
                name = "速度测试路线",
                waypoints = listOf(Waypoint(p0), Waypoint(p1))
            )
            coordinator.startCruise(
                route,
                CruiseConfig(speedMps = 5.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false),
                MockConfig(mode = MockMode.LSPOSED_ONLY, targetPackages = setOf("com.tencent.mm"))
            )
            runCurrent()

            val cruisePointBefore = coordinator.runtimeState.value.currentPoint
            coordinator.updateSingleTarget(GeoPoint(22.5431, 114.0579))
            runCurrent()
            assertEquals(cruisePointBefore, coordinator.runtimeState.value.currentPoint)
            assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)

            coordinator.pauseCruise()
            runCurrent()
            assertEquals(MockRunStatus.CruisePaused, coordinator.runtimeState.value.status)
            assertEquals(0.0, coordinator.runtimeState.value.speedMps, 0.0)

            val broadcastsBeforeSpeed = broadcastSnapshots.size
            coordinator.updateSpeed(15.0)
            runCurrent()

            // 广播立即触发且配置更新为 15.0，但暂停态运行时 speedMps 严格保持 0.0
            assertEquals(broadcastsBeforeSpeed + 1, broadcastSnapshots.size)
            assertEquals(15.0f, broadcastSnapshots.last().speed, 1e-5f)
            assertEquals(0.0, coordinator.runtimeState.value.speedMps, 0.0)

            // 恢复巡航后以新速度 15.0 恢复并推进
            coordinator.resumeCruise()
            runCurrent()
            assertEquals(MockRunStatus.CruiseRunning, coordinator.runtimeState.value.status)
            assertEquals(15.0, coordinator.runtimeState.value.speedMps, 1e-6)

            advanceTimeBy(1100L)
            runCurrent()
            assertEquals(15.0, coordinator.runtimeState.value.speedMps, 1e-6)
        } finally {
            coordinator.stopMock()
        }
    }

    @Test
    fun `real Dispatchers Default stress test prevents ghost frame writeback after stopMock and stale target overwrite`() = runBlocking {
        // (a) 30 轮真实多线程 startSingleMock(LSPOSED_ONLY) -> tick 交错 -> stopMock() 压测
        for (round in 0 until 30) {
            val prefs = InMemorySharedPreferences()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val broadcasts = Collections.synchronizedList(mutableListOf<MockConfig>())
            val repo = MockConfigRepository(prefs = prefs, ioScope = ioScope, debounceMs = 1L)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
                broadcastDispatcher = { cfg, _ -> broadcasts.add(cfg) },
                foregroundLauncher = { true },
                scope = scope,
                tickIntervalMs = 1L,
                motionSimulator = MotionSimulator(Random(round))
            )
            try {
                val p1 = GeoPoint(39.9087, 116.3975)
                val cfg = MockConfig(
                    mode = MockMode.LSPOSED_ONLY,
                    jitterEnabled = true,
                    jitterRadiusMeters = 3.0,
                    targetPackages = setOf("com.tencent.mm")
                )
                coordinator.startSingleMock(p1, cfg)
                Thread.sleep((round % 5 + 1).toLong())
                coordinator.stopMock()

                assertEquals("Round $round immediate status must be Idle", MockRunStatus.Idle, coordinator.runtimeState.value.status)
                assertFalse("Round $round immediate config.enabled must be false", repo.currentConfig.enabled)

                // 等待 50ms 观察是否出现幽灵帧写回
                Thread.sleep(50L)
                repo.flushPendingSync()

                assertEquals("Round $round ghost status writeback detected", MockRunStatus.Idle, coordinator.runtimeState.value.status)
                assertFalse("Round $round ghost memory enabled=true detected", repo.currentConfig.enabled)
                assertFalse(
                    "Round $round ghost disk enabled=true detected",
                    ConfigSyncSerializer.fromPrefs(prefs).enabled
                )
                assertFalse("Round $round last broadcast must have enabled=false", broadcasts.last().enabled)
            } finally {
                coordinator.stopMock()
                scope.cancel()
                ioScope.cancel()
            }
        }

        // (b) 30 轮真实多线程 startSingleMock(P1) -> 高频 updateSingleTarget(P2) 不被旧点 P1 覆盖
        val p1 = GeoPoint(39.9087, 116.3975)
        val p2 = GeoPoint(31.2304, 121.4737)
        for (round in 0 until 30) {
            val prefs = InMemorySharedPreferences()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val repo = MockConfigRepository(prefs = prefs, ioScope = ioScope, debounceMs = 1L)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope,
                tickIntervalMs = 1L,
                motionSimulator = MotionSimulator(Random(100 + round))
            )
            try {
                coordinator.startSingleMock(
                    p1,
                    MockConfig(mode = MockMode.LSPOSED_ONLY, jitterEnabled = true, jitterRadiusMeters = 3.0)
                )
                Thread.sleep((round % 4 + 1).toLong())
                coordinator.updateSingleTarget(p2)
                Thread.sleep(50L)

                val currentPoint = coordinator.runtimeState.value.currentPoint!!
                assertTrue(
                    "Round $round runtime currentPoint overwritten by stale P1: $currentPoint",
                    currentPoint.latitude < 32.0
                )
                assertTrue(
                    "Round $round repo.currentConfig overwritten by stale P1: ${repo.currentConfig.latitude}",
                    repo.currentConfig.latitude < 32.0
                )
            } finally {
                coordinator.stopMock()
                scope.cancel()
                ioScope.cancel()
            }
        }
    }

    @Test
    fun `release during bindService window automatically unbinds upon late onServiceConnected without leaking connection`() = runTest {
        val prefs = InMemorySharedPreferences()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        var capturedConnection: ServiceConnection? = null
        var unbindCalls = 0
        val mockContext = mock<Context>()
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        doAnswer { inv ->
            capturedConnection = inv.getArgument(1)
            true
        }.whenever(mockContext).bindService(any(), any(), any<Int>())
        doAnswer {
            unbindCalls++
            Unit
        }.whenever(mockContext).unbindService(any())

        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            broadcastDispatcher = { _, _ -> },
            foregroundLauncher = { true },
            scope = backgroundScope
        )
        val mockService = mock<MockLocationForegroundService>()
        whenever(mockService.getCoordinator()).thenReturn(coordinator)
        val mockBinder = mock<MockLocationForegroundService.LocalBinder>()
        whenever(mockBinder.getService()).thenReturn(mockService)

        val controller = MockEngineController(
            context = mockContext,
            permissionCheck = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            serviceStarter = {},
            dispatcher = dispatcher,
            serviceStopper = {},
            leaseCheck = { true }
        )

        // 1. 发起绑定但尚未回调 onServiceConnected
        controller.startSingleMock(GeoPoint(39.9087, 116.3975), MockConfig(mode = MockMode.LSPOSED_ONLY))
        assertTrue(capturedConnection != null)
        assertEquals(0, unbindCalls)

        // 2. 在绑定窗口内调用 release()
        controller.release()
        assertEquals(0, unbindCalls)

        // 3. 系统迟到的 onServiceConnected 触发：必须自动调用 unbindService 且不启动模拟
        capturedConnection!!.onServiceConnected(
            ComponentName("com.mockloc", MockLocationForegroundService::class.java.name),
            mockBinder
        )
        runCurrent()

        assertEquals(1, unbindCalls)
        assertEquals(MockRunStatus.Idle, coordinator.runtimeState.value.status)
        assertFalse(repo.currentConfig.enabled)
    }

    @Test
    fun `reconnecting onServiceConnected cancels previous stateCollectJob and maintains single StateFlow collector`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var capturedConnection: ServiceConnection? = null
        val mockContext = mock<Context>()
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        doAnswer { inv ->
            capturedConnection = inv.getArgument(1)
            true
        }.whenever(mockContext).bindService(any(), any(), any<Int>())

        val stateFlow = MutableStateFlow(com.mockloc.domain.model.MockRuntimeState())
        val mockCoordinator = mock<MockEngineCoordinator>()
        whenever(mockCoordinator.runtimeState).thenReturn(stateFlow)

        val mockService = mock<MockLocationForegroundService>()
        whenever(mockService.getCoordinator()).thenReturn(mockCoordinator)
        val mockBinder = mock<MockLocationForegroundService.LocalBinder>()
        whenever(mockBinder.getService()).thenReturn(mockService)

        val controller = MockEngineController(
            context = mockContext,
            permissionCheck = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            serviceStarter = {},
            dispatcher = dispatcher,
            serviceStopper = {},
            leaseCheck = { true }
        )

        try {
            controller.startSingleMock(GeoPoint(39.9087, 116.3975), MockConfig(mode = MockMode.LSPOSED_ONLY))
            val conn = capturedConnection!!

            // 第 1 次连接
            conn.onServiceConnected(ComponentName("com.mockloc", "Service"), mockBinder)
            runCurrent()
            assertEquals(1, stateFlow.subscriptionCount.value)

            // 模拟未触发 disconnect 的重复连接，仍只保留唯一收集器
            conn.onServiceConnected(ComponentName("com.mockloc", "Service"), mockBinder)
            runCurrent()
            assertEquals(1, stateFlow.subscriptionCount.value)

            // 断连后订阅计数归零
            conn.onServiceDisconnected(ComponentName("com.mockloc", "Service"))
            runCurrent()
            assertEquals(0, stateFlow.subscriptionCount.value)

            // 第 2 次重连后仍只存在唯一收集器
            conn.onServiceConnected(ComponentName("com.mockloc", "Service"), mockBinder)
            runCurrent()
            assertEquals(1, stateFlow.subscriptionCount.value)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `restarting mock in same binding session after notification Stop re-invokes serviceStarter`() = runTest {
        val prefs = InMemorySharedPreferences()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = MockConfigRepository(prefs = prefs, ioScope = backgroundScope, debounceMs = 100L)

        var serviceStartedCount = 0
        var capturedConnection: ServiceConnection? = null
        val mockContext = mock<Context>()
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        doAnswer { inv ->
            capturedConnection = inv.getArgument(1)
            true
        }.whenever(mockContext).bindService(any(), any(), any<Int>())

        val coordinator = MockEngineCoordinator(
            configRepository = repo,
            permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            broadcastDispatcher = { _, _ -> },
            foregroundLauncher = { true },
            scope = backgroundScope
        )
        val mockService = mock<MockLocationForegroundService>()
        whenever(mockService.getCoordinator()).thenReturn(coordinator)
        val mockBinder = mock<MockLocationForegroundService.LocalBinder>()
        whenever(mockBinder.getService()).thenReturn(mockService)

        val controller = MockEngineController(
            context = mockContext,
            permissionCheck = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
            serviceStarter = { serviceStartedCount++ },
            dispatcher = dispatcher,
            serviceStopper = {},
            leaseCheck = { true }
        )

        try {
            controller.startSingleMock(GeoPoint(39.9087, 116.3975), MockConfig(mode = MockMode.LSPOSED_ONLY))
            assertEquals(1, serviceStartedCount)

            capturedConnection!!.onServiceConnected(
                ComponentName("com.mockloc", MockLocationForegroundService::class.java.name),
                mockBinder
            )
            runCurrent()
            assertEquals(MockRunStatus.SingleRunning, controller.runtimeState.value.status)

            // 模拟通知栏点击“停止”：直接在服务内调用 coordinator.stopMock()，连接保持未解绑
            coordinator.stopMock()
            runCurrent()
            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)

            // 同一绑定周期内再次 Start：必须再次调用 serviceStarter 恢复 started 前台保活身份
            controller.startSingleMock(GeoPoint(31.2304, 121.4737), MockConfig(mode = MockMode.LSPOSED_ONLY))
            runCurrent()
            assertEquals(2, serviceStartedCount)
            assertEquals(MockRunStatus.SingleRunning, controller.runtimeState.value.status)
        } finally {
            controller.stopMock()
            controller.release()
        }
    }

    @Test
    fun `concurrent startSingleMock stopMock and onServiceConnected in lock window converge cleanly`() = runBlocking {
        for (round in 0 until 20) {
            val prefs = InMemorySharedPreferences()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val repo = MockConfigRepository(prefs = prefs, ioScope = ioScope, debounceMs = 5L)

            var capturedConnection: ServiceConnection? = null
            val bindReady = CountDownLatch(1)
            val mockContext = mock<Context>()
            whenever(mockContext.applicationContext).thenReturn(mockContext)
            doAnswer { inv ->
                capturedConnection = inv.getArgument(1)
                bindReady.countDown()
                true
            }.whenever(mockContext).bindService(any(), any(), any<Int>())

            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope,
                tickIntervalMs = 5L
            )
            val mockService = mock<MockLocationForegroundService>()
            whenever(mockService.getCoordinator()).thenReturn(coordinator)
            val mockBinder = mock<MockLocationForegroundService.LocalBinder>()
            whenever(mockBinder.getService()).thenReturn(mockService)

            val controller = MockEngineController(
                context = mockContext,
                permissionCheck = { PermissionChecker.evaluateStartForegroundEligibility(true, true, true) },
                serviceStarter = {},
                dispatcher = Dispatchers.Default,
                serviceStopper = {},
                leaseCheck = { true }
            )

            try {
                val p1 = GeoPoint(39.9087, 116.3975)
                val p2 = GeoPoint(31.2304, 121.4737)
                controller.startSingleMock(p1, MockConfig(mode = MockMode.LSPOSED_ONLY))
                assertTrue(bindReady.await(2, TimeUnit.SECONDS))

                val connectThread = thread(name = "connect-$round") {
                    capturedConnection?.onServiceConnected(
                        ComponentName("com.mockloc", MockLocationForegroundService::class.java.name),
                        mockBinder
                    )
                }
                val actionThread = thread(name = "action-$round") {
                    controller.startSingleMock(p2, MockConfig(mode = MockMode.LSPOSED_ONLY))
                    controller.stopMock()
                }
                connectThread.join(3000)
                actionThread.join(3000)
                Thread.sleep(20L)

                // 最终收敛为 Idle（因为 actionThread 最后调用了 stopMock），或验证如果未 stopMock 则不异常
                assertEquals(MockRunStatus.Idle, coordinator.runtimeState.value.status)
                assertFalse(repo.currentConfig.enabled)
            } finally {
                controller.stopMock()
                controller.release()
                scope.cancel()
                ioScope.cancel()
            }
        }
    }
}
