package com.mockloc.presentation

import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.GeoPoint
import com.mockloc.presentation.map.OsmMapCoordinateBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OsmMapCoordinateBridgeTest {

    @Test
    fun `domestic coordinates convert between WGS84 and GCJ02 accurately`() {
        val samples = listOf(
            GeoPoint(39.9087, 116.3975), // 北京天安门
            GeoPoint(31.2397, 121.4998), // 上海陆家嘴
            GeoPoint(22.5431, 114.0579)  // 深圳市民中心
        )

        for (wgs in samples) {
            val (gcjLat, gcjLon) = OsmMapCoordinateBridge.toMapDisplayPoint(wgs)
            assertFalse(
                "Domestic coordinate should apply GCJ-02 offset",
                abs(gcjLat - wgs.latitude) < 1e-5 && abs(gcjLon - wgs.longitude) < 1e-5
            )
            val roundTrip = OsmMapCoordinateBridge.toWgs84DomainPoint(gcjLat, gcjLon)
            assertEquals(wgs.latitude, roundTrip.latitude, 1e-5)
            assertEquals(wgs.longitude, roundTrip.longitude, 1e-5)
        }
    }

    @Test
    fun `overseas coordinates do not apply GCJ02 offset`() {
        val overseas = listOf(
            GeoPoint(35.6762, 139.6503), // 东京
            GeoPoint(40.7128, -74.0060), // 纽约
            GeoPoint(51.5074, -0.1278)   // 伦敦
        )

        for (wgs in overseas) {
            assertTrue(CoordinateConverter.isOutOfChina(wgs.latitude, wgs.longitude))
            val (mapLat, mapLon) = OsmMapCoordinateBridge.toMapDisplayPoint(wgs)
            assertEquals(wgs.latitude, mapLat, 1e-9)
            assertEquals(wgs.longitude, mapLon, 1e-9)

            val back = OsmMapCoordinateBridge.toWgs84DomainPoint(mapLat, mapLon)
            assertEquals(wgs.latitude, back.latitude, 1e-9)
            assertEquals(wgs.longitude, back.longitude, 1e-9)
        }
    }

    @Test
    fun `toMarkerRotation negates normalized bearing to counter OSMDroid fneg`() {
        assertEquals(0f, OsmMapCoordinateBridge.toMarkerRotation(0f), 1e-4f)
        assertEquals(-90f, OsmMapCoordinateBridge.toMarkerRotation(90f), 1e-4f)
        assertEquals(-180f, OsmMapCoordinateBridge.toMarkerRotation(180f), 1e-4f)
        assertEquals(-270f, OsmMapCoordinateBridge.toMarkerRotation(270f), 1e-4f)
        assertEquals(0f, OsmMapCoordinateBridge.toMarkerRotation(360f), 1e-4f)
        assertEquals(-270f, OsmMapCoordinateBridge.toMarkerRotation(-90f), 1e-4f)
        assertEquals(0f, OsmMapCoordinateBridge.toMarkerRotation(Float.NaN), 1e-4f)
    }
}
