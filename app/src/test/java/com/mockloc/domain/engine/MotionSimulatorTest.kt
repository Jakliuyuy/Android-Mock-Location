package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * 运动仿真器 (MotionSimulator) 单元测试：
 * 验证 AR-1 速度抖动、OU 均值回归微漂移的有界性、确定性种子与边界情况。
 */
class MotionSimulatorTest {

    @Test
    fun testAR1SpeedJitterBoundsAcrossManySteps() {
        val simulator = MotionSimulator(seed = 42L)
        val baseSpeed = 10.0f
        val steps = 5000

        var sumSpeed = 0.0
        var minObserved = Float.MAX_VALUE
        var maxObserved = Float.MIN_VALUE

        for (i in 0 until steps) {
            val speed = simulator.nextSpeed(baseSpeed, enableJitter = true)
            // 严格约束在 [0.5 * baseSpeed, 1.5 * baseSpeed]
            assertTrue("Speed $speed must be >= 5.0", speed >= 5.0f)
            assertTrue("Speed $speed must be <= 15.0", speed <= 15.0f)

            sumSpeed += speed
            if (speed < minObserved) minObserved = speed
            if (speed > maxObserved) maxObserved = speed
        }

        val avgSpeed = sumSpeed / steps
        // 验证围绕 baseSpeed 波动，均值应接近 baseSpeed
        assertEquals(baseSpeed.toDouble(), avgSpeed, 0.5)
        // 验证确有波动存在，而非死值
        assertTrue("Min observed speed $minObserved should be < 9.5", minObserved < 9.5f)
        assertTrue("Max observed speed $maxObserved should be > 10.5", maxObserved > 10.5f)
    }

    @Test
    fun testSpeedJitterDisabledReturnsExactBaseSpeed() {
        val simulator = MotionSimulator(seed = 12345L)
        val baseSpeed = 4.5f

        for (i in 0 until 100) {
            val speed = simulator.nextSpeed(baseSpeed, enableJitter = false)
            assertEquals(baseSpeed, speed, 0.0001f)
        }
    }

    @Test
    fun testZeroBaseSpeedAlwaysReturnsZero() {
        val simulator = MotionSimulator(seed = 999L)
        for (i in 0 until 100) {
            val speed = simulator.nextSpeed(0.0f, enableJitter = true)
            assertEquals(0.0f, speed, 0.0f)
        }
    }

    @Test
    fun testDeterministicSeedProducesIdenticalSequences() {
        val simA = MotionSimulator(seed = 88888L)
        val simB = MotionSimulator(seed = 88888L)

        val point = GeoPoint(39.9042, 116.4074)

        for (i in 0 until 50) {
            val speedA = simA.nextSpeed(12.0f, true)
            val speedB = simB.nextSpeed(12.0f, true)
            assertEquals("Speed mismatch at step $i", speedA, speedB, 0.0f)

            val driftedA = simA.applyMicroDrift(point, 1.0, true, 4.0)
            val driftedB = simB.applyMicroDrift(point, 1.0, true, 4.0)
            assertEquals("Lat mismatch at step $i", driftedA.latitude, driftedB.latitude, 1e-12)
            assertEquals("Lon mismatch at step $i", driftedA.longitude, driftedB.longitude, 1e-12)
        }
    }

    @Test
    fun testOrnsteinUhlenbeckDriftStrictlyBounded10000Steps() {
        val maxDriftRadius = 4.0
        val simulator = MotionSimulator(seed = 20261019L)
        val origin = GeoPoint(39.9042, 116.4074)

        val steps = 10000
        var maxObservedRadius = 0.0
        var sumX = 0.0
        var sumY = 0.0

        for (step in 0 until steps) {
            val drifted = simulator.applyMicroDrift(
                point = origin,
                deltaSeconds = 0.5,
                enableMicroDrift = true,
                maxDriftRadiusMeters = maxDriftRadius
            )

            val (dx, dy) = simulator.getCurrentDriftOffset()
            val radius = sqrt(dx * dx + dy * dy)
            assertTrue(
                "Internal tangential drift $radius exceeded max $maxDriftRadius at step $step",
                radius <= maxDriftRadius + 1e-6
            )

            val haversineDistance = SpatialMath.calculateHaversineDistance(origin, drifted)
            assertTrue(
                "Haversine distance $haversineDistance exceeded max drift radius $maxDriftRadius at step $step",
                haversineDistance <= maxDriftRadius + 0.05
            )

            if (radius > maxObservedRadius) {
                maxObservedRadius = radius
            }
            sumX += dx
            sumY += dy
        }

        // 验证确实产生漂移（最大观察半径显著大于 0）
        assertTrue("Drift should produce significant displacement, max: $maxObservedRadius", maxObservedRadius > 1.5)

        // 验证 OU 均值回归特性：在 10,000 步后，位移均值收敛于 0
        val meanX = sumX / steps
        val meanY = sumY / steps
        assertEquals("Mean drift X should revert to 0", 0.0, meanX, 0.3)
        assertEquals("Mean drift Y should revert to 0", 0.0, meanY, 0.3)
    }

    @Test
    fun testMicroDriftDisabledLeavesCoordinatesUnchanged() {
        val simulator = MotionSimulator()
        val origin = GeoPoint(31.2304, 121.4737)

        val result = simulator.applyMicroDrift(
            point = origin,
            deltaSeconds = 1.0,
            enableMicroDrift = false,
            maxDriftRadiusMeters = 4.0
        )

        assertEquals(origin.latitude, result.latitude, 0.0)
        assertEquals(origin.longitude, result.longitude, 0.0)
    }

    @Test
    fun testZeroOrNegativeDeltaSecondsLeavesCoordinatesUnchanged() {
        val simulator = MotionSimulator()
        val origin = GeoPoint(31.2304, 121.4737)

        val r0 = simulator.applyMicroDrift(origin, 0.0, true, 4.0)
        assertEquals(origin.latitude, r0.latitude, 0.0)
        assertEquals(origin.longitude, r0.longitude, 0.0)

        val rNeg = simulator.applyMicroDrift(origin, -1.0, true, 4.0)
        assertEquals(origin.latitude, rNeg.latitude, 0.0)
        assertEquals(origin.longitude, rNeg.longitude, 0.0)
    }

    @Test
    fun testResetClearsInternalState() {
        val simulator = MotionSimulator(seed = 42L)
        val origin = GeoPoint(39.9042, 116.4074)

        simulator.nextSpeed(10.0f, true)
        simulator.applyMicroDrift(origin, 2.0, true, 4.0)

        val (dx1, dy1) = simulator.getCurrentDriftOffset()
        assertTrue("Offset should not be zero after step", dx1 != 0.0 || dy1 != 0.0)

        simulator.reset()
        val (dx2, dy2) = simulator.getCurrentDriftOffset()
        assertEquals(0.0, dx2, 0.0)
        assertEquals(0.0, dy2, 0.0)
    }
}
