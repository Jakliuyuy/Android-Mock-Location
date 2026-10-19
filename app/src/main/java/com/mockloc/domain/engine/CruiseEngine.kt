package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import kotlin.math.max

/**
 * 路线巡航仿真引擎：支持单向、往返、闭环多模式步进巡航，融合 AR-1 速度抖动与 OU 均值回归微漂移。
 *
 * @param motionSimulator 运动仿真器实例，用于速度噪声和位置漂移计算
 */
class CruiseEngine(
    val motionSimulator: MotionSimulator = MotionSimulator()
) {
    /**
     * 巡航引擎运行状态。
     */
    enum class State {
        /** 已停止，未在巡航 */
        STOPPED,

        /** 正在巡航中 */
        RUNNING,

        /** 已暂停 */
        PAUSED,

        /** 单向巡航已抵达终点 */
        COMPLETED
    }

    private var _state: State = State.STOPPED
    val state: State
        @Synchronized get() = _state

    private var _route: PlannedRoute? = null
    val route: PlannedRoute?
        @Synchronized get() = _route

    private var _config: CruiseConfig = CruiseConfig(
        baseSpeedMps = SpeedPreset.WALK.speedMps,
        loopMode = LoopMode.ONE_WAY
    )
    val config: CruiseConfig
        @Synchronized get() = _config

    // 内部巡航状态变量
    private var currentSegmentIndex: Int = 0
    private var segmentProgressDistanceMeters: Double = 0.0
    private var _distanceTraveledMeters: Double = 0.0
    val distanceTraveledMeters: Double
        @Synchronized get() = _distanceTraveledMeters

    private var remainingDwellTimeSeconds: Double = 0.0
    private var isForward: Boolean = true // PING_PONG 模式下的正反向指示

    // 当前未加漂移的基础坐标点、高度、朝向与瞬时速度
    private var currentBasePoint: GeoPoint? = null
    private var currentAltitude: Double = 0.0
    private var currentBearingDeg: Float = 0f
    private var currentSpeedMps: Float = 0f

    /**
     * 设置规划路线与可选巡航配置。
     */
    @Synchronized
    fun setRoute(route: PlannedRoute, config: CruiseConfig? = null) {
        this._route = route
        if (config != null) {
            this._config = config
        } else {
            this._config = this._config.copy(loopMode = route.loopMode)
        }
        reset()
    }

    /**
     * 动态更新巡航配置参数。
     */
    @Synchronized
    fun setConfig(config: CruiseConfig) {
        this._config = config
    }

    /**
     * 启动巡航引擎。
     */
    @Synchronized
    fun start() {
        val r = _route
        check(r != null && r.waypoints.isNotEmpty()) {
            "Cannot start cruise engine without a valid PlannedRoute"
        }
        if (_state == State.COMPLETED) {
            reset()
        }
        _state = State.RUNNING
    }

    /**
     * 暂停巡航引擎。
     */
    @Synchronized
    fun pause() {
        if (_state == State.RUNNING) {
            _state = State.PAUSED
            currentSpeedMps = 0f
        }
    }

    /**
     * 继续已暂停的巡航。
     */
    @Synchronized
    fun resume() {
        if (_state == State.PAUSED) {
            _state = State.RUNNING
        }
    }

    /**
     * 停止巡航。
     */
    @Synchronized
    fun stop() {
        _state = State.STOPPED
        currentSpeedMps = 0f
    }

    /**
     * 重置巡航引擎状态至路线起点。
     */
    @Synchronized
    fun reset() {
        _state = State.STOPPED
        currentSegmentIndex = 0
        segmentProgressDistanceMeters = 0.0
        _distanceTraveledMeters = 0.0
        isForward = true
        motionSimulator.reset()

        val r = _route
        if (r != null && r.waypoints.isNotEmpty()) {
            val firstWp = r.waypoints.first()
            currentBasePoint = firstWp.point
            currentAltitude = firstWp.effectiveAltitude
            remainingDwellTimeSeconds = firstWp.dwellTimeSeconds
            currentBearingDeg = if (r.waypoints.size >= 2) {
                SpatialMath.calculateBearing(firstWp.point, r.waypoints[1].point)
            } else {
                0f
            }
        } else {
            currentBasePoint = null
            currentAltitude = 0.0
            remainingDwellTimeSeconds = 0.0
            currentBearingDeg = 0f
        }
        currentSpeedMps = 0f
    }

    /**
     * 步进离散仿真计算。
     *
     * @param deltaSeconds 步进时间间隔（秒）
     * @return 巡航输出数据 CruiseOutput
     */
    @Synchronized
    fun advanceStep(deltaSeconds: Double): CruiseOutput {
        val r = _route
        check(r != null && r.waypoints.isNotEmpty()) {
            "CruiseEngine has no route configured"
        }

        val waypoints = r.waypoints
        val totalDistance = r.totalDistanceMeters()

        // 单点路线边界处理
        if (waypoints.size == 1) {
            val wp = waypoints[0]
            currentBasePoint = wp.point
            currentAltitude = wp.effectiveAltitude
            currentBearingDeg = 0f
            currentSpeedMps = 0f

            if (_state == State.RUNNING) {
                if (remainingDwellTimeSeconds > 0.0) {
                    remainingDwellTimeSeconds = max(0.0, remainingDwellTimeSeconds - deltaSeconds)
                }
                if (remainingDwellTimeSeconds <= 0.0 && _config.loopMode == LoopMode.ONE_WAY) {
                    _state = State.COMPLETED
                }
            }

            val finalPoint = motionSimulator.applyMicroDrift(
                point = wp.point,
                deltaSeconds = deltaSeconds,
                enableMicroDrift = _config.enableMicroDrift,
                maxDriftRadiusMeters = _config.maxDriftRadiusMeters
            )

            return CruiseOutput(
                point = finalPoint,
                altitude = wp.effectiveAltitude,
                speedMps = 0f,
                bearingDeg = 0f,
                progressRatio = 1.0f,
                isCompleted = _state == State.COMPLETED,
                currentSegmentIndex = 0,
                distanceTraveledMeters = _distanceTraveledMeters
            )
        }

        // 非 RUNNING 状态下不推进移动，返回当前位置快照
        if (_state != State.RUNNING || deltaSeconds <= 0.0) {
            val basePt = currentBasePoint ?: waypoints.first().point
            val finalPt = motionSimulator.applyMicroDrift(
                point = basePt,
                deltaSeconds = deltaSeconds,
                enableMicroDrift = _config.enableMicroDrift,
                maxDriftRadiusMeters = _config.maxDriftRadiusMeters
            )
            return CruiseOutput(
                point = finalPt,
                altitude = currentAltitude,
                speedMps = currentSpeedMps,
                bearingDeg = currentBearingDeg,
                progressRatio = computeProgressRatio(totalDistance),
                isCompleted = _state == State.COMPLETED,
                currentSegmentIndex = currentSegmentIndex,
                distanceTraveledMeters = _distanceTraveledMeters
            )
        }

        var dtRemaining = deltaSeconds
        var iterations = 0
        val maxIterations = 1000

        while (dtRemaining > 1e-9 && _state == State.RUNNING && iterations < maxIterations) {
            iterations++

            // 1. 处理航路点驻留停留时间
            if (remainingDwellTimeSeconds > 0.0) {
                if (dtRemaining <= remainingDwellTimeSeconds) {
                    remainingDwellTimeSeconds -= dtRemaining
                    dtRemaining = 0.0
                    currentSpeedMps = 0f
                    break
                } else {
                    dtRemaining -= remainingDwellTimeSeconds
                    remainingDwellTimeSeconds = 0.0
                }
            }

            // 2. 获取当前线段起止航点
            val (fromWp, toWp) = getSegmentEndpoints(waypoints)
            val segmentLength = SpatialMath.calculateHaversineDistance(fromWp.point, toWp.point)

            if (segmentLength < 1e-6) {
                // 两航点重合，直接过渡到线段末尾
                onSegmentCompleted(waypoints, toWp)
                continue
            }

            // 3. 计算步进速度与距离
            val targetBaseSpeed = fromWp.speedMps ?: _config.baseSpeedMps
            val speed = motionSimulator.nextSpeed(targetBaseSpeed, _config.enableJitter)
            currentSpeedMps = speed

            if (speed <= 1e-6f) {
                // 速度为 0，无法前进
                dtRemaining = 0.0
                break
            }

            val remainingSegmentDistance = segmentLength - segmentProgressDistanceMeters
            val stepDistance = speed * dtRemaining

            if (stepDistance < remainingSegmentDistance) {
                // 未超出当前线段：正常插值推进
                segmentProgressDistanceMeters += stepDistance
                _distanceTraveledMeters += stepDistance
                dtRemaining = 0.0

                val fraction = (segmentProgressDistanceMeters / segmentLength).coerceIn(0.0, 1.0)
                currentBasePoint = SpatialMath.interpolateGreatCircle(fromWp.point, toWp.point, fraction)
                currentAltitude = fromWp.effectiveAltitude + (toWp.effectiveAltitude - fromWp.effectiveAltitude) * fraction
                currentBearingDeg = SpatialMath.calculateBearing(fromWp.point, toWp.point)
                break
            } else {
                // 抵达并超出当前线段终点
                _distanceTraveledMeters += remainingSegmentDistance
                val timeUsed = remainingSegmentDistance / speed
                dtRemaining = max(0.0, dtRemaining - timeUsed)
                segmentProgressDistanceMeters = 0.0

                currentBasePoint = toWp.point
                currentAltitude = toWp.effectiveAltitude
                currentBearingDeg = SpatialMath.calculateBearing(fromWp.point, toWp.point)

                // 触发到达该航点的驻留配置
                if (toWp.dwellTimeSeconds > 0.0) {
                    remainingDwellTimeSeconds = toWp.dwellTimeSeconds
                }

                // 推进线段指针与循环模式状态迁移
                onSegmentCompleted(waypoints, toWp)
            }
        }

        val basePt = currentBasePoint ?: waypoints.first().point
        val driftedPoint = motionSimulator.applyMicroDrift(
            point = basePt,
            deltaSeconds = deltaSeconds,
            enableMicroDrift = _config.enableMicroDrift,
            maxDriftRadiusMeters = _config.maxDriftRadiusMeters
        )

        return CruiseOutput(
            point = driftedPoint,
            altitude = currentAltitude,
            speedMps = if (_state == State.RUNNING && remainingDwellTimeSeconds <= 0.0) currentSpeedMps else 0f,
            bearingDeg = currentBearingDeg,
            progressRatio = computeProgressRatio(totalDistance),
            isCompleted = _state == State.COMPLETED,
            currentSegmentIndex = currentSegmentIndex,
            distanceTraveledMeters = _distanceTraveledMeters
        )
    }

    private fun getSegmentEndpoints(waypoints: List<Waypoint>): Pair<Waypoint, Waypoint> {
        val n = waypoints.size
        return when (_config.loopMode) {
            LoopMode.ONE_WAY -> {
                val idx = currentSegmentIndex.coerceIn(0, n - 2)
                Pair(waypoints[idx], waypoints[idx + 1])
            }
            LoopMode.PING_PONG -> {
                if (isForward) {
                    val idx = currentSegmentIndex.coerceIn(0, n - 2)
                    Pair(waypoints[idx], waypoints[idx + 1])
                } else {
                    val idx = currentSegmentIndex.coerceIn(1, n - 1)
                    Pair(waypoints[idx], waypoints[idx - 1])
                }
            }
            LoopMode.LOOP -> {
                val isClosed = SpatialMath.calculateHaversineDistance(
                    waypoints.first().point,
                    waypoints.last().point
                ) < 1.0

                val maxIdx = if (isClosed) n - 2 else n - 1
                val idx = currentSegmentIndex.coerceIn(0, maxIdx)
                val nextIdx = (idx + 1) % n
                Pair(waypoints[idx], waypoints[nextIdx])
            }
        }
    }

    private fun onSegmentCompleted(waypoints: List<Waypoint>, reachedWaypoint: Waypoint) {
        val n = waypoints.size
        when (_config.loopMode) {
            LoopMode.ONE_WAY -> {
                if (currentSegmentIndex >= n - 2) {
                    _state = State.COMPLETED
                    currentSpeedMps = 0f
                } else {
                    currentSegmentIndex++
                }
            }
            LoopMode.PING_PONG -> {
                if (isForward) {
                    if (currentSegmentIndex >= n - 2) {
                        // 到达最末端航点，折返反向
                        isForward = false
                        currentSegmentIndex = n - 1
                    } else {
                        currentSegmentIndex++
                    }
                } else {
                    if (currentSegmentIndex <= 1) {
                        // 到达最前端航点，折返正向
                        isForward = true
                        currentSegmentIndex = 0
                    } else {
                        currentSegmentIndex--
                    }
                }
            }
            LoopMode.LOOP -> {
                val isClosed = SpatialMath.calculateHaversineDistance(
                    waypoints.first().point,
                    waypoints.last().point
                ) < 1.0
                val maxIdx = if (isClosed) n - 2 else n - 1

                if (currentSegmentIndex >= maxIdx) {
                    currentSegmentIndex = 0
                } else {
                    currentSegmentIndex++
                }
            }
        }
    }

    private fun computeProgressRatio(totalDistance: Double): Float {
        if (totalDistance <= 1e-6) return 1.0f
        if (_state == State.COMPLETED) return 1.0f

        return when (_config.loopMode) {
            LoopMode.ONE_WAY -> {
                (_distanceTraveledMeters / totalDistance).toFloat().coerceIn(0f, 1f)
            }
            LoopMode.PING_PONG -> {
                val cycleLength = totalDistance * 2.0
                val progressInCycle = _distanceTraveledMeters % cycleLength
                val ratio = if (progressInCycle <= totalDistance) {
                    progressInCycle / totalDistance
                } else {
                    (cycleLength - progressInCycle) / totalDistance
                }
                ratio.toFloat().coerceIn(0f, 1f)
            }
            LoopMode.LOOP -> {
                val ratio = (_distanceTraveledMeters % totalDistance) / totalDistance
                ratio.toFloat().coerceIn(0f, 1f)
            }
        }
    }
}
