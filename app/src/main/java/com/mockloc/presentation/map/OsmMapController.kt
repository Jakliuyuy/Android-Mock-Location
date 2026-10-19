package com.mockloc.presentation.map

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import com.mockloc.domain.model.GeoPoint as DomainGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * OSMDroid 地图交互控制器。
 * 封装地图基础初始化、触控打点监听、目标图钉 Marker 管理、巡航轨迹绘制与动态平移。
 */
class OsmMapController(
    private val context: Context,
    private val mapView: MapView
) {

    private var mockMarker: Marker? = null
    private var cruiseMarker: Marker? = null
    private var routePolyline: Polyline? = null

    var onLocationPickedListener: ((DomainGeoPoint) -> Unit)? = null

    init {
        initMapDefaults()
    }

    private fun initMapDefaults() {
        // 配置 OSMDroid 全局离线缓存与合规 User-Agent
        Configuration.getInstance().userAgentValue = context.packageName

        // 默认选用国内高速直连的高德矢量图源，规避 OSM 官方海外源在大致区域加载白屏或超时问题
        try {
            val aMapTileSource = object : XYTileSource(
                "AutoNavi-Vector",
                1,
                19,
                256,
                ".png",
                arrayOf(
                    "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&",
                    "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&",
                    "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&",
                    "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&"
                )
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    return (baseUrl
                            + "x=" + MapTileIndex.getX(pMapTileIndex)
                            + "&y=" + MapTileIndex.getY(pMapTileIndex)
                            + "&z=" + MapTileIndex.getZoom(pMapTileIndex))
                }
            }
            mapView.setTileSource(aMapTileSource)
        } catch (_: Throwable) {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }

        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)

        // 添加长按与单击选点事件监听 Overlay
        val eventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean {
                p?.let {
                    val domainPoint = DomainGeoPoint(it.latitude, it.longitude, it.altitude)
                    onLocationPickedListener?.invoke(domainPoint)
                }
                return true
            }

            override fun longPressHelper(p: OsmGeoPoint?): Boolean {
                p?.let {
                    val domainPoint = DomainGeoPoint(it.latitude, it.longitude, it.altitude)
                    onLocationPickedListener?.invoke(domainPoint)
                }
                return true
            }
        }
        val overlay = MapEventsOverlay(eventsReceiver)
        mapView.overlays.add(0, overlay)
    }

    /**
     * 设置或移动单点目标 Marker
     */
    fun setMockPin(point: DomainGeoPoint, title: String = "目标位置", animate: Boolean = true) {
        val osmPoint = OsmGeoPoint(point.latitude, point.longitude, point.altitude)

        if (mockMarker == null) {
            mockMarker = Marker(mapView).apply {
                this.title = title
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(this)
            }
        }

        mockMarker?.let {
            it.position = osmPoint
            it.title = title
            it.snippet = "Lat: ${String.format("%.6f", point.latitude)}, Lng: ${String.format("%.6f", point.longitude)}"
        }

        if (animate) {
            mapView.controller.animateTo(osmPoint)
        }
        mapView.invalidate()
    }

    /**
     * 更新巡航中的动态位置 Marker 及方向朝向
     */
    fun updateCruiseMarker(point: DomainGeoPoint, bearing: Float, follow: Boolean = false) {
        val osmPoint = OsmGeoPoint(point.latitude, point.longitude, point.altitude)

        if (cruiseMarker == null) {
            cruiseMarker = Marker(mapView).apply {
                title = "巡航动态坐标"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(this)
            }
        }

        cruiseMarker?.let {
            it.position = osmPoint
            it.rotation = bearing
        }

        if (follow) {
            mapView.controller.animateTo(osmPoint)
        }
        mapView.invalidate()
    }

    /**
     * 绘制规划轨迹折线
     */
    fun drawRoute(points: List<DomainGeoPoint>) {
        clearRoute()
        if (points.size < 2) return

        val osmPoints = points.map { OsmGeoPoint(it.latitude, it.longitude, it.altitude) }
        val polyline = Polyline(mapView).apply {
            setPoints(osmPoints)
            outlinePaint.color = Color.parseColor("#2979FF")
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }

        routePolyline = polyline
        mapView.overlays.add(polyline)
        mapView.invalidate()
    }

    /**
     * 清除规划路线
     */
    fun clearRoute() {
        routePolyline?.let {
            mapView.overlays.remove(it)
            routePolyline = null
            mapView.invalidate()
        }
    }

    /**
     * 地图中心平移至指定坐标点
     */
    fun centerTo(point: DomainGeoPoint, zoom: Double? = null) {
        val osmPoint = OsmGeoPoint(point.latitude, point.longitude, point.altitude)
        if (zoom != null) {
            mapView.controller.setZoom(zoom)
        }
        mapView.controller.animateTo(osmPoint)
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }
}
