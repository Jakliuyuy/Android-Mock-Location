package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import org.junit.Assert.*
import org.junit.Test

/**
 * 巡航仿真引擎 (CruiseEngine) 单元测试：
 * 覆盖全部循环模式（单向、往返、闭环）、航点停留时间、步进跳跃、暂停恢复与极端边界。
 */
class CruiseEngineTest {

    private fun createLinearRoute(loopMode: LoopMode = LoopMode.ONE_WAY): PlannedRoute {
        // 创建一条从西到东的简单路线，3个航点，各点相距约 111 米（0.001 经度差）
        val p1 = GeoPoint(39.9042, 116.4000, altitude = 10.0)
        val p2 = GeoPoint(39.9042, 116.4010, altitude = 15.0)
        val p3 = GeoPoint(39.9042, 116.4020, altitude = 20.0)

        val w1 = Waypoint(point = p1)
        val w2 = Waypoint(point = p2)
        val w3 = Waypoint(point = p3)

        return PlannedRoute(
            id = "test-route-1",
            name = "Test Route",
            waypoints = listOf(w1, w2, w3),
            loopMode = loopMode
        )
    }

    @Test
    fun testOneWayCruiseToCompletion() {
        val route = createLinearRoute(LoopMode.ONE_WAY)
        val totalDistance = route.totalDistanceMeters()
        assertTrue("Total distance should be around 170m", totalDistance > 100.0)

        val engine = CruiseEngine(MotionSimulator(seed = 1L))
        val config = CruiseConfig(
            baseSpeedMps = 10.0f,
            loopMode = LoopMode.ONE_WAY,
            enableJitter = false,
            enableMicroDrift = false
        )
        engine.setRoute(route, config)
        assertEquals(CruiseEngine.State.STOPPED, engine.state)

        engine.start()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)

        var lastDistance = 0.0
        var output = engine.advanceStep(1.0)

        while (!output.isCompleted) {
            assertTrue("Distance traveled should increase", output.distanceTraveledMeters >= lastDistance)
            assertTrue("Progress ratio should be in [0, 1]", output.progressRatio in 0.0f..1.0f)
            lastDistance = output.distanceTraveledMeters
            output = engine.advanceStep(1.0)
        }

        assertTrue("Should be completed", output.isCompleted)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
        assertEquals(1.0f, output.progressRatio, 0.001f)
        assertEquals(totalDistance, output.distanceTraveledMeters, 0.5)

