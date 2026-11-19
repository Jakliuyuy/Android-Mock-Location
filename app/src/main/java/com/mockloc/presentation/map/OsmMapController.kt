package com.mockloc.presentation.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.mockloc.R
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.Waypoint
import com.mockloc.presentation.model.MapLayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

typealias DomainGeoPoint = com.mockloc.domain.model.GeoPoint
typealias OsmGeoPoint = org.osmdroid.util.GeoPoint

/**
 * 纯 JVM 可测的高德地图坐标与航向角桥接器：
 * - 内部权威点位统一 WGS-84；
 * - 高德底图展示统一转为 GCJ-02（境外点由 CoordinateConverter 自动保持无偏转）；
 * - 修正 OSMDroid Marker.draw 内部的 fneg(-mBearing) 旋转行为。
 */
object OsmMapCoordinateBridge {
    fun toMapDisplayPoint(wgsPoint: DomainGeoPoint): Pair<Double, Double> {
        val gcj = CoordinateConverter.wgs84ToGcj02(wgsPoint.latitude, wgsPoint.longitude)
        return gcj.latitude to gcj.longitude
    }

    fun toWgs84DomainPoint(mapLat: Double, mapLon: Double): DomainGeoPoint {
        return CoordinateConverter.gcj02ToWgs84(mapLat, mapLon)
    }

    fun toMarkerRotation(bearingDeg: Float): Float {
        if (!bearingDeg.isFinite()) return 0f
        val normalized = ((bearingDeg % 360f) + 360f) % 360f
        return if (normalized == 0f) 0f else -normalized
    }
}

interface MapInteractionListener {
    fun onMapSingleTap(wgsPoint: DomainGeoPoint)
    fun onMapLongPress(wgsPoint: DomainGeoPoint)
    fun onSelectedPinDragged(wgsPoint: DomainGeoPoint)
    fun onWaypointMarkerClick(index: Int)
}

/**
 * OSMDroid + 高德地图（矢量 / 卫星）控制器：
 * 固定 Overlays 分层顺序、单实例复用防闪烁、触摸时不抢夺相机视角。
 */
