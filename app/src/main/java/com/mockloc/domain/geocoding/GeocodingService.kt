package com.mockloc.domain.geocoding

import com.mockloc.domain.model.GeoPoint

/**
 * 地理编码与逆地理编码服务接口。
 * 支持在线 HTTP 查询与离线高可用降级。
 */
interface GeocodingService {

    /**
     * 根据关键字查询地点列表
     *
     * @param query 搜索关键词 (如 "天安门", "上海", "西湖")
     * @param limit 返回候选结果的最大数量
     * @return 匹配的地点列表
     */
    suspend fun search(query: String, limit: Int = 10): List<GeocodingResult>

    /**
     * 根据坐标点逆向查询地点信息
     *
     * @param point 待查询的坐标点
     * @return 对应的地点信息，若无匹配则返回 null
     */
    suspend fun reverse(point: GeoPoint): GeocodingResult?

    /**
     * 根据经纬度逆向查询地点信息的便捷方法
     */
    suspend fun reverse(latitude: Double, longitude: Double): GeocodingResult? =
        reverse(GeoPoint(latitude, longitude))
}
