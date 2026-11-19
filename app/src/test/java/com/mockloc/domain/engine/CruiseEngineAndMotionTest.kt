package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CruiseEngineAndMotionTest {

    private fun sampleRoute(): PlannedRoute {
        val p0 = GeoPoint(39.9000, 116.4000)
        val p1 = SpatialMath.offsetByMeters(p0, northMeters = 100.0, eastMeters = 0.0)
        val p2 = SpatialMath.offsetByMeters(p1, northMeters = 0.0, eastMeters = 100.0)
        return PlannedRoute(
            id = "route-1",
            name = "测试三点路线",
            waypoints = listOf(
                Waypoint(p0, "起点"),
                Waypoint(p1, "拐点"),
                Waypoint(p2, "终点")
            )
        )
    }

    @Test
    fun `ONE_WAY reaches COMPLETED stops moving stops micro drift and resets cleanly on stop`() {
        val engine = CruiseEngine(MotionSimulator(Random(42)))
        val route = sampleRoute()
        val cfg = CruiseConfig(
            speedMps = 50.0,
            loopMode = LoopMode.ONE_WAY,
            enableSpeedNoise = false,
            enableMicroDrift = true,
            maxDriftRadiusMeters = 2.0
        )
        engine.loadRoute(route, cfg)
        engine.start()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)

        // 总长约 200m，50m/s 跑 5 秒应抵达终点并进入 COMPLETED
        var lastOutput: CruiseOutput? = null
        repeat(5) {
            lastOutput = engine.advanceStep(1.0)
        }
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
        assertTrue(engine.isFinished())
        assertTrue(lastOutput!!.isCompleted)
        assertEquals(1.0f, lastOutput!!.progressFraction, 1e-5f)
        val endPoint = route.waypoints.last().point
        assertEquals(endPoint.latitude, lastOutput!!.point.latitude, 1e-9)
        assertEquals(endPoint.longitude, lastOutput!!.point.longitude, 1e-9)

        // 到达 COMPLETED 后再次 advanceStep 不再位移也不触发任何微漂移
        val afterComplete1 = engine.advanceStep(1.0)
        val afterComplete2 = engine.advanceStep(1.0)
        assertEquals(endPoint.latitude, afterComplete1.point.latitude, 0.0)
        assertEquals(endPoint.longitude, afterComplete1.point.longitude, 0.0)
        assertEquals(afterComplete1.point, afterComplete2.point)
        assertEquals(0.0, afterComplete1.speedMps, 0.0)

        // stop() 干净复位游标与里程至起点，且可再次 start() 重跑
        engine.stop()
        assertEquals(CruiseEngine.State.STOPPED, engine.state)
        val resetSnapshot = engine.currentSnapshot()
        assertEquals(0, resetSnapshot.activeWaypointIndex)
        assertEquals(0.0, resetSnapshot.distanceTraveledMeters, 1e-9)
        assertEquals(0.0f, resetSnapshot.progressFraction, 1e-6f)
        assertEquals(route.waypoints.first().point, resetSnapshot.point)

        engine.start()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)
        val rerun = engine.advanceStep(1.0)
        assertFalse(rerun.isCompleted)
        assertTrue(rerun.distanceTraveledMeters > 0.0)
    }

    @Test
    fun `LOOP mode includes closing segment in progressFraction smoothly`() {
        val engine = CruiseEngine(MotionSimulator(Random(7)))
        val route = sampleRoute()
        val cfg = CruiseConfig(
            speedMps = 40.0,
            loopMode = LoopMode.LOOP,
            enableSpeedNoise = false,
            enableMicroDrift = false
        )
        engine.loadRoute(route, cfg)
        engine.start()

        val progresses = mutableListOf<Float>()
        // 路线周长 ~341m，40m/s 跑 8 秒（经过第 2 航点后的回程段）
        repeat(8) {
            val out = engine.advanceStep(1.0)
            assertFalse(out.isCompleted)
            progresses.add(out.progressFraction)
        }
        // 在经过第 5 秒（已过 200m 前两段，进入回程闭环段）时进度应严格递增且介于 (0.58, 1.0)
        assertTrue("Expected progress > 0.6 in closing segment, got ${progresses[5]}", progresses[5] > 0.6f)
        assertTrue("Expected monotonic progress into closing segment", progresses[6] > progresses[5])
        assertTrue("Expected progress < 1.0 before closing loop", progresses[7] < 1.0f)
    }

    @Test
    fun `PING_PONG reverses direction at endpoints and handles pause resume`() {
        val engine = CruiseEngine(MotionSimulator(Random(1)))
        val route = sampleRoute()
        val cfg = CruiseConfig(
            speedMps = 100.0,
            loopMode = LoopMode.PING_PONG,
            enableSpeedNoise = false,
            enableMicroDrift = false
        )
        engine.loadRoute(route, cfg)
        engine.start()

        val step1 = engine.advanceStep(1.0) // 到拐点
        assertEquals(1, step1.activeWaypointIndex)

        engine.pause()
        assertEquals(CruiseEngine.State.PAUSED, engine.state)
        val pausedOut = engine.advanceStep(2.0)
        assertEquals(step1.distanceTraveledMeters, pausedOut.distanceTraveledMeters, 1e-9)

        engine.resume()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)
        val step2 = engine.advanceStep(1.0) // 到终点后折返
        assertEquals(2, step2.activeWaypointIndex)

        val step3 = engine.advanceStep(1.0) // 返程回到拐点
        assertEquals(1, step3.activeWaypointIndex)
        assertFalse(step3.isCompleted)
    }

    @Test
    fun `updateSpeed and updateJitter apply dynamically without resetting progress`() {
        val engine = CruiseEngine(MotionSimulator(Random(99)))
        val route = sampleRoute()
        engine.loadRoute(
            route,
            CruiseConfig(
                speedMps = 10.0,
                loopMode = LoopMode.ONE_WAY,
                enableSpeedNoise = false,
                enableMicroDrift = true,
                maxDriftRadiusMeters = 2.0
            )
        )
        engine.start()

        val s1 = engine.advanceStep(2.0) // 行进 20m
        assertEquals(20.0, s1.distanceTraveledMeters, 0.5)

        engine.updateSpeed(30.0)
        engine.updateJitter(false)
        val s2 = engine.advanceStep(1.0) // 应新行进 30m -> 总计 ~50m
        assertEquals(50.0, s2.distanceTraveledMeters, 0.5)
        assertEquals(30.0, s2.speedMps, 1e-6)
    }

    @Test
    fun `empty route zero speed NaN delta and MotionSimulator extremes are safely guarded`() {
        val engine = CruiseEngine()
        engine.loadRoute(PlannedRoute(), CruiseConfig())
        var threw = false
        try {
            engine.start()
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Starting empty route must throw controlled IllegalStateException", threw)

        val route = sampleRoute()
        engine.loadRoute(route, CruiseConfig(speedMps = 0.0, enableSpeedNoise = false, enableMicroDrift = false))
        engine.start()

        val zeroSpeedOut = engine.advanceStep(3.0)
        assertEquals(0.0, zeroSpeedOut.distanceTraveledMeters, 0.0)
        assertEquals(route.waypoints.first().point, zeroSpeedOut.point)

        val nanDeltaOut = engine.advanceStep(Double.NaN)
        assertEquals(route.waypoints.first().point, nanDeltaOut.point)

        val infDeltaOut = engine.advanceStep(Double.POSITIVE_INFINITY)
        assertEquals(route.waypoints.first().point, infDeltaOut.point)

        // MotionSimulator 边界验证：严格 <= maxDriftRadiusMeters
        val sim = MotionSimulator(Random(123))
        assertEquals(0.0, sim.nextSpeed(Double.NaN, 0.1, true, 1.0), 0.0)
        val extremePole = GeoPoint(89.99999, 179.99999)
        repeat(20) {
            val drifted = sim.applyMicroDrift(extremePole, maxDriftRadiusMeters = 5.0, enableDrift = true, deltaSeconds = 1.0)
            assertTrue(GeoPoint.isValid(drifted.latitude, drifted.longitude))
            val dist = SpatialMath.calculateHaversineDistance(extremePole, drifted)
            assertTrue("Drift $dist m exceeded max radius 5.0m", dist <= 5.0)
        }

        // 89.999° 极区多半径（5m / 50m）与北京常规纬度千步压测均严格 <= maxDriftRadiusMeters
        val pole89999 = GeoPoint(89.999, 0.0)
        for (radius in listOf(5.0, 50.0)) {
            sim.reset()
            repeat(1000) { step ->
                val drifted = sim.applyMicroDrift(pole89999, maxDriftRadiusMeters = radius, enableDrift = true, deltaSeconds = 1.0)
                val dist = SpatialMath.calculateHaversineDistance(pole89999, drifted)
                assertTrue("Pole 89.999° step $step drift $dist m exceeded $radius m", dist <= radius)
            }
        }
        val beijing = GeoPoint(39.908722, 116.397499)
        sim.reset()
        repeat(1000) { step ->
            val drifted = sim.applyMicroDrift(beijing, maxDriftRadiusMeters = 5.0, enableDrift = true, deltaSeconds = 1.0)
            val dist = SpatialMath.calculateHaversineDistance(beijing, drifted)
            assertTrue("Beijing step $step drift $dist m exceeded 5.0m", dist <= 5.0)
        }

        assertEquals(extremePole, sim.applyMicroDrift(extremePole, 5.0, enableDrift = true, deltaSeconds = Double.NaN))
    }
}
