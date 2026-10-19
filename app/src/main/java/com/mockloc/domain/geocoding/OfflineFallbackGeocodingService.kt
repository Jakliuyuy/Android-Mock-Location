package com.mockloc.domain.geocoding

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint

/**
 * 内建离线地理编码与逆地理编码服务。
 * 包含中国 50+ 个核心城市与著名地标数据库，具备子串检索、前缀匹配、多音别名及最近邻逆地理编码能力。
 */
class OfflineFallbackGeocodingService : GeocodingService {

    data class LandmarkEntry(
        val name: String,
        val displayName: String,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double = 30.0,
        val type: String = "landmark",
        val aliases: List<String> = emptyList()
    ) {
        fun toGeocodingResult(): GeocodingResult {
            return GeocodingResult(
                name = name,
                displayName = displayName,
                point = GeoPoint(latitude, longitude, altitude),
                type = type
            )
        }
    }

    companion object {
        val LANDMARKS = listOf(
            // 北京
            LandmarkEntry("北京天安门", "北京市东城区天安门广场", 39.9087, 116.3975, 44.0, "landmark", listOf("tiananmen", "beijing")),
            LandmarkEntry("故宫博物院", "北京市东城区景山前街4号", 39.9163, 116.3972, 45.0, "tourism", listOf("forbidden city")),
            LandmarkEntry("颐和园", "北京市海淀区新建宫门路19号", 39.9999, 116.2755, 50.0, "tourism", listOf("summer palace")),
            LandmarkEntry("八达岭长城", "北京市延庆区G6京藏高速58号出口", 40.3598, 116.0152, 600.0, "tourism", listOf("great wall")),
            LandmarkEntry("北京国家体育场鸟巢", "北京市朝阳区国家体育场南路1号", 39.9929, 116.3965, 40.0, "landmark", listOf("bird nest")),

            // 上海
            LandmarkEntry("上海东方明珠", "上海市浦东新区世纪大道1号", 31.2397, 121.4998, 15.0, "landmark", listOf("oriental pearl", "shanghai")),
            LandmarkEntry("上海外滩", "上海市黄浦区中山东一路", 31.2400, 121.4900, 8.0, "tourism", listOf("the bund")),
            LandmarkEntry("上海陆家嘴金融中心", "上海市浦东新区陆家嘴环路", 31.2384, 121.5034, 12.0, "landmark", listOf("lujiazui")),
            LandmarkEntry("上海迪士尼度假区", "上海市浦东新区川沙镇黄赵路310号", 31.1442, 121.6578, 10.0, "tourism", listOf("disneyland")),
            LandmarkEntry("上海人民广场", "上海市黄浦区人民大道", 31.2317, 121.4726, 9.0, "landmark", listOf("people square")),

            // 广州
            LandmarkEntry("广州塔", "广东省广州市海珠区阅江西路222号 (小蛮腰)", 23.1065, 113.3245, 20.0, "landmark", listOf("canton tower", "guangzhou")),
            LandmarkEntry("广州花城广场", "广东省广州市天河区花城大道", 23.1189, 113.3217, 18.0, "landmark", listOf("huacheng square")),
            LandmarkEntry("广州越秀公园", "广东省广州市越秀区解放北路988号", 23.1432, 113.2642, 45.0, "tourism", listOf("yuexiu park")),
            LandmarkEntry("广州白云山风景区", "广东省广州市白云区广园中路", 23.1789, 113.2989, 150.0, "tourism", listOf("baiyun mountain")),

            // 深圳
            LandmarkEntry("深圳市民中心", "广东省深圳市福田区福中三路", 22.5446, 114.0579, 25.0, "landmark", listOf("civic center", "shenzhen")),
            LandmarkEntry("深圳平安金融中心", "广东省深圳市福田区益田路5033号", 22.5332, 114.0545, 30.0, "landmark", listOf("ping an finance center")),
            LandmarkEntry("深圳华强北商业街", "广东省深圳市福田区华强北路", 22.5463, 114.0864, 20.0, "landmark", listOf("huaqiangbei")),
            LandmarkEntry("深圳湾公园", "广东省深圳市南山区滨海大道", 22.4932, 113.9512, 10.0, "tourism", listOf("shenzhen bay")),

            // 杭州
            LandmarkEntry("杭州西湖风景区", "浙江省杭州市西湖区龙井路1号", 30.2435, 120.1417, 15.0, "tourism", listOf("west lake", "hangzhou")),
            LandmarkEntry("杭州灵隐寺", "浙江省杭州市西湖区灵隐路法云弄1号", 30.2422, 120.1009, 50.0, "tourism", listOf("lingyin temple")),
            LandmarkEntry("杭州钱江新城市民中心", "浙江省杭州市上城区新业路", 30.2452, 120.2106, 12.0, "landmark", listOf("qianjiang new town")),

            // 成都
            LandmarkEntry("成都天府广场", "四川省成都市青羊区人民南路一段", 30.6586, 104.0658, 500.0, "landmark", listOf("tianfu square", "chengdu")),
            LandmarkEntry("成都宽窄巷子", "四川省成都市青羊区长顺上街", 30.6667, 104.0531, 502.0, "tourism", listOf("kuanzhai alley")),
            LandmarkEntry("成都春熙路步行街", "四川省成都市锦江区春熙路", 30.6570, 104.0818, 501.0, "landmark", listOf("chunxi road")),
            LandmarkEntry("成都大熊猫繁育研究基地", "四川省成都市成华区熊猫大道1375号", 30.7339, 104.1444, 510.0, "tourism", listOf("panda base")),

            // 武汉
            LandmarkEntry("武汉黄鹤楼", "湖北省武汉市武昌区蛇山脚下", 30.5447, 114.3056, 65.0, "tourism", listOf("yellow crane tower", "wuhan")),
            LandmarkEntry("武汉东湖生态旅游风景区", "湖北省武汉市武昌区东湖路", 30.5604, 114.3853, 35.0, "tourism", listOf("east lake")),
            LandmarkEntry("武汉江汉路步行街", "湖北省武汉市江汉区江汉路", 30.5841, 114.2882, 28.0, "landmark", listOf("jianghan road")),

            // 南京
            LandmarkEntry("南京夫子庙秦淮风光带", "江苏省南京市秦淮区平江府路", 32.0215, 118.7905, 18.0, "tourism", listOf("confucius temple", "nanjing")),
            LandmarkEntry("南京中山陵", "江苏省南京市玄武区石象路7号", 32.0620, 118.8488, 140.0, "tourism", listOf("sun yat-sen mausoleum")),
            LandmarkEntry("南京新街口商业区", "江苏省南京市秦淮区中山南路", 32.0438, 118.7842, 25.0, "landmark", listOf("xinjiekou")),

            // 西安
            LandmarkEntry("西安钟楼", "陕西省西安市碑林区东大街西口", 34.2608, 108.9470, 410.0, "landmark", listOf("bell tower", "xian")),
            LandmarkEntry("西安大雁塔大唐不夜城", "陕西省西安市雁塔区雁塔南路", 34.2185, 108.9642, 420.0, "tourism", listOf("giant wild goose pagoda")),
            LandmarkEntry("西安秦始皇帝陵兵马俑", "陕西省西安市临潼区秦陵北路", 34.3841, 109.2785, 450.0, "tourism", listOf("terracotta warriors")),

            // 重庆
            LandmarkEntry("重庆洪崖洞民俗风貌区", "重庆市渝中区嘉陵江滨江路88号", 29.5629, 106.5818, 210.0, "tourism", listOf("hongyadong", "chongqing")),
            LandmarkEntry("重庆解放碑中央商务区", "重庆市渝中区民权路", 29.5572, 106.5771, 230.0, "landmark", listOf("jiefangbei")),
            LandmarkEntry("重庆朝天门广场", "重庆市渝中区长江滨江路", 29.5684, 106.5878, 190.0, "landmark", listOf("chaotianmen")),

            // 苏州
            LandmarkEntry("苏州拙政园", "江苏省苏州市姑苏区东北街178号", 31.3259, 120.6294, 12.0, "tourism", listOf("humble administrator garden", "suzhou")),
            LandmarkEntry("苏州东方之门大裤衩", "江苏省苏州市苏州工业园区星港街199号", 31.3183, 120.6775, 15.0, "landmark", listOf("gate to the east")),

            // 天津
            LandmarkEntry("天津之眼摩天轮", "天津市红桥区三岔河口", 39.1539, 117.1819, 15.0, "tourism", listOf("tianjin eye", "tianjin")),

            // 青岛
            LandmarkEntry("青岛栈桥", "山东省青岛市市南区太平路12号", 36.0601, 120.3159, 10.0, "tourism", listOf("zhanqiao", "qingdao")),
            LandmarkEntry("青岛五四广场", "山东省青岛市市南区东海西路", 36.0617, 120.3842, 12.0, "landmark", listOf("may fourth square")),

            // 厦门
            LandmarkEntry("厦门鼓浪屿风景名胜区", "福建省厦门市思明区鼓浪屿", 24.4447, 118.0642, 20.0, "tourism", listOf("gulangyu", "xiamen")),

            // 长沙
            LandmarkEntry("长沙橘子洲头毛泽东青年雕塑", "湖南省长沙市岳麓区橘子洲头", 28.1817, 112.9622, 35.0, "tourism", listOf("orange isle", "changsha")),

            // 郑州
            LandmarkEntry("郑州二七纪念塔", "河南省郑州市二七区二七广场", 34.7533, 113.6660, 110.0, "landmark", listOf("erqi tower", "zhengzhou")),

            // 济南
            LandmarkEntry("济南趵突泉风景区", "山东省济南市历下区趵突泉南路1号", 36.6611, 117.0161, 30.0, "tourism", listOf("baotu spring", "jinan")),

            // 合肥
            LandmarkEntry("合肥天鹅湖大剧院", "安徽省合肥市蜀山区东流路", 31.8152, 117.2285, 40.0, "landmark", listOf("swan lake", "hefei")),

            // 福州
            LandmarkEntry("福州三坊七巷历史文化街区", "福建省福州市鼓楼区南后街", 26.0847, 119.2974, 20.0, "tourism", listOf("three lanes seven alleys", "fuzhou")),

            // 昆明
            LandmarkEntry("昆明翠湖公园", "云南省昆明市五华区翠湖南路67号", 25.0489, 102.7051, 1890.0, "tourism", listOf("green lake park", "kunming")),

            // 大理
            LandmarkEntry("大理古城", "云南省大理白族自治州大理市复兴路", 25.6989, 100.1652, 1980.0, "tourism", listOf("dali ancient city")),

            // 三亚
            LandmarkEntry("三亚亚龙湾国家旅游度假区", "海南省三亚市田独镇亚龙湾", 18.2255, 109.6455, 8.0, "tourism", listOf("yalong bay", "sanya")),

            // 哈尔滨
            LandmarkEntry("哈尔滨冰雪大世界", "黑龙江省哈尔滨市松北区太阳岛西侧", 45.7824, 126.5687, 120.0, "tourism", listOf("ice and snow world", "harbin")),

            // 拉萨
            LandmarkEntry("拉萨布达拉宫", "西藏自治区拉萨市城关区北京中路35号", 29.6555, 91.1189, 3650.0, "tourism", listOf("potala palace", "lhasa")),

            // 港澳台
            LandmarkEntry("香港中环金融中心", "香港特别行政区中西区中环金融街8号", 22.2822, 114.1583, 10.0, "landmark", listOf("central", "hong kong")),
            LandmarkEntry("澳门大三巴牌坊", "澳门特别行政区花王堂区炮台山下", 22.1977, 113.5409, 25.0, "tourism", listOf("ruins of st paul", "macau")),
            LandmarkEntry("台北101大厦", "台湾省台北市信义区信义路五段7号", 25.0338, 121.5645, 20.0, "landmark", listOf("taipei 101", "taipei"))
        )
    }

