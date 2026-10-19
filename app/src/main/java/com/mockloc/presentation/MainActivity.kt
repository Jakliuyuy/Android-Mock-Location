package com.mockloc.presentation

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mockloc.R
import com.mockloc.data.MockConfigManager
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.CruiseEngine
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.geocoding.GeocodingResult
import com.mockloc.domain.geocoding.GeocodingService
import com.mockloc.domain.geocoding.NominatimGeocodingService
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.presentation.map.OsmMapController
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.RootProviderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.MapView

/**
 * 模拟定位主控中心 Activity。
 * 提供地图浏览打点、地点搜索、单点位置欺骗、轨迹巡航步进仿真、Root/Xposed环境监测与白名单管理。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var mapController: OsmMapController

    // 环境状态展示
    private lateinit var tvStatusXposed: TextView
    private lateinit var tvStatusRoot: TextView
    private lateinit var tvStatusRecommended: TextView

    // 地点搜索
    private lateinit var etSearchQuery: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var pbSearch: ProgressBar
    private val geocodingService: GeocodingService by lazy { NominatimGeocodingService() }

    // 模式切换与控制面板
    private lateinit var toggleModeGroup: MaterialButtonToggleGroup
    private lateinit var etLatitude: TextInputEditText
    private lateinit var etLongitude: TextInputEditText
    private lateinit var etAltitude: TextInputEditText

    // 单点与巡航控制
    private lateinit var btnToggleMock: Button
    private lateinit var layoutCruiseParams: LinearLayout
    private lateinit var layoutCruiseActions: LinearLayout
    private lateinit var chipGroupSpeed: ChipGroup
    private lateinit var tilCustomSpeed: TextInputLayout
    private lateinit var etCustomSpeed: TextInputEditText
    private lateinit var chipGroupLoopMode: ChipGroup

    private lateinit var btnStartCruise: Button
    private lateinit var btnPauseResumeCruise: Button
    private lateinit var btnStopCruise: Button

    // 引擎与状态
    private val cruiseEngine = CruiseEngine()
    private var cruiseJob: Job? = null
    private val rootProviderManager: RootProviderManager by lazy { RootProviderManager(this) }

    private var isCruiseMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupMap()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        mapController.onResume()
        refreshEnvironmentStatus()
        syncStateWithConfig()
    }

    override fun onPause() {
        super.onPause()
        mapController.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCruiseSimulation()
    }

    private fun initViews() {
        tvStatusXposed = findViewById(R.id.tvStatusXposed)
        tvStatusRoot = findViewById(R.id.tvStatusRoot)
        tvStatusRecommended = findViewById(R.id.tvStatusRecommended)

        mapView = findViewById(R.id.mapView)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnSearch)
        pbSearch = findViewById(R.id.pbSearch)

        toggleModeGroup = findViewById(R.id.toggleModeGroup)
        etLatitude = findViewById(R.id.etLatitude)
        etLongitude = findViewById(R.id.etLongitude)
        etAltitude = findViewById(R.id.etAltitude)

        btnToggleMock = findViewById(R.id.btnToggleMock)
        layoutCruiseParams = findViewById(R.id.layoutCruiseParams)
        layoutCruiseActions = findViewById(R.id.layoutCruiseActions)

        chipGroupSpeed = findViewById(R.id.chipGroupSpeed)
        tilCustomSpeed = findViewById(R.id.tilCustomSpeed)
        etCustomSpeed = findViewById(R.id.etCustomSpeed)
        chipGroupLoopMode = findViewById(R.id.chipGroupLoopMode)

        btnStartCruise = findViewById(R.id.btnStartCruise)
        btnPauseResumeCruise = findViewById(R.id.btnPauseResumeCruise)
        btnStopCruise = findViewById(R.id.btnStopCruise)

        // 默认勾选
        toggleModeGroup.check(R.id.btnModeSingle)
        chipGroupSpeed.check(R.id.chipSpeedWalk)
        chipGroupLoopMode.check(R.id.chipLoopOneWay)

        // 白名单入口跳转
        findViewById<View>(R.id.btnOpenWhitelist).setOnClickListener {
            val intent = Intent(this, WhitelistActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupMap() {
        mapController = OsmMapController(this, mapView)
        val initialPoint = GeoPoint(MockConfigManager.latitude, MockConfigManager.longitude, MockConfigManager.altitude)
        mapController.setMockPin(initialPoint, "预设目标", animate = false)

        mapController.onLocationPickedListener = { pickedPoint ->
            etLatitude.setText(String.format("%.6f", pickedPoint.latitude))
            etLongitude.setText(String.format("%.6f", pickedPoint.longitude))
            mapController.setMockPin(pickedPoint, "已选取坐标", animate = false)

            if (MockConfigManager.isMockEnabled && !isCruiseMode) {
                applySinglePointMock(pickedPoint)
            }
        }
    }

    private fun setupListeners() {
        // 模式切换
        toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnModeSingle -> {
                        isCruiseMode = false
                        btnToggleMock.visibility = View.VISIBLE
                        layoutCruiseParams.visibility = View.GONE
                        layoutCruiseActions.visibility = View.GONE
                    }
                    R.id.btnModeCruise -> {
                        isCruiseMode = true
                        btnToggleMock.visibility = View.GONE
                        layoutCruiseParams.visibility = View.VISIBLE
                        layoutCruiseActions.visibility = View.VISIBLE
                    }
                }
            }
        }

        // 搜索触发
        btnSearch.setOnClickListener { performSearch() }
        etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        // 速度预设选择
        chipGroupSpeed.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.chipSpeedCustom) {
                tilCustomSpeed.visibility = View.VISIBLE
            } else {
                tilCustomSpeed.visibility = View.GONE
            }
        }

        // 单点模拟开关
        btnToggleMock.setOnClickListener {
            toggleSinglePointMock()
        }

        // 巡航控制
        btnStartCruise.setOnClickListener { startCruiseSimulation() }
        btnPauseResumeCruise.setOnClickListener { togglePauseResumeCruise() }
        btnStopCruise.setOnClickListener { stopCruiseSimulation() }
    }

    private fun refreshEnvironmentStatus() {
        val status = EnvironmentDetector.checkEnvironment()

        if (status.isXposedActive) {
            tvStatusXposed.text = getString(R.string.status_active)
            tvStatusXposed.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            tvStatusXposed.text = getString(R.string.status_inactive)
            tvStatusXposed.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }

        if (status.isRootAvailable) {
            tvStatusRoot.text = getString(R.string.status_available)
            tvStatusRoot.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            tvStatusRoot.text = getString(R.string.status_unavailable)
            tvStatusRoot.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
        }

        val modeDesc = when (status.recommendedMode) {
            "XPOSED_HOOK" -> getString(R.string.mode_xposed_hook)
            "ROOT_PROVIDER" -> getString(R.string.mode_root_provider)
            else -> getString(R.string.mode_unprivileged)
        }
        tvStatusRecommended.text = "${getString(R.string.status_recommended_mode)} $modeDesc"
    }

    private fun syncStateWithConfig() {
        etLatitude.setText(String.format("%.6f", MockConfigManager.latitude))
        etLongitude.setText(String.format("%.6f", MockConfigManager.longitude))
        etAltitude.setText(String.format("%.1f", MockConfigManager.altitude))

        updateMockButtonState(MockConfigManager.isMockEnabled)
    }

    private fun updateMockButtonState(isEnabled: Boolean) {
        if (isEnabled) {
            btnToggleMock.text = getString(R.string.btn_stop_mock)
            btnToggleMock.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
        } else {
            btnToggleMock.text = getString(R.string.btn_start_mock)
            btnToggleMock.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun performSearch() {
        val query = etSearchQuery.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show()
            return
        }

        pbSearch.visibility = View.VISIBLE
        btnSearch.visibility = View.INVISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val results = geocodingService.search(query, limit = 10)
            withContext(Dispatchers.Main) {
                pbSearch.visibility = View.GONE
                btnSearch.visibility = View.VISIBLE

                if (results.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.search_no_results, Toast.LENGTH_SHORT).show()
                } else if (results.size == 1) {
                    selectLocation(results.first())
                } else {
                    showLocationSelectDialog(results)
                }
            }
        }
    }

    private fun showLocationSelectDialog(results: List<GeocodingResult>) {
        val items = results.map { "${it.name}\n${it.displayName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.search_select_title)
            .setItems(items) { _, which ->
                selectLocation(results[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectLocation(result: GeocodingResult) {
        val pt = result.point
        etLatitude.setText(String.format("%.6f", pt.latitude))
        etLongitude.setText(String.format("%.6f", pt.longitude))
        etAltitude.setText(String.format("%.1f", pt.altitude))

        mapController.setMockPin(pt, result.name, animate = true)
        Toast.makeText(this, "已选取: ${result.name}", Toast.LENGTH_SHORT).show()

        if (MockConfigManager.isMockEnabled && !isCruiseMode) {
            applySinglePointMock(pt)
        }
    }

    private fun parseInputCoordinates(): GeoPoint? {
        val lat = etLatitude.text?.toString()?.toDoubleOrNull() ?: return null
        val lng = etLongitude.text?.toString()?.toDoubleOrNull() ?: return null
        val alt = etAltitude.text?.toString()?.toDoubleOrNull() ?: 50.0

        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return null
        }
        return GeoPoint(latitude = lat, longitude = lng, altitude = alt)
    }

    private fun toggleSinglePointMock() {
        if (MockConfigManager.isMockEnabled) {
            // 停止模拟
            MockConfigManager.isMockEnabled = false
            try {
                if (rootProviderManager.isRunning) {
                    rootProviderManager.stopMock()
                }
            } catch (_: Throwable) {}

            updateMockButtonState(false)
            Toast.makeText(this, R.string.mock_stopped, Toast.LENGTH_SHORT).show()
        } else {
            // 开启模拟
            val point = parseInputCoordinates()
            if (point == null) {
                Toast.makeText(this, R.string.invalid_coordinates, Toast.LENGTH_SHORT).show()
                return
            }
            applySinglePointMock(point)
            updateMockButtonState(true)
            Toast.makeText(this, R.string.mock_started, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySinglePointMock(point: GeoPoint) {
        MockConfigManager.latitude = point.latitude
        MockConfigManager.longitude = point.longitude
        MockConfigManager.altitude = point.altitude
        MockConfigManager.isMockEnabled = true

        // 如果具备 Root 权限，同步调用 RootProviderManager
        try {
            if (EnvironmentDetector.isRootAvailable()) {
                if (!rootProviderManager.isRunning) {
                    rootProviderManager.startMock(
                        lat = point.latitude,
                        lng = point.longitude,
                        alt = point.altitude
                    )
                } else {
                    rootProviderManager.updateLocation(
                        lat = point.latitude,
                        lng = point.longitude,
                        alt = point.altitude
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    private fun startCruiseSimulation() {
        val startPoint = parseInputCoordinates()
        if (startPoint == null) {
            Toast.makeText(this, R.string.invalid_coordinates, Toast.LENGTH_SHORT).show()
            return
        }

        // 解析速度
        val speedMps: Float = when (chipGroupSpeed.checkedChipId) {
            R.id.chipSpeedWalk -> SpeedPreset.WALK.speedMps
            R.id.chipSpeedBike -> SpeedPreset.BIKE.speedMps
            R.id.chipSpeedDrive -> SpeedPreset.DRIVE.speedMps
            R.id.chipSpeedCustom -> etCustomSpeed.text?.toString()?.toFloatOrNull() ?: 5.0f
            else -> SpeedPreset.WALK.speedMps
        }

        // 解析循环模式
        val loopMode = when (chipGroupLoopMode.checkedChipId) {
            R.id.chipLoopPingPong -> LoopMode.PING_PONG
            R.id.chipLoopLoop -> LoopMode.LOOP
            else -> LoopMode.ONE_WAY
        }

        // 构建路线：由起点向外延伸的多点测试轨迹 (例如 4 个航点构成的轨迹矩形或折线)
        val delta = 0.005 // 约 500 米
        val wp1 = Waypoint(startPoint, dwellTimeSeconds = 0.0)
        val wp2 = Waypoint(GeoPoint(startPoint.latitude + delta, startPoint.longitude, startPoint.altitude))
        val wp3 = Waypoint(GeoPoint(startPoint.latitude + delta, startPoint.longitude + delta, startPoint.altitude))
        val wp4 = Waypoint(GeoPoint(startPoint.latitude, startPoint.longitude + delta, startPoint.altitude))

        val routePoints = listOf(wp1.point, wp2.point, wp3.point, wp4.point)
        val plannedRoute = PlannedRoute(
            id = "cruise_route_${System.currentTimeMillis()}",
            name = "模拟巡航轨迹",
            waypoints = listOf(wp1, wp2, wp3, wp4),
            loopMode = loopMode
        )

        // 绘制路线
        mapController.drawRoute(routePoints)

        // 配置引擎
        val config = CruiseConfig(
            baseSpeedMps = speedMps,
            loopMode = loopMode,
            enableJitter = true,
            enableMicroDrift = true
        )
        cruiseEngine.setRoute(plannedRoute, config)
        cruiseEngine.start()

        MockConfigManager.isMockEnabled = true

        btnStartCruise.isEnabled = false
        btnPauseResumeCruise.isEnabled = true
        btnPauseResumeCruise.text = getString(R.string.btn_pause_cruise)
        btnStopCruise.isEnabled = true

        Toast.makeText(this, R.string.cruise_started, Toast.LENGTH_SHORT).show()

        // 启动定时模拟步进 Job
        cruiseJob?.cancel()
        cruiseJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && cruiseEngine.state != CruiseEngine.State.STOPPED) {
                if (cruiseEngine.state == CruiseEngine.State.RUNNING) {
                    val output = cruiseEngine.advanceStep(1.0)

                    // 同步到 MockConfigManager
                    MockConfigManager.latitude = output.point.latitude
                    MockConfigManager.longitude = output.point.longitude
                    MockConfigManager.altitude = output.altitude
                    MockConfigManager.speed = output.speedMps
                    MockConfigManager.bearing = output.bearingDeg

                    withContext(Dispatchers.Main) {
                        mapController.updateCruiseMarker(output.point, output.bearingDeg, follow = false)
                    }

                    if (output.isCompleted) {
                        withContext(Dispatchers.Main) {
                            stopCruiseSimulation()
                            Toast.makeText(this@MainActivity, "巡航已到达终点", Toast.LENGTH_SHORT).show()
                        }
                        break
                    }
                }
                delay(1000)
            }
        }
    }

    private fun togglePauseResumeCruise() {
        if (cruiseEngine.state == CruiseEngine.State.RUNNING) {
            cruiseEngine.pause()
            btnPauseResumeCruise.text = getString(R.string.btn_resume_cruise)
            Toast.makeText(this, R.string.cruise_paused, Toast.LENGTH_SHORT).show()
        } else if (cruiseEngine.state == CruiseEngine.State.PAUSED) {
            cruiseEngine.resume()
            btnPauseResumeCruise.text = getString(R.string.btn_pause_cruise)
            Toast.makeText(this, R.string.cruise_resumed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCruiseSimulation() {
        cruiseJob?.cancel()
        cruiseJob = null
        cruiseEngine.stop()

        btnStartCruise.isEnabled = true
        btnPauseResumeCruise.isEnabled = false
        btnPauseResumeCruise.text = getString(R.string.btn_pause_cruise)
        btnStopCruise.isEnabled = false

        mapController.clearRoute()
    }
}
