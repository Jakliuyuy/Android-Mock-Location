package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import kotlin.random.Random

class CruiseEngine(
    private val motionSimulator: MotionSimulator = MotionSimulator(Random.Default)
) {
    enum class State {
        STOPPED,
        RUNNING,
        PAUSED,
        COMPLETED
    }

    private var _state: State = State.STOPPED
    val state: State
        @Synchronized get() = _state

    private var route: PlannedRoute = PlannedRoute()
    private var config: CruiseConfig = CruiseConfig()

    private var currentWaypointIndex: Int = 0
    private var isForward: Boolean = true
    private var inClosingLoopSegment: Boolean = false
    private var segmentProgressMeters: Double = 0.0
    private var cycleDistanceMeters: Double = 0.0
    private var totalDistanceTraveledMeters: Double = 0.0
    private var remainingDwellSeconds: Double = 0.0
    private var currentBearingDeg: Float = 0f
    private var currentBasePoint: GeoPoint? = null
    private var lastEffectiveSpeedMps: Double = 0.0

    @Synchronized
    fun loadRoute(newRoute: PlannedRoute, newConfig: CruiseConfig) {
        route = newRoute
        config = newConfig
        resetInternal(keepStateStopped = true)
    }

    @Synchronized
    fun start() {
        check(route.waypoints.isNotEmpty()) {
            "Cannot start cruise on an empty route"
        }
        if (_state == State.COMPLETED) {
            resetInternal(keepStateStopped = true)
        }
        _state = State.RUNNING
        if (currentBasePoint == null) {
            currentBasePoint = route.waypoints.first().point
        }
    }

    @Synchronized
    fun pause() {
        if (_state == State.RUNNING) {
            _state = State.PAUSED
            lastEffectiveSpeedMps = 0.0
        }
    }

    @Synchronized
    fun resume() {
        if (_state == State.PAUSED) {
            _state = State.RUNNING
        }
    }

    @Synchronized
    fun stop() {
        resetInternal(keepStateStopped = true)
    }

    @Synchronized
    fun reset() {
        resetInternal(keepStateStopped = true)
    }

    @Synchronized
    fun updateSpeed(speedMps: Double) {
        if (!speedMps.isFinite() || speedMps < 0.0) return
        config = config.copy(speedMps = speedMps)
    }

    @Synchronized
    fun updateJitter(
        enableMicroDrift: Boolean,
        maxDriftRadiusMeters: Double = config.maxDriftRadiusMeters
    ) {
        val safeRadius = if (maxDriftRadiusMeters.isFinite() && maxDriftRadiusMeters >= 0.0) {
            maxDriftRadiusMeters
        } else {
            config.maxDriftRadiusMeters
        }
        config = config.copy(
            enableMicroDrift = enableMicroDrift,
            maxDriftRadiusMeters = safeRadius
        )
        if (!enableMicroDrift) {
            motionSimulator.reset()
        }
    }

    @Synchronized
    fun isFinished(): Boolean = _state == State.COMPLETED || _state == State.STOPPED

    @Synchronized
    fun currentSnapshot(): CruiseOutput {
        val waypoints = route.waypoints
        val point = currentBasePoint ?: waypoints.firstOrNull()?.point ?: GeoPoint(0.0, 0.0)
        return CruiseOutput(
            point = point,
            speedMps = if (_state == State.RUNNING) lastEffectiveSpeedMps else 0.0,
            bearingDeg = currentBearingDeg,
            progressFraction = computeProgressFraction(),
            activeWaypointIndex = currentWaypointIndex,
            totalWaypoints = waypoints.size,
            distanceTraveledMeters = totalDistanceTraveledMeters,
            isCompleted = _state == State.COMPLETED
        )
    }

    @Synchronized
    fun advanceStep(deltaSeconds: Double): CruiseOutput {
        val waypoints = route.waypoints
        if (waypoints.isEmpty()) {
            return CruiseOutput(
                point = GeoPoint(0.0, 0.0),
                speedMps = 0.0,
                bearingDeg = 0f,
                progressFraction = 0f,
                activeWaypointIndex = 0,
                totalWaypoints = 0,
                distanceTraveledMeters = 0.0,
                isCompleted = false
            )
        }

        if (_state != State.RUNNING || !deltaSeconds.isFinite() || deltaSeconds <= 0.0) {
            return currentSnapshot()
        }

        var remainingDt = deltaSeconds

        // 处理单航点路线
        if (waypoints.size == 1) {
            val onlyWaypoint = waypoints[0]
            currentBasePoint = onlyWaypoint.point
            currentWaypointIndex = 0
            lastEffectiveSpeedMps = 0.0
            if (config.loopMode == LoopMode.ONE_WAY) {
                if (remainingDwellSeconds > 0.0) {
                    remainingDwellSeconds = (remainingDwellSeconds - remainingDt).coerceAtLeast(0.0)
                }
                if (remainingDwellSeconds <= 0.0) {
                    _state = State.COMPLETED
                    return currentSnapshot()
                }
            }
            val drifted = motionSimulator.applyMicroDrift(
                onlyWaypoint.point,
                config.maxDriftRadiusMeters,
                config.enableMicroDrift,
                deltaSeconds
            )
            return CruiseOutput(
                point = drifted,
                speedMps = 0.0,
                bearingDeg = currentBearingDeg,
                progressFraction = if (config.loopMode == LoopMode.ONE_WAY) 0.5f else 0f,
                activeWaypointIndex = 0,
                totalWaypoints = 1,
                distanceTraveledMeters = totalDistanceTraveledMeters,
                isCompleted = false
            )
        }

        // 1. 先消耗航点停留时间
        if (remainingDwellSeconds > 0.0) {
            if (remainingDt < remainingDwellSeconds) {
                remainingDwellSeconds -= remainingDt
                lastEffectiveSpeedMps = 0.0
                val base = currentBasePoint ?: waypoints[currentWaypointIndex].point
                val drifted = motionSimulator.applyMicroDrift(
                    base,
                    config.maxDriftRadiusMeters,
                    config.enableMicroDrift,
                    deltaSeconds
                )
                return CruiseOutput(
                    point = drifted,
                    speedMps = 0.0,
                    bearingDeg = currentBearingDeg,
                    progressFraction = computeProgressFraction(),
                    activeWaypointIndex = currentWaypointIndex,
                    totalWaypoints = waypoints.size,
                    distanceTraveledMeters = totalDistanceTraveledMeters,
                    isCompleted = false
                )
            } else {
                remainingDt -= remainingDwellSeconds
                remainingDwellSeconds = 0.0
                // 若在 ONE_WAY 终点停留耗尽，立即完成
                if (config.loopMode == LoopMode.ONE_WAY && currentWaypointIndex == waypoints.lastIndex) {
                    _state = State.COMPLETED
                    lastEffectiveSpeedMps = 0.0
                    currentBasePoint = waypoints.last().point
                    return currentSnapshot()
                }
            }
        }

        // 2. 计算速度与步进位移
        val baseSpeed = waypoints[currentWaypointIndex].targetSpeedMps?.toDouble() ?: config.speedMps
        val effectiveSpeed = motionSimulator.nextSpeed(
            baseSpeedMps = baseSpeed,
            noiseRatio = config.speedNoiseRatio,
            enableNoise = config.enableSpeedNoise,
            deltaSeconds = remainingDt
        )
        lastEffectiveSpeedMps = effectiveSpeed

        var stepDistance = if (remainingDt > 0.0 && effectiveSpeed > 0.0) {
            effectiveSpeed * remainingDt
        } else {
            0.0
        }

        if (stepDistance <= 0.0) {
            val base = currentBasePoint ?: waypoints[currentWaypointIndex].point
            val drifted = motionSimulator.applyMicroDrift(
                base,
                config.maxDriftRadiusMeters,
                config.enableMicroDrift,
                deltaSeconds
            )
            return CruiseOutput(
                point = drifted,
                speedMps = effectiveSpeed,
                bearingDeg = currentBearingDeg,
                progressFraction = computeProgressFraction(),
                activeWaypointIndex = currentWaypointIndex,
                totalWaypoints = waypoints.size,
                distanceTraveledMeters = totalDistanceTraveledMeters,
                isCompleted = false
            )
        }

        // 3. 沿路线推进（支持单步跨越多段）
        var safetyCounter = 0
        while (stepDistance > 1e-6 && _state == State.RUNNING && safetyCounter < 10_000) {
            safetyCounter++
            val (fromPoint, toPoint) = currentSegmentEndpoints()
            val segLen = SpatialMath.calculateHaversineDistance(fromPoint, toPoint)

            if (segLen < 1e-4) {
                // 零长度路段直接跳过进入下一航点
                onArrivedAtTargetWaypoint()
                continue
            }

            currentBearingDeg = SpatialMath.calculateBearing(fromPoint, toPoint)
            val remainingInSegment = (segLen - segmentProgressMeters).coerceAtLeast(0.0)

            if (stepDistance < remainingInSegment - 1e-4) {
                segmentProgressMeters += stepDistance
                totalDistanceTraveledMeters += stepDistance
                updateCycleDistance(stepDistance)
                stepDistance = 0.0
                val fraction = (segmentProgressMeters / segLen).coerceIn(0.0, 1.0)
                currentBasePoint = SpatialMath.interpolateGreatCircle(fromPoint, toPoint, fraction)
            } else {
                stepDistance = (stepDistance - remainingInSegment).coerceAtLeast(0.0)
                totalDistanceTraveledMeters += remainingInSegment
                updateCycleDistance(remainingInSegment)
                segmentProgressMeters = 0.0
                currentBasePoint = toPoint
                onArrivedAtTargetWaypoint()
                if (_state == State.COMPLETED) {
                    lastEffectiveSpeedMps = 0.0
                    return currentSnapshot()
                }
                if (remainingDwellSeconds > 0.0) {
                    // 到达中间航点需要停留时，截断本步剩余位移并停留在航点
                    stepDistance = 0.0
                }
            }
        }

        if (_state == State.COMPLETED) {
            return currentSnapshot()
        }

        val base = currentBasePoint ?: waypoints[currentWaypointIndex].point
        val drifted = motionSimulator.applyMicroDrift(
            base,
            config.maxDriftRadiusMeters,
            config.enableMicroDrift,
            deltaSeconds
        )
        return CruiseOutput(
            point = drifted,
            speedMps = lastEffectiveSpeedMps,
            bearingDeg = currentBearingDeg,
            progressFraction = computeProgressFraction(),
            activeWaypointIndex = currentWaypointIndex,
            totalWaypoints = waypoints.size,
            distanceTraveledMeters = totalDistanceTraveledMeters,
            isCompleted = false
        )
    }

    private fun currentSegmentEndpoints(): Pair<GeoPoint, GeoPoint> {
        val waypoints = route.waypoints
        if (inClosingLoopSegment) {
            return waypoints.last().point to waypoints.first().point
        }
        return if (isForward) {
            val nextIdx = (currentWaypointIndex + 1).coerceAtMost(waypoints.lastIndex)
            waypoints[currentWaypointIndex].point to waypoints[nextIdx].point
        } else {
            val prevIdx = (currentWaypointIndex - 1).coerceAtLeast(0)
            waypoints[currentWaypointIndex].point to waypoints[prevIdx].point
        }
    }

    private fun onArrivedAtTargetWaypoint() {
        val waypoints = route.waypoints
        if (inClosingLoopSegment) {
            inClosingLoopSegment = false
            currentWaypointIndex = 0
            cycleDistanceMeters = 0.0
            remainingDwellSeconds = waypoints.first().dwellTimeSeconds
            return
        }

        if (isForward) {
            currentWaypointIndex = (currentWaypointIndex + 1).coerceAtMost(waypoints.lastIndex)
            val arrivedWaypoint = waypoints[currentWaypointIndex]
            if (currentWaypointIndex == waypoints.lastIndex) {
                when (config.loopMode) {
                    LoopMode.ONE_WAY -> {
                        if (arrivedWaypoint.dwellTimeSeconds <= 0.0) {
                            _state = State.COMPLETED
                            currentBasePoint = arrivedWaypoint.point
                            remainingDwellSeconds = 0.0
                        } else {
                            remainingDwellSeconds = arrivedWaypoint.dwellTimeSeconds
                        }
                    }
                    LoopMode.PING_PONG -> {
                        isForward = false
                        remainingDwellSeconds = arrivedWaypoint.dwellTimeSeconds
                    }
                    LoopMode.LOOP -> {
                        val closingDist = route.closingSegmentDistanceMeters()
                        if (closingDist >= 1.0) {
                            inClosingLoopSegment = true
                            remainingDwellSeconds = arrivedWaypoint.dwellTimeSeconds
                        } else {
                            currentWaypointIndex = 0
                            cycleDistanceMeters = 0.0
                            remainingDwellSeconds = waypoints.first().dwellTimeSeconds
                        }
                    }
                }
            } else {
                remainingDwellSeconds = arrivedWaypoint.dwellTimeSeconds
            }
        } else {
            // PING_PONG 返程
            currentWaypointIndex = (currentWaypointIndex - 1).coerceAtLeast(0)
            val arrivedWaypoint = waypoints[currentWaypointIndex]
            if (currentWaypointIndex == 0) {
                isForward = true
                cycleDistanceMeters = 0.0
            }
            remainingDwellSeconds = arrivedWaypoint.dwellTimeSeconds
        }
    }

    private fun updateCycleDistance(deltaDist: Double) {
        when (config.loopMode) {
            LoopMode.ONE_WAY, LoopMode.LOOP -> cycleDistanceMeters += deltaDist
            LoopMode.PING_PONG -> {
                if (isForward) {
                    cycleDistanceMeters += deltaDist
                } else {
                    cycleDistanceMeters = (cycleDistanceMeters - deltaDist).coerceAtLeast(0.0)
                }
            }
        }
    }

    private fun computeProgressFraction(): Float {
        if (route.waypoints.size < 2) {
            return if (_state == State.COMPLETED) 1.0f else 0.0f
        }
        if (_state == State.COMPLETED) return 1.0f

        val baseDist = route.totalDistanceMeters()
        val totalCycleDist = when (config.loopMode) {
            LoopMode.ONE_WAY, LoopMode.PING_PONG -> baseDist
            LoopMode.LOOP -> {
                val closing = route.closingSegmentDistanceMeters()
                if (closing >= 1.0) baseDist + closing else baseDist
            }
        }
        if (totalCycleDist <= 0.0) return 0.0f
        return (cycleDistanceMeters / totalCycleDist).toFloat().coerceIn(0.0f, 1.0f)
    }

    private fun resetInternal(keepStateStopped: Boolean) {
        if (keepStateStopped) {
            _state = State.STOPPED
        }
        currentWaypointIndex = 0
        isForward = true
        inClosingLoopSegment = false
        segmentProgressMeters = 0.0
        cycleDistanceMeters = 0.0
        totalDistanceTraveledMeters = 0.0
        remainingDwellSeconds = route.waypoints.firstOrNull()?.dwellTimeSeconds ?: 0.0
        currentBasePoint = route.waypoints.firstOrNull()?.point
        lastEffectiveSpeedMps = 0.0
        currentBearingDeg = if (route.waypoints.size >= 2) {
            SpatialMath.calculateBearing(route.waypoints[0].point, route.waypoints[1].point)
        } else {
            0f
        }
        motionSimulator.reset()
    }
}