        // 终点位置应当在最后一个航路点
        val endPoint = route.waypoints.last().point
        assertEquals(endPoint.latitude, output.point.latitude, 1e-5)
        assertEquals(endPoint.longitude, output.point.longitude, 1e-5)
        assertEquals(20.0, output.altitude, 0.1)
    }

    @Test
    fun testPingPongLoopModeReversesAndLoops() {
        val route = createLinearRoute(LoopMode.PING_PONG)
        val totalDistance = route.totalDistanceMeters()

        val engine = CruiseEngine(MotionSimulator(seed = 1L))
        val config = CruiseConfig(
            baseSpeedMps = 20.0f,
            loopMode = LoopMode.PING_PONG,
            enableJitter = false,
            enableMicroDrift = false
        )
        engine.setRoute(route, config)
        engine.start()

        // 推进超过 3 倍总长（去程 -> 回程 -> 再次去程）
        val stepTime = 1.0
        val totalSteps = ((totalDistance * 3.5) / 20.0).toInt()

        for (i in 0 until totalSteps) {
            val output = engine.advanceStep(stepTime)
            assertFalse("Ping pong mode should never complete", output.isCompleted)
            assertEquals(CruiseEngine.State.RUNNING, engine.state)
            assertTrue("Progress ratio in [0, 1]", output.progressRatio in 0.0f..1.0f)
        }

        assertTrue(
            "Traveled distance should exceed 3x one-way distance",
            engine.distanceTraveledMeters > totalDistance * 3.0
        )
    }

    @Test
    fun testLoopModeWrapsAround() {
        val route = createLinearRoute(LoopMode.LOOP)
        val totalDistance = route.totalDistanceMeters()

        val engine = CruiseEngine(MotionSimulator(seed = 2L))
        val config = CruiseConfig(
            baseSpeedMps = 25.0f,
            loopMode = LoopMode.LOOP,
            enableJitter = false,
            enableMicroDrift = false
        )
        engine.setRoute(route, config)
        engine.start()

        // 模拟推进多圈
        for (i in 0 until 50) {
            val output = engine.advanceStep(1.0)
            assertFalse("Loop mode should never complete", output.isCompleted)
            assertEquals(CruiseEngine.State.RUNNING, engine.state)
        }

        assertTrue(
            "Distance traveled should be large",
            engine.distanceTraveledMeters >= 50 * 25.0 * 0.9
        )
    }

    @Test
    fun testPauseAndResume() {
        val route = createLinearRoute(LoopMode.ONE_WAY)
        val engine = CruiseEngine(MotionSimulator(seed = 3L))
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 10.0f, loopMode = LoopMode.ONE_WAY, enableJitter = false, enableMicroDrift = false))
        engine.start()

        // 运行 2 秒
        engine.advanceStep(2.0)
        val distanceBeforePause = engine.distanceTraveledMeters
        assertTrue("Should have moved", distanceBeforePause > 0.0)

        // 暂停
        engine.pause()
        assertEquals(CruiseEngine.State.PAUSED, engine.state)

        // 尝试推进，应当不移动
        val pausedOutput = engine.advanceStep(5.0)
        assertEquals(distanceBeforePause, engine.distanceTraveledMeters, 0.0001)
        assertEquals(0.0f, pausedOutput.speedMps, 0.0f)

        // 恢复运行
        engine.resume()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)
        engine.advanceStep(2.0)
        assertTrue("Should continue moving after resume", engine.distanceTraveledMeters > distanceBeforePause)
    }

    @Test
    fun testDwellTimeAtWaypoint() {
        val p1 = GeoPoint(39.9042, 116.4000)
        val p2 = GeoPoint(39.9042, 116.4005)
        val p3 = GeoPoint(39.9042, 116.4010)

        val dist1 = SpatialMath.calculateHaversineDistance(p1, p2)

        val w1 = Waypoint(point = p1)
        val w2 = Waypoint(point = p2, dwellTimeSeconds = 4.0) // 停留 4 秒
        val w3 = Waypoint(point = p3)

        val route = PlannedRoute("dwell-test", "Dwell Test", listOf(w1, w2, w3))
        val engine = CruiseEngine(MotionSimulator())
        val speed = 10.0f
        engine.setRoute(route, CruiseConfig(baseSpeedMps = speed, loopMode = LoopMode.ONE_WAY, enableJitter = false, enableMicroDrift = false))
        engine.start()

        // 走到点 2 所需时间
        val timeToP2 = dist1 / speed
        engine.advanceStep(timeToP2)

        // 此时已到达点 2，进入停留状态，累计走过 dist1
        assertEquals(dist1, engine.distanceTraveledMeters, 0.01)

        // 推进 2 秒停留时间（仍剩余 2 秒停留）
        val dwellOutput = engine.advanceStep(2.0)
        assertEquals(dist1, engine.distanceTraveledMeters, 0.01)
        assertEquals(0.0f, dwellOutput.speedMps, 0.0f)

        // 再推进 3 秒（2 秒用尽停留，1 秒向点 3 移动）
        val movingOutput = engine.advanceStep(3.0)
        assertTrue(
            "Traveled distance should increase after dwell finishes",
            engine.distanceTraveledMeters > dist1
        )
        assertTrue("Speed should be non-zero", movingOutput.speedMps > 0.0f)
    }

    @Test
    fun testLargeStepAdvanceLeapsAcrossMultipleSegments() {
        val route = createLinearRoute(LoopMode.ONE_WAY)
        val totalDistance = route.totalDistanceMeters()

        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 10.0f, loopMode = LoopMode.ONE_WAY, enableJitter = false, enableMicroDrift = false))
        engine.start()

        // 传入一个极其巨大的时间（例如 1000 秒，直接越过所有段）
        val output = engine.advanceStep(1000.0)
        assertTrue("Must be completed", output.isCompleted)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
        assertEquals(totalDistance, output.distanceTraveledMeters, 0.01)
        assertEquals(1.0f, output.progressRatio, 0.0f)
    }

    @Test
    fun testSinglePointRoute() {
        val singlePoint = GeoPoint(39.9042, 116.4074, altitude = 50.0)
        val route = PlannedRoute(
            id = "single-pt",
            name = "Single Point",
            waypoints = listOf(Waypoint(point = singlePoint, dwellTimeSeconds = 2.0))
        )

        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 10.0f, loopMode = LoopMode.ONE_WAY, enableMicroDrift = false))
        engine.start()

        // 首次步进 1.0 秒（仍在停留中）
        val out1 = engine.advanceStep(1.0)
        assertEquals(singlePoint.latitude, out1.point.latitude, 1e-6)
        assertEquals(50.0, out1.altitude, 0.01)
        assertFalse(out1.isCompleted)

        // 步进 2.0 秒（耗尽停留，完成巡航）
        val out2 = engine.advanceStep(2.0)
        assertTrue(out2.isCompleted)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
    }

    @Test
    fun testIdenticalAdjacentPointsDoesNotCrash() {
        val p = GeoPoint(39.9042, 116.4074)
        val pNext = GeoPoint(39.9050, 116.4074)

        // 包含相邻两个重合点
        val route = PlannedRoute(
            id = "dup-pt",
            name = "Duplicate Points",
            waypoints = listOf(Waypoint(p), Waypoint(p), Waypoint(pNext))
        )

        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 10.0f, enableJitter = false, enableMicroDrift = false))
        engine.start()

        val output = engine.advanceStep(100.0)
        assertTrue(output.isCompleted)
    }

    @Test
    fun testWaypointSpecifiedSpeedOverride() {
        val p1 = GeoPoint(39.9042, 116.4000)
        val p2 = GeoPoint(39.9042, 116.4010)

        // 航路点指定了 25.0 m/s，而全局配置是 5.0 m/s
        val w1 = Waypoint(point = p1, speedMps = 25.0f)
        val w2 = Waypoint(point = p2)

        val route = PlannedRoute("speed-override", "Speed Override", listOf(w1, w2))
        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 5.0f, enableJitter = false, enableMicroDrift = false))
        engine.start()

        val output = engine.advanceStep(1.0)
        assertEquals(25.0f, output.speedMps, 0.01f)
        assertEquals(25.0, engine.distanceTraveledMeters, 0.1)
    }

    @Test
    fun testResetRestoresInitialState() {
        val route = createLinearRoute(LoopMode.ONE_WAY)
        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route)
        engine.start()
        engine.advanceStep(5.0)

        assertTrue("Distance traveled should be > 0", engine.distanceTraveledMeters > 0.0)

        engine.reset()
        assertEquals(CruiseEngine.State.STOPPED, engine.state)
        assertEquals(0.0, engine.distanceTraveledMeters, 0.0)

        val output = engine.advanceStep(0.0)
        val firstPoint = route.waypoints.first().point
        assertEquals(firstPoint.latitude, output.point.latitude, 1e-5)
        assertEquals(firstPoint.longitude, output.point.longitude, 1e-5)
    }

    @Test
    fun testZeroOrNegativeDeltaSeconds() {
        val route = createLinearRoute(LoopMode.ONE_WAY)
        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route)
        engine.start()

        val out0 = engine.advanceStep(0.0)
        assertEquals(0.0, engine.distanceTraveledMeters, 0.0)

        val outNeg = engine.advanceStep(-2.0)
        assertEquals(0.0, engine.distanceTraveledMeters, 0.0)
    }
}
