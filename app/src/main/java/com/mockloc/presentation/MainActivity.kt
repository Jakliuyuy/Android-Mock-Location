package com.mockloc.presentation

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mockloc.R
import com.mockloc.data.FavoriteLocation
import com.mockloc.data.FavoriteRepository
import com.mockloc.data.MockConfigManager
import com.mockloc.data.MockPrefs
import com.mockloc.data.WhitelistRepository
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
 * 模拟定位主控中心 Activity (Modern Material 3 Redesign)
 * 提供地图浏览打点、地点搜索、单点位置欺骗、轨迹巡航步进仿真、Root/Xposed环境监测、白名单分流及地点收藏夹。
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
    private lateinit var btnOpenFavorites: ImageButton
    private lateinit var btnOpenWhitelist: ImageButton
    private lateinit var fabAddFavorite: FloatingActionButton
    private lateinit var fabLocateCurrent: FloatingActionButton
    private val geocodingService: GeocodingService by lazy { NominatimGeocodingService() }
    private val favoriteRepository by lazy { FavoriteRepository.getInstance(this) }
    private val whitelistRepository by lazy { WhitelistRepository.getInstance(this) }

    // 模式切换与控制面板
    private lateinit var toggleModeGroup: MaterialButtonToggleGroup
    private lateinit var etLatitude: TextInputEditText
    private lateinit var etLongitude: TextInputEditText
    private lateinit var etAltitude: TextInputEditText

    // 单点与巡航控制
    private lateinit var btnToggleMock: MaterialButton
    private lateinit var layoutCruiseParams: LinearLayout
    private lateinit var layoutCruiseActions: LinearLayout
    private lateinit var chipGroupSpeed: ChipGroup
    private lateinit var tilCustomSpeed: TextInputLayout
    private lateinit var etCustomSpeed: TextInputEditText
    private lateinit var chipGroupLoopMode: ChipGroup

    private lateinit var btnStartCruise: MaterialButton
    private lateinit var btnPauseResumeCruise: MaterialButton
    private lateinit var btnStopCruise: MaterialButton

    // 引擎与状态
    private val cruiseEngine = CruiseEngine()
    private var cruiseJob: Job? = null
    private var singlePointJob: Job? = null
    private val rootProviderManager: RootProviderManager by lazy { RootProviderManager(this) }

    private var isCruiseMode: Boolean = false
    private var lastSelectedPoiName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 确保从持久化存储恢复配置与白名单
        try {
            MockPrefs.loadConfig(this)
            whitelistRepository.loadSelectedPackages()
        } catch (_: Throwable) {}

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
        singlePointJob?.cancel()
        singlePointJob = null
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
        btnOpenFavorites = findViewById(R.id.btnOpenFavorites)
        btnOpenWhitelist = findViewById(R.id.btnOpenWhitelist)
        fabAddFavorite = findViewById(R.id.fabAddFavorite)
        fabLocateCurrent = findViewById(R.id.fabLocateCurrent)

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
        btnOpenWhitelist.setOnClickListener {
            val intent = Intent(this, WhitelistActivity::class.java)
            startActivity(intent)
        }

        // 收藏夹入口点击
        btnOpenFavorites.setOnClickListener {
            showFavoritesBottomSheet()
        }

        // 收藏当前选定位置按钮
        fabAddFavorite.setOnClickListener {
            showAddFavoriteDialog()
        }

        // 快速复位到输入框坐标
        fabLocateCurrent.setOnClickListener {
            val pt = parseInputCoordinates()
            if (pt != null) {
                mapController.setMockPin(pt, if (lastSelectedPoiName.isNotEmpty()) lastSelectedPoiName else "目标点", animate = true)
                Toast.makeText(this, "已定位到当前坐标", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.invalid_coordinates, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupMap() {
        mapController = OsmMapController(this, mapView)
        val initialPoint = GeoPoint(MockConfigManager.latitude, MockConfigManager.longitude, MockConfigManager.altitude)
        mapController.setMockPin(initialPoint, "预设目标", animate = false)

        mapController.onLocationPickedListener = { pickedPoint ->
            etLatitude.setText(String.format("%.6f", pickedPoint.latitude))
            etLongitude.setText(String.format("%.6f", pickedPoint.longitude))
            lastSelectedPoiName = "选定位置"
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
        try {
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
        } catch (_: Throwable) {}
    }

    private fun syncStateWithConfig() {
        etLatitude.setText(String.format("%.6f", MockConfigManager.latitude))
        etLongitude.setText(String.format("%.6f", MockConfigManager.longitude))
        etAltitude.setText(String.format("%.1f", MockConfigManager.altitude))

        updateMockButtonState(MockConfigManager.isMockEnabled)
    }

    private fun updateMockButtonState(isEnabled: Boolean) {
        try {
            if (isEnabled) {
                btnToggleMock.text = "⏹️ " + getString(R.string.btn_stop_mock)
                btnToggleMock.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_inactive)
            } else {
                btnToggleMock.text = "🚀 " + getString(R.string.btn_start_mock)
                btnToggleMock.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            }
        } catch (_: Throwable) {}
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
        lastSelectedPoiName = result.name
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

    /**
     * 发送跨进程广播，通知已注入的目标应用进程实时更新模拟定位配置与最新坐标
     */
    private fun broadcastConfigChange() {
        try {
            val bundle = MockConfigManager.toBundle()
            // 1. 发送全局广播
            val globalIntent = Intent(MockConfigManager.ACTION_CONFIG_CHANGED).apply {
                putExtras(bundle)
            }
            sendBroadcast(globalIntent)

            // 2. 针对白名单中勾选的目标应用逐个发送显式广播
            val targets = MockConfigManager.getTargetPackages()
            for (pkg in targets) {
                try {
                    val explicitIntent = Intent(MockConfigManager.ACTION_CONFIG_CHANGED).apply {
                        setPackage(pkg)
                        putExtras(bundle)
                    }
                    sendBroadcast(explicitIntent)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun toggleSinglePointMock() {
        if (MockConfigManager.isMockEnabled) {
            // 停止模拟
            MockConfigManager.isMockEnabled = false
            try {
                MockPrefs.saveConfig(this)
            } catch (_: Throwable) {}

            singlePointJob?.cancel()
            singlePointJob = null

            try {
                if (rootProviderManager.isRunning) {
                    rootProviderManager.stopMock()
                }
            } catch (_: Throwable) {}

            broadcastConfigChange()
            updateMockButtonState(false)
            Toast.makeText(this, R.string.mock_stopped, Toast.LENGTH_SHORT).show()
        } else {
            // 开启模拟前检查目标白名单
            val targets = whitelistRepository.loadSelectedPackages()
            if (targets.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.whitelist_dialog_title)
                    .setMessage(R.string.whitelist_empty_warning)
                    .setPositiveButton(R.string.btn_start_mock) { _, _ ->
                        proceedStartMock()
                    }
                    .show()
                return
            }
            proceedStartMock()
        }
    }

    private fun proceedStartMock() {
        val point = parseInputCoordinates()
        if (point == null) {
            Toast.makeText(this, R.string.invalid_coordinates, Toast.LENGTH_SHORT).show()
            return
        }
        applySinglePointMock(point)
        updateMockButtonState(true)
        Toast.makeText(this, R.string.mock_started, Toast.LENGTH_SHORT).show()
    }

    private fun applySinglePointMock(point: GeoPoint) {
        MockConfigManager.latitude = point.latitude
        MockConfigManager.longitude = point.longitude
        MockConfigManager.altitude = point.altitude
        MockConfigManager.isMockEnabled = true

        // 立即持久化配置
        try {
            MockPrefs.saveConfig(this)
        } catch (_: Throwable) {}

        // 1. 触发跨进程广播同步
        broadcastConfigChange()

        // 2. 启动协程脉冲心跳（与轨迹巡航机制一致，安全无崩溃）
        singlePointJob?.cancel()
        singlePointJob = lifecycleScope.launch(Dispatchers.Default) {
            var tick = 0
            while (isActive && MockConfigManager.isMockEnabled && !isCruiseMode) {
                tick++
                val jitterLat = Math.sin(tick.toDouble()) * 0.000001
                val jitterLng = Math.cos(tick.toDouble()) * 0.000001
                val curLat = MockConfigManager.latitude + jitterLat
                val curLng = MockConfigManager.longitude + jitterLng

                if (EnvironmentDetector.isRootAvailable() && rootProviderManager.isRunning) {
                    try {
                        rootProviderManager.updateLocation(
                            lat = curLat,
                            lng = curLng,
                            alt = MockConfigManager.altitude,
                            speed = 0.0f,
                            bearing = 0.0f,
                            accuracy = 3.0f
                        )
                    } catch (_: Throwable) {}
                }

                if (tick % 2 == 0) {
                    broadcastConfigChange()
                }

                delay(1000)
            }
        }

        // 3. 如果具备 Root 权限，同步向系统 TestProvider 注册并投递坐标
        try {
            if (EnvironmentDetector.isRootAvailable()) {
                try {
                    rootProviderManager.grantMockPermissionWithSu(packageName)
                } catch (_: Throwable) {}

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

    // ==========================================
    // 地点收藏夹交互逻辑
    // ==========================================

    private fun showFavoritesBottomSheet() {
        try {
            val bottomSheetDialog = BottomSheetDialog(this)
            val sheetView = layoutInflater.inflate(R.layout.dialog_favorites_bottom_sheet, null)
            bottomSheetDialog.setContentView(sheetView)

            val rvFavorites = sheetView.findViewById<RecyclerView>(R.id.rvFavorites)
            val layoutEmpty = sheetView.findViewById<View>(R.id.layoutEmptyFav)
            val tvFavCount = sheetView.findViewById<TextView>(R.id.tvFavCount)

            rvFavorites.layoutManager = LinearLayoutManager(this)

            var list = favoriteRepository.getAll()
            tvFavCount.text = "共 ${list.size} 个地点"
            layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            rvFavorites.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

            lateinit var adapter: FavoriteAdapter
            adapter = FavoriteAdapter(
                items = list,
                onApplyClick = { fav ->
                    lastSelectedPoiName = fav.name
                    val point = GeoPoint(fav.latitude, fav.longitude, fav.altitude)
                    etLatitude.setText(String.format("%.6f", fav.latitude))
                    etLongitude.setText(String.format("%.6f", fav.longitude))
                    etAltitude.setText(String.format("%.1f", fav.altitude))

                    mapController.setMockPin(point, fav.name, animate = true)
                    Toast.makeText(this, "已载入收藏: ${fav.name}", Toast.LENGTH_SHORT).show()

                    if (MockConfigManager.isMockEnabled && !isCruiseMode) {
                        applySinglePointMock(point)
                    }
                    bottomSheetDialog.dismiss()
                },
                onDeleteClick = { fav ->
                    favoriteRepository.delete(fav.id)
                    list = favoriteRepository.getAll()
                    adapter.updateData(list)
                    tvFavCount.text = "共 ${list.size} 个地点"
                    layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    rvFavorites.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                    Toast.makeText(this, R.string.fav_deleted, Toast.LENGTH_SHORT).show()
                }
            )

            rvFavorites.adapter = adapter
            bottomSheetDialog.show()
        } catch (t: Throwable) {
            Toast.makeText(this, "打开收藏夹失败: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddFavoriteDialog() {
        val point = parseInputCoordinates()
        if (point == null) {
            Toast.makeText(this, R.string.invalid_coordinates, Toast.LENGTH_SHORT).show()
            return
        }

        val inputEditText = EditText(this).apply {
            hint = getString(R.string.dialog_add_fav_hint)
            val defaultName = if (lastSelectedPoiName.isNotEmpty() && lastSelectedPoiName != "选定位置") {
                lastSelectedPoiName
            } else {
                "自定义位置 (%.4f, %.4f)".format(point.latitude, point.longitude)
            }
            setText(defaultName)
            setSelection(text.length)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(inputEditText)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_fav_title)
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = inputEditText.text?.toString()?.trim().orEmpty()
                val finalName = if (name.isNotEmpty()) name else "未命名地点"
                val favorite = FavoriteLocation(
                    name = finalName,
                    address = "坐标: %.6f, %.6f".format(point.latitude, point.longitude),
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitude = point.altitude
                )
                favoriteRepository.add(favorite)
                Toast.makeText(this, R.string.fav_added_success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==========================================
    // 轨迹巡航逻辑
    // ==========================================

    private fun startCruiseSimulation() {
        val startPoint = parseInputCoordinates()
        if (startPoint == null) {
            Toast.makeText(this, R.string.invalid_coordinates, Toast.LENGTH_SHORT).show()
            return
        }

        // 停止单点心跳
        singlePointJob?.cancel()
        singlePointJob = null

        val speedMps: Float = when (chipGroupSpeed.checkedChipId) {
            R.id.chipSpeedWalk -> SpeedPreset.WALK.speedMps
            R.id.chipSpeedBike -> SpeedPreset.BIKE.speedMps
            R.id.chipSpeedDrive -> SpeedPreset.DRIVE.speedMps
            R.id.chipSpeedCustom -> etCustomSpeed.text?.toString()?.toFloatOrNull() ?: 5.0f
            else -> SpeedPreset.WALK.speedMps
        }

        val loopMode = when (chipGroupLoopMode.checkedChipId) {
            R.id.chipLoopPingPong -> LoopMode.PING_PONG
            R.id.chipLoopLoop -> LoopMode.LOOP
            else -> LoopMode.ONE_WAY
        }

        val delta = 0.005
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

        mapController.drawRoute(routePoints)

        val config = CruiseConfig(
            baseSpeedMps = speedMps,
            loopMode = loopMode,
            enableJitter = true,
            enableMicroDrift = true
        )
        cruiseEngine.setRoute(plannedRoute, config)
        cruiseEngine.start()

        MockConfigManager.isMockEnabled = true
        try {
            MockPrefs.saveConfig(this)
        } catch (_: Throwable) {}

        btnStartCruise.isEnabled = false
        btnPauseResumeCruise.isEnabled = true
        btnPauseResumeCruise.text = getString(R.string.btn_pause_cruise)
        btnStopCruise.isEnabled = true

        Toast.makeText(this, R.string.cruise_started, Toast.LENGTH_SHORT).show()

        cruiseJob?.cancel()
        cruiseJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive && cruiseEngine.state != CruiseEngine.State.STOPPED) {
                if (cruiseEngine.state == CruiseEngine.State.RUNNING) {
                    val output = cruiseEngine.advanceStep(1.0)

                    MockConfigManager.latitude = output.point.latitude
                    MockConfigManager.longitude = output.point.longitude
                    MockConfigManager.altitude = output.altitude
                    MockConfigManager.speed = output.speedMps
                    MockConfigManager.bearing = output.bearingDeg

                    broadcastConfigChange()

                    if (EnvironmentDetector.isRootAvailable() && rootProviderManager.isRunning) {
                        try {
                            rootProviderManager.updateLocation(
                                lat = output.point.latitude,
                                lng = output.point.longitude,
                                alt = output.altitude,
                                speed = output.speedMps,
                                bearing = output.bearingDeg
                            )
                        } catch (_: Throwable) {}
                    }

                    withContext(Dispatchers.Main) {
                        mapController.setMockPin(output.point, "巡航中: %.1f km/h".format(output.speedMps * 3.6), animate = false)
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

        if (EnvironmentDetector.isRootAvailable() && rootProviderManager.isRunning) {
            try {
                rootProviderManager.stopMock()
            } catch (_: Throwable) {}
        }
        broadcastConfigChange()

        btnStartCruise.isEnabled = true
        btnPauseResumeCruise.isEnabled = false
        btnPauseResumeCruise.text = getString(R.string.btn_pause_cruise)
        btnStopCruise.isEnabled = false

        mapController.clearRoute()
        Toast.makeText(this, R.string.cruise_stopped, Toast.LENGTH_SHORT).show()
    }
}