    override suspend fun search(query: String, limit: Int): List<GeocodingResult> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) {
            return emptyList()
        }

        val effectiveLimit = if (limit <= 0) 10 else limit

        // 匹配度排序：精确匹配 > 前缀匹配 > 子串包含匹配
        val scoredResults = LANDMARKS.mapNotNull { landmark ->
            val nameLower = landmark.name.lowercase()
            val descLower = landmark.displayName.lowercase()
            val aliasesLower = landmark.aliases.map { it.lowercase() }

            val score = when {
                nameLower == cleanQuery -> 100
                nameLower.startsWith(cleanQuery) -> 80
                aliasesLower.any { it == cleanQuery } -> 75
                nameLower.contains(cleanQuery) -> 50
                descLower.contains(cleanQuery) -> 30
                aliasesLower.any { it.contains(cleanQuery) } -> 25
                else -> 0
            }

            if (score > 0) Pair(landmark, score) else null
        }

        return scoredResults
            .sortedByDescending { it.second }
            .take(effectiveLimit)
            .map { it.first.toGeocodingResult() }
    }

    override suspend fun reverse(point: GeoPoint): GeocodingResult? {
        if (LANDMARKS.isEmpty()) return null

        var nearestLandmark = LANDMARKS.first()
        var minDistance = SpatialMath.calculateHaversineDistance(
            point,
            GeoPoint(nearestLandmark.latitude, nearestLandmark.longitude)
        )

        for (i in 1 until LANDMARKS.size) {
            val candidate = LANDMARKS[i]
            val dist = SpatialMath.calculateHaversineDistance(
                point,
                GeoPoint(candidate.latitude, candidate.longitude)
            )
            if (dist < minDistance) {
                minDistance = dist
                nearestLandmark = candidate
            }
        }

        // 返回包含相对距离信息的 GeocodingResult
        val distanceKm = minDistance / 1000.0
        val detailedDesc = if (minDistance < 500) {
            nearestLandmark.displayName
        } else {
            "${nearestLandmark.displayName} (距此约 ${String.format("%.1f", distanceKm)} 公里)"
        }

        return GeocodingResult(
            name = nearestLandmark.name,
            displayName = detailedDesc,
            point = GeoPoint(point.latitude, point.longitude, nearestLandmark.altitude),
            type = nearestLandmark.type
        )
    }
}
