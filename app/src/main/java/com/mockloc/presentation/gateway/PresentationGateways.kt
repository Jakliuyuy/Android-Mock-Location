package com.mockloc.presentation.gateway

import com.mockloc.data.config.MockConfig
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockRuntimeState
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.root.EnvironmentStatus
import com.mockloc.service.MockEngineController
import com.mockloc.service.PermissionCheckResult
import kotlinx.coroutines.flow.StateFlow

/**
 * 模拟定位引擎控制网关抽象，解耦 Android Service 绑定以支持纯 JVM 单元测试。
 */
interface MockEngineGateway {
    val runtimeState: StateFlow<MockRuntimeState>
    val mayEmitAsyncStopConfig: Boolean
        get() = false
    fun startSingleMock(point: GeoPoint, config: MockConfig)
    fun updateSingleTarget(point: GeoPoint)
    fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig)
    fun pauseCruise()
    fun resumeCruise()
    fun stopMock()
    fun updateSpeed(speedMps: Double)
    fun updateJitter(enabled: Boolean)
    fun release()
}

/**
 * 生产环境控制器适配器，将调用透传给 [MockEngineController]。
 */
class ControllerEngineGateway(
    private val controller: MockEngineController
) : MockEngineGateway {
    override val runtimeState: StateFlow<MockRuntimeState>
        get() = controller.runtimeState

    override val mayEmitAsyncStopConfig: Boolean
        get() = true

    override fun startSingleMock(point: GeoPoint, config: MockConfig) {
        controller.startSingleMock(point, config)
    }

    override fun updateSingleTarget(point: GeoPoint) {
        controller.updateSingleTarget(point)
    }

    override fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig) {
        controller.startCruise(route, cruiseConfig, baseConfig)
    }

    override fun pauseCruise() {
        controller.pauseCruise()
    }

    override fun resumeCruise() {
        controller.resumeCruise()
    }

    override fun stopMock() {
        controller.stopMock()
    }

    override fun updateSpeed(speedMps: Double) {
        controller.updateSpeed(speedMps)
    }

    override fun updateJitter(enabled: Boolean) {
        controller.updateJitter(enabled)
    }

    override fun release() {
        controller.release()
    }
}

/**
 * 运行环境探测网关（LSPosed / Root / 权限状态）。
 */
fun interface EnvironmentDetectorGateway {
    suspend fun detect(): EnvironmentStatus
}

/**
 * 运行时权限查询网关。
 */
fun interface PermissionStatusProvider {
    fun check(): PermissionCheckResult
}