class OsmMapController(
    private val context: Context,
    private val mapView: MapView,
    private val listener: MapInteractionListener
) {
    private val appContext = context.applicationContext
    private var currentLayer: MapLayerType = MapLayerType.VECTOR
    private var isUserInteracting = false
    private var lastInteractionAtMs = 0L

    private var lastSelectedPinPoint: DomainGeoPoint? = null
    private var lastSelectedPinVisible: Boolean? = null
    private var lastWaypoints: List<Waypoint>? = null
    private val badgeDrawableCache = HashMap<Pair<Int, Int>, Drawable>()
    private var lastCruiseVisible: Boolean? = null
    private var lastCruisePoint: DomainGeoPoint? = null
    private var lastCruiseBearing: Float? = null

    private val vectorTileSource: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "AMapVector",
        3,
        18,
        256,
        ".png",
        arrayOf(
            "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
            "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
            "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
            "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8"
        ),
        "© 高德地图"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            val z = MapTileIndex.getZoom(pMapTileIndex)
            return "$baseUrl&x=$x&y=$y&z=$z"
        }
    }

    private val satelliteTileSource: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "AMapSatellite",
        3,
        18,
        256,
        ".jpg",
        arrayOf(
            "https://webst01.is.autonavi.com/appmaptile?style=6",
            "https://webst02.is.autonavi.com/appmaptile?style=6",
            "https://webst03.is.autonavi.com/appmaptile?style=6",
            "https://webst04.is.autonavi.com/appmaptile?style=6"
        ),
        "© 高德卫星"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            val z = MapTileIndex.getZoom(pMapTileIndex)
            return "$baseUrl&x=$x&y=$y&z=$z"
        }
    }

    private val mapEventsOverlay: MapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
            if (p == null) return false
            val wgs = OsmMapCoordinateBridge.toWgs84DomainPoint(p.latitude, p.longitude)
            listener.onMapSingleTap(wgs)
            return true
        }

        override fun longPressHelper(p: OsmGeoPoint?): Boolean {
            if (p == null) return false
            val wgs = OsmMapCoordinateBridge.toWgs84DomainPoint(p.latitude, p.longitude)
            listener.onMapLongPress(wgs)
            return true
        }
    })

    private val routePolyline: Polyline = Polyline(mapView).apply {
        outlinePaint.color = ContextCompat.getColor(appContext, R.color.map_route_line)
        outlinePaint.strokeWidth = dpToPx(4.5f)
        outlinePaint.isAntiAlias = true
        setVisible(false)
    }

    private val waypointMarkers = mutableListOf<Marker>()

    private val selectedPinMarker: Marker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = ContextCompat.getDrawable(appContext, R.drawable.ic_location_pin_36)
        isDraggable = true
        setInfoWindow(null)
        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker?) = Unit
            override fun onMarkerDragStart(marker: Marker?) {
                isUserInteracting = true
            }

            override fun onMarkerDragEnd(marker: Marker?) {
                isUserInteracting = false
                lastInteractionAtMs = System.currentTimeMillis()
                val pos = marker?.position ?: return
                val wgs = OsmMapCoordinateBridge.toWgs84DomainPoint(pos.latitude, pos.longitude)
                listener.onSelectedPinDragged(wgs)
            }
        })
    }

    private val cruiseMarker: Marker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(appContext, R.drawable.ic_navigation_arrow_32)
        isFlat = true
        setInfoWindow(null)
        setPanToView(false)
        setOnMarkerClickListener { _, _ -> false }
        isEnabled = false
        setVisible(false)
    }

    init {
        configureBasePaths(appContext)
        setupMapView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMapView() {
        mapView.setTileSource(vectorTileSource)
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.minZoomLevel = 3.0
        mapView.maxZoomLevel = 19.0
        mapView.controller.setZoom(15.5)

        val defaultPos = toOsmGeoPoint(DomainGeoPoint(39.9087, 116.3975))
        mapView.controller.setCenter(defaultPos)

        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isUserInteracting = true
                    lastInteractionAtMs = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserInteracting = false
                    lastInteractionAtMs = System.currentTimeMillis()
                }
            }
            false
        }

        rebuildOverlaysInOrder()
    }

    private fun rebuildOverlaysInOrder() {
        mapView.overlays.clear()
        // Layer 0: 底图点击与长按事件
        mapView.overlays.add(mapEventsOverlay)
        // Layer 1: 路线 Polyline
        mapView.overlays.add(routePolyline)
        // Layer 2: 编号航点 Markers
        mapView.overlays.addAll(waypointMarkers)
        // Layer 3: 可拖拽单点 Pin
        mapView.overlays.add(selectedPinMarker)
        // Layer 4: 实时巡航方向指示箭头
        mapView.overlays.add(cruiseMarker)
    }

    fun setMapLayer(layer: MapLayerType) {
        if (currentLayer == layer) return
        currentLayer = layer
        mapView.setTileSource(
            if (layer == MapLayerType.SATELLITE) satelliteTileSource else vectorTileSource
        )
        mapView.invalidate()
    }

    fun updateSelectedPin(wgsPoint: DomainGeoPoint, visible: Boolean = true) {
        if (lastSelectedPinPoint == wgsPoint && lastSelectedPinVisible == visible) return
        lastSelectedPinPoint = wgsPoint
        lastSelectedPinVisible = visible
        selectedPinMarker.position = toOsmGeoPoint(wgsPoint)
        selectedPinMarker.isEnabled = visible
        selectedPinMarker.setVisible(visible)
        mapView.invalidate()
    }

    fun updateWaypointsAndRoute(waypoints: List<Waypoint>) {
        if (lastWaypoints == waypoints) return
        lastWaypoints = waypoints.toList()

        val osmPoints = waypoints.map { toOsmGeoPoint(it.point) }
        routePolyline.setPoints(osmPoints)
        routePolyline.setVisible(osmPoints.size >= 2)

        val needsRebuild = waypointMarkers.size != waypoints.size
        while (waypointMarkers.size < waypoints.size) {
            val idx = waypointMarkers.size
            val marker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                setPanToView(false)
            }
            waypointMarkers.add(marker)
            marker.setOnMarkerClickListener { _, _ ->
                listener.onWaypointMarkerClick(idx)
                true
            }
        }
        while (waypointMarkers.size > waypoints.size) {
            waypointMarkers.removeAt(waypointMarkers.lastIndex)
        }

        for (i in waypoints.indices) {
            val marker = waypointMarkers[i]
            marker.position = osmPoints[i]
            marker.isEnabled = true
            marker.icon = badgeDrawableCache.getOrPut(i to waypoints.size) {
                createNumberedBadgeDrawable(i, waypoints.size)
            }
            marker.setOnMarkerClickListener { _, _ ->
                listener.onWaypointMarkerClick(i)
                true
            }
        }

        if (needsRebuild) {
            rebuildOverlaysInOrder()
        }
        mapView.invalidate()
    }

    fun updateCruiseIndicator(
        visible: Boolean,
        wgsPoint: DomainGeoPoint?,
        bearingDeg: Float,
        followCamera: Boolean
    ) {
        val shouldShow = visible && wgsPoint != null
        val rotation = OsmMapCoordinateBridge.toMarkerRotation(bearingDeg)
        val stateUnchanged = lastCruiseVisible == shouldShow &&
            (!shouldShow || (lastCruisePoint == wgsPoint && lastCruiseBearing == rotation))
        if (stateUnchanged) return

        lastCruiseVisible = shouldShow
        lastCruisePoint = wgsPoint
        lastCruiseBearing = rotation

        cruiseMarker.isEnabled = shouldShow
        cruiseMarker.setVisible(shouldShow)
        if (visible && wgsPoint != null) {
            val osmPos = toOsmGeoPoint(wgsPoint)
            cruiseMarker.position = osmPos
            cruiseMarker.rotation = rotation
            val recentlyTouched = (System.currentTimeMillis() - lastInteractionAtMs) < 2500L
            if (followCamera && !isUserInteracting && !recentlyTouched) {
                mapView.controller.animateTo(osmPos)
            }
        }
        mapView.invalidate()
    }

    fun animateToPoint(wgsPoint: DomainGeoPoint, zoom: Double = 16.0) {
        val osmPos = toOsmGeoPoint(wgsPoint)
        if (mapView.zoomLevelDouble < zoom - 0.5) {
            mapView.controller.animateTo(osmPos, zoom, 450L)
        } else {
            mapView.controller.animateTo(osmPos)
        }
    }

    fun fitRoute(waypoints: List<Waypoint>) {
        if (waypoints.isEmpty()) return
        if (waypoints.size == 1) {
            animateToPoint(waypoints.first().point, 16.0)
            return
        }
        val osmPoints = waypoints.map { toOsmGeoPoint(it.point) }
        val minLat = osmPoints.minOf { it.latitude }
        val maxLat = osmPoints.maxOf { it.latitude }
        val minLon = osmPoints.minOf { it.longitude }
        val maxLon = osmPoints.maxOf { it.longitude }
        if (abs(maxLat - minLat) < 1e-5 && abs(maxLon - minLon) < 1e-5) {
            animateToPoint(waypoints.first().point, 16.0)
            return
        }
        val box = BoundingBox.fromGeoPointsSafe(osmPoints)
        mapView.post {
            mapView.zoomToBoundingBox(box, true, dpToPx(48f).toInt())
        }
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onDetach() {
        badgeDrawableCache.clear()
        mapView.setOnTouchListener(null)
        mapView.overlays.clear()
        mapView.onDetach()
    }

    private fun toOsmGeoPoint(wgsPoint: DomainGeoPoint): OsmGeoPoint {
        val (lat, lon) = OsmMapCoordinateBridge.toMapDisplayPoint(wgsPoint)
        return OsmGeoPoint(lat, lon)
    }

    private fun createNumberedBadgeDrawable(index: Int, total: Int): Drawable {
        val sizePx = dpToPx(28f).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        val fillColor = when {
            index == 0 -> ContextCompat.getColor(appContext, R.color.map_marker_start)
            index == total - 1 -> ContextCompat.getColor(appContext, R.color.map_marker_end)
            else -> ContextCompat.getColor(appContext, R.color.map_marker_waypoint)
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius, radius - 1f, borderPaint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius, radius - dpToPx(2f), circlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = selectBadgeTextColor(fillColor)
            textSize = dpToPx(12f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val text = (index + 1).toString()
        val textY = radius - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, radius, textY, textPaint)

        return BitmapDrawable(appContext.resources, bitmap)
    }

    private fun dpToPx(dp: Float): Float = dp * appContext.resources.displayMetrics.density

    companion object {
        internal const val BADGE_TEXT_LIGHT: Int = 0xFFFFFFFF.toInt()
        internal const val BADGE_TEXT_DARK: Int = 0xFF000000.toInt()

        internal fun calculateRelativeLuminance(argbColor: Int): Double {
            fun linearize(channel: Int): Double {
                val srgb = channel / 255.0
                return if (srgb <= 0.04045) {
                    srgb / 12.92
                } else {
                    ((srgb + 0.055) / 1.055).pow(2.4)
                }
            }
            val r = linearize((argbColor ushr 16) and 0xFF)
            val g = linearize((argbColor ushr 8) and 0xFF)
            val b = linearize(argbColor and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        internal fun calculateContrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
            val l1 = calculateRelativeLuminance(foregroundArgb)
            val l2 = calculateRelativeLuminance(backgroundArgb)
            val lighter = maxOf(l1, l2)
            val darker = minOf(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        internal fun selectBadgeTextColor(fillColorArgb: Int): Int {
            val lightContrast = calculateContrastRatio(BADGE_TEXT_LIGHT, fillColorArgb)
            val darkContrast = calculateContrastRatio(BADGE_TEXT_DARK, fillColorArgb)
            return if (lightContrast >= darkContrast) BADGE_TEXT_LIGHT else BADGE_TEXT_DARK
        }

        fun configureBasePaths(context: Context) {
            val appCtx = context.applicationContext
            val baseDir = File(appCtx.cacheDir, "osmdroid")
            val tileDir = File(baseDir, "tiles")
            Configuration.getInstance().apply {
                osmdroidBasePath = baseDir
                osmdroidTileCache = tileDir
                userAgentValue = appCtx.packageName
                isMapViewHardwareAccelerated = true
            }
        }

        suspend fun prepareCacheAsync(context: Context) = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val baseDir = File(appCtx.cacheDir, "osmdroid")
            val tileDir = File(baseDir, "tiles")
            if (!tileDir.exists()) {
                tileDir.mkdirs()
            }
            val prefs = appCtx.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
            Configuration.getInstance().apply {
                load(appCtx, prefs)
                osmdroidBasePath = baseDir
                osmdroidTileCache = tileDir
                userAgentValue = appCtx.packageName
                isMapViewHardwareAccelerated = true
            }
        }
    }
}
