package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Milestone 4 对抗与实证压力测试套件：
 * 针对 MotionSimulator 和 CruiseEngine 进行多纬度、极值步长、长期迭代和模糊测试。
 */
class AdversarialMotionAndCruiseTest {

    // =========================================================================
    // 1. MotionSimulator: 50,000 步长期漂移与多纬度实证压力测试
    // =========================================================================

    private fun verifyOrnsteinUhlenbeckDrift50kSteps(
        origin: GeoPoint,
        maxDriftRadiusMeters: Double = 4.0,
        steps: Int = 50000,
        seed: Long = 20261019L
    ) {
        val simulator = MotionSimulator(seed = seed)
        var maxObservedTangentialRadius = 0.0
        var maxObservedHaversineDistance = 0.0
        var sumX = 0.0
        var sumY = 0.0

        for (i in 0 until steps) {
            val drifted = simulator.applyMicroDrift(
                point = origin,
                deltaSeconds = 0.5,
                enableMicroDrift = true,
                maxDriftRadiusMeters = maxDriftRadiusMeters
            )

            // 1. 验证经纬度非 NaN 且在合法数值范围内
            assertFalse("Latitude cannot be NaN at step $i", drifted.latitude.isNaN())
            assertFalse("Longitude cannot be NaN at step $i", drifted.longitude.isNaN())
            assertTrue("Latitude must be in [-90, 90]", drifted.latitude in -90.0..90.0)
            assertTrue("Longitude must be in [-180, 180]", drifted.longitude in -180.0..180.0)

            // 2. 验证切平面位移严格 <= maxDriftRadiusMeters (容差 1e-9)
            val (dx, dy) = simulator.getCurrentDriftOffset()
            val rTangential = sqrt(dx * dx + dy * dy)
            assertTrue(
                "Tangential drift $rTangential exceeded $maxDriftRadiusMeters at step $i",
                rTangential <= maxDriftRadiusMeters + 1e-9
            )

            // 3. 验证真实球面 Haversine 距离不超过允许半径（加微小曲率容差 0.05 米）
            val haversineDist = SpatialMath.calculateHaversineDistance(origin, drifted)
            assertTrue(
                "Haversine distance $haversineDist exceeded max $maxDriftRadiusMeters at step $i for lat=${origin.latitude}",
                haversineDist <= maxDriftRadiusMeters + 0.05
            )

            if (rTangential > maxObservedTangentialRadius) {
                maxObservedTangentialRadius = rTangential
            }
            if (haversineDist > maxObservedHaversineDistance) {
                maxObservedHaversineDistance = haversineDist
            }
            sumX += dx
            sumY += dy
        }

        // 验证确实发生了微漂移且探索了大部分圆盘（最大观察半径 > 2.0m）
        assertTrue("Max observed drift $maxObservedTangentialRadius should be > 2.0m", maxObservedTangentialRadius > 2.0)

        // 验证 Ornstein-Uhlenbeck 均值回归特性：50,000 步后位移累积均值收敛于 0
        val meanX = sumX / steps
        val meanY = sumY / steps
        assertEquals("Mean drift X should revert to 0 across 50k steps", 0.0, meanX, 0.25)
        assertEquals("Mean drift Y should revert to 0 across 50k steps", 0.0, meanY, 0.25)
    }

    @Test
    fun testMotionSimulator50000StepsEquator() {
        // 赤道点 (0.0, 0.0)
        verifyOrnsteinUhlenbeckDrift50kSteps(GeoPoint(0.0, 0.0))
    }

    @Test
    fun testMotionSimulator50000StepsMidLatitude45N() {
        // 中纬度点 (45.0, 116.0)
        verifyOrnsteinUhlenbeckDrift50kSteps(GeoPoint(45.0, 116.0))
    }

    @Test
    fun testMotionSimulator50000StepsHighLatitude85N() {
        // 高纬度北极圈 (85.0, 10.0)
        verifyOrnsteinUhlenbeckDrift50kSteps(GeoPoint(85.0, 10.0))
    }

