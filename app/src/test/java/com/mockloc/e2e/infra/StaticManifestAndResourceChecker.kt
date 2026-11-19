package com.mockloc.e2e.infra

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ComplianceReport(
    val passed: Boolean,
    val violations: List<String>,
    val warnings: List<String> = emptyList()
)

/**
 * 基于真实 DOM/XML 解析与词法级源码规则匹配的静态合规审计引擎。
 * 零生产类引用，供 Phase 1 Fixture 自检与 Phase 2 全量合规强断言共用。
 */
object StaticManifestAndResourceChecker {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    val REQUIRED_PERMISSIONS: List<String> = listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.INTERNET",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.QUERY_ALL_PACKAGES"
    )

    val CORE_RECOMMENDED_SCOPE_PACKAGES: List<String> = listOf(
        "com.mockloc",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.tencent.map",
        "com.tencent.mm",
        "com.tencent.wework",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "com.sankuai.meituan",
        "com.dianping.v1"
    )

    private val CRITICAL_COMPONENT_NAMES = setOf(
        ".service.MockLocationForegroundService",
        "com.mockloc.service.MockLocationForegroundService",
        ".ipc.LocationConfigProvider",
        "com.mockloc.ipc.LocationConfigProvider",
        ".presentation.MainActivity",
        "com.mockloc.presentation.MainActivity",
        ".MainActivity"
    )

    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    private fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    private fun parseXmlDocument(xmlContent: String): Result<Document> {
        if (xmlContent.isBlank()) {
            return Result.failure(IllegalArgumentException("XML content is blank"))
        }
        return runCatching {
            newDocumentBuilder().parse(InputSource(StringReader(xmlContent)))
        }
    }

    private fun Element.androidAttr(localName: String): String? {
        val nsVal = getAttributeNS(ANDROID_NS, localName)
        if (!nsVal.isNullOrEmpty()) return nsVal
        val prefixed = getAttribute("android:$localName")
        if (!prefixed.isNullOrEmpty()) return prefixed
        return null
    }

    private fun Element.hasAndroidAttr(localName: String): Boolean {
        return hasAttributeNS(ANDROID_NS, localName) || hasAttribute("android:$localName")
    }

    private fun childElements(parent: Element, tagName: String? = null): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && (tagName == null || node.tagName == tagName)) {
                result.add(node)
            }
        }
        return result
    }

    /**
     * 校验 AndroidManifest.xml 语法良构且根节点为 `<manifest>`。
     */
    fun checkManifestXmlWellFormed(xmlContent: String): ComplianceReport {
        val violations = mutableListOf<String>()
        val docResult = parseXmlDocument(xmlContent)
        if (docResult.isFailure) {
            violations.add("Manifest XML is not well-formed: ${docResult.exceptionOrNull()?.message}")
            return ComplianceReport(passed = false, violations = violations)
        }
        val doc = docResult.getOrThrow()
        val root = doc.documentElement
        if (root == null || root.tagName != "manifest") {
            violations.add("Manifest root element must be <manifest>, found: <${root?.tagName ?: "null"}>")
        }
        return ComplianceReport(passed = violations.isEmpty(), violations = violations)
    }

    /**
     * 校验 Android 14–17 强合规 Manifest 规范。
     */
    fun checkAndroid14To17ManifestCompliance(xmlContent: String): ComplianceReport {
        val wellFormed = checkManifestXmlWellFormed(xmlContent)
        if (!wellFormed.passed) return wellFormed

        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val doc = parseXmlDocument(xmlContent).getOrThrow()
        val manifest = doc.documentElement

        // 1. 校验必需权限，并禁止在必需权限上添加 android:maxSdkVersion
        val declaredPermissions = mutableSetOf<String>()
        for (permEl in childElements(manifest, "uses-permission")) {
            val permName = permEl.androidAttr("name")?.trim() ?: continue
            declaredPermissions.add(permName)
            if (permName in REQUIRED_PERMISSIONS && permEl.hasAndroidAttr("maxSdkVersion")) {
                val maxSdk = permEl.androidAttr("maxSdkVersion")?.trim()
                violations.add(
                    "Required permission $permName must not specify android:maxSdkVersion (found: \"$maxSdk\")"
                )
            }
        }
        for (perm in REQUIRED_PERMISSIONS) {
            if (perm !in declaredPermissions) {
                violations.add("Missing required <uses-permission>: $perm")
            }
        }

        // 2. 校验 <queries> 结构合法
        val queriesNodes = childElements(manifest, "queries")
        if (queriesNodes.isEmpty()) {
            violations.add("Missing <queries> block required for Android 11–17 package visibility")
        } else {
            val hasValidEntry = queriesNodes.any { q ->
                childElements(q).any { child ->
                    when (child.tagName) {
                        "intent" -> childElements(child, "action").any { !it.androidAttr("name").isNullOrBlank() }
                        "package" -> !child.androidAttr("name").isNullOrBlank()
                        "provider" -> !child.androidAttr("authorities").isNullOrBlank()
                        else -> false
                    }
                }
            }
            if (!hasValidEntry) {
                violations.add("<queries> block contains no valid <intent>, <package>, or <provider> entry")
            }
        }

        // 3. 校验 <application> 与 MockLocApp
        val applications = childElements(manifest, "application")
        if (applications.size != 1) {
            violations.add("Expected exactly 1 <application> element, found ${applications.size}")
            return ComplianceReport(passed = false, violations = violations, warnings = warnings)
        }
        val app = applications.first()
        val appName = app.androidAttr("name")?.trim()
        if (appName != ".MockLocApp" && appName != "com.mockloc.MockLocApp") {
            violations.add("<application> must declare android:name=\".MockLocApp\" or \"com.mockloc.MockLocApp\", found: \"$appName\"")
        }

        // 4. 校验所有组件显式声明 android:exported，并拦截关键组件 android:enabled="false"
        val componentTags = listOf("activity", "activity-alias", "service", "receiver", "provider")
        val allComponents = componentTags.flatMap { tag -> childElements(app, tag) }
        for (comp in allComponents) {
            val compName = comp.androidAttr("name")?.trim() ?: "<unnamed-${comp.tagName}>"
            val exportedVal = comp.androidAttr("exported")?.trim()
            if (exportedVal != "true" && exportedVal != "false") {
                violations.add("Component <${comp.tagName} android:name=\"$compName\"> must explicitly set android:exported=\"true\" or \"false\"")
            }
            if (compName in CRITICAL_COMPONENT_NAMES) {
                val enabledVal = comp.androidAttr("enabled")?.trim()
                if (enabledVal.equals("false", ignoreCase = true)) {
                    violations.add(
                        "Critical component <${comp.tagName} android:name=\"$compName\"> must not declare android:enabled=\"false\""
                    )
                }
            }
        }

        // 5. 校验 MockLocationForegroundService
        val services = childElements(app, "service")
        val fgService = services.firstOrNull { svc ->
            val n = svc.androidAttr("name")?.trim()
            n == ".service.MockLocationForegroundService" || n == "com.mockloc.service.MockLocationForegroundService"
        }
        if (fgService == null) {
            violations.add("Missing <service> declaration for MockLocationForegroundService")
        } else {
            val svcExported = fgService.androidAttr("exported")?.trim()
            if (svcExported != "false") {
                violations.add("MockLocationForegroundService must declare android:exported=\"false\", found: \"$svcExported\"")
            }
            val fgTypes = fgService.androidAttr("foregroundServiceType")
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            if ("location" !in fgTypes) {
                violations.add("MockLocationForegroundService must include \"location\" in android:foregroundServiceType, found: \"${fgService.androidAttr("foregroundServiceType")}\"")
            }
        }

        // 6. 校验 LocationConfigProvider
        val providers = childElements(app, "provider")
        val configProvider = providers.firstOrNull { prv ->
            val n = prv.androidAttr("name")?.trim()
            n == ".ipc.LocationConfigProvider" || n == "com.mockloc.ipc.LocationConfigProvider"
        }
        if (configProvider == null) {
            violations.add("Missing <provider> declaration for LocationConfigProvider")
        } else {
            val authorities = configProvider.androidAttr("authorities")?.trim()
            if (authorities != "com.mockloc.configprovider") {
                violations.add("LocationConfigProvider must declare android:authorities=\"com.mockloc.configprovider\", found: \"$authorities\"")
            }
            val prvExported = configProvider.androidAttr("exported")?.trim()
            if (prvExported != "true") {
                violations.add("LocationConfigProvider must declare android:exported=\"true\", found: \"$prvExported\"")
            }
        }

        // 7. 校验 Xposed/LSPosed 元数据
        val metaDataMap = mutableMapOf<String, Element>()
        for (meta in childElements(app, "meta-data")) {
            val key = meta.androidAttr("name")?.trim()
            if (!key.isNullOrEmpty()) {
                metaDataMap[key] = meta
            }
        }

        val xposedModule = metaDataMap["xposedmodule"]?.androidAttr("value")?.trim()
        if (xposedModule != "true") {
            violations.add("Missing or invalid <meta-data android:name=\"xposedmodule\" android:value=\"true\"/>, found: \"$xposedModule\"")
        }

        val xposedSharedPrefs = metaDataMap["xposedsharedprefs"]?.androidAttr("value")?.trim()
        if (xposedSharedPrefs != "true") {
            violations.add("Missing or invalid <meta-data android:name=\"xposedsharedprefs\" android:value=\"true\"/>, found: \"$xposedSharedPrefs\"")
        }

        val minVerStr = metaDataMap["xposedminversion"]?.androidAttr("value")?.trim()
        val minVer = minVerStr?.toIntOrNull()
        if (minVer == null || minVer < 93) {
            violations.add("xposedminversion must be an integer >= 93 for New XSharedPreferences support, found: \"$minVerStr\"")
        }

        val xposedDesc = metaDataMap["xposeddescription"]?.androidAttr("value")?.trim()
        if (xposedDesc.isNullOrEmpty()) {
            violations.add("Missing non-empty <meta-data android:name=\"xposeddescription\"/>")
        }

        val xposedScopeMeta = metaDataMap["xposedscope"]
        val scopeRes = xposedScopeMeta?.androidAttr("resource")?.trim()
        val scopeVal = xposedScopeMeta?.androidAttr("value")?.trim()
        if (scopeRes != "@array/xposed_scope") {
            if (scopeVal == "@array/xposed_scope") {
                violations.add(
                    "xposedscope meta-data must use android:resource=\"@array/xposed_scope\" instead of android:value"
                )
            } else {
                violations.add(
                    "xposedscope meta-data must specify android:resource=\"@array/xposed_scope\", found resource=\"$scopeRes\" value=\"$scopeVal\""
                )
            }
        }

        return ComplianceReport(
            passed = violations.isEmpty(),
            violations = violations,
            warnings = warnings
        )
    }

    /**
     * 校验 `assets/xposed_init` 内容是否准确声明 `com.mockloc.hook.XposedInit`。
     */
    fun checkXposedInitContent(content: String): ComplianceReport {
        val violations = mutableListOf<String>()
        val entries = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        if (entries.isEmpty()) {
            violations.add("xposed_init contains no active entry class")
        } else {
            if ("com.mockloc.hook.XposedInit" !in entries) {
                violations.add("xposed_init must contain \"com.mockloc.hook.XposedInit\", found: $entries")
            }
            val invalidEntries = entries.filter { !PACKAGE_NAME_REGEX.matches(it) }
            if (invalidEntries.isNotEmpty()) {
                violations.add("xposed_init contains invalid class entries: $invalidEntries")
            }
        }
        return ComplianceReport(passed = violations.isEmpty(), violations = violations)
    }

    /**
     * 校验 `arrays.xml` 中 `xposed_scope` 数组非空、格式合法、无重复、且覆盖核心推荐应用。
     */
    fun checkXposedScopeArrayXml(xmlContent: String): ComplianceReport {
        val violations = mutableListOf<String>()
        val docResult = parseXmlDocument(xmlContent)
        if (docResult.isFailure) {
            violations.add("arrays XML is not well-formed: ${docResult.exceptionOrNull()?.message}")
            return ComplianceReport(passed = false, violations = violations)
        }
        val root = docResult.getOrThrow().documentElement
        if (root == null || root.tagName != "resources") {
            violations.add("Root element must be <resources>, found: <${root?.tagName ?: "null"}>")
            return ComplianceReport(passed = false, violations = violations)
        }

        val scopeArrays = (childElements(root, "string-array") + childElements(root, "array"))
            .filter { it.getAttribute("name").trim() == "xposed_scope" }

        if (scopeArrays.isEmpty()) {
            violations.add("Missing <string-array name=\"xposed_scope\"> in arrays.xml")
            return ComplianceReport(passed = false, violations = violations)
        }

        val items = childElements(scopeArrays.first(), "item")
        if (items.isEmpty()) {
            violations.add("xposed_scope array must not be empty")
            return ComplianceReport(passed = false, violations = violations)
        }

        val seen = mutableSetOf<String>()
        for ((index, item) in items.withIndex()) {
            val pkg = item.textContent?.trim().orEmpty()
            if (pkg.isEmpty()) {
                violations.add("xposed_scope item at index $index is blank")
                continue
            }
            if (!PACKAGE_NAME_REGEX.matches(pkg)) {
                violations.add("xposed_scope item \"$pkg\" is not a valid Android package name")
            }
            if (!seen.add(pkg)) {
                violations.add("Duplicate package in xposed_scope: \"$pkg\"")
            }
        }

        for (requiredPkg in CORE_RECOMMENDED_SCOPE_PACKAGES) {
            if (requiredPkg !in seen) {
                violations.add("xposed_scope is missing core recommended package: \"$requiredPkg\"")
            }
        }

        return ComplianceReport(passed = violations.isEmpty(), violations = violations)
    }

    /**
     * 仅验证 `resDir` 下已有 XML 文件均为良构 XML（用于 Phase 1 并行期基线检查，不断言未重写的布局/主题名）。
     */
    fun checkResXmlWellFormedOnly(resDir: File): ComplianceReport {
        val violations = mutableListOf<String>()
        if (!resDir.isDirectory) {
            violations.add("Resource directory does not exist: ${resDir.absolutePath}")
            return ComplianceReport(passed = false, violations = violations)
        }
        val xmlFiles = resDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
            .sortedBy { it.absolutePath }
            .toList()
        if (xmlFiles.isEmpty()) {
            violations.add("No XML resource files found under ${resDir.absolutePath}")
            return ComplianceReport(passed = false, violations = violations)
        }
        for (file in xmlFiles) {
            val parseRes = parseXmlDocument(file.readText(Charsets.UTF_8))
            if (parseRes.isFailure) {
                violations.add("Malformed XML file ${file.relativeTo(resDir).path}: ${parseRes.exceptionOrNull()?.message}")
            }
        }
        return ComplianceReport(passed = violations.isEmpty(), violations = violations)
    }

    /**
     * 全面校验 `res/` 下 XML 良构性、`values-night/` 存在性以及 Material 3 主题基线。
     */
    fun checkResourcesAndNightSupport(resDir: File): ComplianceReport {
        val baseReport = checkResXmlWellFormedOnly(resDir)
        val violations = baseReport.violations.toMutableList()
        val warnings = baseReport.warnings.toMutableList()

        if (!resDir.isDirectory) {
            return ComplianceReport(passed = false, violations = violations, warnings = warnings)
        }

        // 1. 校验 values-night 目录存在且含有效夜间资源 XML
        val nightDir = File(resDir, "values-night")
        if (!nightDir.isDirectory) {
            violations.add("Missing dark mode resource directory: values-night/")
        } else {
            val nightXmls = nightDir.listFiles { f -> f.isFile && f.extension.equals("xml", ignoreCase = true) }
                ?.toList()
                ?: emptyList()
            if (nightXmls.isEmpty()) {
                violations.add("values-night/ directory exists but contains no XML files")
            }
        }

        // 2. 校验 Material 3 主主题基线
        fun normalizeStyleRef(ref: String): String {
            val trimmed = ref.trim()
            return if (trimmed.startsWith("@style/")) trimmed.removePrefix("@style/") else trimmed
        }

        val valuesDir = File(resDir, "values")
        val themeParents = mutableListOf<String>()
        val valuesStyleEntries = mutableListOf<Triple<String, String, String>>()
        val valuesStyleParentMap = mutableMapOf<String, String>()
        val valuesXmlFiles = valuesDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension.equals("xml", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

        for (xmlFile in valuesXmlFiles) {
            val doc = parseXmlDocument(xmlFile.readText(Charsets.UTF_8)).getOrNull() ?: continue
            val root = doc.documentElement ?: continue
            if (root.tagName != "resources") continue
            for (style in childElements(root, "style")) {
                val styleName = style.getAttribute("name").trim()
                val parent = normalizeStyleRef(style.getAttribute("parent"))
                if (styleName.isNotEmpty()) {
                    valuesStyleParentMap[styleName] = parent
                }
                if (parent.isNotEmpty()) {
                    themeParents.add(parent)
                }
                valuesStyleEntries.add(Triple(xmlFile.name, styleName, parent))
            }
        }

        val nightXmlFiles = nightDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension.equals("xml", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        val nightStyleEntries = mutableListOf<Triple<String, String, String>>()
        val nightStyleParentMap = mutableMapOf<String, String>()

        for (nightFile in nightXmlFiles) {
            val doc = parseXmlDocument(nightFile.readText(Charsets.UTF_8)).getOrNull() ?: continue
            val root = doc.documentElement ?: continue
            if (root.tagName != "resources") continue
            for (style in childElements(root, "style")) {
                val styleName = style.getAttribute("name").trim()
                val parent = normalizeStyleRef(style.getAttribute("parent"))
                if (styleName.isNotEmpty()) {
                    nightStyleParentMap[styleName] = parent
                }
                if (parent.isNotEmpty()) {
                    themeParents.add(parent)
                }
                nightStyleEntries.add(Triple(nightFile.name, styleName, parent))
            }
        }

        val hasMaterial3Theme = themeParents.any { it.startsWith("Theme.Material3.") }
        if (!hasMaterial3Theme) {
            violations.add("No Material 3 theme (parent=\"Theme.Material3.*\") found in values/ or values-night/")
        }

        fun isOfficialM3ThemePrefix(name: String): Boolean {
            return name.startsWith("Theme.Material3.") ||
                name.startsWith("ThemeOverlay.Material3.")
        }

        fun isOfficialM3ComponentPrefix(name: String): Boolean {
            return name.startsWith("Widget.Material3.") ||
                name.startsWith("TextAppearance.Material3.") ||
                name.startsWith("ShapeAppearance.Material3.")
        }

        fun isLegacyNonM3Prefix(name: String): Boolean {
            return name.startsWith("Theme.MaterialComponents") ||
                name.startsWith("ThemeOverlay.MaterialComponents") ||
                name.startsWith("Widget.MaterialComponents") ||
                name.startsWith("TextAppearance.MaterialComponents") ||
                name.startsWith("ShapeAppearance.MaterialComponents") ||
                name.startsWith("Theme.AppCompat") ||
                name.startsWith("ThemeOverlay.AppCompat") ||
                name.startsWith("Widget.AppCompat") ||
                name.startsWith("TextAppearance.AppCompat") ||
                name.startsWith("Theme.Holo") ||
                name.startsWith("android:Theme") ||
                name.startsWith("@android:style/Theme") ||
                name.startsWith("Theme.DeviceDefault")
        }

        fun isMainThemeStyleName(styleName: String): Boolean {
            if (styleName == "Theme" || styleName == "AppTheme") return true
            if (styleName.startsWith("Theme.") || styleName.startsWith("ThemeOverlay.")) return true
            val isComponentPrefix = styleName.startsWith("Widget.") ||
                styleName.startsWith("TextAppearance.") ||
                styleName.startsWith("ShapeAppearance.")
            return !isComponentPrefix && styleName.contains("Theme")
        }

        // 样式父链溯源解析（0=M3主题, 1=M3组件, 2=遗留非M3, 3=环状继承, 4=未定义/无法溯源）
        val ORIGIN_M3_THEME = 0
        val ORIGIN_M3_COMPONENT = 1
        val ORIGIN_LEGACY = 2
        val ORIGIN_CYCLE = 3
        val ORIGIN_UNRESOLVED = 4

        fun resolveRefOrigin(
            refName: String,
            scopeParentMap: Map<String, String>,
            memo: MutableMap<String, Int>,
            visiting: MutableSet<String>
        ): Int {
            if (isOfficialM3ThemePrefix(refName)) return ORIGIN_M3_THEME
            if (isOfficialM3ComponentPrefix(refName)) return ORIGIN_M3_COMPONENT
            if (isLegacyNonM3Prefix(refName)) return ORIGIN_LEGACY
            if (refName in visiting) return ORIGIN_CYCLE
            memo[refName]?.let { return it }

            val declaredParent = scopeParentMap[refName] ?: return ORIGIN_UNRESOLVED
            visiting.add(refName)
            val origin = when {
                declaredParent.isNotEmpty() ->
                    resolveRefOrigin(declaredParent, scopeParentMap, memo, visiting)
                isLegacyNonM3Prefix(refName) ->
                    ORIGIN_LEGACY
                refName.contains('.') ->
                    resolveRefOrigin(refName.substringBeforeLast('.'), scopeParentMap, memo, visiting)
                else ->
                    ORIGIN_UNRESOLVED
            }
            visiting.remove(refName)
            if (origin != ORIGIN_CYCLE) {
                memo[refName] = origin
            }
            return origin
        }

        val valuesMemo = mutableMapOf<String, Int>()
        val mergedNightParentMap = valuesStyleParentMap + nightStyleParentMap
        val nightMemo = mutableMapOf<String, Int>()

        fun validateStyleEntries(
            dirLabel: String,
            entries: List<Triple<String, String, String>>,
            scopeParentMap: Map<String, String>,
            memo: MutableMap<String, Int>,
            allowUnparentedSimpleNonTheme: Boolean
        ) {
            for ((fileName, styleName, parent) in entries) {
                val isTheme = isMainThemeStyleName(styleName)
                if (isTheme) {
                    if (parent.isEmpty()) {
                        if (isLegacyNonM3Prefix(styleName)) {
                            violations.add(
                                "$dirLabel/$fileName theme style \"$styleName\" uses legacy non-Material 3 implicit parent"
                            )
                        } else if (!styleName.contains('.')) {
                            violations.add(
                                "$dirLabel/$fileName theme style \"$styleName\" without dot-notation implicit inheritance must specify a Material 3 parent"
                            )
                        } else {
                            val implicitParent = styleName.substringBeforeLast('.')
                            val origin = resolveRefOrigin(implicitParent, scopeParentMap, memo, mutableSetOf(styleName))
                            if (origin != ORIGIN_M3_THEME) {
                                violations.add(
                                    "$dirLabel/$fileName theme style \"$styleName\" implicit parent \"$implicitParent\" does not resolve to Material 3"
                                )
                            }
                        }
                    } else {
                        val origin = resolveRefOrigin(parent, scopeParentMap, memo, mutableSetOf(styleName))
                        if (origin != ORIGIN_M3_THEME) {
                            violations.add(
                                "$dirLabel/$fileName theme style \"$styleName\" declares non-Material 3 parent \"$parent\""
                            )
                        }
                    }
                } else {
                    if (parent.isEmpty()) {
                        if (isLegacyNonM3Prefix(styleName)) {
                            violations.add(
                                "$dirLabel/$fileName style \"$styleName\" uses legacy non-Material 3 implicit parent"
                            )
                        } else if (styleName.contains('.')) {
                            val implicitParent = styleName.substringBeforeLast('.')
                            val origin = resolveRefOrigin(implicitParent, scopeParentMap, memo, mutableSetOf(styleName))
                            if (origin != ORIGIN_M3_THEME && origin != ORIGIN_M3_COMPONENT) {
                                violations.add(
                                    "$dirLabel/$fileName style \"$styleName\" implicit parent \"$implicitParent\" does not resolve to Material 3"
                                )
                            }
                        } else if (!allowUnparentedSimpleNonTheme) {
                            violations.add(
                                "$dirLabel/$fileName style \"$styleName\" without dot-notation implicit inheritance must specify a Material 3 parent"
                            )
                        }
                    } else {
                        val origin = resolveRefOrigin(parent, scopeParentMap, memo, mutableSetOf(styleName))
                        if (origin != ORIGIN_M3_THEME && origin != ORIGIN_M3_COMPONENT) {
                            violations.add(
                                "$dirLabel/$fileName style \"$styleName\" declares non-Material 3 parent \"$parent\""
                            )
                        }
                    }
                }
            }
        }

        // 3. 检查 values/ 下主主题、组件样式及隐式/显式继承链（仅使用 values/ 自身定义，防止被 values-night/ 掩盖）
        validateStyleEntries(
            dirLabel = "values",
            entries = valuesStyleEntries,
            scopeParentMap = valuesStyleParentMap,
            memo = valuesMemo,
            allowUnparentedSimpleNonTheme = true
        )

        // 4. 检查 values-night/ 下全部 XML 样式文件（允许回退查找 values/ 中定义的基样式如 Base.Theme.MockLoc）
        validateStyleEntries(
            dirLabel = "values-night",
            entries = nightStyleEntries,
            scopeParentMap = mergedNightParentMap,
            memo = nightMemo,
            allowUnparentedSimpleNonTheme = false
        )

        return ComplianceReport(
            passed = violations.isEmpty(),
            violations = violations,
            warnings = warnings
        )
    }

    private data class PreprocessedSource(
        val codeNoComments: String,
        val codeNoCommentsMaskedStrings: String
    )

    private sealed interface LexMode {
        data class StringMode(val isRaw: Boolean) : LexMode
        data class TemplateBraceMode(var braceDepth: Int) : LexMode
    }

    /**
     * 精确 Kotlin 词法状态机（O(N) 非递归）：
     * - 支持嵌套块注释深度计数 `blockCommentDepth`
     * - 支持原始字符串 `"""..."""` 及多连续引号闭合（如 `"""abc""""` 与内嵌 `""`）
     * - 支持普通字符串 `"..."` 与字符字面量 `'...'`（含转义）
     * - 支持字符串模板 `${ ... }` 内嵌表达式（含嵌套花括号与内层字符串），在 `codeNoCommentsMaskedStrings` 中仅掩码纯文本而保留 `${...}` 内的真实代码
     * - 输出两个字符索引 1:1 对齐的视图
     */
    private fun preprocessKotlinSource(source: String): PreprocessedSource {
        val n = source.length
        val sbRaw = StringBuilder(n)
        val sbMasked = StringBuilder(n)

        val modeStack = ArrayDeque<LexMode>()
        var inChar = false
        var inLineComment = false
        var blockCommentDepth = 0
        var i = 0

        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            val topMode = modeStack.lastOrNull()

            when {
                inLineComment -> {
                    if (c == '\n') {
                        inLineComment = false
                        sbRaw.append('\n')
                        sbMasked.append('\n')
                    } else {
                        sbRaw.append(' ')
                        sbMasked.append(' ')
                    }
                    i++
                }
                blockCommentDepth > 0 -> {
                    if (c == '/' && next == '*') {
                        blockCommentDepth++
                        sbRaw.append("  ")
                        sbMasked.append("  ")
                        i += 2
                    } else if (c == '*' && next == '/') {
                        blockCommentDepth--
                        sbRaw.append("  ")
                        sbMasked.append("  ")
                        i += 2
                    } else {
                        val out = if (c == '\n') '\n' else ' '
                        sbRaw.append(out)
                        sbMasked.append(out)
                        i++
                    }
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        sbRaw.append(c).append(next)
                        sbMasked.append(' ').append(if (next == '\n') '\n' else ' ')
                        i += 2
                    } else if (c == '\'') {
                        inChar = false
                        sbRaw.append('\'')
                        sbMasked.append('\'')
                        i++
                    } else if (c == '\n') {
                        inChar = false
                        sbRaw.append('\n')
                        sbMasked.append('\n')
                        i++
                    } else {
                        sbRaw.append(c)
                        sbMasked.append(' ')
                        i++
                    }
                }
                topMode is LexMode.StringMode && topMode.isRaw -> {
                    if (c == '$' && next == '{') {
                        sbRaw.append("\${")
                        sbMasked.append("  ")
                        modeStack.addLast(LexMode.TemplateBraceMode(1))
                        i += 2
                    } else if (c == '"') {
                        var quoteCount = 0
                        while (i + quoteCount < n && source[i + quoteCount] == '"') {
                            quoteCount++
                        }
                        if (quoteCount < 3) {
                            repeat(quoteCount) {
                                sbRaw.append('"')
                                sbMasked.append(' ')
                            }
                        } else {
                            repeat(quoteCount) {
                                sbRaw.append('"')
                                sbMasked.append(' ')
                            }
                            modeStack.removeLast()
                        }
                        i += quoteCount
                    } else {
                        sbRaw.append(c)
                        sbMasked.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                topMode is LexMode.StringMode && !topMode.isRaw -> {
                    if (c == '\\' && i + 1 < n) {
                        sbRaw.append(c).append(next)
                        sbMasked.append(' ').append(if (next == '\n') '\n' else ' ')
                        i += 2
                    } else if (c == '$' && next == '{') {
                        sbRaw.append("\${")
                        // 在进入模板表达式前先闭合外层普通字符串占位引号，避免下游状态机将 ${...} 内部代码误当字符串跳过
                        sbMasked.append("\" ")
                        modeStack.addLast(LexMode.TemplateBraceMode(1))
                        i += 2
                    } else if (c == '"') {
                        modeStack.removeLast()
                        sbRaw.append('"')
                        sbMasked.append('"')
                        i++
                    } else {
                        sbRaw.append(c)
                        sbMasked.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                c == '/' && next == '/' -> {
                    inLineComment = true
                    sbRaw.append("  ")
                    sbMasked.append("  ")
                    i += 2
                }
                c == '/' && next == '*' -> {
                    blockCommentDepth = 1
                    sbRaw.append("  ")
                    sbMasked.append("  ")
                    i += 2
                }
                c == '"' && i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"' -> {
                    modeStack.addLast(LexMode.StringMode(isRaw = true))
                    sbRaw.append("\"\"\"")
                    // 原始字符串以自闭合 "" + 空格占位，既保持 rhsMasked 非空，又不引入跨越 ${...} 的未闭合引号
                    sbMasked.append("\"\" ")
                    i += 3
                }
                c == '"' -> {
                    modeStack.addLast(LexMode.StringMode(isRaw = false))
                    sbRaw.append('"')
                    sbMasked.append('"')
                    i++
                }
                c == '\'' -> {
                    inChar = true
                    sbRaw.append('\'')
                    sbMasked.append('\'')
                    i++
                }
                c == '{' && topMode is LexMode.TemplateBraceMode -> {
                    topMode.braceDepth++
                    sbRaw.append('{')
                    sbMasked.append('{')
                    i++
                }
                c == '}' && topMode is LexMode.TemplateBraceMode -> {
                    topMode.braceDepth--
                    sbRaw.append('}')
                    if (topMode.braceDepth == 0) {
                        modeStack.removeLast()
                        val outerString = modeStack.lastOrNull() as? LexMode.StringMode
                        if (outerString != null && !outerString.isRaw) {
                            // 离开 ${...} 回到普通字符串，重新开启占位引号以与结尾 " 配对
                            sbMasked.append('"')
                        } else {
                            sbMasked.append(' ')
                        }
                    } else {
                        sbMasked.append('}')
                    }
                    i++
                }
                else -> {
                    sbRaw.append(c)
                    sbMasked.append(c)
                    i++
                }
            }
        }

        return PreprocessedSource(
            codeNoComments = sbRaw.toString(),
            codeNoCommentsMaskedStrings = sbMasked.toString()
        )
    }

    private enum class ScopeKind { FILE, CONTAINER, COMPANION, LOCAL }

    private data class LexicalScope(
        val id: Int,
        val kind: ScopeKind,
        val start: Int,
        val end: Int,
        val parentId: Int
    )

    private data class FunctionRange(
        val start: Int,
        val end: Int,
        val bodyScopeId: Int?
    )

    private data class VarBinding(
        val id: Int,
        val name: String,
        val scopeId: Int,
        val scopeStart: Int,
        val scopeEnd: Int,
        val availableFrom: Int,
        val rhsMasked: String,
        val rhsRaw: String,
        val isShadowOnly: Boolean = false
    )

    private data class SourceLexicalModel(
        val scopes: List<LexicalScope>,
        val scopeById: Map<Int, LexicalScope>,
        val functions: List<FunctionRange>,
        val bindingsByName: Map<String, List<VarBinding>>,
        val parenBracketDepths: IntArray
    )

    private val VAL_VAR_ANCHOR_REGEX = Regex("""(?<![\w.])(val|var)\s+([A-Za-z_]\w*)\b""")
    private val REASSIGN_ANCHOR_REGEX = Regex("""(?<![\w.])([A-Za-z_]\w*)\s*=(?![=>])""")
    private val FUN_ANCHOR_REGEX = Regex("""\bfun\s+""")
    private val CONTINUATION_END_KEYWORDS = setOf("or", "and", "xor", "shl", "shr", "ushr")
    private val CONTINUATION_START_REGEX = Regex("""^(?:\.|\?\.|\?:|\|\||&&|\+|-(?!>)|(?:or|and|xor|shl|shr|ushr)\b)""")
    private val KOTLIN_MODIFIER_KEYWORDS = setOf(
        "public", "internal", "private", "protected", "override", "open", "final",
        "abstract", "suspend", "inline", "infix", "operator", "tailrec", "external",
        "expect", "actual", "const", "lateinit", "data", "inner", "sealed", "enum",
        "annotation", "value", "vararg", "noinline", "crossinline"
    )
    private val REASSIGN_EXCLUDED_NAMES = setOf(
        "val", "var", "fun", "return", "throw", "if", "else", "when", "for", "while",
        "class", "object", "interface", "package", "import", "typealias"
    )

    private fun buildLexicalScopes(codeMasked: String): Pair<List<LexicalScope>, Map<Int, LexicalScope>> {
        val n = codeMasked.length
        val scopes = mutableListOf<LexicalScope>()
        val fileScope = LexicalScope(id = 0, kind = ScopeKind.FILE, start = 0, end = n, parentId = -1)
        scopes.add(fileScope)

        val openStack = ArrayDeque<Int>()
        var nextId = 1
        var i = 0
        var inString = false
        var inChar = false

        while (i < n) {
            val c = codeMasked[i]
            when {
                inString -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '\'' || c == '\n') inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '{' -> {
                    val parentId = openStack.lastOrNull() ?: 0
                    val kind = classifyBraceScopeKind(codeMasked, i)
                    val id = nextId++
                    scopes.add(LexicalScope(id = id, kind = kind, start = i, end = n, parentId = parentId))
                    openStack.addLast(id)
                }
                c == '}' -> {
                    if (openStack.isNotEmpty()) {
                        val closedId = openStack.removeLast()
                        val old = scopes[closedId]
                        scopes[closedId] = old.copy(end = i)
                    }
                }
            }
            i++
        }

        val byId = scopes.associateBy { it.id }
        return scopes to byId
    }

    private fun classifyBraceScopeKind(codeMasked: String, openBraceIdx: Int): ScopeKind {
        var p = openBraceIdx - 1
        var paren = 0
        var angle = 0
        while (p >= 0) {
            val ch = codeMasked[p]
            when {
                ch == ')' -> paren++
                ch == '(' -> if (paren > 0) paren-- else return ScopeKind.LOCAL
                ch == '>' && paren == 0 -> angle++
                ch == '<' && paren == 0 -> if (angle > 0) angle-- else return ScopeKind.LOCAL
                paren == 0 && angle == 0 -> {
                    if (ch == '{' || ch == '}' || ch == ';' || ch == '=') {
                        return ScopeKind.LOCAL
                    }
                    if (ch.isLetterOrDigit() || ch == '_') {
                        val end = p + 1
                        while (p >= 0 && (codeMasked[p].isLetterOrDigit() || codeMasked[p] == '_')) p--
                        val wordStart = p + 1
                        val word = codeMasked.substring(wordStart, end)
                        val beforeChar = if (wordStart > 0) codeMasked[wordStart - 1] else '\u0000'
                        if (beforeChar != '.' && beforeChar != ':') {
                            when (word) {
                                "fun", "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "init" ->
                                    return ScopeKind.LOCAL
                                "class", "interface" -> return ScopeKind.CONTAINER
                                "object" -> {
                                    // 检查前面是否为 companion
                                    var k = wordStart - 1
                                    while (k >= 0 && codeMasked[k].isWhitespace()) k--
                                    if (k >= 8 && codeMasked.substring(k - 8, k + 1) == "companion" &&
                                        (k - 9 < 0 || (!codeMasked[k - 9].isLetterOrDigit() && codeMasked[k - 9] != '_'))
                                    ) {
                                        return ScopeKind.COMPANION
                                    }
                                    // 检查 object 后面跳过空白是名称还是 ':'/'{'（匿名对象）
                                    var after = end
                                    while (after < openBraceIdx && codeMasked[after].isWhitespace()) after++
                                    return if (after < openBraceIdx && (codeMasked[after].isLetter() || codeMasked[after] == '_')) {
                                        ScopeKind.CONTAINER
                                    } else {
                                        ScopeKind.LOCAL
                                    }
                                }
                            }
                        }
                        continue
                    }
                }
            }
            p--
        }
        return ScopeKind.LOCAL
    }

    private fun innermostScopeAt(offset: Int, scopes: List<LexicalScope>): LexicalScope {
        var best = scopes[0]
        for (idx in 1 until scopes.size) {
            val s = scopes[idx]
            if (s.start <= offset && offset <= s.end && (s.end - s.start) <= (best.end - best.start)) {
                best = s
            }
        }
        return best
    }

    private fun resolveEffectiveScope(
        directScope: LexicalScope,
        declOffset: Int,
        scopeById: Map<Int, LexicalScope>
    ): Pair<LexicalScope, Int> {
        return when (directScope.kind) {
            ScopeKind.COMPANION -> {
                val parent = scopeById[directScope.parentId]
                val effective = if (parent != null && parent.kind == ScopeKind.CONTAINER) parent else directScope
                effective to effective.start
            }
            ScopeKind.CONTAINER, ScopeKind.FILE -> {
                directScope to directScope.start
            }
            ScopeKind.LOCAL -> {
                directScope to declOffset
            }
        }
    }

    private fun buildParentDepthArray(codeMasked: String): IntArray {
        val n = codeMasked.length
        val depths = IntArray(n + 1)
        var paren = 0
        var bracket = 0
        var inString = false
        var inChar = false
        var i = 0
        while (i < n) {
            depths[i] = paren + bracket
            val c = codeMasked[i]
            when {
                inString -> {
                    if (c == '\\' && i + 1 < n) {
                        depths[i + 1] = paren + bracket
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        depths[i + 1] = paren + bracket
                        i += 2
                        continue
                    }
                    if (c == '\'' || c == '\n') inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> paren++
                c == ')' -> if (paren > 0) paren--
                c == '[' -> bracket++
                c == ']' -> if (bracket > 0) bracket--
            }
            i++
        }
        depths[n] = paren + bracket
        return depths
    }

    private fun extractParamName(paramDecl: String): String? {
        val beforeDefault = paramDecl.substringBefore('=').trim()
        val beforeColon = beforeDefault.substringBefore(':').trim()
        if (beforeColon.isEmpty()) return null
        val tokens = beforeColon.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val candidate = tokens.lastOrNull() ?: return null
        return if (Regex("""^[A-Za-z_]\w*$""").matches(candidate) && candidate !in KOTLIN_MODIFIER_KEYWORDS) {
            candidate
        } else {
            null
        }
    }

    private fun skipTypeAndFindEqualOrBody(codeMasked: String, startIndex: Int): Pair<Char?, Int> {
        val n = codeMasked.length
        var i = startIndex
        while (i < n && codeMasked[i].isWhitespace()) i++
        if (i >= n) return null to i

        if (codeMasked[i] == ':') {
            i++
            var paren = 0
            var angle = 0
            var bracket = 0
            while (i < n) {
                val c = codeMasked[i]
                when (c) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '[' -> bracket++
                    ']' -> if (bracket > 0) bracket--
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    '=' -> if (paren == 0 && angle == 0 && bracket == 0) {
                        val next = if (i + 1 < n) codeMasked[i + 1] else '\u0000'
                        if (next != '=' && next != '>') return '=' to i
                    }
                    '{', '}', ';' -> if (paren == 0 && angle == 0 && bracket == 0) return c to i
                    '\n' -> if (paren == 0 && angle == 0 && bracket == 0) {
                        // 检查下一行是否续行 '=' 或 '{'
                        var q = i + 1
                        while (q < n && codeMasked[q].isWhitespace()) q++
                        if (q < n && (codeMasked[q] == '=' || codeMasked[q] == '{')) {
                            i = q
                            continue
                        }
                        return '\n' to i
                    }
                }
                i++
            }
            return null to n
        }

        val c = codeMasked[i]
        return when (c) {
            '=' -> {
                val next = if (i + 1 < n) codeMasked[i + 1] else '\u0000'
                if (next != '=' && next != '>') '=' to i else null to i
            }
            '{', '}', ';' -> c to i
            else -> null to i
        }
    }

    private fun analyzeSourceLexicalModel(codeRaw: String, codeMasked: String): SourceLexicalModel {
        val (scopes, scopeById) = buildLexicalScopes(codeMasked)
        val parenBracketDepths = buildParentDepthArray(codeMasked)
        val bindingsByName = mutableMapOf<String, MutableList<VarBinding>>()
        val functions = mutableListOf<FunctionRange>()
        var nextBindingId = 1

        fun addBinding(binding: VarBinding) {
            bindingsByName.getOrPut(binding.name) { mutableListOf() }.add(binding)
        }

        // 1. 提取函数声明范围与函数参数遮蔽绑定
        val n = codeMasked.length
        for (funMatch in FUN_ANCHOR_REGEX.findAll(codeMasked)) {
            if (parenBracketDepths[funMatch.range.first] != 0) continue
            var cursor = funMatch.range.last + 1
            // 跳过可选泛型 <T, R>
            while (cursor < n && codeMasked[cursor].isWhitespace()) cursor++
            if (cursor < n && codeMasked[cursor] == '<') {
                var angle = 0
                while (cursor < n) {
                    when (codeMasked[cursor]) {
                        '<' -> angle++
                        '>' -> {
                            angle--
                            if (angle == 0) {
                                cursor++
                                break
                            }
                        }
                    }
                    cursor++
                }
            }
            // 跳过函数名及可选接收者类型，直到参数列表 '('
            while (cursor < n && codeMasked[cursor] != '(' && codeMasked[cursor] != '{' && codeMasked[cursor] != ';' && codeMasked[cursor] != '=') {
                cursor++
            }
            if (cursor >= n || codeMasked[cursor] != '(') continue
            val openParenIdx = cursor
            val closeParenAfter = findMatchingCallEnd(codeMasked, openParenIdx)
            val closeParenIdx = (closeParenAfter - 1).coerceAtLeast(openParenIdx)
            val paramsText = codeMasked.substring(openParenIdx + 1, closeParenIdx)
            val paramNames = splitTopLevelArgs(paramsText).mapNotNull { extractParamName(it) }

            val (bodyCh, bodyPos) = skipTypeAndFindEqualOrBody(codeMasked, closeParenAfter)
            when (bodyCh) {
                '{' -> {
                    val bodyScope = scopes.firstOrNull { it.start == bodyPos }
                    if (bodyScope != null) {
                        functions.add(FunctionRange(funMatch.range.first, bodyScope.end, bodyScope.id))
                        for (pName in paramNames) {
                            addBinding(
                                VarBinding(
                                    id = nextBindingId++,
                                    name = pName,
                                    scopeId = bodyScope.id,
                                    scopeStart = bodyScope.start,
                                    scopeEnd = bodyScope.end,
                                    availableFrom = openParenIdx,
                                    rhsMasked = "",
                                    rhsRaw = "",
                                    isShadowOnly = true
                                )
                            )
                        }
                    }
                }
                '=' -> {
                    val exprEnd = findExpressionEnd(codeMasked, bodyPos + 1)
                    functions.add(FunctionRange(funMatch.range.first, exprEnd, null))
                    for (pName in paramNames) {
                        addBinding(
                            VarBinding(
                                id = nextBindingId++,
                                name = pName,
                                scopeId = -1,
                                scopeStart = bodyPos,
                                scopeEnd = exprEnd,
                                availableFrom = openParenIdx,
                                rhsMasked = "",
                                rhsRaw = "",
                                isShadowOnly = true
                            )
                        )
                    }
                }
            }
        }

        // 2. 提取 val / var 声明（非回溯线性类型扫描）
        for (match in VAL_VAR_ANCHOR_REGEX.findAll(codeMasked)) {
            if (parenBracketDepths[match.range.first] != 0) continue
            val varName = match.groupValues[2]
            val afterName = match.range.last + 1
            val directScope = innermostScopeAt(match.range.first, scopes)
            val (effectiveScope, availableFrom) = resolveEffectiveScope(directScope, match.range.first, scopeById)
            val (termCh, termPos) = skipTypeAndFindEqualOrBody(codeMasked, afterName)

            if (termCh == '=') {
                val rhsStart = termPos + 1
                if (rhsStart >= n) continue
                val rhsEnd = findExpressionEnd(codeMasked, rhsStart)
                if (rhsEnd <= rhsStart) continue
                val rhsMasked = codeMasked.substring(rhsStart, rhsEnd).trim()
                val rhsRaw = codeRaw.substring(rhsStart, rhsEnd).trim()
                if (rhsMasked.isNotEmpty()) {
                    addBinding(
                        VarBinding(
                            id = nextBindingId++,
                            name = varName,
                            scopeId = effectiveScope.id,
                            scopeStart = effectiveScope.start,
                            scopeEnd = effectiveScope.end,
                            availableFrom = availableFrom,
                            rhsMasked = rhsMasked,
                            rhsRaw = rhsRaw,
                            isShadowOnly = false
                        )
                    )
                }
            } else {
                // 未立即赋值的局部声明（如 `var x: Int`），同样遮蔽外层同名常量
                addBinding(
                    VarBinding(
                        id = nextBindingId++,
                        name = varName,
                        scopeId = effectiveScope.id,
                        scopeStart = effectiveScope.start,
                        scopeEnd = effectiveScope.end,
                        availableFrom = availableFrom,
                        rhsMasked = "",
                        rhsRaw = "",
                        isShadowOnly = true
                    )
                )
            }
        }

        // 3. 提取语句级变量重赋值 `x = newExpr`（仅 parenDepth == 0 && bracketDepth == 0，排除具名参数与比较）
        for (match in REASSIGN_ANCHOR_REGEX.findAll(codeMasked)) {
            val nameStart = match.range.first
            if (parenBracketDepths[nameStart] != 0) continue
            val varName = match.groupValues[1]
            if (varName in REASSIGN_EXCLUDED_NAMES) continue

            // 确认变量名之前紧邻的非空白字符为语句边界（文件头、\n、;、{、}），避免 `val x =` 重复进入
            var prev = nameStart - 1
            while (prev >= 0 && (codeMasked[prev] == ' ' || codeMasked[prev] == '\t' || codeMasked[prev] == '\r')) {
                prev--
            }
            if (prev >= 0 && codeMasked[prev] !in charArrayOf('\n', ';', '{', '}')) continue

            val rhsStart = match.range.last + 1
            if (rhsStart >= n) continue
            val rhsEnd = findExpressionEnd(codeMasked, rhsStart)
            if (rhsEnd <= rhsStart) continue
            val rhsMasked = codeMasked.substring(rhsStart, rhsEnd).trim()
            val rhsRaw = codeRaw.substring(rhsStart, rhsEnd).trim()
            if (rhsMasked.isEmpty()) continue

            val directScope = innermostScopeAt(nameStart, scopes)
            val (effectiveScope, _) = resolveEffectiveScope(directScope, nameStart, scopeById)
            addBinding(
                VarBinding(
                    id = nextBindingId++,
                    name = varName,
                    scopeId = effectiveScope.id,
                    scopeStart = effectiveScope.start,
                    scopeEnd = effectiveScope.end,
                    availableFrom = nameStart,
                    rhsMasked = rhsMasked,
                    rhsRaw = rhsRaw,
                    isShadowOnly = false
                )
            )
        }

        return SourceLexicalModel(
            scopes = scopes,
            scopeById = scopeById,
            functions = functions,
            bindingsByName = bindingsByName,
            parenBracketDepths = parenBracketDepths
        )
    }

    private fun findExpressionEnd(codeMasked: String, startIndex: Int): Int {
        val n = codeMasked.length
        var i = startIndex
        var parenDepth = 0
        var bracketDepth = 0
        var braceDepth = 0
        var inString = false
        var inChar = false
        val limit = minOf(n, startIndex + 1500)

        while (i < limit) {
            val c = codeMasked[i]
            when {
                inString -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '\'' || c == '\n') inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> parenDepth++
                c == ')' -> {
                    if (parenDepth > 0) parenDepth-- else if (bracketDepth == 0 && braceDepth == 0) return i
                }
                c == '[' -> bracketDepth++
                c == ']' -> {
                    if (bracketDepth > 0) bracketDepth-- else if (parenDepth == 0 && braceDepth == 0) return i
                }
                c == '{' -> braceDepth++
                c == '}' -> {
                    if (braceDepth > 0) braceDepth-- else if (parenDepth == 0 && bracketDepth == 0) return i
                }
                (c == ';' || c == '\n') && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0 -> {
                    if (c == ';') return i
                    // 检查当前换行前是否以续行符号结尾，或下一行是否以续行符号开头
                    if (!isLineContinued(codeMasked, startIndex, i)) {
                        return i
                    }
                }
            }
            i++
        }
        return i
    }

    private fun isLineContinued(code: String, exprStart: Int, newlineIndex: Int): Boolean {
        // 向前寻找最近非空格/非tab字符
        var p = newlineIndex - 1
        while (p >= exprStart && (code[p] == ' ' || code[p] == '\t' || code[p] == '\r')) {
            p--
        }
        if (p < exprStart) {
            // '=' 后立即换行，例如 `const val FLAGS =\n PendingIntent...`
            return true
        }
        val prevChar = code[p]
        if (prevChar in charArrayOf('=', '+', '-', '*', '/', '.', ',', '|', '&', ':')) {
            return true
        }
        if (prevChar.isLetterOrDigit() || prevChar == '_') {
            var wordStart = p
            while (wordStart > exprStart && (code[wordStart - 1].isLetterOrDigit() || code[wordStart - 1] == '_')) {
                wordStart--
            }
            val lastWord = code.substring(wordStart, p + 1)
            if (lastWord in CONTINUATION_END_KEYWORDS) return true
        }
        // 向后跳过空白与换行检查下一个 token
        var q = newlineIndex + 1
        while (q < code.length && code[q].isWhitespace()) {
            q++
        }
        if (q >= code.length) return false
        val remaining = code.substring(q, minOf(code.length, q + 20))
        return CONTINUATION_START_REGEX.containsMatchIn(remaining)
    }

    private val IDENTIFIER_NOT_AFTER_DOT_REGEX = Regex("""(?<!\.)\b([A-Za-z_]\w*)\b""")

    private fun resolveBinding(
        name: String,
        refOffset: Int,
        model: SourceLexicalModel,
        excludedBindingIds: Set<Int> = emptySet()
    ): VarBinding? {
        val candidates = model.bindingsByName[name] ?: return null
        var best: VarBinding? = null
        var bestSpan = Int.MAX_VALUE
        for (b in candidates) {
            if (b.id in excludedBindingIds) continue
            if (b.scopeStart <= refOffset && refOffset <= b.scopeEnd && b.availableFrom <= refOffset) {
                val span = b.scopeEnd - b.scopeStart
                if (best == null || span < bestSpan || (span == bestSpan && b.availableFrom >= best.availableFrom)) {
                    best = b
                    bestSpan = span
                }
            }
        }
        return best
    }

    private fun expandExpressionWithBindings(
        exprRaw: String,
        exprMasked: String,
        refOffset: Int,
        model: SourceLexicalModel,
        useRawStrings: Boolean,
        depth: Int = 8,
        visitedBindingIds: Set<Int> = emptySet()
    ): String {
        val target = if (useRawStrings) exprRaw else exprMasked
        if (depth <= 0 || model.bindingsByName.isEmpty() || exprMasked.isBlank()) return target

        // 确保用于扫描标识符的掩码视图与 target 等长对齐
        val scanView = if (exprMasked.length == target.length) {
            exprMasked
        } else {
            preprocessKotlinSource(target).codeNoCommentsMaskedStrings
        }

        val sb = StringBuilder(target.length)
        var cursor = 0
        var replaced = false
        for (m in IDENTIFIER_NOT_AFTER_DOT_REGEX.findAll(scanView)) {
            val ident = m.groupValues[1]

            // 跳过具名参数名 `ident = ...`
            var afterIdent = m.range.last + 1
            while (afterIdent < scanView.length && scanView[afterIdent].isWhitespace()) afterIdent++
            if (afterIdent < scanView.length && scanView[afterIdent] == '=' &&
                (afterIdent + 1 >= scanView.length || (scanView[afterIdent + 1] != '=' && scanView[afterIdent + 1] != '>'))
            ) {
                continue
            }

            val binding = resolveBinding(ident, refOffset, model, visitedBindingIds) ?: continue
            val nextRefOffset = if (binding.availableFrom > binding.scopeStart) {
                binding.availableFrom - 1
            } else {
                binding.availableFrom
            }
            val replacement = if (binding.isShadowOnly) {
                "__SHADOWED__"
            } else {
                expandExpressionWithBindings(
                    exprRaw = binding.rhsRaw,
                    exprMasked = binding.rhsMasked,
                    refOffset = nextRefOffset,
                    model = model,
                    useRawStrings = useRawStrings,
                    depth = depth - 1,
                    visitedBindingIds = visitedBindingIds + binding.id
                )
            }
            sb.append(target, cursor, m.range.first)
            sb.append(replacement)
            cursor = m.range.last + 1
            replaced = true
        }
        if (!replaced) return target
        sb.append(target, cursor, target.length)
        return sb.toString()
    }

    /**
     * 探测从 `openAngleIdx` 开始的 `<...>` 是否为紧跟标识符的合法闭合泛型实参（支持嵌套 `<>` 与函数类型 `()` / `->`）。
     * 若合法且成对闭合则返回闭合 `>` 的索引，否则返回 `-1`。
     */
    private fun findClosedGenericArgEnd(text: String, openAngleIdx: Int): Int {
        val n = text.length
        if (openAngleIdx <= 0 || openAngleIdx >= n || text[openAngleIdx] != '<') return -1
        val prev = text[openAngleIdx - 1]
        if (!prev.isLetterOrDigit() && prev != '_') return -1

        var angleDepth = 1
        var innerParen = 0
        var hasContent = false
        var j = openAngleIdx + 1
        while (j < n) {
            val ch = text[j]
            when {
                ch == '<' -> {
                    angleDepth++
                    hasContent = true
                }
                ch == '>' -> {
                    // `->` 箭头不是泛型闭合尖括号
                    if (j > openAngleIdx + 1 && text[j - 1] == '-') {
                        j++
                        continue
                    }
                    angleDepth--
                    if (angleDepth == 0) {
                        if (!hasContent || innerParen != 0) return -1
                        var after = j + 1
                        while (after < n && text[after].isWhitespace()) after++
                        if (after < n) {
                            val nextCh = text[after]
                            if (nextCh.isLetterOrDigit() || nextCh == '_') return -1
                        }
                        return j
                    }
                }
                ch == '(' -> {
                    innerParen++
                    hasContent = true
                }
                ch == ')' -> {
                    if (innerParen == 0) return -1
                    innerParen--
                }
                ch == '-' -> {
                    if (j + 1 >= n || text[j + 1] != '>') return -1
                    hasContent = true
                }
                ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == ',' || ch == '*' || ch == '?' || ch.isWhitespace() -> {
                    if (!ch.isWhitespace()) hasContent = true
                }
                else -> return -1
            }
            j++
        }
        return -1
    }

    /**
     * 按顶层逗号（括号 `()[]{}` 深度为 0、跳过闭合泛型实参 `<...>` 且不在字符串/字符字面量内）分割实参列表，返回相对 `argsMasked` 的 `[start, end)` 区间。
     */
    private fun splitTopLevelArgRanges(argsMasked: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var paren = 0
        var bracket = 0
        var brace = 0
        var inString = false
        var inChar = false
        var lastSplit = 0
        var i = 0
        val n = argsMasked.length

        while (i < n) {
            val c = argsMasked[i]
            when {
                inString -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '\'' || c == '\n') inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '<' -> {
                    val genericEnd = findClosedGenericArgEnd(argsMasked, i)
                    if (genericEnd > i) {
                        i = genericEnd + 1
                        continue
                    }
                }
                c == '(' -> paren++
                c == ')' -> if (paren > 0) paren--
                c == '[' -> bracket++
                c == ']' -> if (bracket > 0) bracket--
                c == '{' -> brace++
                c == '}' -> if (brace > 0) brace--
                c == ',' && paren == 0 && bracket == 0 && brace == 0 -> {
                    ranges.add(lastSplit until i)
                    lastSplit = i + 1
                }
            }
            i++
        }
        if (lastSplit <= n) {
            val tail = argsMasked.substring(lastSplit, n).trim()
            if (tail.isNotEmpty() || ranges.isNotEmpty()) {
                ranges.add(lastSplit until n)
            }
        }
        return ranges
    }

    private fun splitTopLevelArgs(argsText: String): List<String> =
        splitTopLevelArgRanges(argsText).map { r -> argsText.substring(r.first, r.last + 1).trim() }

    private fun selectFlagsArg(
        argsMasked: List<String>,
        defaultPositionalIndex: Int,
        altPositionalIndex: Int? = null
    ): String? {
        val namedRegex = Regex("""^\s*flags\s*=\s*([\s\S]+)$""")
        for (arg in argsMasked) {
            val m = namedRegex.matchEntire(arg)
            if (m != null) {
                return m.groupValues[1].trim()
            }
        }
        val targetIdx = if (altPositionalIndex != null && argsMasked.size >= 6) altPositionalIndex else defaultPositionalIndex
        val positional = argsMasked.getOrNull(targetIdx)?.trim() ?: return null
        if (Regex("""^\s*[A-Za-z_]\w*\s*=(?![=>])""").containsMatchIn(positional)) {
            return null
        }
        return positional
    }

    private fun containsSuCommandLiteral(code: String): Boolean {
        val n = code.length
        var i = 0

        fun literalHasSuToken(content: String): Boolean {
            if (content.isEmpty()) return false
            var start = 0
            val len = content.length
            while (start < len) {
                while (start < len && content[start].isWhitespace()) start++
                if (start >= len) break
                var end = start
                while (end < len && !content[end].isWhitespace()) end++
                val token = content.substring(start, end).trim('\'', '"', '(', ')', ';', ',')
                if (token == "su" || token.endsWith("/su") || token.endsWith("\\su")) {
                    return true
                }
                start = end
            }
            return false
        }

        while (i < n) {
            val c = code[i]
            if (c == '"' && i + 2 < n && code[i + 1] == '"' && code[i + 2] == '"') {
                val contentStart = i + 3
                var j = contentStart
                while (j < n) {
                    if (code[j] == '"') {
                        var q = 0
                        while (j + q < n && code[j + q] == '"') q++
                        if (q >= 3) {
                            val contentEnd = j + q - 3
                            if (literalHasSuToken(code.substring(contentStart, contentEnd))) {
                                return true
                            }
                            i = j + q
                            break
                        }
                        j += q
                    } else {
                        j++
                    }
                }
                if (j >= n) {
                    if (literalHasSuToken(code.substring(contentStart, n))) return true
                    break
                }
                continue
            } else if (c == '"') {
                val sb = StringBuilder()
                var j = i + 1
                while (j < n) {
                    val sc = code[j]
                    if (sc == '\\' && j + 1 < n) {
                        val esc = code[j + 1]
                        sb.append(
                            when (esc) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                else -> esc
                            }
                        )
                        j += 2
                    } else if (sc == '"') {
                        j++
                        break
                    } else {
                        sb.append(sc)
                        j++
                    }
                }
                if (literalHasSuToken(sb.toString())) return true
                i = j
                continue
            } else if (c == '\'') {
                var j = i + 1
                while (j < n) {
                    if (code[j] == '\\' && j + 1 < n) {
                        j += 2
                    } else if (code[j] == '\'' || code[j] == '\n') {
                        j++
                        break
                    } else {
                        j++
                    }
                }
                i = j
                continue
            }
            i++
        }
        return false
    }

    private fun collectLeadingFunctionModifiers(codeMasked: String, fnKeywordOffset: Int): Set<String> {
        val modifiers = mutableSetOf<String>()
        var idx = fnKeywordOffset - 1
        while (idx >= 0) {
            while (idx >= 0 && codeMasked[idx].isWhitespace()) idx--
            if (idx < 0) break

            val ch = codeMasked[idx]
            if (ch == ')') {
                // 跳过注解参数列表 @Annot(...)
                var depth = 0
                while (idx >= 0) {
                    when (codeMasked[idx]) {
                        ')' -> depth++
                        '(' -> {
                            depth--
                            if (depth == 0) {
                                idx--
                                break
                            }
                        }
                    }
                    idx--
                }
                continue
            }
            if (ch == '>' && idx > 0 && codeMasked[idx - 1] != '-') {
                // 跳过注解泛型参数 @Annot<...>
                var angle = 0
                while (idx >= 0) {
                    when (codeMasked[idx]) {
                        '>' -> angle++
                        '<' -> {
                            angle--
                            if (angle == 0) {
                                idx--
                                break
                            }
                        }
                    }
                    idx--
                }
                continue
            }
            if (ch.isLetterOrDigit() || ch == '_') {
                val end = idx + 1
                while (idx >= 0 && (codeMasked[idx].isLetterOrDigit() || codeMasked[idx] == '_')) idx--
                val word = codeMasked.substring(idx + 1, end)
                if (idx >= 0 && codeMasked[idx] == '@') {
                    idx--
                    continue
                }
                if (idx >= 0 && (codeMasked[idx] == '.' || codeMasked[idx] == ':')) {
                    // 可能是带包名限定或站点前缀的注解（例如 @kotlin.jvm.JvmStatic、@receiver:JvmStatic）
                    var scan = idx
                    var foundAt = false
                    while (scan >= 0) {
                        val sc = codeMasked[scan]
                        if (sc == '@') {
                            foundAt = true
                            scan--
                            break
                        }
                        if (sc.isLetterOrDigit() || sc == '_' || sc == '.' || sc == ':') {
                            scan--
                        } else {
                            break
                        }
                    }
                    if (foundAt) {
                        idx = scan
                        continue
                    }
                    break
                }
                if (word in KOTLIN_MODIFIER_KEYWORDS) {
                    modifiers.add(word)
                    continue
                }
                break
            }
            break
        }
        return modifiers
    }

    private fun isFunctionDeclarationBefore(codeMasked: String, fnNameStart: Int): Boolean {
        var idx = fnNameStart - 1
        while (idx >= 0 && codeMasked[idx].isWhitespace()) idx--
        if (idx >= 0 && codeMasked[idx] == '.') {
            // 跳过接收者类型 (含泛型 <...>)
            idx--
            var angle = 0
            while (idx >= 0) {
                val c = codeMasked[idx]
                when {
                    c == '>' -> angle++
                    c == '<' -> if (angle > 0) angle-- else break
                    angle == 0 && (c == ';' || c == '{' || c == '}' || c == '\n') -> {
                        // 如果是跨行 `fun Context\n.registerReceiver`，允许继续往上一行找 `fun`
                        if (c != '\n') break
                    }
                    angle == 0 && (c.isLetterOrDigit() || c == '_' || c == '.' || c == '?' || c == '*' || c.isWhitespace()) -> Unit
                    else -> break
                }
                // 检查是否已退回到关键字 fun
                if (angle == 0 && idx + 3 <= fnNameStart &&
                    codeMasked.startsWith("fun", idx) &&
                    (idx == 0 || (!codeMasked[idx - 1].isLetterOrDigit() && codeMasked[idx - 1] != '_')) &&
                    (idx + 3 >= codeMasked.length || codeMasked[idx + 3].isWhitespace() || codeMasked[idx + 3] == '<')
                ) {
                    return true
                }
                idx--
            }
            return false
        }
        // 跳过可选泛型 <T, R>
        if (idx >= 0 && codeMasked[idx] == '>') {
            var angle = 0
            while (idx >= 0) {
                when (codeMasked[idx]) {
                    '>' -> angle++
                    '<' -> {
                        angle--
                        if (angle == 0) {
                            idx--
                            break
                        }
                    }
                }
                idx--
            }
            while (idx >= 0 && codeMasked[idx].isWhitespace()) idx--
        }
        val end = idx + 1
        while (idx >= 0 && (codeMasked[idx].isLetterOrDigit() || codeMasked[idx] == '_')) idx--
        val word = if (end > idx + 1) codeMasked.substring(idx + 1, end) else ""
        return word == "fun"
    }

    private fun matchesDotExecuteAt(codeMasked: String, startPos: Int): Boolean {
        val n = codeMasked.length
        var pos = startPos
        while (pos < n && codeMasked[pos].isWhitespace()) pos++
        if (pos >= n || codeMasked[pos] != '.') return false
        pos++
        while (pos < n && codeMasked[pos].isWhitespace()) pos++
        if (!codeMasked.startsWith("execute", pos)) return false
        val afterExec = pos + 7
        if (afterExec < n && (codeMasked[afterExec].isLetterOrDigit() || codeMasked[afterExec] == '_')) {
            return false
        }
        pos = afterExec
        while (pos < n && codeMasked[pos].isWhitespace()) pos++
        return pos < n && codeMasked[pos] == '('
    }

    private fun findOkHttpSyncCallOffsets(codeMasked: String): List<Int> {
        val offsets = mutableListOf<Int>()
        for (m in SYNC_NETWORK_SIMPLE_REGEX.findAll(codeMasked)) {
            offsets.add(m.range.first)
        }
        for (m in OKHTTP_NEW_CALL_REGEX.findAll(codeMasked)) {
            val callEnd = findMatchingCallEnd(codeMasked, m.range.last)
            if (matchesDotExecuteAt(codeMasked, callEnd)) {
                offsets.add(m.range.first)
            }
        }
        return offsets
    }

    private val ENCLOSING_CONTROL_KEYWORDS = setOf(
        "if", "else", "when", "for", "while", "catch", "finally", "try", "do", "synchronized",
        "fun", "class", "object", "interface", "val", "var", "return", "throw"
    )
    private val IO_DISPATCHER_REGEX = Regex("""\bDispatchers\s*\.\s*IO\b""")
    private val EXECUTORS_IO_REGEX = Regex(
        """\bExecutors\s*\.\s*(?:newCachedThreadPool|newFixedThreadPool|newSingleThreadExecutor|newScheduledThreadPool|newWorkStealingPool|newVirtualThreadPerTaskExecutor)\b"""
    )
    private val MAIN_DISPATCHER_OR_UI_REGEX = Regex(
        """\bDispatchers\s*\.\s*Main\b|\bMainScope\s*\(|\brunOnUiThread\b|\bLooper\s*\.\s*getMainLooper\s*\("""
    )
    private val NON_SWITCHING_SCOPE_FUNCTIONS = setOf(
        "let", "also", "apply", "run", "takeIf", "takeUnless"
    )

    private fun extractTrailingCallName(header: String): String? {
        var p = header.length - 1
        while (p >= 0 && header[p].isWhitespace()) p--
        if (p < 0) return null
        if (header[p] == ')') {
            var depth = 0
            while (p >= 0) {
                when (header[p]) {
                    ')' -> depth++
                    '(' -> {
                        depth--
                        if (depth == 0) {
                            p--
                            break
                        }
                    }
                }
                p--
            }
            while (p >= 0 && header[p].isWhitespace()) p--
        }
        if (p >= 0 && header[p] == '>' && (p == 0 || header[p - 1] != '-')) {
            var angle = 0
            while (p >= 0) {
                when (header[p]) {
                    '>' -> angle++
                    '<' -> {
                        angle--
                        if (angle == 0) {
                            p--
                            break
                        }
                    }
                }
                p--
            }
            while (p >= 0 && header[p].isWhitespace()) p--
        }
        if (p < 0 || (!header[p].isLetterOrDigit() && header[p] != '_')) return null
        val end = p + 1
        while (p >= 0 && (header[p].isLetterOrDigit() || header[p] == '_')) p--
        return header.substring(p + 1, end)
    }

    private fun findEnclosingCallHeader(codeMasked: String, openBraceIdx: Int): String? {
        var p = openBraceIdx - 1
        while (p >= 0 && codeMasked[p].isWhitespace()) p--
        if (p < 0) return null

        // 若紧邻 '{' 之前是 '->'（如 lambda 参数或 when 分支），不构成调度器调用头
        if (codeMasked[p] == '>' && p > 0 && codeMasked[p - 1] == '-') return null

        var chainStart = p + 1
        while (p >= 0) {
            while (p >= 0 && codeMasked[p].isWhitespace()) p--
            if (p < 0) break
            val ch = codeMasked[p]
            when {
                ch == ')' -> {
                    var depth = 0
                    var matchedOpen = -1
                    while (p >= 0) {
                        when (codeMasked[p]) {
                            ')' -> depth++
                            '(' -> {
                                depth--
                                if (depth == 0) {
                                    matchedOpen = p
                                    break
                                }
                            }
                        }
                        p--
                    }
                    if (matchedOpen < 0) break
                    chainStart = matchedOpen
                    p = matchedOpen - 1
                    // 允许 '(' 前带可选泛型 <...>
                    while (p >= 0 && codeMasked[p].isWhitespace()) p--
                    if (p >= 0 && codeMasked[p] == '>' && (p == 0 || codeMasked[p - 1] != '-')) {
                        var angle = 0
                        var matchedAngle = -1
                        while (p >= 0) {
                            when (codeMasked[p]) {
                                '>' -> angle++
                                '<' -> {
                                    angle--
                                    if (angle == 0) {
                                        matchedAngle = p
                                        break
                                    }
                                }
                            }
                            p--
                        }
                        if (matchedAngle >= 0) {
                            chainStart = matchedAngle
                            p = matchedAngle - 1
                        }
                    }
                    // 括号后左侧必须紧接标识符（否则停止，如 `if (cond)` 中的 `)` 后跟 `withContext`）
                    while (p >= 0 && codeMasked[p].isWhitespace()) p--
                    if (p < 0 || (!codeMasked[p].isLetterOrDigit() && codeMasked[p] != '_')) {
                        break
                    }
                }
                ch.isLetterOrDigit() || ch == '_' -> {
                    val wordEnd = p + 1
                    while (p >= 0 && (codeMasked[p].isLetterOrDigit() || codeMasked[p] == '_')) p--
                    val wordStart = p + 1
                    val word = codeMasked.substring(wordStart, wordEnd)
                    if (word in ENCLOSING_CONTROL_KEYWORDS) {
                        // 若遇到控制流头本身（如 `if (...) {` 或 `when (...) {`），直接否决
                        if (word in setOf("if", "when", "for", "while", "catch", "synchronized", "fun", "class", "object", "interface")) {
                            return null
                        }
                        break
                    }
                    chainStart = wordStart
                    // 检查该标识符左侧跳过空白后是否为 '.' 或 '?.'，若是则继续向左追溯调用链
                    var q = p
                    while (q >= 0 && codeMasked[q].isWhitespace()) q--
                    if (q >= 0 && codeMasked[q] == '.') {
                        q--
                        if (q >= 0 && codeMasked[q] == '?') q--
                        p = q
                        continue
                    }
                    break
                }
                else -> break
            }
        }
        val header = codeMasked.substring(chainStart, openBraceIdx).trim()
        return header.takeIf { it.isNotEmpty() }
    }

    private fun isSyncCallProtectedByIoDispatcher(
        syncOffset: Int,
        codeMasked: String,
        model: SourceLexicalModel
    ): Boolean {
        val fnRange = model.functions
            .filter { it.start <= syncOffset && syncOffset <= it.end }
            .minByOrNull { it.end - it.start }
        val fnBodyScopeIds = model.functions.mapNotNull { it.bodyScopeId }.toSet()

        val localScopes = model.scopes
            .filter { s ->
                s.kind == ScopeKind.LOCAL &&
                    s.id !in fnBodyScopeIds &&
                    s.start <= syncOffset && syncOffset <= s.end &&
                    (fnRange == null || (s.start >= fnRange.start && s.end <= fnRange.end))
            }
            .sortedBy { it.end - it.start }

        for (scope in localScopes) {
            // 由内向外依据严格包围该网络调用的祖先 LOCAL 块调用头判定：内层切回主线程立即阻断，且排除非切线程作用域函数
            val callHeader = findEnclosingCallHeader(codeMasked, scope.start) ?: continue
            val expandedHeader = expandExpressionWithBindings(
                exprRaw = callHeader,
                exprMasked = callHeader,
                refOffset = scope.start,
                model = model,
                useRawStrings = false
            )
            if (MAIN_DISPATCHER_OR_UI_REGEX.containsMatchIn(expandedHeader)) {
                return false
            }
            val trailingCall = extractTrailingCallName(expandedHeader)
            if (trailingCall in NON_SWITCHING_SCOPE_FUNCTIONS) {
                continue
            }
            if (IO_DISPATCHER_REGEX.containsMatchIn(expandedHeader) ||
                EXECUTORS_IO_REGEX.containsMatchIn(expandedHeader)
            ) {
                return true
            }
        }

        return false
    }

    private fun isUiLayerPath(normalizedPath: String): Boolean {
        val dirMarkers = listOf("presentation/", "ui/", "map/", "adapter/")
        if (dirMarkers.any { marker ->
                normalizedPath.startsWith(marker) || normalizedPath.contains("/$marker")
            }
        ) {
            return true
        }
        val fileSuffixes = listOf(
            "Activity.kt",
            "Fragment.kt",
            "ViewModel.kt",
            "Dialog.kt",
            "BottomSheet.kt"
        )
        return fileSuffixes.any { normalizedPath.endsWith(it) }
    }

    private fun isStrictUiComponentPath(normalizedPath: String): Boolean {
        val strictSuffixes = listOf("Activity.kt", "Fragment.kt", "Dialog.kt", "BottomSheet.kt")
        if (strictSuffixes.any { normalizedPath.endsWith(it) }) return true
        val uiDirMarkers = listOf("presentation/", "ui/", "map/", "adapter/")
        val inUiDir = uiDirMarkers.any { normalizedPath.startsWith(it) || normalizedPath.contains("/$it") }
        return inUiDir && !normalizedPath.endsWith("ViewModel.kt")
    }

    private val CONTEXT_COMPAT_REGISTER_REGEX = Regex(
        """(?:androidx\s*\.\s*core\s*\.\s*content\s*\.\s*)?ContextCompat\s*\.\s*registerReceiver\s*\("""
    )
    private val BARE_REGISTER_RECEIVER_REGEX = Regex(
        """(?<!\w)registerReceiver\s*\("""
    )
    private val PENDING_INTENT_CALL_REGEX = Regex(
        """PendingIntent\s*\.\s*(getActivity|getActivities|getBroadcast|getService|getForegroundService)\s*\("""
    )
    private val HOOK_ALL_METHODS_CALL_REGEX = Regex(
        """(?:XposedBridge\s*\.\s*|(?<![\w.]))hookAllMethods\s*\("""
    )
    private val INTERFACE_LISTENER_NAMES = listOf(
        "TencentLocationListener",
        "AMapLocationListener",
        "BDLocationListener",
        "BDAbstractLocationListener",
        "CellInfoListener",
        "LocationListener",
        "OnNmeaMessageListener",
        "LocationCallback",
        "ScanResultsCallback"
    )
    private val INTERFACE_LISTENER_WORD_REGEX = Regex(
        """\b(${INTERFACE_LISTENER_NAMES.joinToString("|") { Regex.escape(it) }})\b"""
    )
    private val STUB_TODO_REGEX = Regex("""\bTODO\s*\(|(?:throw\s+)?\bNotImplementedError\s*\(""")
    private val RUNTIME_EXEC_REGEX = Regex("""\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(""")
    private val RUNTIME_GET_REGEX = Regex("""\bRuntime\s*\.\s*getRuntime\s*\(\s*\)""")
    private val PROCESS_BUILDER_CALL_REGEX = Regex("""\bProcessBuilder\s*\(""")
    private val SYNC_NETWORK_SIMPLE_REGEX = Regex(
        """\.\s*openConnection\s*\(|\bHttpURLConnection\b|\bHttpsURLConnection\b"""
    )
    private val OKHTTP_NEW_CALL_REGEX = Regex("""\.\s*newCall\s*\(""")
    private val RECEIVER_DOT_EXEC_REGEX = Regex("""\b([A-Za-z_]\w*)\s*\.\s*exec\s*\(""")
    private val RECEIVER_DOT_COMMAND_REGEX = Regex("""\b([A-Za-z_]\w*)\s*\.\s*command\s*\(""")

    private fun findDetectEnvironmentFunOffsets(codeMasked: String, model: SourceLexicalModel): List<Int> {
        val n = codeMasked.length
        val offsets = mutableListOf<Int>()
        for (funMatch in FUN_ANCHOR_REGEX.findAll(codeMasked)) {
            val fnStart = funMatch.range.first
            if (model.parenBracketDepths[fnStart] != 0) continue
            var cursor = funMatch.range.last + 1
            while (cursor < n && codeMasked[cursor].isWhitespace()) cursor++
            if (cursor < n && codeMasked[cursor] == '<') {
                var angle = 0
                while (cursor < n) {
                    when (codeMasked[cursor]) {
                        '<' -> angle++
                        '>' -> {
                            angle--
                            if (angle == 0) {
                                cursor++
                                break
                            }
                        }
                    }
                    cursor++
                }
            }
            val nameRegionStart = cursor
            while (cursor < n && codeMasked[cursor] != '(' && codeMasked[cursor] != '{' && codeMasked[cursor] != ';' && codeMasked[cursor] != '=') {
                cursor++
            }
            if (cursor >= n || codeMasked[cursor] != '(') continue
            // 提取紧邻 '(' 前的函数名（兼容接收者类型如 Context.detectEnvironment）
            var nameEnd = cursor
            while (nameEnd > nameRegionStart && codeMasked[nameEnd - 1].isWhitespace()) nameEnd--
            var nameStart = nameEnd
            while (nameStart > nameRegionStart && (codeMasked[nameStart - 1].isLetterOrDigit() || codeMasked[nameStart - 1] == '_')) {
                nameStart--
            }
            val fnName = codeMasked.substring(nameStart, nameEnd)
            if (fnName == "detectEnvironment") {
                offsets.add(fnStart)
            }
        }
        return offsets
    }

    /**
     * 静态规则检测器：检查源码中的架构违规与 Android 14–17 兼容性反模式。
     * @param kotlinSources key 为相对文件路径（例如 `"presentation/MainActivity.kt"`），value 为源码文本。
     */
    fun checkSourceComplianceRules(kotlinSources: Map<String, String>): ComplianceReport {
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        for ((path, rawSource) in kotlinSources.toSortedMap()) {
            val prep = preprocessKotlinSource(rawSource)
            val codeRaw = prep.codeNoComments
            val codeMasked = prep.codeNoCommentsMaskedStrings
            val normalizedPath = path.replace('\\', '/')
            val model = analyzeSourceLexicalModel(codeRaw, codeMasked)

            // 规则 1：UI/Presentation 层禁止直接调用 ShellUtil / su / 同步阻塞网络；EnvironmentDetector.detectEnvironment 必须 suspend
            if (isUiLayerPath(normalizedPath)) {
                if (Regex("""\bShellUtil\b""").containsMatchIn(codeMasked)) {
                    violations.add("[$normalizedPath] UI/Presentation layer must not reference or invoke ShellUtil directly")
                }

                var hasShellExecViolation = RUNTIME_EXEC_REGEX.containsMatchIn(codeMasked)

                if (!hasShellExecViolation) {
                    // 作用域安全追踪 `val rt = Runtime.getRuntime(); rt.exec(...)`
                    for (execMatch in RECEIVER_DOT_EXEC_REGEX.findAll(codeMasked)) {
                        val varName = execMatch.groupValues[1]
                        val binding = resolveBinding(varName, execMatch.range.first, model) ?: continue
                        if (!binding.isShadowOnly && RUNTIME_GET_REGEX.containsMatchIn(binding.rhsMasked)) {
                            hasShellExecViolation = true
                            break
                        }
                    }
                }

                if (!hasShellExecViolation) {
                    // 检查 ProcessBuilder("su") / ProcessBuilder(listOf("su", ...)) / ProcessBuilder("/system/bin/su")
                    for (pbMatch in PROCESS_BUILDER_CALL_REGEX.findAll(codeMasked)) {
                        val callEnd = findMatchingCallEnd(codeMasked, pbMatch.range.last)
                        val callRaw = codeRaw.substring(pbMatch.range.first, callEnd)
                        val callMasked = codeMasked.substring(pbMatch.range.first, callEnd)
                        val expandedRaw = expandExpressionWithBindings(
                            exprRaw = callRaw,
                            exprMasked = callMasked,
                            refOffset = pbMatch.range.first,
                            model = model,
                            useRawStrings = true
                        )
                        if (containsSuCommandLiteral(expandedRaw)) {
                            hasShellExecViolation = true
                            break
                        }
                    }
                }

                if (!hasShellExecViolation) {
                    // 作用域安全追踪 `val pb = ProcessBuilder(...); pb.command("su")`
                    for (cmdMatch in RECEIVER_DOT_COMMAND_REGEX.findAll(codeMasked)) {
                        val varName = cmdMatch.groupValues[1]
                        val binding = resolveBinding(varName, cmdMatch.range.first, model) ?: continue
                        if (!binding.isShadowOnly && (PROCESS_BUILDER_CALL_REGEX.containsMatchIn(binding.rhsMasked) || binding.rhsMasked.contains("ProcessBuilder"))) {
                            val callEnd = findMatchingCallEnd(codeMasked, cmdMatch.range.last)
                            val callRaw = codeRaw.substring(cmdMatch.range.first, callEnd)
                            val callMasked = codeMasked.substring(cmdMatch.range.first, callEnd)
                            val expandedRaw = expandExpressionWithBindings(
                                exprRaw = callRaw,
                                exprMasked = callMasked,
                                refOffset = cmdMatch.range.first,
                                model = model,
                                useRawStrings = true
                            )
                            if (containsSuCommandLiteral(expandedRaw)) {
                                hasShellExecViolation = true
                                break
                            }
                        }
                    }
                }

                if (hasShellExecViolation) {
                    violations.add("[$normalizedPath] UI/Presentation layer must not execute shell/su processes directly")
                }

                // UI 层逐调用/逐函数同步阻塞网络调用检查（含 OkHttp newCall(...).execute() 嵌套括号）
                if (isStrictUiComponentPath(normalizedPath)) {
                    val syncOffsets = findOkHttpSyncCallOffsets(codeMasked)
                    for (syncOffset in syncOffsets) {
                        if (!isSyncCallProtectedByIoDispatcher(syncOffset, codeMasked, model)) {
                            violations.add(
                                "[$normalizedPath] UI layer must not perform synchronous blocking network I/O without Dispatchers.IO"
                            )
                            break
                        }
                    }
                }
            }

            if (normalizedPath.endsWith("EnvironmentDetector.kt")) {
                for (fnOffset in findDetectEnvironmentFunOffsets(codeMasked, model)) {
                    val modifiers = collectLeadingFunctionModifiers(codeMasked, fnOffset)
                    if ("suspend" !in modifiers) {
                        violations.add("[$normalizedPath] EnvironmentDetector.detectEnvironment() must be declared as suspend")
                    }
                }
            }

            // 规则 2：禁止未带 ContextCompat.registerReceiver + RECEIVER_EXPORTED/NOT_EXPORTED 的裸 registerReceiver
            val compatCallParenIndices = CONTEXT_COMPAT_REGISTER_REGEX
                .findAll(codeMasked)
                .map { it.range.last }
                .toSet()

            for (match in BARE_REGISTER_RECEIVER_REGEX.findAll(codeMasked)) {
                val parenIdx = match.range.last
                if (parenIdx in compatCallParenIndices) continue
                if (isFunctionDeclarationBefore(codeMasked, match.range.first)) continue

                violations.add(
                    "[$normalizedPath] Bare registerReceiver() detected without ContextCompat.registerReceiver(..., RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED)"
                )
            }

            for (match in CONTEXT_COMPAT_REGISTER_REGEX.findAll(codeMasked)) {
                val callEnd = findMatchingCallEnd(codeMasked, match.range.last)
                val openParen = match.range.last
                val closeParen = if (callEnd > openParen + 1 && codeMasked[callEnd - 1] == ')') callEnd - 1 else callEnd
                val innerArgsMasked = codeMasked.substring(openParen + 1, closeParen)
                val argsMasked = splitTopLevelArgs(innerArgsMasked)
                val flagsArgMasked = selectFlagsArg(argsMasked, defaultPositionalIndex = 3, altPositionalIndex = 5)
                val expandedFlags = flagsArgMasked?.let {
                    expandExpressionWithBindings(
                        exprRaw = it,
                        exprMasked = it,
                        refOffset = match.range.first,
                        model = model,
                        useRawStrings = false
                    )
                }.orEmpty()
                if (!Regex("""\bRECEIVER_EXPORTED\b""").containsMatchIn(expandedFlags) &&
                    !Regex("""\bRECEIVER_NOT_EXPORTED\b""").containsMatchIn(expandedFlags)
                ) {
                    violations.add(
                        "[$normalizedPath] ContextCompat.registerReceiver() call must specify ContextCompat.RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED"
                    )
                }
            }

            // 规则 3：PendingIntent.get* 必须在其第 4 个实参（或 flags = ...）包含 FLAG_IMMUTABLE
            for (match in PENDING_INTENT_CALL_REGEX.findAll(codeMasked)) {
                val callEnd = findMatchingCallEnd(codeMasked, match.range.last)
                val openParen = match.range.last
                val closeParen = if (callEnd > openParen + 1 && codeMasked[callEnd - 1] == ')') callEnd - 1 else callEnd
                val innerArgsMasked = codeMasked.substring(openParen + 1, closeParen)
                val argsMasked = splitTopLevelArgs(innerArgsMasked)
                val flagsArgMasked = selectFlagsArg(argsMasked, defaultPositionalIndex = 3)
                val expandedFlags = flagsArgMasked?.let {
                    expandExpressionWithBindings(
                        exprRaw = it,
                        exprMasked = it,
                        refOffset = match.range.first,
                        model = model,
                        useRawStrings = false
                    )
                }.orEmpty()
                if (!Regex("""\bFLAG_IMMUTABLE\b""").containsMatchIn(expandedFlags)) {
                    violations.add(
                        "[$normalizedPath] PendingIntent.${match.groupValues[1]}() missing PendingIntent.FLAG_IMMUTABLE"
                    )
                }
            }

            // 规则 4：禁止对第三方 SDK 或系统监听器接口直接 hookAllMethods（仅分析第 1 个实参表达式）
            for (match in HOOK_ALL_METHODS_CALL_REGEX.findAll(codeMasked)) {
                if (isFunctionDeclarationBefore(codeMasked, match.range.first)) continue

                val callEnd = findMatchingCallEnd(codeMasked, match.range.last)
                val openParen = match.range.last
                val closeParen = if (callEnd > openParen + 1 && codeMasked[callEnd - 1] == ')') callEnd - 1 else callEnd
                val innerArgsMasked = codeMasked.substring(openParen + 1, closeParen)
                val argRanges = splitTopLevelArgRanges(innerArgsMasked)
                val firstRange = argRanges.firstOrNull() ?: continue
                val firstArgMasked = innerArgsMasked.substring(firstRange.first, firstRange.last + 1).trim()
                val firstArgRaw = codeRaw.substring(openParen + 1 + firstRange.first, openParen + 1 + firstRange.last + 1).trim()
                if (firstArgMasked.isEmpty()) continue

                val expandedFirstArgRaw = expandExpressionWithBindings(
                    exprRaw = firstArgRaw,
                    exprMasked = firstArgMasked,
                    refOffset = match.range.first,
                    model = model,
                    useRawStrings = true
                )
                val ifaceMatch = INTERFACE_LISTENER_WORD_REGEX.find(expandedFirstArgRaw)
                if (ifaceMatch != null) {
                    val iface = ifaceMatch.groupValues[1]
                    val msg = "[$normalizedPath] Forbidden XposedBridge.hookAllMethods directly on listener interface \"$iface\""
                    if (msg !in violations) {
                        violations.add(msg)
                    }
                }
            }

            // 规则 5：禁止占位桩代码 TODO() / NotImplementedError（在掩码字符串后检测）
            if (STUB_TODO_REGEX.containsMatchIn(codeMasked)) {
                violations.add("[$normalizedPath] Stub placeholder (TODO()/NotImplementedError) is forbidden in production source")
            }
        }

        return ComplianceReport(
            passed = violations.isEmpty(),
            violations = violations,
            warnings = warnings
        )
    }

    private fun findMatchingCallEnd(codeMasked: String, openParenIndex: Int): Int {
        val n = codeMasked.length
        if (openParenIndex !in codeMasked.indices || codeMasked[openParenIndex] != '(') {
            return minOf(n, openParenIndex + 250)
        }
        var depth = 0
        var i = openParenIndex
        var inString = false
        var inChar = false

        while (i < n) {
            val c = codeMasked[i]
            when {
                inString -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (c == '\'' || c == '\n') inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return n
    }
}
