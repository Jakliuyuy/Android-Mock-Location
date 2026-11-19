package com.mockloc.domain.geocoding

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import java.util.Locale

class OfflineFallbackGeocodingService : GeocodingService {

    internal data class OfflinePlaceEntry(
        val name: String,
        val city: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val pinyin: String,
        val initials: String,
        val aliases: List<String> = emptyList(),
        val priority: Int = 0
    ) {
        fun toGeocodingResult(source: String = SOURCE_FALLBACK): GeocodingResult {
            return GeocodingResult(
                name = name,
                address = address,
                latitude = latitude,
                longitude = longitude,
                coordinateType = CoordinateType.WGS84,
                city = city,
                source = source
            )
        }
    }

    override suspend fun search(query: String): Result<List<GeocodingResult>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return Result.success(emptyList())
        }

        val normalizedQ = trimmed.lowercase(Locale.ROOT)
        val compactQ = WHITESPACE_REGEX.replace(normalizedQ, "")
        val tokens = WHITESPACE_REGEX.split(normalizedQ).filter { it.isNotEmpty() }

        val scored = ArrayList<Pair<Int, OfflinePlaceEntry>>()
        for (entry in PLACES) {
            val score = scoreEntry(entry, normalizedQ, compactQ, tokens)
            if (score > 0) {
                scored.add(score to entry)
            }
        }

        scored.sortWith(
            Comparator { a, b ->
                val scoreCmp = b.first.compareTo(a.first)
                if (scoreCmp != 0) return@Comparator scoreCmp
                val prioCmp = b.second.priority.compareTo(a.second.priority)
                if (prioCmp != 0) return@Comparator prioCmp
                a.second.name.compareTo(b.second.name)
            }
        )

        val limited = scored.take(MAX_RESULTS).map { it.second.toGeocodingResult() }
        return Result.success(limited)
    }

    override suspend fun reverseGeocode(lat: Double, lon: Double): Result<GeocodingResult?> {
        if (!GeoPoint.isValid(lat, lon)) {
            return Result.success(null)
        }

        var nearest: OfflinePlaceEntry? = null
        var minDistance = Double.MAX_VALUE

        for (entry in PLACES) {
            val dist = SpatialMath.calculateHaversineDistance(lat, lon, entry.latitude, entry.longitude)
            if (dist < minDistance) {
                minDistance = dist
                nearest = entry
            }
        }

        val target = nearest ?: return Result.success(
            buildOffshoreCoordResult(lat, lon)
        )

        val result = when {
            minDistance < NEAR_RADIUS_METERS -> {
                GeocodingResult(
                    name = target.name,
                    address = "${target.address}（距中心约 ${minDistance.toInt()} 米）",
                    latitude = lat,
                    longitude = lon,
                    coordinateType = CoordinateType.WGS84,
                    city = target.city,
                    source = SOURCE_NEAREST
                )
            }
            minDistance <= REGIONAL_RADIUS_METERS -> {
                val bearing = SpatialMath.calculateBearing(
                    target.latitude,
                    target.longitude,
                    lat,
                    lon
                )
                val direction = bearingToChineseDirection(bearing)
                val distText = formatDistanceText(minDistance)
                GeocodingResult(
                    name = "${target.name}${direction}方向约${distText}",
                    address = "${target.address}（${direction}方向约${distText}）",
                    latitude = lat,
                    longitude = lon,
                    coordinateType = CoordinateType.WGS84,
                    city = target.city,
                    source = SOURCE_NEAREST
                )
            }
            else -> buildOffshoreCoordResult(lat, lon)
        }

        return Result.success(result)
    }

    private fun buildOffshoreCoordResult(lat: Double, lon: Double): GeocodingResult {
        return GeocodingResult(
            name = String.format(Locale.US, "位置 (%.4f, %.4f)", lat, lon),
            address = "WGS-84 离线坐标位置",
            latitude = lat,
            longitude = lon,
            coordinateType = CoordinateType.WGS84,
            city = "",
            source = SOURCE_COORD
        )
    }

    private fun scoreEntry(
        entry: OfflinePlaceEntry,
        normalizedQ: String,
        compactQ: String,
        tokens: List<String>
    ): Int {
        val nameLower = entry.name.lowercase(Locale.ROOT)
        val cityLower = entry.city.lowercase(Locale.ROOT)
        val addrLower = entry.address.lowercase(Locale.ROOT)
        val pinyinLower = entry.pinyin.lowercase(Locale.ROOT)
        val initialsLower = entry.initials.lowercase(Locale.ROOT)
        val aliasesLower = entry.aliases.map { it.lowercase(Locale.ROOT) }

        var bestScore = 0

        // 1. 精确命中
        when {
            nameLower == normalizedQ || nameLower == compactQ -> bestScore = maxOf(bestScore, 1000)
            aliasesLower.any { it == normalizedQ || it == compactQ } -> bestScore = maxOf(bestScore, 950)
            pinyinLower == compactQ -> bestScore = maxOf(bestScore, 900)
            initialsLower == compactQ -> bestScore = maxOf(bestScore, 880)
        }

        // 2. 前缀命中
        when {
            nameLower.startsWith(normalizedQ) || nameLower.startsWith(compactQ) -> bestScore = maxOf(bestScore, 750)
            aliasesLower.any { it.startsWith(normalizedQ) || it.startsWith(compactQ) } -> bestScore = maxOf(bestScore, 700)
            pinyinLower.startsWith(compactQ) -> bestScore = maxOf(bestScore, 650)
            initialsLower.startsWith(compactQ) -> bestScore = maxOf(bestScore, 620)
        }

        // 3. 子串命中
        when {
            nameLower.contains(normalizedQ) || nameLower.contains(compactQ) -> bestScore = maxOf(bestScore, 520)
            aliasesLower.any { it.contains(normalizedQ) || it.contains(compactQ) } -> bestScore = maxOf(bestScore, 480)
            cityLower.contains(normalizedQ) || cityLower.contains(compactQ) -> bestScore = maxOf(bestScore, 400)
            pinyinLower.contains(compactQ) -> bestScore = maxOf(bestScore, 350)
            addrLower.contains(normalizedQ) -> bestScore = maxOf(bestScore, 320)
            initialsLower.contains(compactQ) -> bestScore = maxOf(bestScore, 300)
        }

        // 4. 空格分词全命中
        if (tokens.size > 1) {
            val allTokensMatched = tokens.all { token ->
                nameLower.contains(token) ||
                    cityLower.contains(token) ||
                    addrLower.contains(token) ||
                    pinyinLower.contains(token) ||
                    initialsLower.contains(token) ||
                    aliasesLower.any { it.contains(token) }
            }
            if (allTokensMatched) {
                bestScore = maxOf(bestScore, 420 + tokens.size * 30)
            }
        }

        // 5. 有序子序列模糊匹配
        if (bestScore == 0 && compactQ.length >= 2) {
            val matchedInName = isSubsequence(compactQ, nameLower)
            val matchedInAlias = aliasesLower.any { isSubsequence(compactQ, it) }
            val matchedInPinyin = compactQ.length >= 3 && isSubsequence(compactQ, pinyinLower)
            val matchedInInitials = isSubsequence(compactQ, initialsLower)

            when {
                matchedInName -> {
                    val coverageBonus = (compactQ.length * 40) / nameLower.length.coerceAtLeast(1)
                    bestScore = 180 + coverageBonus
                }
                matchedInAlias -> bestScore = 165
                matchedInPinyin -> {
                    val coverageBonus = (compactQ.length * 30) / pinyinLower.length.coerceAtLeast(1)
                    bestScore = 150 + coverageBonus
                }
                matchedInInitials -> bestScore = 140
            }
        }

        return if (bestScore > 0) bestScore + entry.priority else 0
    }

    private fun isSubsequence(pattern: String, text: String): Boolean {
        if (pattern.isEmpty() || pattern.length > text.length) return false
        var pIdx = 0
        for (i in text.indices) {
            if (text[i] == pattern[pIdx]) {
                pIdx++
                if (pIdx == pattern.length) return true
            }
        }
        return false
    }

    private fun bearingToChineseDirection(bearingDeg: Float): String {
        val normalized = ((bearingDeg % 360f) + 360f) % 360f
        val index = (((normalized + 22.5f) / 45f).toInt()) % 8
        return DIRECTIONS_8[index]
    }

    private fun formatDistanceText(meters: Double): String {
        return if (meters < 1000.0) {
            "${meters.toInt().coerceAtLeast(1)}米"
        } else {
            String.format(Locale.US, "%.1f公里", meters / 1000.0)
        }
    }

    companion object {
        const val SOURCE_FALLBACK = "offline_fallback"
        const val SOURCE_NEAREST = "offline_nearest"
        const val SOURCE_COORD = "offline_coord"

        private const val MAX_RESULTS = 20
        private const val NEAR_RADIUS_METERS = 500.0
        private const val REGIONAL_RADIUS_METERS = 500_000.0
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val DIRECTIONS_8 = arrayOf("正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北")

        internal val PLACES: List<OfflinePlaceEntry> = listOf(
            // ========== 1. 直辖市 / 特别行政区 / 省会与首府（34 条，权威 WGS-84）==========
            OfflinePlaceEntry("北京市", "北京市", "北京市东城区正义路2号", 39.9042, 116.4074, "beijingshi", "bjs", listOf("北京", "beijing", "bj", "peking"), 50),
            OfflinePlaceEntry("上海市", "上海市", "上海市黄浦区人民大道200号", 31.2304, 121.4737, "shanghaishi", "shs", listOf("上海", "shanghai", "sh"), 50),
            OfflinePlaceEntry("天津市", "天津市", "天津市河西区友谊路30号", 39.0842, 117.2010, "tianjinshi", "tjs", listOf("天津", "tianjin", "tj"), 40),
            OfflinePlaceEntry("重庆市", "重庆市", "重庆市渝中区人民路232号", 29.5630, 106.5516, "chongqingshi", "cqs", listOf("重庆", "chongqing", "cq"), 45),
            OfflinePlaceEntry("香港", "香港", "香港特别行政区中西区添马", 22.2793, 114.1628, "xianggang", "xg", listOf("香港特别行政区", "hongkong", "hk"), 45),
            OfflinePlaceEntry("澳门", "澳门", "澳门特别行政区风顺堂区南湾大马路", 22.1987, 113.5439, "aomen", "am", listOf("澳门特别行政区", "macau", "mo"), 40),
            OfflinePlaceEntry("台北市", "台北市", "台湾省台北市信义区市府路1号", 25.0330, 121.5654, "taibeishi", "tbs", listOf("台北", "taibei", "taipei"), 40),
            OfflinePlaceEntry("石家庄市", "石家庄市", "河北省石家庄市长安区中山东路216号", 38.0428, 114.5149, "shijiazhuangshi", "sjzs", listOf("石家庄", "shijiazhuang", "sjz"), 35),
            OfflinePlaceEntry("太原市", "太原市", "山西省太原市杏花岭区新建路89号", 37.8706, 112.5489, "taiyuanshi", "tys", listOf("太原", "taiyuan", "ty"), 35),
            OfflinePlaceEntry("呼和浩特市", "呼和浩特市", "内蒙古自治区呼和浩特市新城区新华东街1号", 40.8424, 111.7492, "huhehaoteshi", "hhhts", listOf("呼和浩特", "huhehaote", "hhht"), 35),
            OfflinePlaceEntry("沈阳市", "沈阳市", "辽宁省沈阳市浑南区沈中大街206号", 41.8057, 123.4315, "shenyangshi", "sys", listOf("沈阳", "shenyang", "sy"), 40),
            OfflinePlaceEntry("长春市", "长春市", "吉林省长春市南关区人民大街10111号", 43.8171, 125.3235, "changchunshi", "ccs", listOf("长春", "changchun", "cc"), 35),
            OfflinePlaceEntry("哈尔滨市", "哈尔滨市", "黑龙江省哈尔滨市松北区世纪大道1号", 45.8038, 126.5349, "haerbinshi", "hebs", listOf("哈尔滨", "haerbin", "heb", "harbin"), 40),
            OfflinePlaceEntry("南京市", "南京市", "江苏省南京市玄武区北京东路41号", 32.0603, 118.7969, "nanjingshi", "njs", listOf("南京", "nanjing", "nj"), 45),
            OfflinePlaceEntry("杭州市", "杭州市", "浙江省杭州市上城区解放东路18号", 30.2741, 120.1551, "hangzhoushi", "hzs", listOf("杭州", "hangzhou", "hz"), 45),
            OfflinePlaceEntry("合肥市", "合肥市", "安徽省合肥市蜀山区东流路100号", 31.8206, 117.2272, "hefeishi", "hfs", listOf("合肥", "hefei", "hf"), 40),
            OfflinePlaceEntry("福州市", "福州市", "福建省福州市鼓楼区乌山路96号", 26.0745, 119.2965, "fuzhoushi", "fzs", listOf("福州", "fuzhou", "fz"), 35),
            OfflinePlaceEntry("南昌市", "南昌市", "江西省南昌市红谷滩区新府路118号", 28.6829, 115.8582, "nanchangshi", "ncs", listOf("南昌", "nanchang", "nc"), 35),
            OfflinePlaceEntry("济南市", "济南市", "山东省济南市历下区龙鼎大道1号", 36.6512, 117.1201, "jinanshi", "jns", listOf("济南", "jinan", "jn"), 40),
            OfflinePlaceEntry("郑州市", "郑州市", "河南省郑州市中原区中原西路233号", 34.7466, 113.6253, "zhengzhoushi", "zzs", listOf("郑州", "zhengzhou", "zz"), 40),
            OfflinePlaceEntry("武汉市", "武汉市", "湖北省武汉市江岸区沿江大道188号", 30.5928, 114.3055, "wuhanshi", "whs", listOf("武汉", "wuhan", "wh"), 45),
            OfflinePlaceEntry("长沙市", "长沙市", "湖南省长沙市岳麓区岳麓大道218号", 28.2282, 112.9388, "changshashi", "css", listOf("长沙", "changsha", "cs"), 40),
            OfflinePlaceEntry("广州市", "广州市", "广东省广州市越秀区府前路1号", 23.1291, 113.2644, "guangzhoushi", "gzs", listOf("广州", "guangzhou", "gz"), 50),
            OfflinePlaceEntry("南宁市", "南宁市", "广西壮族自治区南宁市青秀区嘉宾路1号", 22.8170, 108.3665, "nanningshi", "nns", listOf("南宁", "nanning", "nn"), 35),
            OfflinePlaceEntry("海口市", "海口市", "海南省海口市秀英区长滨一路8号", 20.0440, 110.1999, "haikoushi", "hks", listOf("海口", "haikou", "hk"), 35),
            OfflinePlaceEntry("成都市", "成都市", "四川省成都市武侯区锦悦西路2号", 30.5728, 104.0668, "chengdushi", "cds", listOf("成都", "chengdu", "cd"), 45),
            OfflinePlaceEntry("贵阳市", "贵阳市", "贵州省贵阳市观山湖区林城东路市级行政中心", 26.6470, 106.6302, "guiyangshi", "gys", listOf("贵阳", "guiyang", "gy"), 35),
            OfflinePlaceEntry("昆明市", "昆明市", "云南省昆明市呈贡区锦绣大街1号", 25.0389, 102.7183, "kunmingshi", "kms", listOf("昆明", "kunming", "km"), 40),
            OfflinePlaceEntry("拉萨市", "拉萨市", "西藏自治区拉萨市城关区江苏大道69号", 29.6525, 91.1721, "lasashi", "lss", listOf("拉萨", "lasa", "ls", "lhasa"), 35),
            OfflinePlaceEntry("西安市", "西安市", "陕西省西安市未央区凤城八路109号", 34.3416, 108.9398, "xianshi", "xas", listOf("西安", "xian", "xa"), 45),
            OfflinePlaceEntry("兰州市", "兰州市", "甘肃省兰州市城关区南滨河东路735号", 36.0611, 103.8343, "lanzhoushi", "lzs", listOf("兰州", "lanzhou", "lz"), 35),
            OfflinePlaceEntry("西宁市", "西宁市", "青海省西宁市城中区南关街43号", 36.6171, 101.7782, "xiningshi", "xns", listOf("西宁", "xining", "xn"), 35),
            OfflinePlaceEntry("银川市", "银川市", "宁夏回族自治区银川市金凤区北京中路166号", 38.4872, 106.2309, "yinchuanshi", "ycs", listOf("银川", "yinchuan", "yc"), 35),
            OfflinePlaceEntry("乌鲁木齐市", "乌鲁木齐市", "新疆维吾尔自治区乌鲁木齐市水磨沟区准噶尔街299号", 43.8256, 87.6168, "wulumuqishi", "wlmqs", listOf("乌鲁木齐", "wulumuqi", "wlmq", "urumqi"), 35),

            // ========== 2. 主要地级市 / 经济与交通枢纽城市（56 条）==========
            OfflinePlaceEntry("深圳市", "深圳市", "广东省深圳市福田区福中三路市民中心", 22.5431, 114.0579, "shenzhenshi", "szs", listOf("深圳", "shenzhen", "sz"), 50),
            OfflinePlaceEntry("苏州市", "苏州市", "江苏省苏州市姑苏区三香路998号", 31.2989, 120.5853, "suzhoushi", "szs", listOf("苏州", "suzhou", "sz"), 40),
            OfflinePlaceEntry("无锡市", "无锡市", "江苏省无锡市滨湖区观山路199号", 31.4912, 120.3119, "wuxishi", "wxs", listOf("无锡", "wuxi", "wx"), 30),
            OfflinePlaceEntry("常州市", "常州市", "江苏省常州市新北区龙城大道1280号", 31.8112, 119.9741, "changzhoushi", "czs", listOf("常州", "changzhou"), 25),
            OfflinePlaceEntry("徐州市", "徐州市", "江苏省徐州市云龙区昆仑大道1号", 34.2044, 117.2841, "xuzhoushi", "xzs", listOf("徐州", "xuzhou"), 25),
            OfflinePlaceEntry("南通市", "南通市", "江苏省南通市崇川区世纪大道6号", 31.9802, 120.8943, "nantongshi", "nts", listOf("南通", "nantong"), 25),
            OfflinePlaceEntry("扬州市", "扬州市", "江苏省扬州市邗江区文昌西路8号", 32.3942, 119.4129, "yangzhoushi", "yzs", listOf("扬州", "yangzhou"), 25),
            OfflinePlaceEntry("镇江市", "镇江市", "江苏省镇江市润州区南徐大道68号", 32.1879, 119.4258, "zhenjiangshi", "zjs", listOf("镇江", "zhenjiang"), 20),
            OfflinePlaceEntry("宁波市", "宁波市", "浙江省宁波市鄞州区宁穿路2001号", 29.8683, 121.5440, "ningboshi", "nbs", listOf("宁波", "ningbo", "nb"), 35),
            OfflinePlaceEntry("温州市", "温州市", "浙江省温州市鹿城区绣山路321号", 27.9943, 120.6994, "wenzhoushi", "wzs", listOf("温州", "wenzhou"), 30),
            OfflinePlaceEntry("嘉兴市", "嘉兴市", "浙江省嘉兴市南湖区广场路1号", 30.7468, 120.7555, "jiaxingshi", "jxs", listOf("嘉兴", "jiaxing"), 25),
            OfflinePlaceEntry("绍兴市", "绍兴市", "浙江省绍兴市越城区洋江西路589号", 30.0303, 120.5802, "shaoxingshi", "sxs", listOf("绍兴", "shaoxing"), 25),
            OfflinePlaceEntry("金华市", "金华市", "浙江省金华市婺城区双龙南街801号", 29.0792, 119.6474, "jinhuashi", "jhs", listOf("金华", "jinhua"), 20),
            OfflinePlaceEntry("台州市", "台州市", "浙江省台州市椒江区市府大道1号", 28.6564, 121.4208, "taizhoushi", "tzs", listOf("台州", "taizhou"), 20),
            OfflinePlaceEntry("芜湖市", "芜湖市", "安徽省芜湖市鸠江区政通路66号", 31.3529, 118.4331, "wuhushi", "whs", listOf("芜湖", "wuhu"), 20),
            OfflinePlaceEntry("马鞍山市", "马鞍山市", "安徽省马鞍山市雨山区太白大道2008号", 31.6705, 118.5068, "maanshanshi", "mass", listOf("马鞍山", "maanshan"), 20),
            OfflinePlaceEntry("珠海市", "珠海市", "广东省珠海市香洲区人民东路2号", 22.2707, 113.5767, "zhuhaishi", "zhs", listOf("珠海", "zhuhai"), 30),
            OfflinePlaceEntry("佛山市", "佛山市", "广东省佛山市禅城区岭南大道北12号", 23.0215, 113.1214, "foshanshi", "fss", listOf("佛山", "foshan"), 30),
            OfflinePlaceEntry("东莞市", "东莞市", "广东省东莞市南城街道鸿福路99号", 23.0207, 113.7518, "dongguanshi", "dgs", listOf("东莞", "dongguan"), 30),
            OfflinePlaceEntry("中山市", "中山市", "广东省中山市东区松苑路1号", 22.5170, 113.3926, "zhongshanshi", "zss", listOf("中山", "zhongshan"), 25),
            OfflinePlaceEntry("惠州市", "惠州市", "广东省惠州市惠城区云山西路6号", 23.1118, 114.4162, "huizhoushi", "hzs", listOf("惠州", "huizhou"), 25),
            OfflinePlaceEntry("江门市", "江门市", "广东省江门市蓬江区白沙大道西1号", 22.5787, 113.0815, "jiangmenshi", "jms", listOf("江门", "jiangmen"), 20),
            OfflinePlaceEntry("汕头市", "汕头市", "广东省汕头市金平区跃进路28号", 23.3541, 116.6820, "shantoushi", "sts", listOf("汕头", "shantou"), 20),
            OfflinePlaceEntry("湛江市", "湛江市", "广东省湛江市赤坎区跃进路67号", 21.2707, 110.3594, "zhanjiangshi", "zjs", listOf("湛江", "zhanjiang"), 20),
            OfflinePlaceEntry("厦门市", "厦门市", "福建省厦门市思明区湖滨北路61号", 24.4798, 118.0894, "xiamenshi", "xms", listOf("厦门", "xiamen", "xm"), 35),
            OfflinePlaceEntry("泉州市", "泉州市", "福建省泉州市丰泽区府西路1号", 24.8741, 118.6757, "quanzhoushi", "qzs", listOf("泉州", "quanzhou"), 25),
            OfflinePlaceEntry("漳州市", "漳州市", "福建省漳州市芗城区胜利西路118号", 24.5130, 117.6471, "zhangzhoushi", "zzs", listOf("漳州", "zhangzhou"), 20),
            OfflinePlaceEntry("青岛市", "青岛市", "山东省青岛市市南区香港中路11号", 36.0671, 120.3826, "qingdaoshi", "qds", listOf("青岛", "qingdao", "qd"), 40),
            OfflinePlaceEntry("烟台市", "烟台市", "山东省烟台市莱山区芙蓉路6号", 37.4638, 121.4479, "yantaishi", "yts", listOf("烟台", "yantai"), 25),
            OfflinePlaceEntry("威海市", "威海市", "山东省威海市环翠区新威路1号", 37.5131, 122.1204, "weihaishi", "whs", listOf("威海", "weihai"), 25),
            OfflinePlaceEntry("潍坊市", "潍坊市", "山东省潍坊市奎文区胜利东街99号", 36.7067, 119.1619, "weifangshi", "wfs", listOf("潍坊", "weifang"), 20),
            OfflinePlaceEntry("淄博市", "淄博市", "山东省淄博市张店区人民西路8号", 36.8135, 118.0549, "ziboshi", "zbs", listOf("淄博", "zibo"), 20),
            OfflinePlaceEntry("临沂市", "临沂市", "山东省临沂市兰山区北京路17号", 35.1047, 118.3564, "linyishi", "lys", listOf("临沂", "linyi"), 20),
            OfflinePlaceEntry("济宁市", "济宁市", "山东省济宁市任城区太白湖新区省运会指挥中心", 35.4149, 116.5872, "jiningshi", "jns", listOf("济宁", "jining"), 20),
            OfflinePlaceEntry("保定市", "保定市", "河北省保定市竞秀区东风西路5号", 38.8740, 115.4646, "baodingshi", "bds", listOf("保定", "baoding"), 25),
            OfflinePlaceEntry("唐山市", "唐山市", "河北省唐山市路北区西山道3号", 39.6305, 118.1802, "tangshanshi", "tss", listOf("唐山", "tangshan"), 25),
            OfflinePlaceEntry("廊坊市", "廊坊市", "河北省廊坊市广阳区广阳道230号", 39.5380, 116.6838, "langfangshi", "lfs", listOf("廊坊", "langfang"), 20),
            OfflinePlaceEntry("秦皇岛市", "秦皇岛市", "河北省秦皇岛市海港区翠岛大街1号", 39.9354, 119.6005, "qinhuangdaoshi", "qhds", listOf("秦皇岛", "qinhuangdao"), 25),
            OfflinePlaceEntry("邯郸市", "邯郸市", "河北省邯郸市丛台区人民东路342号", 36.6256, 114.5390, "handanshi", "hds", listOf("邯郸", "handan"), 20),
            OfflinePlaceEntry("大同市", "大同市", "山西省大同市平城区兴云街2799号", 40.0768, 113.3001, "datongshi", "dts", listOf("大同", "datong"), 20),
            OfflinePlaceEntry("包头市", "包头市", "内蒙古自治区包头市九原区开元大街1号", 40.6574, 109.8404, "baotoushi", "bts", listOf("包头", "baotou"), 20),
            OfflinePlaceEntry("鄂尔多斯市", "鄂尔多斯市", "内蒙古自治区鄂尔多斯市康巴什区湖滨路", 39.6083, 109.7813, "eerduosishi", "eedss", listOf("鄂尔多斯", "eerduosi"), 20),
            OfflinePlaceEntry("大连市", "大连市", "辽宁省大连市西岗区人民广场1号", 38.9140, 121.6147, "dalianshi", "dls", listOf("大连", "dalian", "dl"), 35),
            OfflinePlaceEntry("鞍山市", "鞍山市", "辽宁省鞍山市铁东区胜利南路25号", 41.1087, 122.9946, "anshanshi", "ass", listOf("鞍山", "anshan"), 20),
            OfflinePlaceEntry("吉林市", "吉林市", "吉林省吉林市船营区松江中路65号", 43.8378, 126.5496, "jilinshi", "jls", listOf("吉林", "jilin"), 20),
            OfflinePlaceEntry("大庆市", "大庆市", "黑龙江省大庆市萨尔图区东风路19号", 46.5902, 125.1038, "daqingshi", "dqs", listOf("大庆", "daqing"), 20),
            OfflinePlaceEntry("齐齐哈尔市", "齐齐哈尔市", "黑龙江省齐齐哈尔市建华区新明大街27号", 47.3543, 123.9182, "qiqihaershi", "qqhes", listOf("齐齐哈尔", "qiqihaer"), 20),
            OfflinePlaceEntry("洛阳市", "洛阳市", "河南省洛阳市洛龙区开元大道228号", 34.6197, 112.4540, "luoyangshi", "lys", listOf("洛阳", "luoyang"), 30),
            OfflinePlaceEntry("开封市", "开封市", "河南省开封市城乡一体化示范区晋安路158号", 34.7972, 114.3076, "kaifengshi", "kfs", listOf("开封", "kaifeng"), 25),
            OfflinePlaceEntry("宜昌市", "宜昌市", "湖北省宜昌市西陵区沿江大道102号", 30.6919, 111.2865, "yichangshi", "ycs", listOf("宜昌", "yichang"), 25),
            OfflinePlaceEntry("襄阳市", "襄阳市", "湖北省襄阳市襄城区荆州街73号", 32.0090, 112.1224, "xiangyangshi", "xys", listOf("襄阳", "xiangyang"), 25),
            OfflinePlaceEntry("荆州市", "荆州市", "湖北省荆州市沙市区江津西路262号", 30.3351, 112.2397, "jingzhoushi", "jzs", listOf("荆州", "jingzhou"), 20),
            OfflinePlaceEntry("株洲市", "株洲市", "湖南省株洲市天元区天台路58号", 27.8274, 113.1338, "zhuzhoushi", "zzs", listOf("株洲", "zhuzhou"), 20),
            OfflinePlaceEntry("湘潭市", "湘潭市", "湖南省湘潭市岳塘区双拥中路1号", 27.8297, 112.9440, "xiangtanshi", "xts", listOf("湘潭", "xiangtan"), 20),
            OfflinePlaceEntry("岳阳市", "岳阳市", "湖南省岳阳市岳阳楼区金鹗中路235号", 29.3571, 113.1289, "yueyangshi", "yys", listOf("岳阳", "yueyang"), 20),
            OfflinePlaceEntry("衡阳市", "衡阳市", "湖南省衡阳市蒸湘区延安路22号", 26.8932, 112.5719, "hengyangshi", "hys", listOf("衡阳", "hengyang"), 20),
            OfflinePlaceEntry("赣州市", "赣州市", "江西省赣州市章贡区长征大道8号", 25.8311, 114.9350, "ganzhoushi", "gzs", listOf("赣州", "ganzhou"), 20),
            OfflinePlaceEntry("九江市", "九江市", "江西省九江市浔阳区长虹大道32号", 29.7051, 116.0019, "jiujiangshi", "jjs", listOf("九江", "jiujiang"), 20),
            OfflinePlaceEntry("绵阳市", "绵阳市", "四川省绵阳市涪城区临园路东段72号", 31.4675, 104.6791, "mianyangshi", "mys", listOf("绵阳", "mianyang"), 25),
            OfflinePlaceEntry("宜宾市", "宜宾市", "四川省宜宾市叙州区南岸蜀南大道东段", 28.7513, 104.6431, "yibinshi", "ybs", listOf("宜宾", "yibin"), 20),
            OfflinePlaceEntry("泸州市", "泸州市", "四川省泸州市江阳区大山坪路南段1号", 28.8718, 105.4423, "luzhoushi", "lzs", listOf("泸州", "luzhou"), 20),
            OfflinePlaceEntry("遵义市", "遵义市", "贵州省遵义市新蒲新区府前路行政中心", 27.7257, 106.9273, "zunyishi", "zys", listOf("遵义", "zunyi"), 25),
            OfflinePlaceEntry("六盘水市", "六盘水市", "贵州省六盘水市钟山区钟山中路", 26.5944, 104.8333, "liupanshuishi", "lpss", listOf("六盘水", "凉都", "liupanshui", "lps"), 28),
            OfflinePlaceEntry("毕节市", "毕节市", "贵州省毕节市七星关区碧阳大道", 27.3019, 105.2850, "bijieshi", "bjs", listOf("毕节", "bijie"), 20),
            OfflinePlaceEntry("安顺市", "安顺市", "贵州省安顺市西秀区黄果树大街", 26.2537, 105.9476, "anshunshi", "ass", listOf("安顺", "anshun"), 20),
            OfflinePlaceEntry("铜仁市", "铜仁市", "贵州省铜仁市碧江区花果山中路", 27.7315, 109.1896, "tongrenshi", "trs", listOf("铜仁", "tongren"), 20),
            OfflinePlaceEntry("日喀则市", "日喀则市", "西藏自治区日喀则市桑珠孜区山东路", 29.2669, 88.8806, "rikazeshi", "rkzs", listOf("日喀则", "rikaze"), 20),
            OfflinePlaceEntry("林芝市", "林芝市", "西藏自治区林芝市巴宜区八一大街", 29.6491, 94.3615, "linzhishi", "lzs", listOf("林芝", "linzhi"), 20),
            OfflinePlaceEntry("曲靖市", "曲靖市", "云南省曲靖市麒麟区文昌街", 25.4900, 103.7962, "qujingshi", "qjs", listOf("曲靖", "qujing"), 20),
            OfflinePlaceEntry("乐山市", "乐山市", "四川省乐山市市中区滨河路", 29.5521, 103.7656, "leshanshi", "lss", listOf("乐山", "leshan"), 20),
            OfflinePlaceEntry("湛江市", "湛江市", "广东省湛江市赤坎区跃进路", 21.2707, 110.3594, "zhanjiangshi", "zjs", listOf("湛江", "zhanjiang"), 20),
            OfflinePlaceEntry("大理市", "大理白族自治州", "云南省大理白族自治州大理市龙山行政区", 25.6065, 100.2676, "dalishi", "dls", listOf("大理", "dali"), 25),
            OfflinePlaceEntry("丽江市", "丽江市", "云南省丽江市古城区雪山路", 26.8550, 100.2278, "lijiangshi", "ljs", listOf("丽江", "lijiang"), 25),
            OfflinePlaceEntry("桂林市", "桂林市", "广西壮族自治区桂林市临桂区西城中路69号", 25.2736, 110.2902, "guilinshi", "gls", listOf("桂林", "guilin"), 30),
            OfflinePlaceEntry("柳州市", "柳州市", "广西壮族自治区柳州市城中区文昌路66号", 24.3264, 109.4281, "liuzhoushi", "lzs", listOf("柳州", "liuzhou"), 20),
            OfflinePlaceEntry("咸阳市", "咸阳市", "陕西省咸阳市秦都区渭阳中路6号", 34.3296, 108.7090, "xianyangshi", "xys", listOf("咸阳", "xianyang"), 20),
            OfflinePlaceEntry("宝鸡市", "宝鸡市", "陕西省宝鸡市金台区行政大道1号", 34.3619, 107.2375, "baojishi", "bjs", listOf("宝鸡", "baoji"), 20),
            OfflinePlaceEntry("三亚市", "三亚市", "海南省三亚市吉阳区新风街257号", 18.2528, 109.5119, "sanyashi", "sys", listOf("三亚", "sanya", "sy"), 35),
            OfflinePlaceEntry("天水市", "天水市", "甘肃省天水市秦州区民主西路34号", 34.5809, 105.7249, "tianshuishi", "tss", listOf("天水", "tianshui"), 20),
            OfflinePlaceEntry("喀什市", "喀什地区", "新疆维吾尔自治区喀什地区喀什市解放北路", 39.4704, 75.9897, "kashishi", "kss", listOf("喀什", "kashi"), 20),
            OfflinePlaceEntry("克拉玛依市", "克拉玛依市", "新疆维吾尔自治区克拉玛依市克拉玛依区迎宾路", 45.5799, 84.8892, "kelamayishi", "klmys", listOf("克拉玛依", "kelamayi"), 20),
            OfflinePlaceEntry("高雄市", "高雄市", "台湾省高雄市苓雅区四维三路2号", 22.6273, 120.3014, "gaoxiongshi", "gxs", listOf("高雄", "gaoxiong", "kaohsiung"), 25),

            // ========== 3. 热门地标与 E2E 标准测试坐标（34 条，权威 WGS-84）==========
            OfflinePlaceEntry("天安门", "北京市", "北京市东城区东长安街天安门广场", 39.9087, 116.3975, "tiananmen", "tam", listOf("天安门广场", "beijing tiananmen", "tiananmen"), 60),
            OfflinePlaceEntry("故宫博物院", "北京市", "北京市东城区景山前街4号", 39.9163, 116.3972, "gugongbowuyuan", "ggbwy", listOf("故宫", "紫禁城", "gugong", "forbidden city"), 55),
            OfflinePlaceEntry("国家体育场（鸟巢）", "北京市", "北京市朝阳区国家体育场南路1号", 39.9929, 116.3965, "guojiatiyuchangniaochao", "gjtycnc", listOf("鸟巢", "国家体育场", "奥林匹克公园", "niaochao", "birds nest"), 55),
            OfflinePlaceEntry("北京国贸", "北京市", "北京市朝阳区建国门外大街1号", 39.9088, 116.4608, "beijingguomao", "bjgm", listOf("国贸", "国贸中心", "guomao", "cbd"), 45),
            OfflinePlaceEntry("中关村", "北京市", "北京市海淀区中关村大街", 39.9834, 116.3168, "zhongguancun", "zgc", listOf("中关村科技园", "zgc"), 50),
            OfflinePlaceEntry("北京南站", "北京市", "北京市丰台区永外大街车站路12号", 39.8651, 116.3789, "beijingnanzhan", "bjnz", listOf("北京南火车站", "南站"), 45),
            OfflinePlaceEntry("北京西站", "北京市", "北京市丰台区莲花池东路118号", 39.8949, 116.3214, "beijingxizhan", "bjxz", listOf("北京西火车站"), 45),
            OfflinePlaceEntry("北京首都国际机场", "北京市", "北京市顺义区机场西路", 40.0799, 116.6031, "beijingshouduguojijichang", "bjsdgjjc", listOf("首都机场", "pek"), 45),
            OfflinePlaceEntry("北京大兴国际机场", "北京市", "北京市大兴区榆垡镇大兴国际机场", 39.5098, 116.4105, "beijingdaxingguojijichang", "bjdxgjjc", listOf("大兴机场", "pkx"), 45),
            OfflinePlaceEntry("王府井", "北京市", "北京市东城区王府井大街", 39.9137, 116.4108, "wangfujing", "wfj", listOf("王府井步行街"), 40),
            OfflinePlaceEntry("上海陆家嘴", "上海市", "上海市浦东新区陆家嘴金融贸易区世纪大道", 31.2397, 121.4998, "shanghailujiazui", "shljz", listOf("陆家嘴", "东方明珠", "lujiazui", "ljz"), 60),
            OfflinePlaceEntry("上海外滩", "上海市", "上海市黄浦区中山东一路外滩", 31.2377, 121.4906, "shanghaiwaitan", "shwt", listOf("外滩", "waitan", "the bund"), 55),
            OfflinePlaceEntry("人民广场", "上海市", "上海市黄浦区人民大道", 31.2304, 121.4737, "renminguangchang", "rmgc", listOf("上海人民广场"), 45),
            OfflinePlaceEntry("上海虹桥站", "上海市", "上海市闵行区申贵路1500号", 31.1940, 121.3204, "shanghaihongqiaozhan", "shhqz", listOf("虹桥火车站", "虹桥枢纽", "虹桥机场", "hongqiao"), 45),
            OfflinePlaceEntry("上海浦东国际机场", "上海市", "上海市浦东新区启航路900号", 31.1443, 121.8083, "shanghaipudongguojijichang", "shpdgjjc", listOf("浦东机场", "pvg"), 45),
            OfflinePlaceEntry("南山科技园", "深圳市", "广东省深圳市南山区高新南一道", 22.5332, 113.9512, "nanshankejiyuan", "nskjy", listOf("深圳南山科技园", "高新园", "nanshankejyuan", "nanshan"), 55),
            OfflinePlaceEntry("深圳市民中心", "深圳市", "广东省深圳市福田区福中三路1号", 22.5446, 114.0579, "shenzhenshiminzhongxin", "szsmzx", listOf("市民中心", "福田中心区"), 55),
            OfflinePlaceEntry("深圳北站", "深圳市", "广东省深圳市龙华区致远中路28号", 22.6097, 114.0292, "shenzhenbeizhan", "szbz", listOf("深北", "shenzhenbei"), 50),
            OfflinePlaceEntry("广州塔", "广州市", "广东省广州市海珠区阅江西路222号", 23.1065, 113.3245, "guangzhouta", "gzt", listOf("小蛮腰", "canton tower"), 55),
            OfflinePlaceEntry("天河体育中心", "广州市", "广东省广州市天河区天河路299号", 23.1375, 113.3247, "tianhetiyuzhongxin", "thtyzx", listOf("天河城", "体育西"), 45),
            OfflinePlaceEntry("广州白云国际机场", "广州市", "广东省广州市花都区空港大道", 23.3924, 113.2988, "guangzhoubaiyunguojijichang", "gzbygjjc", listOf("白云机场", "can"), 45),
            OfflinePlaceEntry("杭州西湖", "杭州市", "浙江省杭州市西湖区龙井路1号西湖风景名胜区", 30.2516, 120.1489, "hangzhouxihu", "hzxh", listOf("西湖", "断桥残雪", "xihu", "west lake"), 55),
            OfflinePlaceEntry("成都春熙路", "成都市", "四川省成都市锦江区春熙路步行街", 30.6562, 104.0790, "chengduchunxilu", "cdcxl", listOf("春熙路", "太古里", "ifs"), 50),
            OfflinePlaceEntry("成都天府广场", "成都市", "四川省成都市青羊区人民南路一段86号", 30.6574, 104.0658, "chengdutianfuguangchang", "cdtfgc", listOf("天府广场"), 45),
            OfflinePlaceEntry("重庆洪崖洞", "重庆市", "重庆市渝中区嘉陵江滨江路88号", 29.5628, 106.5789, "chongqinghongyadong", "cqhyd", listOf("洪崖洞"), 50),
            OfflinePlaceEntry("重庆解放碑", "重庆市", "重庆市渝中区民族路177号", 29.5572, 106.5771, "chongqingjiefangbei", "cqjfb", listOf("解放碑"), 45),
            OfflinePlaceEntry("武汉黄鹤楼", "武汉市", "湖北省武汉市武昌区蛇山西山坡特1号", 30.5447, 114.3025, "wuhanhuanghelou", "whhhl", listOf("黄鹤楼"), 50),
            OfflinePlaceEntry("西安大雁塔", "西安市", "陕西省西安市雁塔区大慈恩寺内", 34.2194, 108.9647, "xiandayanta", "xadyt", listOf("大雁塔", "大唐不夜城"), 50),
            OfflinePlaceEntry("南京新街口", "南京市", "江苏省南京市秦淮区中山南路1号", 32.0418, 118.7841, "nanjingxinjiekou", "njxjk", listOf("新街口"), 45),
            OfflinePlaceEntry("青岛五四广场", "青岛市", "山东省青岛市市南区东海西路五四广场", 36.0638, 120.3826, "qingdaowusiguangchang", "qdwsgc", listOf("五四广场"), 45),
            OfflinePlaceEntry("布达拉宫", "拉萨市", "西藏自治区拉萨市城关区北京中路35号", 29.6555, 91.1189, "budalagong", "bdlg", listOf("拉萨布达拉宫", "potala palace"), 55),
            OfflinePlaceEntry("三亚亚龙湾", "三亚市", "海南省三亚市吉阳区亚龙湾国家旅游度假区", 18.2036, 109.6345, "sanyayalongwan", "syylw", listOf("亚龙湾"), 45),
            OfflinePlaceEntry("香港维多利亚港", "香港", "香港特别行政区油尖旺区尖沙咀海滨", 22.2930, 114.1704, "xianggangweiduoliyagang", "xgwdlyg", listOf("维多利亚港", "维港", "尖沙咀"), 45),
            OfflinePlaceEntry("台北101大楼", "台北市", "台湾省台北市信义区信义路五段7号", 25.0339, 121.5645, "taibeiyilingyidalou", "tb101dl", listOf("台北101", "101大楼", "taipei 101"), 45)
        )
    }
}