    @Test
    fun testMotionSimulator50000StepsNearNorthPole89_99N() {
        // 极近北极点 (89.999, 120.0)
        verifyOrnsteinUhlenbeckDrift50kSteps(GeoPoint(89.999, 120.0))
    }

    @Test
    fun testMotionSimulator50000StepsNearSouthPole89_99S() {
        // 极近南极点 (-89.999, -45.0)
        verifyOrnsteinUhlenbeckDrift50kSteps(GeoPoint(-89.999, -45.0))
    }

    @Test
    fun testMotionSimulatorExactPolesStability() {
        // 正北极 (90.0) 与正南极 (-90.0) 边界数值稳定性测试
        val simulator = MotionSimulator(seed = 9999L)
        val northPole = GeoPoint(90.0, 0.0)
        val southPole = GeoPoint(-90.0, 180.0)

        for (i in 0 until 5000) {
            val driftedN = simulator.applyMicroDrift(northPole, 1.0, true, 4.0)
            assertFalse("North pole lat is NaN", driftedN.latitude.isNaN())
            assertFalse("North pole lon is NaN", driftedN.longitude.isNaN())
            assertTrue("North pole lat clamped", driftedN.latitude in 89.999..90.0)

            val driftedS = simulator.applyMicroDrift(southPole, 1.0, true, 4.0)
            assertFalse("South pole lat is NaN", driftedS.latitude.isNaN())
            assertFalse("South pole lon is NaN", driftedS.longitude.isNaN())
            assertTrue("South pole lat clamped", driftedS.latitude in -90.0..-89.999)
        }
    }

    @Test
    fun testMotionSimulatorSpeedJitterStrictBounds50000Steps() {
        val simulator = MotionSimulator(seed = 12345L)
        val baseSpeeds = listOf(1.2f, 4.5f, 12.0f, 33.3f, 0.05f)

        for (baseSpeed in baseSpeeds) {
            val minBound = 0.5f * baseSpeed
            val maxBound = 1.5f * baseSpeed
            var sumSpeed = 0.0

            for (i in 0 until 10000) {
                val speed = simulator.nextSpeed(baseSpeed, enableJitter = true)
                assertTrue(
                    "Speed $speed violated lower bound $minBound at step $i for base $baseSpeed",
                    speed >= minBound
                )
                assertTrue(
                    "Speed $speed violated upper bound $maxBound at step $i for base $baseSpeed",
                    speed <= maxBound
                )
                sumSpeed += speed
            }

            val avgSpeed = sumSpeed / 10000.0
            assertEquals(
                "Speed mean should stay close to base speed $baseSpeed",
                baseSpeed.toDouble(),
                avgSpeed,
                baseSpeed * 0.1
            )
        }
    }

    @Test
    fun testMotionSimulatorExtremeDeltaSecondsStability() {
        val simulator = MotionSimulator()
        val origin = GeoPoint(39.9042, 116.4074)

        // 1. 0.0 步长
        val p0 = simulator.applyMicroDrift(origin, 0.0, true, 4.0)
        assertEquals(origin.latitude, p0.latitude, 0.0)
        assertEquals(origin.longitude, p0.longitude, 0.0)

        // 2. 负步长
        val pNeg = simulator.applyMicroDrift(origin, -100.0, true, 4.0)
        assertEquals(origin.latitude, pNeg.latitude, 0.0)
        assertEquals(origin.longitude, pNeg.longitude, 0.0)

        // 3. 超微小步长 1e-12
        val pTiny = simulator.applyMicroDrift(origin, 1e-12, true, 4.0)
        assertFalse(pTiny.latitude.isNaN())
        assertFalse(pTiny.longitude.isNaN())

        // 4. 超大步长 100,000 秒
        val pHuge = simulator.applyMicroDrift(origin, 100000.0, true, 4.0)
        assertFalse(pHuge.latitude.isNaN())
        assertFalse(pHuge.longitude.isNaN())
        val distHuge = SpatialMath.calculateHaversineDistance(origin, pHuge)
        assertTrue("Distance under huge dt must remain <= 4.05", distHuge <= 4.05)

        // 5. 极端 Double.MAX_VALUE 步长
        val pMax = simulator.applyMicroDrift(origin, 1e15, true, 4.0)
        assertFalse(pMax.latitude.isNaN())
        assertFalse(pMax.longitude.isNaN())
    }

