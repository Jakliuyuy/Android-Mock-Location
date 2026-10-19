package com.mockloc.domain.geocoding

import com.mockloc.domain.model.GeoPoint

/**
 * 地理编码与地点检索结果数据模型。
 *
 * @property name 地点或地标简要名称 (如 "北京天安门", "西湖")
 * @property displayName 包含行政区划的详细展示名称
 * @property point 对应的地理经纬度坐标与海拔
 * @property type 地点类别 (如 "poi", "city", "landmark", "tourism")
 */
data class GeocodingResult(
    val name: String,
    val displayName: String,
    val point: GeoPoint,
    val type: String = "poi"
)
