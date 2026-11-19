package com.mockloc.presentation

import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import com.mockloc.presentation.map.OsmMapController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class PresentationComplianceVerificationTest {

    @Test
    fun `manifest resources night theme xposed scope and source rules all pass`() {
        val projectRoot = WorkspacePathResolver.resolveProjectRoot()
        val appMain = File(projectRoot, "app/src/main")

        val manifestFile = File(appMain, "AndroidManifest.xml")
        val manifestReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            manifestFile.readText(Charsets.UTF_8)
        )
        assertTrue(
            "Manifest compliance violations:\n${manifestReport.violations.joinToString("\n")}",
            manifestReport.passed
        )

        val resDir = File(appMain, "res")
        val resReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(resDir)
        assertTrue(
            "Resource & Night theme violations:\n${resReport.violations.joinToString("\n")}",
            resReport.passed
        )

        val arraysFile = File(resDir, "values/arrays.xml")
        val arraysReport = StaticManifestAndResourceChecker.checkXposedScopeArrayXml(
            arraysFile.readText(Charsets.UTF_8)
        )
        assertTrue(
            "xposed_scope violations:\n${arraysReport.violations.joinToString("\n")}",
            arraysReport.passed
        )

        val mainJavaDir = WorkspacePathResolver.mainJavaDir()
        val realSources = mainJavaDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { file ->
                file.relativeTo(mainJavaDir).path.replace('\\', '/') to file.readText(Charsets.UTF_8)
            }
        val sourceReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(realSources)
        assertTrue(
            "Source compliance violations:\n${sourceReport.violations.joinToString("\n")}",
            sourceReport.passed
        )
    }

    @Test
    fun `all MaterialToolbar elements with navigationIcon explicitly declare high-contrast navigationIconTint`() {
        val resDir = WorkspacePathResolver.resDir()
        val layoutDir = File(resDir, "layout")
        assertTrue("Expected layout directory at ${layoutDir.absolutePath}", layoutDir.isDirectory)

        val dayColors = parseColorsXml(File(resDir, "values/colors.xml"))
        val nightColors = parseColorsXml(File(resDir, "values-night/colors.xml"))

        val layoutFiles = layoutDir.listFiles { file ->
            file.isFile && file.extension.equals("xml", ignoreCase = true)
        }?.sortedBy { it.name }.orEmpty()
        assertTrue("Expected at least one layout XML file", layoutFiles.isNotEmpty())

        var inspectedToolbarCount = 0
        for (layoutFile in layoutFiles) {
            val doc = newSecureDocumentBuilder().parse(InputSource(StringReader(layoutFile.readText(Charsets.UTF_8))))
            val nodes = doc.getElementsByTagName("*")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                if (el.tagName != "com.google.android.material.appbar.MaterialToolbar" &&
                    el.tagName != "androidx.appcompat.widget.Toolbar"
                ) {
                    continue
                }
                val navIcon = el.attr(APP_NS, "app", "navigationIcon")?.trim()
                if (navIcon.isNullOrEmpty()) continue

                inspectedToolbarCount++
                val toolbarId = el.attr(ANDROID_NS, "android", "id") ?: "<unnamed>"
                val navIconTint = el.attr(APP_NS, "app", "navigationIconTint")?.trim()
                assertTrue(
                    "${layoutFile.name} ($toolbarId) sets app:navigationIcon=\"$navIcon\" " +
                        "but is missing app:navigationIconTint",
                    !navIconTint.isNullOrEmpty()
                )
                assertTrue(
                    "${layoutFile.name} ($toolbarId) app:navigationIconTint must reference @color/..., found: $navIconTint",
                    navIconTint!!.startsWith("@color/")
                )

                val tintColorName = navIconTint.removePrefix("@color/")
                val bgAttr = el.attr(ANDROID_NS, "android", "background")?.trim() ?: "@color/md_theme_surface"
                assertTrue(
                    "${layoutFile.name} ($toolbarId) android:background must reference @color/..., found: $bgAttr",
                    bgAttr.startsWith("@color/")
                )
                val bgColorName = bgAttr.removePrefix("@color/")

                for ((paletteLabel, palette) in listOf("values" to dayColors, "values-night" to nightColors)) {
                    val fgArgb = palette[tintColorName]
                    val bgArgb = palette[bgColorName]
                    assertNotNull("$paletteLabel/colors.xml missing color \"$tintColorName\"", fgArgb)
                    assertNotNull("$paletteLabel/colors.xml missing color \"$bgColorName\"", bgArgb)
                    val contrast = OsmMapController.calculateContrastRatio(fgArgb!!, bgArgb!!)
                    assertTrue(
                        "${layoutFile.name} ($toolbarId) [$paletteLabel] navigationIconTint contrast " +
                            "$tintColorName on $bgColorName is $contrast (< 3.0:1)",
                        contrast >= 3.0
                    )

                    val titleColorRef = el.attr(APP_NS, "app", "titleTextColor")?.trim()
                    if (!titleColorRef.isNullOrEmpty() && titleColorRef.startsWith("@color/")) {
                        val titleName = titleColorRef.removePrefix("@color/")
                        val titleArgb = palette[titleName]
                        assertNotNull("$paletteLabel/colors.xml missing titleTextColor \"$titleName\"", titleArgb)
                        val titleContrast = OsmMapController.calculateContrastRatio(titleArgb!!, bgArgb)
                        assertTrue(
                            "${layoutFile.name} ($toolbarId) [$paletteLabel] titleTextColor contrast " +
                                "$titleName on $bgColorName is $titleContrast (< 4.5:1)",
                            titleContrast >= 4.5
                        )
                    }
                }
            }
        }

        assertTrue("Expected at least one MaterialToolbar with navigationIcon in res/layout", inspectedToolbarCount >= 1)
    }

    @Test
    fun `OsmMapController waypoint badge text color meets WCAG AA contrast across day and night palettes`() {
        val resDir = WorkspacePathResolver.resDir()
        val dayColors = parseColorsXml(File(resDir, "values/colors.xml"))
        val nightColors = parseColorsXml(File(resDir, "values-night/colors.xml"))

        val markerKeys = listOf("map_marker_start", "map_marker_waypoint", "map_marker_end")
        for (key in markerKeys) {
            val dayFill = dayColors[key]
            assertNotNull("values/colors.xml missing $key", dayFill)
            val dayText = OsmMapController.selectBadgeTextColor(dayFill!!)
            val dayContrast = OsmMapController.calculateContrastRatio(dayText, dayFill)
            assertEquals("Day $key should select light text", OsmMapController.BADGE_TEXT_LIGHT, dayText)
            assertTrue("Day $key contrast $dayContrast must be >= 4.5:1", dayContrast >= 4.5)

            val nightFill = nightColors[key]
            assertNotNull("values-night/colors.xml missing $key", nightFill)
            val nightText = OsmMapController.selectBadgeTextColor(nightFill!!)
            val nightContrast = OsmMapController.calculateContrastRatio(nightText, nightFill)
            assertEquals("Night $key should select dark text", OsmMapController.BADGE_TEXT_DARK, nightText)
            assertTrue("Night $key contrast $nightContrast must be >= 4.5:1", nightContrast >= 4.5)
        }

        val edgeSamples = listOf(
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF808080.toInt(),
            0xFFFFEB3B.toInt(),
            0xFF0D47A1.toInt(),
            0xFF26C6DA.toInt()
        )
        for (fillArgb in edgeSamples) {
            val selectedText = OsmMapController.selectBadgeTextColor(fillArgb)
            val contrast = OsmMapController.calculateContrastRatio(selectedText, fillArgb)
            assertTrue(
                "Edge color 0x${Integer.toHexString(fillArgb)} achieved contrast $contrast (< 4.5:1)",
                contrast >= 4.5
            )
        }
    }

    private fun parseColorsXml(file: File): Map<String, Int> {
        assertTrue("Expected colors XML at ${file.absolutePath}", file.isFile)
        val doc = newSecureDocumentBuilder().parse(InputSource(StringReader(file.readText(Charsets.UTF_8))))
        val colorNodes = doc.getElementsByTagName("color")
        val result = LinkedHashMap<String, Int>()
        for (i in 0 until colorNodes.length) {
            val el = colorNodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name").trim()
            val value = el.textContent?.trim().orEmpty()
            if (name.isNotEmpty() && value.startsWith("#")) {
                result[name] = parseArgbHex(value)
            }
        }
        return result
    }

    private fun parseArgbHex(hex: String): Int {
        val raw = hex.removePrefix("#")
        return when (raw.length) {
            6 -> (0xFF000000L or raw.toLong(16)).toInt()
            8 -> raw.toLong(16).toInt()
            else -> throw IllegalArgumentException("Unsupported hex color format: $hex")
        }
    }

    private fun Element.attr(nsUri: String, prefix: String, localName: String): String? {
        val nsVal = getAttributeNS(nsUri, localName)
        if (!nsVal.isNullOrEmpty()) return nsVal
        val prefixed = getAttribute("$prefix:$localName")
        return prefixed.ifEmpty { null }
    }

    private fun newSecureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