    // =========================================================================
    // 2. CruiseEngine: 极端输入、大步进跳跃与长期无限循环压力测试
    // =========================================================================

    private fun generateMultiSegmentRoute(
        segmentCount: Int = 50,
        segmentLengthMeters: Double = 100.0,
        loopMode: LoopMode = LoopMode.ONE_WAY
    ): PlannedRoute {
        // 沿经度方向生成 segmentCount + 1 个航点
        val waypoints = mutableListOf<Waypoint>()
        val startLat = 39.9042
        val startLon = 116.4000

        // 经度 1度 在 39.9N 约等于 85400 米
        val lonDeltaPerSegment = segmentLengthMeters / (SpatialMath.EARTH_RADIUS * kotlin.math.cos(Math.toRadians(startLat)) * (Math.PI / 180.0))

        for (i in 0..segmentCount) {
            val point = GeoPoint(
                latitude = startLat,
                longitude = startLon + i * lonDeltaPerSegment,
                altitude = 10.0 + i * 2.0
            )
            waypoints.add(Waypoint(point = point, dwellTimeSeconds = 0.0))
        }

        return PlannedRoute(
            id = "stress-multi-$segmentCount",
            name = "Stress Multi Segment Route",
            waypoints = waypoints,
            loopMode = loopMode
        )
    }

