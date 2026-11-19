package com.mockloc.presentation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mockloc.R
import com.mockloc.databinding.ActivityMainBinding
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.Waypoint
import com.mockloc.presentation.adapter.SearchResultAdapter
import com.mockloc.presentation.adapter.WaypointAdapter
import com.mockloc.presentation.map.DomainGeoPoint
import com.mockloc.presentation.map.MapInteractionListener
import com.mockloc.presentation.map.OsmMapController
import com.mockloc.presentation.model.ControlPanelTab
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.presentation.model.MainUiState
import com.mockloc.presentation.model.MapLayerType
import com.mockloc.presentation.ui.EdgeToEdgeHelper
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(this)
    }

    private lateinit var mapController: OsmMapController
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var searchAdapter: SearchResultAdapter
    private lateinit var waypointAdapter: WaypointAdapter

    private var isUpdatingUi = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }
        viewModel.onPermissionsResult(
            locationGranted = fineGranted || coarseGranted,
            notificationGranted = notifGranted
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 在 MapView 构造（inflate）之前先设置纯内存缓存路径指针，杜绝主线程 StrictMode 磁盘 I/O
        OsmMapController.configureBasePaths(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            OsmMapController.prepareCacheAsync(applicationContext)
        }

        setupEdgeToEdge()
        setupMap()
        setupBottomSheet()
        setupSearchSection()
        setupEnvironmentSection()
        setupModeAndTabControls()
        setupSinglePointSection()
        setupCruiseSection()
        setupAntiDetectSection()
        observeStateAndEvents()
    }

    private fun setupEdgeToEdge() {
        EdgeToEdgeHelper.applyTopMarginInsets(binding.topFloatingContainer)
        EdgeToEdgeHelper.applyRightMarginInsets(binding.mapToolsContainer)
        EdgeToEdgeHelper.applyBottomPaddingInsets(binding.sheetContentContainer, includeIme = true)
    }

    private fun setupMap() {
        mapController = OsmMapController(
            context = this,
            mapView = binding.mapView,
            listener = object : MapInteractionListener {
                override fun onMapSingleTap(wgsPoint: DomainGeoPoint) {
                    clearCoordinateFocusAndHideKeyboard()
                    viewModel.onMapTapped(wgsPoint)
                }

                override fun onMapLongPress(wgsPoint: DomainGeoPoint) {
                    clearCoordinateFocusAndHideKeyboard()
                    viewModel.onMapTapped(wgsPoint)
                    if (viewModel.uiState.value.activeTab == ControlPanelTab.CRUISE) {
                        viewModel.appendSelectedPointToRoute()
                    }
                }

                override fun onSelectedPinDragged(wgsPoint: DomainGeoPoint) {
                    clearCoordinateFocusAndHideKeyboard()
                    viewModel.onPinDragged(wgsPoint)
                }

                override fun onWaypointMarkerClick(index: Int) {
                    val waypoints = viewModel.uiState.value.draftWaypoints
                    if (index in waypoints.indices) {
                        val wp = waypoints[index]
                        mapController.animateToPoint(wp.point, 16.5)
                        val wpTitle = wp.name.ifBlank { getString(R.string.waypoint_unnamed) }
                        showMainSnackbar(getString(R.string.waypoint_title_format, index + 1, wpTitle))
                    }
                }
            }
        )

        binding.fabMapLayer.setOnClickListener { viewModel.toggleMapLayer() }
        binding.fabCenterTarget.setOnClickListener { viewModel.centerMapOnCurrentTarget() }
        binding.fabFavorites.setOnClickListener {
            clearCoordinateFocusAndHideKeyboard()
            FavoritesBottomSheet().show(supportFragmentManager, "FavoritesBottomSheet")
        }
        binding.fabWhitelist.setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        binding.fabScopeGuide.setOnClickListener {
            ScopeGuideDialogFragment().show(supportFragmentManager, "ScopeGuideDialogFragment")
        }
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheetContainer)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        val density = resources.displayMetrics.density
        binding.rootCoordinator.addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val rootHeight = bottom - top
            if (rootHeight <= 0) return@addOnLayoutChangeListener
            val headerH = binding.sheetHeaderHandle.height.takeIf { it > 0 } ?: (96f * density).toInt()
            val targetPeek = (headerH + (4f * density).toInt()).coerceAtMost((rootHeight * 0.35f).toInt())
            if (bottomSheetBehavior.peekHeight != targetPeek) {
                bottomSheetBehavior.peekHeight = targetPeek
            }
            val topInset = ViewCompat.getRootWindowInsets(v)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
            val available = (rootHeight - topInset - headerH - (24f * density).toInt()).coerceAtLeast((160f * density).toInt())
            val maxScrollPx = minOf((420f * density).toInt(), (rootHeight * 0.56f).toInt(), available)
            val lp = binding.sheetScrollView.layoutParams
            if (lp.height != maxScrollPx) {
                lp.height = maxScrollPx
                binding.sheetScrollView.layoutParams = lp
            }
        }

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                val expanded = newState == BottomSheetBehavior.STATE_EXPANDED
                binding.btnToggleSheet.setImageResource(
                    if (expanded) R.drawable.ic_arrow_down_24 else R.drawable.ic_arrow_up_24
                )
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })

        val toggleAction = View.OnClickListener {
            bottomSheetBehavior.state = if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_COLLAPSED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }
        binding.btnToggleSheet.setOnClickListener(toggleAction)
        binding.sheetHeaderHandle.setOnClickListener(toggleAction)

        binding.btnQuickAction.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isRunningAny) {
                viewModel.stopMock()
            } else if (state.activeTab == ControlPanelTab.SINGLE) {
                viewModel.onStartSingleClicked()
            } else {
                viewModel.onStartCruiseClicked()
            }
        }
    }

    private fun setupSearchSection() {
        searchAdapter = SearchResultAdapter { item ->
            clearCoordinateFocusAndHideKeyboard()
            binding.etSearch.clearFocus()
            hideKeyboard(binding.etSearch)
            viewModel.selectSearchResult(item)
        }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = searchAdapter

        binding.etSearch.doAfterTextChanged { editable ->
            if (!isUpdatingUi) {
                viewModel.onSearchQueryChanged(editable?.toString().orEmpty())
            }
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.submitSearchNow()
                true
            } else {
                false
            }
        }
        binding.btnSearchSubmit.setOnClickListener { viewModel.submitSearchNow() }
        binding.btnClearSearch.setOnClickListener {
            isUpdatingUi = true
            binding.etSearch.setText("")
            isUpdatingUi = false
            binding.etSearch.clearFocus()
            hideKeyboard(binding.etSearch)
            viewModel.clearSearch()
        }
    }

    private fun setupEnvironmentSection() {
        binding.tvLsposedBadge.setOnClickListener {
            ScopeGuideDialogFragment().show(supportFragmentManager, "ScopeGuideDialogFragment")
        }
        binding.tvWhitelistBadge.setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        binding.btnRefreshEnv.setOnClickListener {
            viewModel.refreshEnvironment()
        }
        binding.btnGoWhitelistFromHint.setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        binding.btnDismissWhitelistHint.setOnClickListener {
            viewModel.dismissWhitelistHint()
        }
    }

    private fun setupModeAndTabControls() {
        binding.toggleMockMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingUi || !isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnModeAuto -> MockMode.AUTO
                R.id.btnModeLsposed -> MockMode.LSPOSED_ONLY
                R.id.btnModeRoot -> MockMode.ROOT_ONLY
                else -> MockMode.AUTO
            }
            viewModel.setMockMode(mode)
        }

        binding.togglePanelTab.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingUi || !isChecked) return@addOnButtonCheckedListener
            val tab = if (checkedId == R.id.btnTabCruise) {
                ControlPanelTab.CRUISE
            } else {
                ControlPanelTab.SINGLE
            }
            viewModel.setActiveTab(tab)
        }
    }

    private fun setupSinglePointSection() {
        binding.chipGroupCoordType.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            clearCoordinateFocusAndHideKeyboard()
            val type = when (checkedIds.first()) {
                R.id.chipCoordGcj02 -> CoordinateType.GCJ02
                R.id.chipCoordBd09 -> CoordinateType.BD09
                else -> CoordinateType.WGS84
            }
            viewModel.setInputCoordinateType(type)
        }

        val syncInputs = {
            if (!isUpdatingUi) {
                viewModel.onCoordinateTextChanged(
                    latText = binding.etLat.text?.toString().orEmpty(),
                    lonText = binding.etLon.text?.toString().orEmpty(),
                    altText = binding.etAlt.text?.toString().orEmpty(),
                    accuracyText = binding.etAccuracy.text?.toString().orEmpty()
                )
            }
        }
        binding.etLat.doAfterTextChanged { syncInputs() }
        binding.etLon.doAfterTextChanged { syncInputs() }
        binding.etAlt.doAfterTextChanged { syncInputs() }
        binding.etAccuracy.doAfterTextChanged { syncInputs() }

        binding.btnApplyCoords.setOnClickListener {
            clearCoordinateFocusAndHideKeyboard()
            viewModel.applyCoordinateInputs(
                latText = binding.etLat.text?.toString().orEmpty(),
                lonText = binding.etLon.text?.toString().orEmpty(),
                altText = binding.etAlt.text?.toString().orEmpty(),
                accuracyText = binding.etAccuracy.text?.toString().orEmpty()
            )
        }

        binding.btnFavoriteCurrent.setOnClickListener {
            showQuickSaveFavoriteDialog()
        }

        binding.btnAddToRoute.setOnClickListener {
            viewModel.appendSelectedPointToRoute()
            viewModel.setActiveTab(ControlPanelTab.CRUISE)
        }

        binding.switchTapUpdatesRunning.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setTapMapUpdatesRunningSingle(isChecked)
        }

        binding.btnStartSingle.setOnClickListener { viewModel.onStartSingleClicked() }
        binding.btnStopSingle.setOnClickListener { viewModel.stopMock() }
    }

    private fun setupCruiseSection() {
        waypointAdapter = WaypointAdapter(
            onMoveUp = { index -> viewModel.moveWaypointUp(index) },
            onMoveDown = { index -> viewModel.moveWaypointDown(index) },
            onRemove = { index -> viewModel.removeWaypointAt(index) },
            onClick = { _, wp -> mapController.animateToPoint(wp.point, 16.5) }
        )
        binding.rvWaypoints.layoutManager = LinearLayoutManager(this)
        binding.rvWaypoints.adapter = waypointAdapter

        binding.etRouteName.doAfterTextChanged { editable ->
            if (!isUpdatingUi) {
                viewModel.updateDraftRouteName(editable?.toString().orEmpty())
            }
        }
        binding.btnManageRoutes.setOnClickListener {
            RouteManagerBottomSheet().show(supportFragmentManager, "RouteManagerBottomSheet")
        }
        binding.btnSaveRoute.setOnClickListener {
            viewModel.saveDraftRoute(binding.etRouteName.text?.toString())
        }
        binding.btnFitRoute.setOnClickListener { viewModel.fitDraftRouteOnMap() }
        binding.btnClearWaypoints.setOnClickListener { viewModel.clearDraftWaypoints() }

        binding.chipGroupSpeed.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val preset = when (checkedIds.first()) {
                R.id.chipSpeedBike -> SpeedPreset.BIKE
                R.id.chipSpeedDrive -> SpeedPreset.DRIVE
                R.id.chipSpeedCustom -> SpeedPreset.CUSTOM
                else -> SpeedPreset.WALK
            }
            viewModel.selectSpeedPreset(preset)
        }

        binding.sliderCustomSpeed.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingUi) {
                viewModel.updateCustomSpeed(value.toDouble())
            }
        }

        binding.chipGroupLoop.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingUi || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val mode = when (checkedIds.first()) {
                R.id.chipLoopPingPong -> LoopMode.PING_PONG
                R.id.chipLoopCycle -> LoopMode.LOOP
                else -> LoopMode.ONE_WAY
            }
            viewModel.setLoopMode(mode)
        }

        binding.switchSpeedNoise.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setSpeedNoiseEnabled(isChecked)
        }
        binding.switchMicroDrift.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setMicroDriftEnabled(isChecked)
        }
        binding.switchFollowCruise.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setFollowCruise(isChecked)
        }

        binding.btnStartCruise.setOnClickListener {
            if (viewModel.uiState.value.isOneWayCompleted) {
                viewModel.restartCruiseFromCompleted()
            } else {
                viewModel.onStartCruiseClicked()
            }
        }
        binding.btnPauseCruise.setOnClickListener { viewModel.pauseCruise() }
        binding.btnResumeCruise.setOnClickListener { viewModel.resumeCruise() }
        binding.btnStopCruise.setOnClickListener { viewModel.stopMock() }
    }

    private fun setupAntiDetectSection() {
        binding.btnToggleAntiDetect.setOnClickListener {
            val current = viewModel.uiState.value.antiDetectExpanded
            viewModel.setAntiDetectExpanded(!current)
        }
        binding.switchSingleJitter.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setSingleJitterEnabled(isChecked)
        }
        binding.btnApplyJitterRadius.setOnClickListener {
            viewModel.updateJitterRadiusInput(binding.etJitterRadius.text?.toString().orEmpty())
        }
        binding.switchAntiCell.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setAntiDetectCellEnabled(isChecked)
        }
        binding.switchAntiWifi.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setAntiDetectWifiEnabled(isChecked)
        }
        binding.switchAntiGnss.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setAntiDetectGnssEnabled(isChecked)
        }
    }

    private fun observeStateAndEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { renderState(it) }
                }
                launch {
                    viewModel.events.collect { handleEvent(it) }
                }
            }
        }
    }

    private fun renderState(state: MainUiState) {
        isUpdatingUi = true
        try {
            renderTopAndEnvironment(state)
            renderSearch(state)
            renderModeAndTab(state)
            renderSinglePoint(state)
            renderCruise(state)
            renderAntiDetect(state)
            renderMapOverlays(state)
        } finally {
            isUpdatingUi = false
        }
    }

    private fun renderTopAndEnvironment(state: MainUiState) {
        val status = state.runtimeState.status
        val (statusText, textColorRes, bgColorRes) = when {
            status is MockRunStatus.SingleRunning ->
                Triple(getString(R.string.status_single_running), R.color.status_success, R.color.status_success_container)
            status is MockRunStatus.CruiseRunning ->
                Triple(getString(R.string.status_cruise_running), R.color.status_success, R.color.status_success_container)
            status is MockRunStatus.CruisePaused ->
                Triple(getString(R.string.status_cruise_paused), R.color.status_warning, R.color.status_warning_container)
            status is MockRunStatus.Error ->
                Triple(getString(R.string.status_error, status.reason), R.color.status_error, R.color.status_error_container)
            state.isOneWayCompleted ->
                Triple(getString(R.string.status_cruise_completed), R.color.status_info, R.color.status_info_container)
            else ->
                Triple(getString(R.string.status_idle), R.color.status_neutral, R.color.status_neutral_container)
        }
        binding.tvRunStatusBadge.text = statusText
        binding.tvRunStatusBadge.setTextColor(ContextCompat.getColor(this, textColorRes))
        binding.tvRunStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgColorRes))

        val env = state.environmentStatus
        if (env != null && env.isLSPosedActive) {
            binding.tvLsposedBadge.text = getString(R.string.env_lsposed_active, env.xposedApiVersion)
            binding.tvLsposedBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            binding.tvLsposedBadge.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_success_container))
        } else {
            binding.tvLsposedBadge.text = getString(R.string.env_lsposed_inactive)
            binding.tvLsposedBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
            binding.tvLsposedBadge.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_warning_container))
        }

        if (env != null && env.isRootAvailable) {
            binding.tvRootBadge.text = getString(R.string.env_root_available, env.rootProviderName)
            binding.tvRootBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            binding.tvRootBadge.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_success_container))
        } else {
            binding.tvRootBadge.text = getString(R.string.env_root_unavailable)
            binding.tvRootBadge.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            binding.tvRootBadge.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_neutral_container))
        }

        binding.tvWhitelistBadge.text = getString(R.string.whitelist_badge_format, state.whitelistCount)
        binding.cardWhitelistHint.visibility =
            if (state.shouldShowEmptyWhitelistHint) View.VISIBLE else View.GONE

        binding.btnQuickAction.text = if (state.isRunningAny) {
            getString(R.string.quick_action_stop)
        } else if (state.activeTab == ControlPanelTab.CRUISE && state.isOneWayCompleted) {
            getString(R.string.quick_action_restart)
        } else {
            getString(R.string.quick_action_start)
        }
    }

    private fun renderSearch(state: MainUiState) {
        if (!binding.etSearch.hasFocus() && binding.etSearch.text?.toString() != state.searchQuery) {
            binding.etSearch.setText(state.searchQuery)
        }
        binding.pbSearchLoading.visibility = if (state.isSearching) View.VISIBLE else View.GONE
        binding.btnClearSearch.visibility = if (state.searchQuery.isNotEmpty()) View.VISIBLE else View.GONE

        val showResultsCard = state.searchResults.isNotEmpty() || state.searchInfoMessage != null
        binding.cardSearchResults.visibility = if (showResultsCard) View.VISIBLE else View.GONE
        searchAdapter.submitList(state.searchResults)

        if (state.searchInfoMessage != null) {
            binding.tvSearchBanner.visibility = View.VISIBLE
            binding.tvSearchBanner.text = state.searchInfoMessage
        } else if (state.isSearchOfflineFallback) {
            binding.tvSearchBanner.visibility = View.VISIBLE
            binding.tvSearchBanner.text = getString(R.string.search_offline_fallback_banner)
        } else {
            binding.tvSearchBanner.visibility = View.GONE
        }
    }

    private fun renderModeAndTab(state: MainUiState) {
        val modeBtnId = when (state.config.mode) {
            MockMode.AUTO -> R.id.btnModeAuto
            MockMode.LSPOSED_ONLY -> R.id.btnModeLsposed
            MockMode.ROOT_ONLY -> R.id.btnModeRoot
        }
        if (binding.toggleMockMode.checkedButtonId != modeBtnId) {
            binding.toggleMockMode.check(modeBtnId)
        }

        val tabBtnId = if (state.activeTab == ControlPanelTab.CRUISE) {
            R.id.btnTabCruise
        } else {
            R.id.btnTabSingle
        }
        if (binding.togglePanelTab.checkedButtonId != tabBtnId) {
            binding.togglePanelTab.check(tabBtnId)
        }

        binding.panelSinglePoint.visibility =
            if (state.activeTab == ControlPanelTab.SINGLE) View.VISIBLE else View.GONE
        binding.panelRouteCruise.visibility =
            if (state.activeTab == ControlPanelTab.CRUISE) View.VISIBLE else View.GONE
    }

    private fun renderSinglePoint(state: MainUiState) {
        val wgs = state.selectedPoint
        val gcj = CoordinateConverter.wgs84ToGcj02(wgs.latitude, wgs.longitude)
        binding.tvSelectedAddress.text = state.selectedAddressLabel
        binding.tvSheetSummaryCoords.text = getString(
            R.string.sheet_summary_coords_format,
            wgs.latitude,
            wgs.longitude
        )
        val outOfChinaTag = if (CoordinateConverter.isOutOfChina(wgs.latitude, wgs.longitude)) {
            getString(R.string.coord_out_of_china_tag)
        } else {
            ""
        }
        binding.tvCoordComparison.text = getString(
            R.string.coord_comparison_format,
            wgs.latitude,
            wgs.longitude,
            wgs.altitude,
            gcj.latitude,
            gcj.longitude,
            outOfChinaTag
        )

        val coordChipId = when (state.inputCoordinateType) {
            CoordinateType.WGS84 -> R.id.chipCoordWgs84
            CoordinateType.GCJ02 -> R.id.chipCoordGcj02
            CoordinateType.BD09 -> R.id.chipCoordBd09
        }
        if (binding.chipGroupCoordType.checkedChipId != coordChipId) {
            binding.chipGroupCoordType.check(coordChipId)
        }

        if (!binding.etLat.hasFocus() && binding.etLat.text?.toString() != state.latInput) {
            binding.etLat.setText(state.latInput)
        }
        if (!binding.etLon.hasFocus() && binding.etLon.text?.toString() != state.lonInput) {
            binding.etLon.setText(state.lonInput)
        }
        if (!binding.etAlt.hasFocus() && binding.etAlt.text?.toString() != state.altInput) {
            binding.etAlt.setText(state.altInput)
        }
        if (!binding.etAccuracy.hasFocus() && binding.etAccuracy.text?.toString() != state.accuracyInput) {
            binding.etAccuracy.setText(state.accuracyInput)
        }

        binding.tilLat.error = state.latError
        binding.tilLon.error = state.lonError
        binding.tilAlt.error = state.altError
        binding.tilAccuracy.error = state.accuracyError

        binding.switchTapUpdatesRunning.isChecked = state.tapMapUpdatesRunningSingle
        binding.btnStopSingle.isEnabled = state.isRunningAny
    }

    private fun renderCruise(state: MainUiState) {
        if (!binding.etRouteName.hasFocus() && binding.etRouteName.text?.toString() != state.draftRouteName) {
            binding.etRouteName.setText(state.draftRouteName)
        }

        val totalMeters = com.mockloc.domain.model.PlannedRoute(
            waypoints = state.draftWaypoints
        ).totalDistanceMeters()
        val distLabel = if (totalMeters >= 1000.0) {
            String.format(Locale.US, "%.2f km", totalMeters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f m", totalMeters)
        }
        binding.tvRouteStats.text = getString(R.string.route_stats_format, state.draftWaypoints.size, distLabel)

        waypointAdapter.submitList(state.draftWaypoints)
        val hasWaypoints = state.draftWaypoints.isNotEmpty()
        binding.tvWaypointsEmpty.visibility = if (hasWaypoints) View.GONE else View.VISIBLE
        binding.rvWaypoints.visibility = if (hasWaypoints) View.VISIBLE else View.GONE

        val speedChipId = when (state.cruiseSpeedPreset) {
            SpeedPreset.WALK -> R.id.chipSpeedWalk
            SpeedPreset.BIKE -> R.id.chipSpeedBike
            SpeedPreset.DRIVE -> R.id.chipSpeedDrive
            SpeedPreset.CUSTOM -> R.id.chipSpeedCustom
        }
        if (binding.chipGroupSpeed.checkedChipId != speedChipId) {
            binding.chipGroupSpeed.check(speedChipId)
        }

        binding.layoutCustomSpeed.visibility =
            if (state.cruiseSpeedPreset == SpeedPreset.CUSTOM) View.VISIBLE else View.GONE
        val step = 0.5f
        val snappedSlider = ((state.customSpeedMps.toFloat() / step).roundToInt() * step)
            .coerceIn(binding.sliderCustomSpeed.valueFrom, binding.sliderCustomSpeed.valueTo)
        if (binding.sliderCustomSpeed.value != snappedSlider) {
            binding.sliderCustomSpeed.value = snappedSlider
        }
        binding.tvCustomSpeedValue.text = getString(
            R.string.custom_speed_value_format,
            state.customSpeedMps,
            state.customSpeedMps * 3.6
        )

        val loopChipId = when (state.cruiseLoopMode) {
            LoopMode.ONE_WAY -> R.id.chipLoopOneWay
            LoopMode.PING_PONG -> R.id.chipLoopPingPong
            LoopMode.LOOP -> R.id.chipLoopCycle
        }
        if (binding.chipGroupLoop.checkedChipId != loopChipId) {
            binding.chipGroupLoop.check(loopChipId)
        }

        binding.switchSpeedNoise.isChecked = state.enableSpeedNoise
        binding.switchMicroDrift.isChecked = state.enableMicroDrift
        binding.switchFollowCruise.isChecked = state.followCruise

        val rt = state.runtimeState
        val progressPercent = (rt.progressFraction.coerceIn(0f, 1f) * 100f)
        binding.progressCruise.progress = (rt.progressFraction.coerceIn(0f, 1f) * 1000f).toInt()
        binding.tvCruiseTelemetry.text = getString(
            R.string.cruise_telemetry_format,
            progressPercent,
            if (rt.totalWaypoints > 0) rt.activeWaypointIndex + 1 else 0,
            rt.totalWaypoints,
            rt.speedMps,
            rt.bearingDeg
        )
        binding.tvCruiseCompletedBadge.visibility =
            if (state.isOneWayCompleted) View.VISIBLE else View.GONE

        when (rt.status) {
            is MockRunStatus.CruiseRunning -> {
                binding.btnStartCruise.visibility = View.GONE
                binding.btnPauseCruise.visibility = View.VISIBLE
                binding.btnResumeCruise.visibility = View.GONE
                binding.btnStopCruise.isEnabled = true
            }
            is MockRunStatus.CruisePaused -> {
                binding.btnStartCruise.visibility = View.GONE
                binding.btnPauseCruise.visibility = View.GONE
                binding.btnResumeCruise.visibility = View.VISIBLE
                binding.btnStopCruise.isEnabled = true
            }
            else -> {
                binding.btnStartCruise.visibility = View.VISIBLE
                binding.btnStartCruise.text = if (state.isOneWayCompleted) {
                    getString(R.string.action_restart_cruise)
                } else {
                    getString(R.string.action_start_cruise)
                }
                binding.btnPauseCruise.visibility = View.GONE
                binding.btnResumeCruise.visibility = View.GONE
                binding.btnStopCruise.isEnabled = state.isRunningAny
            }
        }
    }

    private fun renderAntiDetect(state: MainUiState) {
        binding.panelAntiDetect.visibility = if (state.antiDetectExpanded) View.VISIBLE else View.GONE
        binding.ivAntiDetectArrow.setImageResource(
            if (state.antiDetectExpanded) R.drawable.ic_arrow_up_24 else R.drawable.ic_arrow_down_24
        )
        binding.switchSingleJitter.isChecked = state.config.jitterEnabled
        if (!binding.etJitterRadius.hasFocus() &&
            binding.etJitterRadius.text?.toString() != state.jitterRadiusInput
        ) {
            binding.etJitterRadius.setText(state.jitterRadiusInput)
        }
        binding.tilJitterRadius.error = state.jitterRadiusError
        binding.switchAntiCell.isChecked = state.config.antiDetectCellEnabled
        binding.switchAntiWifi.isChecked = state.config.antiDetectWifiEnabled
        binding.switchAntiGnss.isChecked = state.config.antiDetectGnssEnabled
    }

    private fun renderMapOverlays(state: MainUiState) {
        mapController.setMapLayer(state.mapLayer)
        binding.fabMapLayer.contentDescription = if (state.mapLayer == MapLayerType.SATELLITE) {
            getString(R.string.map_layer_satellite)
        } else {
            getString(R.string.map_layer_vector)
        }
        mapController.updateSelectedPin(
            wgsPoint = state.selectedPoint,
            visible = state.activeTab == ControlPanelTab.SINGLE || state.draftWaypoints.isEmpty()
        )
        mapController.updateWaypointsAndRoute(state.draftWaypoints)

        val rt = state.runtimeState
        val showCruiseArrow = rt.status is MockRunStatus.CruiseRunning ||
            rt.status is MockRunStatus.CruisePaused ||
            rt.status is MockRunStatus.SingleRunning
        mapController.updateCruiseIndicator(
            visible = showCruiseArrow,
            wgsPoint = rt.currentPoint,
            bearingDeg = rt.bearingDeg,
            followCamera = state.followCruise && rt.status is MockRunStatus.CruiseRunning
        )
    }

    private fun handleEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.ShowSnackbar -> {
                showMainSnackbar(event.message)
            }
            is MainUiEvent.RequestPermissions -> {
                val permissions = mutableListOf<String>()
                if (event.needLocation) {
                    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                    permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (event.needNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (permissions.isNotEmpty()) {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            }
            is MainUiEvent.AnimateMapToPoint -> {
                clearCoordinateFocusAndHideKeyboard()
                mapController.animateToPoint(event.point, event.zoom)
            }
            is MainUiEvent.FitRouteOnMap -> {
                mapController.fitRoute(event.waypoints)
            }
            is MainUiEvent.CopyToClipboard -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(event.label, event.text))
            }
            is MainUiEvent.FavoriteFeedback,
            is MainUiEvent.RouteFeedback,
            is MainUiEvent.ScopeGuideFeedback -> Unit
        }
    }

    private fun showMainSnackbar(message: String) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
        val bottomInset = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        if (bottomInset > 0) {
            snackbar.view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin += bottomInset
            }
        }
        snackbar.show()
    }

    private fun clearCoordinateFocusAndHideKeyboard() {
        val coordFields = listOf(binding.etLat, binding.etLon, binding.etAlt, binding.etAccuracy)
        var hadFocus = false
        for (field in coordFields) {
            if (field.hasFocus()) {
                field.clearFocus()
                hadFocus = true
            }
        }
        if (hadFocus) {
            hideKeyboard(binding.root)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showQuickSaveFavoriteDialog() {
        val state = viewModel.uiState.value
        val point = state.selectedPoint
        val defaultHintLabel = getString(R.string.default_selected_address_hint)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val tilName = TextInputLayout(this).apply { hint = getString(R.string.dialog_fav_hint_name_required) }
        val etName = TextInputEditText(this).apply {
            setText(
                state.selectedAddressLabel
                    .takeIf { it.isNotBlank() && it != defaultHintLabel }
                    ?.substringBefore(" · ")
                    .orEmpty()
            )
        }
        tilName.addView(etName)

        val tilAddr = TextInputLayout(this).apply { hint = getString(R.string.dialog_fav_hint_addr_optional) }
        val etAddr = TextInputEditText(this).apply {
            setText(String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude))
        }
        tilAddr.addView(etAddr)

        container.addView(tilName)
        container.addView(tilAddr)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_fav_title_add)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                viewModel.addFavorite(
                    name = etName.text?.toString().orEmpty(),
                    address = etAddr.text?.toString().orEmpty(),
                    point = point,
                    fromMainScreen = true
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        mapController.onResume()
        viewModel.refreshEnvironment()
    }

    override fun onPause() {
        mapController.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::mapController.isInitialized) {
            mapController.onDetach()
        }
        super.onDestroy()
    }
}