    @Test
    fun testCruiseEngineHugeLeapAcross50SegmentsOneWay() {
        val segmentCount = 50
        val segmentLength = 100.0
        val route = generateMultiSegmentRoute(segmentCount, segmentLength, LoopMode.ONE_WAY)
        val totalDistance = route.totalDistanceMeters()
        assertTrue("Total distance around 5000m", totalDistance in 4900.0..5100.0)

        val engine = CruiseEngine(MotionSimulator(seed = 42L))
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 10.0f, loopMode = LoopMode.ONE_WAY, enableJitter = false, enableMicroDrift = false))
        engine.start()

        // 传入超大步进时间（例如 50000.0 秒），直接一次性跨越全部 50 个航点
        val output = engine.advanceStep(50000.0)

        assertTrue("Engine must be COMPLETED", output.isCompleted)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
        assertEquals(1.0f, output.progressRatio, 1e-5f)
        assertEquals(totalDistance, output.distanceTraveledMeters, 0.5)

        // 最终坐标必须位于最后一个航点
        val finalExpectedPoint = route.waypoints.last().point
        assertEquals(finalExpectedPoint.latitude, output.point.latitude, 1e-5)
        assertEquals(finalExpectedPoint.longitude, output.point.longitude, 1e-5)
    }

    @Test
    fun testCruiseEngineLongTermInfiniteLoopMode50000Steps() {
        // 创建一个三角形闭合路线（3段），在 LOOP 模式下运行 50,000 步
        val p1 = GeoPoint(39.9042, 116.4000)
        val p2 = GeoPoint(39.9080, 116.4050)
        val p3 = GeoPoint(39.9000, 116.4050)
        val route = PlannedRoute(
            id = "loop-50k",
            name = "Loop 50k",
            waypoints = listOf(Waypoint(p1), Waypoint(p2), Waypoint(p3)),
            loopMode = LoopMode.LOOP
        )
        val totalDistance = route.totalDistanceMeters()
        assertTrue(totalDistance > 500.0)

        val engine = CruiseEngine(MotionSimulator(seed = 1001L))
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 15.0f, loopMode = LoopMode.LOOP, enableJitter = true, enableMicroDrift = true))
        engine.start()

        var lastDistanceTraveled = 0.0

        for (step in 0 until 50000) {
            val output = engine.advanceStep(0.2) // 每步 0.2 秒
            assertFalse("LOOP mode should never complete", output.isCompleted)
            assertEquals(CruiseEngine.State.RUNNING, engine.state)

            // 验证距离严格单调递增
            assertTrue("Distance must increase monotonically at step $step", output.distanceTraveledMeters >= lastDistanceTraveled)
            lastDistanceTraveled = output.distanceTraveledMeters

            // 验证 progressRatio 在 [0, 1] 严格有界
            assertTrue("Progress ratio must be within [0, 1] at step $step, was: ${output.progressRatio}", output.progressRatio in 0.0f..1.0f)

            // 验证输出坐标有效
            assertFalse(output.point.latitude.isNaN())
            assertFalse(output.point.longitude.isNaN())
            assertTrue(output.point.latitude in 39.89..39.92)
            assertTrue(output.point.longitude in 116.39..116.42)
        }

        // 50,000 步 x 0.2s = 10,000 秒，以 15m/s 计算约行驶 150,000 米
        assertTrue("Distance traveled should exceed 100km", engine.distanceTraveledMeters > 100000.0)
    }

    @Test
    fun testCruiseEngineLongTermInfinitePingPongMode50000Steps() {
        val p1 = GeoPoint(31.2304, 121.4737)
        val p2 = GeoPoint(31.2350, 121.4800)
        val p3 = GeoPoint(31.2400, 121.4850)
        val route = PlannedRoute(
            id = "pingpong-50k",
            name = "PingPong 50k",
            waypoints = listOf(Waypoint(p1), Waypoint(p2), Waypoint(p3)),
            loopMode = LoopMode.PING_PONG
        )

        val engine = CruiseEngine(MotionSimulator(seed = 2002L))
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 12.0f, loopMode = LoopMode.PING_PONG, enableJitter = true, enableMicroDrift = true))
        engine.start()

        var lastDistanceTraveled = 0.0

        for (step in 0 until 50000) {
            val output = engine.advanceStep(0.2)
            assertFalse("PING_PONG mode should never complete", output.isCompleted)
            assertEquals(CruiseEngine.State.RUNNING, engine.state)

            assertTrue("Distance must monotonically increase", output.distanceTraveledMeters >= lastDistanceTraveled)
            lastDistanceTraveled = output.distanceTraveledMeters

            assertTrue("Progress ratio in [0, 1]", output.progressRatio in 0.0f..1.0f)
            assertFalse(output.point.latitude.isNaN())
            assertFalse(output.point.longitude.isNaN())
        }

        assertTrue("Distance traveled should exceed 100km", engine.distanceTraveledMeters > 100000.0)
    }

    @Test
    fun testCruiseEngineAllIdenticalWaypointsDefensiveLoop() {
        // 5 个完全相同的点
        val p = GeoPoint(39.9042, 116.4074)
        val route = PlannedRoute(
            id = "all-identical",
            name = "All Identical Points",
            waypoints = listOf(Waypoint(p), Waypoint(p), Waypoint(p), Waypoint(p), Waypoint(p)),
            loopMode = LoopMode.LOOP
        )

        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 10.0f, loopMode = LoopMode.LOOP, enableMicroDrift = false))
        engine.start()

        // 步进应当由 maxIterations = 1000 保护，不会陷入死循环
        val output = engine.advanceStep(1.0)
        assertFalse(output.point.latitude.isNaN())
        assertEquals(p.latitude, output.point.latitude, 1e-6)
    }

    @Test
    fun testCruiseEngineWaypointDwellTimeConsistencyIntermediateAndStart() {
        val p1 = GeoPoint(39.9000, 116.4000)
        val p2 = GeoPoint(39.9010, 116.4000) // ~111m
        val p3 = GeoPoint(39.9020, 116.4000) // ~111m

        val dist1 = SpatialMath.calculateHaversineDistance(p1, p2)
        val dist2 = SpatialMath.calculateHaversineDistance(p2, p3)

        val w1 = Waypoint(point = p1, dwellTimeSeconds = 3.0)
        val w2 = Waypoint(point = p2, dwellTimeSeconds = 5.0)
        val w3 = Waypoint(point = p3, dwellTimeSeconds = 0.0)

        val route = PlannedRoute("dwell-multi", "Dwell Multi", listOf(w1, w2, w3), LoopMode.ONE_WAY)
        val engine = CruiseEngine(MotionSimulator())
        val speed = 10.0f // 10 m/s
        engine.setRoute(route, CruiseConfig(baseSpeedMps = speed, loopMode = LoopMode.ONE_WAY, enableJitter = false, enableMicroDrift = false))
        engine.start()

        // 1. 起点驻留 3.0 秒验证：前 3 秒内累计行驶距离为 0，速度为 0
        val outDwellStart1 = engine.advanceStep(1.5)
        assertEquals(0.0, engine.distanceTraveledMeters, 1e-6)
        assertEquals(0.0f, outDwellStart1.speedMps, 0.0f)

        val outDwellStart2 = engine.advanceStep(1.5)
        assertEquals(0.0, engine.distanceTraveledMeters, 1e-6)

        // 2. 走向 p2
        val travelTimeP2 = dist1 / speed
        engine.advanceStep(travelTimeP2)
        assertEquals(dist1, engine.distanceTraveledMeters, 0.01)

        // 3. 在 p2 驻留 5 秒验证：在 p2 驻留期间，距离不再增加，速度为 0
        val outDwellP2_1 = engine.advanceStep(3.0)
        assertEquals(dist1, engine.distanceTraveledMeters, 0.01)
        assertEquals(0.0f, outDwellP2_1.speedMps, 0.0f)

        val outDwellP2_2 = engine.advanceStep(2.0)
        assertEquals(dist1, engine.distanceTraveledMeters, 0.01)

        // 4. 走向 p3
        val travelTimeP3 = dist2 / speed
        val finalOutput = engine.advanceStep(travelTimeP3)
        assertEquals(dist1 + dist2, engine.distanceTraveledMeters, 0.01)
        assertTrue("Engine must be COMPLETED upon reaching final waypoint", finalOutput.isCompleted)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
    }

    @Test
    fun testCruiseEnginePingPongTerminalWaypointDwellTime() {
        val p1 = GeoPoint(39.9000, 116.4000)
        val p2 = GeoPoint(39.9010, 116.4000) // ~111m

        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        val w1 = Waypoint(point = p1, dwellTimeSeconds = 0.0)
        val w2 = Waypoint(point = p2, dwellTimeSeconds = 4.0) // 终点设置停留 4 秒

        val route = PlannedRoute("dwell-pingpong", "Dwell PingPong", listOf(w1, w2), LoopMode.PING_PONG)
        val engine = CruiseEngine(MotionSimulator())
        val speed = 10.0f
        engine.setRoute(route, CruiseConfig(baseSpeedMps = speed, loopMode = LoopMode.PING_PONG, enableJitter = false, enableMicroDrift = false))
        engine.start()

        // 走向 p2 终点
        val travelTime = dist / speed
        engine.advanceStep(travelTime)
        assertEquals(dist, engine.distanceTraveledMeters, 0.01)

        // 验证在最末端航点 p2 触发 4 秒驻留：在驻留时间内不开始返程折返移动
        val dwellOut1 = engine.advanceStep(2.5)
        assertEquals(dist, engine.distanceTraveledMeters, 0.01)
        assertEquals(0.0f, dwellOut1.speedMps, 0.0f)

        // 再步进 3.0 秒（消耗剩余 1.5 秒驻留，剩余 1.5 秒进行折返移动）
        val moveOut = engine.advanceStep(3.0)
        assertTrue("Traveled distance should increase after terminal dwell finishes", engine.distanceTraveledMeters > dist)
        assertTrue("Speed should be non-zero during movement", moveOut.speedMps > 0.0f)
    }

    @Test
    fun testCruiseEngineOneWayTerminalWaypointImmediateCompletionBehavior() {
        // 实测验证：在多航点 ONE_WAY 模式下，抵达最终航点即刻标记 COMPLETED
        val p1 = GeoPoint(39.9000, 116.4000)
        val p2 = GeoPoint(39.9010, 116.4000)
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)

        val w1 = Waypoint(point = p1)
        val w2 = Waypoint(point = p2, dwellTimeSeconds = 2.0)
        val route = PlannedRoute("terminal-oneway", "Terminal OneWay", listOf(w1, w2), LoopMode.ONE_WAY)

        val engine = CruiseEngine(MotionSimulator())
        val speed = 10.0f
        engine.setRoute(route, CruiseConfig(baseSpeedMps = speed, loopMode = LoopMode.ONE_WAY, enableJitter = false, enableMicroDrift = false))
        engine.start()

        // 移动至终点航点
        val out = engine.advanceStep(dist / speed)
        assertTrue("ONE_WAY cruise marks completed upon reaching destination", out.isCompleted)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
    }

    @Test
    fun testCruiseEngineFuzzingPauseResumeAndChaoticDt() {
        val route = generateMultiSegmentRoute(10, 50.0, LoopMode.PING_PONG)
        val engine = CruiseEngine(MotionSimulator(seed = 5555L))
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 8.0f, loopMode = LoopMode.PING_PONG))
        engine.start()

        val random = Random(7777L)

        // 随机执行 10,000 次状态切换与步进操作
        for (i in 0 until 10000) {
            when (random.nextInt(10)) {
                0 -> engine.pause()
                1 -> engine.resume()
                2 -> engine.stop()
                3 -> {
                    engine.start()
                }
                else -> {
                    val dt = when (random.nextInt(5)) {
                        0 -> 0.0
                        1 -> -1.0
                        2 -> random.nextDouble() * 0.1
                        3 -> random.nextDouble() * 5.0
                        else -> 50.0 // 偶尔来一次大步进
                    }
                    val output = engine.advanceStep(dt)
                    assertFalse("Lat cannot be NaN", output.point.latitude.isNaN())
                    assertFalse("Lon cannot be NaN", output.point.longitude.isNaN())
                    assertTrue("Progress must stay in [0, 1]", output.progressRatio in 0.0f..1.0f)
                }
            }
        }
    }

    @Test
    fun testTranscontinentalRouteCrossingDateLine() {
        // 跨越 180 度国际日期变更线的航线
        val p1 = GeoPoint(10.0, 179.0)
        val p2 = GeoPoint(10.0, -179.0) // 跨越日期变更线，两点相距仅约 220 公里
        val route = PlannedRoute(
            id = "dateline-route",
            name = "Date Line Route",
            waypoints = listOf(Waypoint(p1), Waypoint(p2)),
            loopMode = LoopMode.ONE_WAY
        )

        val totalDist = route.totalDistanceMeters()
        // 验证两点距离大约在 200km ~ 250km，而不是绕地球大半圈的 39,000km
        assertTrue("Haversine distance across dateline should be around 220km, was: $totalDist", totalDist in 200000.0..250000.0)

        val engine = CruiseEngine(MotionSimulator())
        engine.setRoute(route, CruiseConfig(baseSpeedMps = 100.0f, loopMode = LoopMode.ONE_WAY, enableJitter = false, enableMicroDrift = false))
        engine.start()

        val steps = 100
        val dt = (totalDist / 100.0f) / steps
        for (i in 0 until steps) {
            val output = engine.advanceStep(dt)
            assertFalse(output.point.latitude.isNaN())
            assertFalse(output.point.longitude.isNaN())
            assertTrue("Longitude must be valid", output.point.longitude in -180.0..180.0)
        }
    }
}
