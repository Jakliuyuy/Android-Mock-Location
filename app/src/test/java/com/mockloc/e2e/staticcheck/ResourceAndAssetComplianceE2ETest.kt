package com.mockloc.e2e.staticcheck

import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResourceAndAssetComplianceE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `workspace res directory XML files are well formed baseline check`() {
        val resDir = WorkspacePathResolver.resDir()
        assertTrue("Expected res/ directory at ${resDir.absolutePath}", resDir.isDirectory)
        val report = StaticManifestAndResourceChecker.checkResXmlWellFormedOnly(resDir)
        assertTrue("Existing res XMLs must be well-formed: ${report.violations}", report.passed)

        // Phase 2 强断言已开启：全量校验 res/values-night、Material 3 主题、arrays.xml 与 assets/xposed_init
        val resCompliance = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(resDir)
        assertTrue("res/ compliance violations: ${resCompliance.violations}", resCompliance.passed)

        val xposedInitReport = StaticManifestAndResourceChecker.checkXposedInitContent(
            WorkspacePathResolver.xposedInitFile().readText(Charsets.UTF_8)
        )
        assertTrue("assets/xposed_init compliance violations: ${xposedInitReport.violations}", xposedInitReport.passed)

        val scopeArrayReport = StaticManifestAndResourceChecker.checkXposedScopeArrayXml(
            WorkspacePathResolver.xposedScopeArraysFile().readText(Charsets.UTF_8)
        )
        assertTrue("xposed_scope arrays.xml compliance violations: ${scopeArrayReport.violations}", scopeArrayReport.passed)
    }

    @Test
    fun `xposed_init validator accepts valid entry and rejects empty or wrong class`() {
        val valid = """
            # LSPosed entry point
            com.mockloc.hook.XposedInit
        """.trimIndent()
        assertTrue(StaticManifestAndResourceChecker.checkXposedInitContent(valid).passed)

        val empty = "# comment only\n   \n"
        assertFalse(StaticManifestAndResourceChecker.checkXposedInitContent(empty).passed)

        val wrongClass = "com.mockloc.old.OldHookInit"
        val wrongReport = StaticManifestAndResourceChecker.checkXposedInitContent(wrongClass)
        assertFalse(wrongReport.passed)
        assertTrue(wrongReport.violations.any { it.contains("com.mockloc.hook.XposedInit") })
    }

    @Test
    fun `xposed_scope array XML validator verifies completeness, format, and duplicates`() {
        val validItems = StaticManifestAndResourceChecker.CORE_RECOMMENDED_SCOPE_PACKAGES
            .joinToString("\n        ") { "<item>$it</item>" }
        val validXml = """
            |<?xml version="1.0" encoding="utf-8"?>
            |<resources>
            |    <string-array name="xposed_scope">
            |        $validItems
            |    </string-array>
            |</resources>
        """.trimMargin()
        assertTrue(StaticManifestAndResourceChecker.checkXposedScopeArrayXml(validXml).passed)

        val emptyArray = """<resources><string-array name="xposed_scope"/></resources>"""
        assertFalse(StaticManifestAndResourceChecker.checkXposedScopeArrayXml(emptyArray).passed)

        val duplicateAndInvalid = """
            <resources>
                <string-array name="xposed_scope">
                    <item>com.mockloc</item>
                    <item>com.mockloc</item>
                    <item>invalid package!</item>
                </string-array>
            </resources>
        """.trimIndent()
        val badReport = StaticManifestAndResourceChecker.checkXposedScopeArrayXml(duplicateAndInvalid)
        assertFalse(badReport.passed)
        assertTrue(badReport.violations.any { it.contains("Duplicate") })
        assertTrue(badReport.violations.any { it.contains("not a valid Android package name") })
        assertTrue(badReport.violations.any { it.contains("missing core recommended package") })
    }

    @Test
    fun `resources and night mode checker validates values-night and Material 3 theme`() {
        val resRoot = tempFolder.newFolder("res_valid")
        File(resRoot, "values").mkdirs()
        File(resRoot, "values-night").mkdirs()

        File(resRoot, "values/themes.xml").writeText(
            """
            <resources>
                <style name="Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" />
            </resources>
            """.trimIndent()
        )
        File(resRoot, "values-night/themes.xml").writeText(
            """
            <resources>
                <style name="Theme.MockLoc" parent="Theme.Material3.Dark.NoActionBar" />
            </resources>
            """.trimIndent()
        )
        File(resRoot, "values-night/colors.xml").writeText(
            """<resources><color name="surface">#121212</color></resources>"""
        )

        val okReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(resRoot)
        assertTrue("Valid M3 + night resources should pass: ${okReport.violations}", okReport.passed)

        // 缺少 values-night 应检出违规
        val noNightRoot = tempFolder.newFolder("res_no_night")
        File(noNightRoot, "values").mkdirs()
        File(noNightRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.Light.NoActionBar" /></resources>"""
        )
        val noNightReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(noNightRoot)
        assertFalse(noNightReport.passed)
        assertTrue(noNightReport.violations.any { it.contains("values-night") })

        // 旧 MaterialComponents 主题应被检出非 Material 3
        val legacyThemeRoot = tempFolder.newFolder("res_legacy_theme")
        File(legacyThemeRoot, "values").mkdirs()
        File(legacyThemeRoot, "values-night").mkdirs()
        File(legacyThemeRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.MaterialComponents.DayNight.NoActionBar" /></resources>"""
        )
        File(legacyThemeRoot, "values-night/colors.xml").writeText("<resources/>")
        val legacyReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(legacyThemeRoot)
        assertFalse(legacyReport.passed)
        assertTrue(legacyReport.violations.any { it.contains("Material 3") })
    }

    @Test
    fun `source compliance checker detects forbidden anti-patterns and ignores comments`() {
        val badSources = mapOf(
            "presentation/MainActivity.kt" to """
                package com.mockloc.presentation
                class MainActivity {
                    fun runRootCheck() {
                        val out = ShellUtil.execute("id")
                        Runtime.getRuntime().exec("su")
                    }
                    fun register(context: Context, r: BroadcastReceiver, filter: IntentFilter) {
                        context.registerReceiver(r, filter)
                    }
                }
            """.trimIndent(),
            "service/MockLocationForegroundService.kt" to """
                package com.mockloc.service
                class MockLocationForegroundService {
                    fun createPi(ctx: Context, intent: Intent) =
                        PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                }
            """.trimIndent(),
            "hook/ThirdPartyLbsSdkHook.kt" to """
                package com.mockloc.hook
                class ThirdPartyLbsSdkHook {
                    fun hook(cl: ClassLoader) {
                        val cls = XposedHelpers.findClass("com.tencent.map.geolocation.TencentLocationListener", cl)
                        XposedBridge.hookAllMethods(cls, "onLocationChanged", callback)
                    }
                    fun notImplementedYet() {
                        TODO("stub")
                    }
                }
            """.trimIndent()
        )

        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badSources)
        assertFalse(badReport.passed)
        assertTrue(badReport.violations.any { it.contains("ShellUtil") })
        assertTrue(badReport.violations.any { it.contains("shell/su") })
        assertTrue(badReport.violations.any { it.contains("Bare registerReceiver()") })
        assertTrue(badReport.violations.any { it.contains("FLAG_IMMUTABLE") })
        assertTrue(badReport.violations.any { it.contains("TencentLocationListener") && it.contains("hookAllMethods") })
        assertTrue(badReport.violations.any { it.contains("Stub placeholder") })

        val cleanSources = mapOf(
            "presentation/MainViewModel.kt" to """
                package com.mockloc.presentation
                // Note: Never use ShellUtil or Runtime.getRuntime().exec("su") in UI layer.
                class MainViewModel {
                    /* PendingIntent.getBroadcast without FLAG_IMMUTABLE is forbidden */
                    fun buildIntent(ctx: Context, intent: Intent) =
                        PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                }
            """.trimIndent(),
            "hook/XposedInit.kt" to """
                package com.mockloc.hook
                class XposedInit {
                    fun registerIpc(ctx: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
                        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                    }
                }
            """.trimIndent()
        )

        val cleanReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(cleanSources)
        assertTrue("Clean sources with comments must pass: ${cleanReport.violations}", cleanReport.passed)
    }

    @Test
    fun `real M1 kotlin sources in workspace pass checkSourceComplianceRules with zero false positives`() {
        val mainJavaDir = WorkspacePathResolver.mainJavaDir()
        assertTrue("Expected M1 sources at ${mainJavaDir.absolutePath}", mainJavaDir.isDirectory)
        val realSources = mainJavaDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { file ->
                file.relativeTo(mainJavaDir).path.replace('\\', '/') to file.readText(Charsets.UTF_8)
            }
        assertTrue("Expected real M1 .kt files to be non-empty", realSources.isNotEmpty())
        val report = StaticManifestAndResourceChecker.checkSourceComplianceRules(realSources)
        assertTrue("Real M1 Kotlin sources must have 0 violations, got: ${report.violations}", report.passed)
    }

    @Test
    fun `lexical state machine handles nested block comments, multi-quote raw strings, char literals, and string masking`() {
        val cleanTrickySources = mapOf(
            "presentation/TrickySyntax.kt" to """
                package com.mockloc.presentation
                class TrickySyntax {
                    /* outer comment
                       /* inner comment */
                       ShellUtil.execute("id")
                       PendingIntent.getActivity(ctx, 0, intent, 0)
                       TODO("ignored in nested comment")
                    */
                    val raw = ${"\"\"\""}abc""def${"\"\"\"\""}
                    // PendingIntent.getActivity(ctx, 0, intent, 0)
                    val uiHint = "ShellUtil is disabled; TODO() and PendingIntent.getActivity( are safe in UI strings"
                    val rawWithKeywords = ${"\"\"\""}
                        ShellUtil.execute("su")
                        TODO("in raw string")
                    ${"\"\"\""}
                    fun safePi(ctx: Context, intent: Intent) =
                        PendingIntent.getActivity(ctx, ')'.code, intent, PendingIntent.FLAG_IMMUTABLE)
                }
            """.trimIndent()
        )
        val report = StaticManifestAndResourceChecker.checkSourceComplianceRules(cleanTrickySources)
        assertTrue("Nested comments, raw strings, char literals, and string texts must pass: ${report.violations}", report.passed)
    }

    @Test
    fun `rule 1 detects multiline Runtime, split var Runtime, ProcessBuilder variants, UI sync network, and non-suspend detectEnvironment`() {
        val badRule1Sources = mapOf(
            "presentation/NewlineRuntime.kt" to """
                class NewlineRuntime {
                    fun run() {
                        Runtime
                            .getRuntime().exec("su")
                    }
                }
            """.trimIndent(),
            "presentation/SplitVarRuntime.kt" to """
                class SplitVarRuntime {
                    fun run() {
                        val rt = Runtime.getRuntime()
                        rt.exec("su")
                    }
                }
            """.trimIndent(),
            "presentation/PbList.kt" to """
                class PbList {
                    fun run() { ProcessBuilder(listOf("su", "-c", "id")).start() }
                }
            """.trimIndent(),
            "presentation/PbPath.kt" to """
                class PbPath {
                    fun run() { ProcessBuilder("/system/bin/su").start() }
                }
            """.trimIndent(),
            "presentation/PbCommand.kt" to """
                class PbCommand {
                    fun run() {
                        val pb = ProcessBuilder()
                        pb.command("su")
                    }
                }
            """.trimIndent(),
            "ui/ScopeGuideDialog.kt" to """
                class ScopeGuideDialog {
                    fun check() { Runtime.getRuntime().exec("su") }
                }
            """.trimIndent(),
            "map/MapController.kt" to """
                class MapController {
                    fun check() { ShellUtil.exec("id") }
                }
            """.trimIndent(),
            "adapter/AppAdapter.kt" to """
                class AppAdapter {
                    fun check() { Runtime.getRuntime().exec("su") }
                }
            """.trimIndent(),
            "presentation/BadNetActivity.kt" to """
                class BadNetActivity {
                    fun fetch() {
                        val conn = java.net.URL("https://example.cn").openConnection()
                    }
                }
            """.trimIndent(),
            "root/EnvironmentDetector.kt" to """
                object EnvironmentDetector {
                    fun detectEnvironment(context: Context): String = "blocking"
                }
            """.trimIndent()
        )

        val report = StaticManifestAndResourceChecker.checkSourceComplianceRules(badRule1Sources)
        assertFalse(report.passed)
        assertTrue(report.violations.any { it.contains("NewlineRuntime.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("SplitVarRuntime.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("PbList.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("PbPath.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("PbCommand.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("ScopeGuideDialog.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("MapController.kt") && it.contains("ShellUtil") })
        assertTrue(report.violations.any { it.contains("AppAdapter.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("BadNetActivity.kt") && it.contains("Dispatchers.IO") })
        assertTrue(report.violations.any { it.contains("EnvironmentDetector.kt") && it.contains("suspend") })

        val okSources = mapOf(
            "root/EnvironmentDetector.kt" to """
                object EnvironmentDetector {
                    suspend fun detectEnvironment(context: Context): String = "ok"
                }
            """.trimIndent(),
            "presentation/GoodNetActivity.kt" to """
                class GoodNetActivity {
                    suspend fun fetch() = withContext(Dispatchers.IO) {
                        java.net.URL("https://example.cn").openConnection()
                    }
                }
            """.trimIndent()
        )
        val okReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(okSources)
        assertTrue("Valid suspend detectEnvironment and Dispatchers.IO network should pass: ${okReport.violations}", okReport.passed)
    }

    @Test
    fun `rule 2 and rule 3 handle long calls, multiline dots, constant tracking, and reject string spoofing`() {
        val longFilterActions = (1..12).joinToString(";\n") { "filter.addAction(\"com.mockloc.ACTION_NUMBER_$it\")" }
        val validLongAndConstSources = mapOf(
            "hook/LongAndConstOk.kt" to """
                class LongAndConstOk {
                    fun reg(ctx: Context, r: BroadcastReceiver) {
                        val flags = ContextCompat.RECEIVER_NOT_EXPORTED
                        androidx.core.content.ContextCompat.registerReceiver(
                            ctx,
                            r,
                            IntentFilter().apply {
                                $longFilterActions
                            },
                            flags
                        )
                        ctx.unregisterReceiver(r)
                    }
                    fun piBroadcast(ctx: Context, intent: Intent) =
                        PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    fun piConst(ctx: Context, intent: Intent) =
                        PendingIntent.getActivity(ctx, 0, intent, PI_FLAGS)
                    companion object {
                        const val PI_FLAGS =
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    }
                }
            """.trimIndent()
        )
        val okReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(validLongAndConstSources)
        assertTrue("Long registerReceiver and constant-tracked PendingIntent must pass: ${okReport.violations}", okReport.passed)

        val badBypassSources = mapOf(
            "hook/BareNewlineReceiver.kt" to """
                class BareNewlineReceiver {
                    fun reg(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
                        ctx
                            .registerReceiver(r, f)
                    }
                }
            """.trimIndent(),
            "hook/CompatNewlineMissingFlag.kt" to """
                class CompatNewlineMissingFlag {
                    fun reg(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
                        ContextCompat
                            .registerReceiver(ctx, r, f, 0)
                    }
                }
            """.trimIndent(),
            "hook/WindowLeakBypass.kt" to """
                class WindowLeakBypass {
                    fun reg(ctx: Context, r1: BroadcastReceiver, r2: BroadcastReceiver, f: IntentFilter) {
                        ContextCompat.registerReceiver(ctx, r1, f, 0)
                        ContextCompat.registerReceiver(ctx, r2, f, ContextCompat.RECEIVER_NOT_EXPORTED)
                    }
                }
            """.trimIndent(),
            "service/PiSpoofs.kt" to """
                class PiSpoofs {
                    fun a(c: Context, intents: Array<Intent>) = PendingIntent.getActivities(c, 0, intents, 0)
                    fun b(c: Context, i: Intent) = PendingIntent
                        .getActivity(c, 0, i, PendingIntent.FLAG_MUTABLE)
                    fun c(c: Context) = PendingIntent.getActivity(c, 0, Intent().putExtra("FLAG_IMMUTABLE", 1), 0)
                    fun d() { throw NotImplementedError("stub") }
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badBypassSources)
        assertFalse(badReport.passed)
        assertTrue(badReport.violations.any { it.contains("BareNewlineReceiver.kt") && it.contains("Bare registerReceiver") })
        assertTrue(badReport.violations.any { it.contains("CompatNewlineMissingFlag.kt") && it.contains("RECEIVER_EXPORTED") })
        assertTrue(badReport.violations.any { it.contains("WindowLeakBypass.kt") && it.contains("RECEIVER_EXPORTED") })
        assertTrue(badReport.violations.any { it.contains("PiSpoofs.kt") && it.contains("PendingIntent.getActivities()") })
        assertTrue(badReport.violations.any { it.contains("PiSpoofs.kt") && it.contains("PendingIntent.getActivity()") })
        assertEquals(3, badReport.violations.count { it.contains("PiSpoofs.kt") && it.contains("PendingIntent.") })
        assertTrue(badReport.violations.any { it.contains("PiSpoofs.kt") && it.contains("Stub placeholder") })
    }

    @Test
    fun `rule 4 inspects only first arg of hookAllMethods and detects multiline findClass, static import, and LocationCallback`() {
        val legitHookWithCallbackCheck = mapOf(
            "hook/LocationHook.kt" to """
                class LocationHook {
                    fun hookImpl(implClass: Class<*>) {
                        XposedBridge.hookAllMethods(implClass, "onLocationChanged", object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (param.thisObject is LocationListener) {
                                    // checking instance type in callback body is completely legal
                                }
                            }
                        })
                    }
                }
            """.trimIndent()
        )
        val legitReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(legitHookWithCallbackCheck)
        assertTrue("Callback body referencing LocationListener must not trigger false positive: ${legitReport.violations}", legitReport.passed)

        val badHookSources = mapOf(
            "hook/MultilineFindClass.kt" to """
                class MultilineFindClass {
                    fun hook(cl: ClassLoader) {
                        val cls = XposedHelpers.findClass(
                            "com.tencent.map.geolocation.TencentLocationListener",
                            cl
                        )
                        XposedBridge.hookAllMethods(cls, "onLocationChanged", cb)
                    }
                }
            """.trimIndent(),
            "hook/StaticImportHook.kt" to """
                import de.robv.android.xposed.XposedBridge.hookAllMethods
                class StaticImportHook {
                    fun hook() {
                        hookAllMethods(LocationCallback::class.java, "onLocationResult", cb)
                        XposedBridge.hookAllMethods(ScanResultsCallback::class.java, "onScanResultsAvailable", cb)
                    }
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badHookSources)
        assertFalse(badReport.passed)
        assertTrue(badReport.violations.any { it.contains("MultilineFindClass.kt") && it.contains("TencentLocationListener") })
        assertTrue(badReport.violations.any { it.contains("StaticImportHook.kt") && it.contains("LocationCallback") })
        assertTrue(badReport.violations.any { it.contains("StaticImportHook.kt") && it.contains("ScanResultsCallback") })
    }

    @Test
    fun `values-night themes check allows ThemeOverlay Material3 and implicit dot styles, rejects non-M3 and empty dir`() {
        // 1. ThemeOverlay.Material3.* 与点号隐式子样式应放行
        val validOverlayRoot = tempFolder.newFolder("res_night_overlay")
        File(validOverlayRoot, "values").mkdirs()
        File(validOverlayRoot, "values-night").mkdirs()
        File(validOverlayRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" /></resources>"""
        )
        File(validOverlayRoot, "values-night/themes.xml").writeText(
            """
            <resources>
                <style name="Theme.MockLoc.Night" />
                <style name="Theme.MockLoc.Overlay" parent="ThemeOverlay.Material3.Dark" />
            </resources>
            """.trimIndent()
        )
        val okReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(validOverlayRoot)
        assertTrue("ThemeOverlay.Material3 and dot-notation night style should pass: ${okReport.violations}", okReport.passed)

        // 2. values-night/themes.xml 显式声明非 M3 父主题应报错
        val badNightParentRoot = tempFolder.newFolder("res_night_bad_parent")
        File(badNightParentRoot, "values").mkdirs()
        File(badNightParentRoot, "values-night").mkdirs()
        File(badNightParentRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" /></resources>"""
        )
        File(badNightParentRoot, "values-night/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.MaterialComponents.DayNight.DarkActionBar" /></resources>"""
        )
        val badParentReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(badNightParentRoot)
        assertFalse(badParentReport.passed)
        assertTrue(badParentReport.violations.any { it.contains("Theme.MaterialComponents") })

        // 3. values-night 空目录应报错
        val emptyNightDirRoot = tempFolder.newFolder("res_night_empty")
        File(emptyNightDirRoot, "values").mkdirs()
        File(emptyNightDirRoot, "values-night").mkdirs()
        File(emptyNightDirRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" /></resources>"""
        )
        val emptyNightReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(emptyNightDirRoot)
        assertFalse(emptyNightReport.passed)
        assertTrue(emptyNightReport.violations.any { it.contains("contains no XML files") })

        // 4. values-night/styles.xml 中非 Material 3 父样式应被检出 (X7)
        val badNightStylesRoot = tempFolder.newFolder("res_night_styles_non_m3")
        File(badNightStylesRoot, "values").mkdirs()
        File(badNightStylesRoot, "values-night").mkdirs()
        File(badNightStylesRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" /></resources>"""
        )
        File(badNightStylesRoot, "values-night/styles.xml").writeText(
            """<resources><style name="AppNightStyle" parent="Theme.AppCompat.DayNight" /></resources>"""
        )
        val badStylesReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(badNightStylesRoot)
        assertFalse(badStylesReport.passed)
        assertTrue(badStylesReport.violations.any { it.contains("values-night/styles.xml") && it.contains("Theme.AppCompat.DayNight") })
    }

    @Test
    fun `checker resists regex StackOverflowError on 4KB-20KB ProcessBuilder literals and 3000-5000 modifiers`() {
        val longPad = "x".repeat(4096)
        val raw20KbPad = "y".repeat(20 * 1024)
        val manyPublic3k = "public ".repeat(3000)
        val manyPublic5k = "public ".repeat(5000)

        val stressSources = mapOf(
            "presentation/LongPbActivity.kt" to """
                class LongPbActivity {
                    fun run() {
                        ProcessBuilder("$longPad/su").start()
                    }
                }
            """.trimIndent(),
            "presentation/Raw20KbPbActivity.kt" to """
                class Raw20KbPbActivity {
                    fun run() {
                        ProcessBuilder(${"\"\"\""}$raw20KbPad/su${"\"\"\""}).start()
                    }
                }
            """.trimIndent(),
            "root/EnvironmentDetector.kt" to """
                object EnvironmentDetector {
                    ${manyPublic3k}fun detectEnvironment(context: Context): String = "blocking"
                }
            """.trimIndent(),
            "service/ManyModsService.kt" to """
                class ManyModsService {
                    ${manyPublic5k}val x = 1
                }
            """.trimIndent()
        )

        val startNs = System.nanoTime()
        val report = StaticManifestAndResourceChecker.checkSourceComplianceRules(stressSources)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertFalse(report.passed)
        assertTrue(report.violations.any { it.contains("LongPbActivity.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("Raw20KbPbActivity.kt") && it.contains("shell/su") })
        assertTrue(report.violations.any { it.contains("EnvironmentDetector.kt") && it.contains("suspend") })
        assertFalse("ManyModsService with safe val should not trigger false positive", report.violations.any { it.contains("ManyModsService.kt") })
        assertTrue("Stress check should finish well under 3000ms, took ${elapsedMs}ms", elapsedMs < 3000L)
    }

    @Test
    fun `checker closes OkHttp nested parens, per-function Dispatchers IO, lexical scope shadow, var reassign, arg position, template code, and 4-hop hook chain`() {
        val badAdversarialSources = mapOf(
            // X1: OkHttp newCall(Request.Builder()...).execute() 嵌套括号
            "presentation/BadOkHttpActivity.kt" to """
                class BadOkHttpActivity {
                    fun fetch() {
                        OkHttpClient().newCall(Request.Builder().url("https://example.cn").build()).execute()
                    }
                }
            """.trimIndent(),
            // X2: 同文件混用 Dispatchers.IO 与裸同步网络调用
            "presentation/MixedNetActivity.kt" to """
                class MixedNetActivity {
                    suspend fun good() = withContext(Dispatchers.IO) {
                        java.net.URL("https://a.cn").openConnection()
                    }
                    fun bad() {
                        java.net.URL("https://b.cn").openConnection().connect()
                    }
                }
            """.trimIndent(),
            // X3 & reviewer_2_r2 Major 2: 跨函数同名参数遮蔽
            "service/CrossFnShadow.kt" to """
                class CrossFnShadow {
                    fun safe(ctx: Context, intent: Intent) {
                        val flags = PendingIntent.FLAG_IMMUTABLE
                        PendingIntent.getActivity(ctx, 0, intent, flags)
                    }
                    fun unsafe(ctx: Context, intent: Intent, flags: Int) {
                        PendingIntent.getActivity(ctx, 0, intent, flags)
                    }
                    fun unsafeExpr(ctx: Context, intent: Intent, flags: Int) =
                        PendingIntent.getActivity(ctx, 0, intent, flags)
                }
            """.trimIndent(),
            "hook/CrossFnReceiverShadow.kt" to """
                class CrossFnReceiverShadow {
                    fun helper(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
                        val flags = ContextCompat.RECEIVER_NOT_EXPORTED
                        ContextCompat.registerReceiver(ctx, r, f, flags)
                    }
                    fun badCall(ctx: Context, r: BroadcastReceiver, f: IntentFilter, flags: Int) {
                        ContextCompat.registerReceiver(ctx, r, f, flags)
                    }
                }
            """.trimIndent(),
            // X4 & X9: var 重赋值为 0 与局部常量遮蔽 RECEIVER_EXPORTED = 0
            "service/ReassignAndLocalShadow.kt" to """
                class ReassignAndLocalShadow {
                    fun reassigned(ctx: Context, intent: Intent) {
                        var flags = PendingIntent.FLAG_IMMUTABLE
                        flags = 0
                        PendingIntent.getActivity(ctx, 0, intent, flags)
                    }
                    fun shadowedConst(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
                        val RECEIVER_EXPORTED = 0
                        ContextCompat.registerReceiver(ctx, r, f, RECEIVER_EXPORTED)
                    }
                }
            """.trimIndent(),
            // X15: FLAG_IMMUTABLE 泄漏到第 2 参数位，第 4 参数为 0
            "service/WrongArgPosPi.kt" to """
                class WrongArgPosPi {
                    fun leak(c: Context, i: Intent) {
                        val req = PendingIntent.FLAG_IMMUTABLE
                        PendingIntent.getActivity(c, req, i, 0)
                    }
                }
            """.trimIndent(),
            // X8: 字符串模板内嵌真实调用
            "service/TemplateCall.kt" to """
                class TemplateCall {
                    fun logPi(c: Context, i: Intent): String =
                        "pi=${'$'}{PendingIntent.getActivity(c, 0, i, 0)}"
                }
            """.trimIndent(),
            // X6: 4 跳变量链 hookAllMethods
            "hook/FourHopHook.kt" to """
                class FourHopHook {
                    fun hook(cl: ClassLoader) {
                        val n1 = "com.tencent.map.geolocation.TencentLocationListener"
                        val n2 = n1
                        val n3 = n2
                        val cls = XposedHelpers.findClass(n3, cl)
                        XposedBridge.hookAllMethods(cls, "onLocationChanged", cb)
                    }
                }
            """.trimIndent()
        )

        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badAdversarialSources)
        assertFalse(badReport.passed)
        assertTrue("X1 OkHttp nested parens must fail: ${badReport.violations}", badReport.violations.any { it.contains("BadOkHttpActivity.kt") })
        assertTrue("X2 Mixed Dispatchers.IO must fail: ${badReport.violations}", badReport.violations.any { it.contains("MixedNetActivity.kt") })
        assertEquals("X3 CrossFnShadow must detect both unsafe and unsafeExpr", 2, badReport.violations.count { it.contains("CrossFnShadow.kt") })
        assertTrue("X3 CrossFnReceiverShadow must fail: ${badReport.violations}", badReport.violations.any { it.contains("CrossFnReceiverShadow.kt") })
        assertTrue("X4 var reassigned to 0 must fail: ${badReport.violations}", badReport.violations.any { it.contains("ReassignAndLocalShadow.kt") && it.contains("PendingIntent.getActivity") })
        assertTrue("X9 local shadow RECEIVER_EXPORTED=0 must fail: ${badReport.violations}", badReport.violations.any { it.contains("ReassignAndLocalShadow.kt") && it.contains("ContextCompat.registerReceiver") })
        assertTrue("X15 wrong arg position must fail: ${badReport.violations}", badReport.violations.any { it.contains("WrongArgPosPi.kt") })
        assertTrue("X8 template code call must fail: ${badReport.violations}", badReport.violations.any { it.contains("TemplateCall.kt") })
        assertTrue("X6 4-hop hookAllMethods must fail: ${badReport.violations}", badReport.violations.any { it.contains("FourHopHook.kt") && it.contains("TencentLocationListener") })

        // 正向回归：合法的 OkHttp in Dispatchers.IO、var 先 0 后赋值 FLAG_IMMUTABLE、具名 flags、6参 registerReceiver、模板内嵌引号均应放行
        val goodSources = mapOf(
            "presentation/GoodOkHttpActivity.kt" to """
                class GoodOkHttpActivity {
                    suspend fun fetch() = withContext(Dispatchers.IO) {
                        OkHttpClient().newCall(Request.Builder().url("https://example.cn").build()).execute()
                    }
                }
            """.trimIndent(),
            "service/GoodReassignAndNamedPi.kt" to """
                class GoodReassignAndNamedPi {
                    fun okReassign(c: Context, i: Intent) {
                        var flags = 0
                        flags = PendingIntent.FLAG_IMMUTABLE
                        PendingIntent.getActivity(c, 0, i, flags)
                    }
                    fun okNamed(c: Context, i: Intent) {
                        val hint = "hi ${'$'}{foo(")")}"
                        PendingIntent.getActivity(flags = PendingIntent.FLAG_IMMUTABLE, context = c, requestCode = 0, intent = i)
                    }
                    fun okSixArgReceiver(c: Context, r: BroadcastReceiver, f: IntentFilter) {
                        ContextCompat.registerReceiver(c, r, f, null, null, ContextCompat.RECEIVER_NOT_EXPORTED)
                    }
                }
            """.trimIndent()
        )
        val goodReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(goodSources)
        assertTrue("Valid adversarial regression cases must pass: ${goodReport.violations}", goodReport.passed)
    }

    @Test
    fun `iteration 4 NEW_A2 requires strictly enclosing IO dispatcher and rejects sibling or trailing Dispatchers IO`() {
        val badNonEnclosingSources = mapOf(
            "presentation/PostLaunchIoActivity.kt" to """
                class PostLaunchIoActivity {
                    fun onClick() {
                        java.net.URL("https://example.cn").openConnection().connect()
                        lifecycleScope.launch(Dispatchers.IO) {
                            val ok = 1
                        }
                    }
                }
            """.trimIndent(),
            "presentation/BareRefIoActivity.kt" to """
                class BareRefIoActivity {
                    fun onClick() {
                        val d = Dispatchers.IO
                        OkHttpClient().newCall(Request.Builder().url("https://example.cn").build()).execute()
                    }
                }
            """.trimIndent(),
            "presentation/IfGuardIoActivity.kt" to """
                class IfGuardIoActivity {
                    fun onClick() {
                        if (check(Dispatchers.IO)) {
                            java.net.URL("https://example.cn").openConnection().connect()
                        }
                    }
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badNonEnclosingSources)
        assertFalse(badReport.passed)
        assertTrue("NEW_A2 post launch IO must fail: ${badReport.violations}", badReport.violations.any { it.contains("PostLaunchIoActivity.kt") })
        assertTrue("NEW_A2 bare ref IO must fail: ${badReport.violations}", badReport.violations.any { it.contains("BareRefIoActivity.kt") })
        assertTrue("NEW_A2 if guard IO must fail: ${badReport.violations}", badReport.violations.any { it.contains("IfGuardIoActivity.kt") })

        val goodEnclosingSources = mapOf(
            "presentation/CoroutineScopeLaunchActivity.kt" to """
                class CoroutineScopeLaunchActivity {
                    private val ioDispatcher = Dispatchers.IO
                    fun onClick() {
                        CoroutineScope(Dispatchers.IO).launch {
                            java.net.URL("https://a.cn").openConnection().connect()
                        }
                        CoroutineScope(SupervisorJob() + ioDispatcher)
                            .launch {
                                if (true) {
                                    OkHttpClient().newCall(Request.Builder().url("https://b.cn").build()).execute()
                                }
                            }
                        Executors.newSingleThreadExecutor().execute {
                            java.net.URL("https://c.cn").openConnection().connect()
                        }
                    }
                    suspend fun fetch() = withContext(ioDispatcher) {
                        java.net.URL("https://d.cn").openConnection().connect()
                    }
                }
            """.trimIndent()
        )
        val goodReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(goodEnclosingSources)
        assertTrue("Strictly enclosing Dispatchers.IO / Executors headers must pass: ${goodReport.violations}", goodReport.passed)
    }

    @Test
    fun `iteration 4 NEW_A1 keeps template brace and string state consistent and supports var self-reference updates`() {
        val validTemplateAndVarSelfRef = mapOf(
            "service/TemplateInnerBraceOk.kt" to """
                class TemplateInnerBraceOk {
                    fun build(c: Context, i: Intent): PendingIntent {
                        val flags = PendingIntent.FLAG_IMMUTABLE
                        val msg1 = "${'$'}{run { val flags = 0; 1 }}"
                        val msg2 = "${'$'}{foo(
                            1,
                            flags = 0
                        )}"
                        val msg3 = "${'$'}{foo(")") { val flags = 0 }}"
                        val msg4 = ${"\"\"\""}raw ${'$'}{foo(")") { val flags = 0 }}${"\"\"\""}
                        return PendingIntent.getActivity(c, 0, i, flags)
                    }
                }
            """.trimIndent(),
            "service/VarSelfOrOk.kt" to """
                class VarSelfOrOk {
                    fun build(c: Context, i: Intent): PendingIntent {
                        var flags = PendingIntent.FLAG_IMMUTABLE
                        flags = flags or PendingIntent.FLAG_UPDATE_CURRENT
                        return PendingIntent.getActivity(c, 0, i, flags)
                    }
                }
            """.trimIndent()
        )
        val okReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(validTemplateAndVarSelfRef)
        assertTrue("NEW_A1 valid templates and var self-reference must pass: ${okReport.violations}", okReport.passed)

        val badTemplateAndVarSelfRef = mapOf(
            "service/TemplateViolationStillCaught.kt" to """
                class TemplateViolationStillCaught {
                    fun logPi(c: Context, i: Intent): String =
                        "pi=${'$'}{PendingIntent.getActivity(c, 0, i, 0)}"
                }
            """.trimIndent(),
            "service/VarResetBeforeSelfOr.kt" to """
                class VarResetBeforeSelfOr {
                    fun build(c: Context, i: Intent): PendingIntent {
                        var flags = PendingIntent.FLAG_IMMUTABLE
                        flags = 0
                        flags = flags or PendingIntent.FLAG_UPDATE_CURRENT
                        return PendingIntent.getActivity(c, 0, i, flags)
                    }
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badTemplateAndVarSelfRef)
        assertFalse(badReport.passed)
        assertTrue("X8 inside template must still be caught: ${badReport.violations}", badReport.violations.any { it.contains("TemplateViolationStillCaught.kt") })
        assertTrue("Var reset to 0 before self-or must still be caught: ${badReport.violations}", badReport.violations.any { it.contains("VarResetBeforeSelfOr.kt") })
    }

    @Test
    fun `iteration 4 NEW_A8 detects legacy non-Material3 main theme in values even when values-night uses Material3`() {
        val maskedLegacyRoot = tempFolder.newFolder("res_values_legacy_night_m3")
        File(maskedLegacyRoot, "values").mkdirs()
        File(maskedLegacyRoot, "values-night").mkdirs()
        File(maskedLegacyRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.MaterialComponents.DayNight.NoActionBar" /></resources>"""
        )
        File(maskedLegacyRoot, "values-night/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.Dark.NoActionBar" /></resources>"""
        )
        val badReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(maskedLegacyRoot)
        assertFalse("Legacy theme in values/themes.xml must not be masked by values-night M3", badReport.passed)
        assertTrue(badReport.violations.any { it.contains("values/themes.xml") && it.contains("Theme.MaterialComponents.DayNight.NoActionBar") })

        val validValuesWithLocalWidgetRoot = tempFolder.newFolder("res_values_m3_with_widgets")
        File(validValuesWithLocalWidgetRoot, "values").mkdirs()
        File(validValuesWithLocalWidgetRoot, "values-night").mkdirs()
        File(validValuesWithLocalWidgetRoot, "values/themes.xml").writeText(
            """
            <resources>
                <style name="Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" />
                <style name="Theme.MockLoc.Splash" />
                <style name="Widget.MockLoc.Card" parent="Widget.Material3.CardView.Elevated" />
            </resources>
            """.trimIndent()
        )
        File(validValuesWithLocalWidgetRoot, "values-night/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.Dark.NoActionBar" /></resources>"""
        )
        val okReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(validValuesWithLocalWidgetRoot)
        assertTrue("Valid M3 values/ with dot child and M3 widget style must pass: ${okReport.violations}", okReport.passed)
    }

    @Test
    fun `iteration 4 NEW_A7 catches OkHttp long comment gap and NEW_A6 handles qualified annotations, generics, and extensions`() {
        val longComment = "/* " + "c".repeat(80) + " */"
        val badSources = mapOf(
            "presentation/LongCommentOkHttpActivity.kt" to """
                class LongCommentOkHttpActivity {
                    fun fetch() {
                        OkHttpClient().newCall(Request.Builder().url("https://example.cn").build())
                        $longComment
                        .execute()
                    }
                }
            """.trimIndent(),
            "root/GenericEnvDetector/EnvironmentDetector.kt" to """
                object GenericEnv {
                    fun <T> detectEnvironment(context: Context): T = error("bad")
                }
            """.trimIndent(),
            "root/ExtEnvDetector/EnvironmentDetector.kt" to """
                object ExtEnv {
                    fun Context.detectEnvironment(): String = "bad"
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badSources)
        assertFalse(badReport.passed)
        assertTrue("NEW_A7 OkHttp long comment gap must fail: ${badReport.violations}", badReport.violations.any { it.contains("LongCommentOkHttpActivity.kt") })
        assertTrue("NEW_A6 generic non-suspend detectEnvironment must fail: ${badReport.violations}", badReport.violations.any { it.contains("GenericEnvDetector/EnvironmentDetector.kt") })
        assertTrue("NEW_A6 extension non-suspend detectEnvironment must fail: ${badReport.violations}", badReport.violations.any { it.contains("ExtEnvDetector/EnvironmentDetector.kt") })

        val goodSources = mapOf(
            "root/AnnotatedGenericExt/EnvironmentDetector.kt" to """
                object AnnotatedEnv {
                    suspend @kotlin.jvm.JvmStatic fun detectEnvironment(context: Context): String = "ok"
                    suspend @receiver:JvmStatic fun <T : Any> Context.detectEnvironment(): String = "ok"
                }
            """.trimIndent()
        )
        val goodReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(goodSources)
        assertTrue("NEW_A6 qualified/use-site annotated suspend detectEnvironment must pass: ${goodReport.violations}", goodReport.passed)
    }

    @Test
    fun `iteration 5 NEW_A8 supports Base Theme M3 hierarchy, at-style prefix, cross-file values-night lookup, and rejects dot-implicit legacy, missing parent, and cycles`() {
        // 1. 标准 Android Studio Material 3 分层模板 (Base.Theme.MockLoc -> Theme.MockLoc -> Theme.MockLoc.Splash + @style/)
        val baseHierarchyRoot = tempFolder.newFolder("res_base_theme_m3_hierarchy")
        File(baseHierarchyRoot, "values").mkdirs()
        File(baseHierarchyRoot, "values-night").mkdirs()
        File(baseHierarchyRoot, "values/themes.xml").writeText(
            """
            <resources>
                <style name="Base.Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" />
                <style name="Theme.MockLoc" parent="Base.Theme.MockLoc" />
                <style name="Theme.MockLoc.Splash" />
                <style name="Theme.MockLoc.AtStyle" parent="@style/Theme.Material3.DayNight.NoActionBar" />
                <style name="Theme.MockLoc.AtBase" parent="@style/Base.Theme.MockLoc" />
                <style name="Widget.MockLoc.Button" parent="@style/Widget.Material3.Button" />
                <style name="Widget.MockLoc.Button.Primary" />
            </resources>
            """.trimIndent()
        )
        File(baseHierarchyRoot, "values-night/themes.xml").writeText(
            """
            <resources>
                <style name="Base.Theme.MockLoc" parent="Theme.Material3.Dark.NoActionBar" />
                <style name="Theme.MockLoc" parent="Base.Theme.MockLoc" />
                <style name="Theme.MockLoc.NightOnly" />
            </resources>
            """.trimIndent()
        )
        val hierarchyReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(baseHierarchyRoot)
        assertTrue("Standard Base.Theme M3 hierarchy and @style/ prefix must pass: ${hierarchyReport.violations}", hierarchyReport.passed)

        // 2. values-night/ 中引用 values/ 定义的 Base.Theme.MockLoc（跨文件回退解析）
        val crossFileNightRoot = tempFolder.newFolder("res_night_fallback_to_values")
        File(crossFileNightRoot, "values").mkdirs()
        File(crossFileNightRoot, "values-night").mkdirs()
        File(crossFileNightRoot, "values/themes.xml").writeText(
            """
            <resources>
                <style name="Base.Theme.MockLoc" parent="Theme.Material3.DayNight.NoActionBar" />
                <style name="Theme.MockLoc" parent="Base.Theme.MockLoc" />
            </resources>
            """.trimIndent()
        )
        File(crossFileNightRoot, "values-night/themes.xml").writeText(
            """
            <resources>
                <style name="Theme.MockLoc" parent="Base.Theme.MockLoc" />
            </resources>
            """.trimIndent()
        )
        val crossFileReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(crossFileNightRoot)
        assertTrue("values-night inheriting Base.Theme.MockLoc from values/ must pass: ${crossFileReport.violations}", crossFileReport.passed)

        // 3. 无 parent 的点号隐式遗留基类必须拦截
        val dotImplicitLegacyRoot = tempFolder.newFolder("res_dot_implicit_legacy")
        File(dotImplicitLegacyRoot, "values").mkdirs()
        File(dotImplicitLegacyRoot, "values-night").mkdirs()
        File(dotImplicitLegacyRoot, "values/themes.xml").writeText(
            """<resources><style name="Theme.MaterialComponents.Light.NoActionBar" /></resources>"""
        )
        File(dotImplicitLegacyRoot, "values-night/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.Dark.NoActionBar" /></resources>"""
        )
        val dotLegacyReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(dotImplicitLegacyRoot)
        assertFalse("Dot-implicit legacy Theme.MaterialComponents without parent must fail", dotLegacyReport.passed)
        assertTrue(dotLegacyReport.violations.any { it.contains("Theme.MaterialComponents.Light.NoActionBar") })

        // 4. 空壳未定义父样式（显式 MissingParent & 隐式 Unknown.Sub & @style/Widget.MaterialComponents）必须拦截
        val missingParentRoot = tempFolder.newFolder("res_missing_parent_shell")
        File(missingParentRoot, "values").mkdirs()
        File(missingParentRoot, "values-night").mkdirs()
        File(missingParentRoot, "values/themes.xml").writeText(
            """
            <resources>
                <style name="Theme.MockLoc" parent="Theme.MockLoc.MissingParent" />
                <style name="Theme.Unknown.Sub" />
                <style name="Widget.MockLoc.BadBtn" parent="@style/Widget.MaterialComponents.Button" />
            </resources>
            """.trimIndent()
        )
        File(missingParentRoot, "values-night/themes.xml").writeText(
            """<resources><style name="ThemeOverlay.Dummy" parent="ThemeOverlay.Material3.Dark" /></resources>"""
        )
        val missingReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(missingParentRoot)
        assertFalse("Missing parent shell and @style/Widget.MaterialComponents must fail", missingReport.passed)
        assertTrue(missingReport.violations.any { it.contains("Theme.MockLoc.MissingParent") })
        assertTrue(missingReport.violations.any { it.contains("Theme.Unknown.Sub") })
        assertTrue(missingReport.violations.any { it.contains("Widget.MaterialComponents.Button") })

        // 5. 环状继承 (A -> B -> A) 必须安全终止并拦截
        val cycleRoot = tempFolder.newFolder("res_style_cycle")
        File(cycleRoot, "values").mkdirs()
        File(cycleRoot, "values-night").mkdirs()
        File(cycleRoot, "values/themes.xml").writeText(
            """
            <resources>
                <style name="Theme.CycleA" parent="Theme.CycleB" />
                <style name="Theme.CycleB" parent="Theme.CycleA" />
            </resources>
            """.trimIndent()
        )
        File(cycleRoot, "values-night/themes.xml").writeText(
            """<resources><style name="Theme.MockLoc" parent="Theme.Material3.Dark.NoActionBar" /></resources>"""
        )
        val cycleReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(cycleRoot)
        assertFalse("Cyclic style inheritance must terminate safely and fail", cycleReport.passed)
        assertTrue(cycleReport.violations.any { it.contains("Theme.CycleA") })
        assertTrue(cycleReport.violations.any { it.contains("Theme.CycleB") })
    }

    @Test
    fun `iteration 5 NEW_A2 catches IO-to-Main thread switches from inner to outer and rejects Dispatchers IO scope functions`() {
        val badNestedMainAndScopeSources = mapOf(
            "presentation/IoThenMainActivity.kt" to """
                class IoThenMainActivity {
                    suspend fun fetch() = withContext(Dispatchers.IO) {
                        withContext(Dispatchers.Main) {
                            OkHttpClient().newCall(Request.Builder().url("https://example.cn").build()).execute()
                        }
                    }
                }
            """.trimIndent(),
            "presentation/IoThenMainImmediateActivity.kt" to """
                class IoThenMainImmediateActivity {
                    suspend fun fetch() = withContext(Dispatchers.IO) {
                        withContext(Dispatchers.Main.immediate) {
                            java.net.URL("https://example.cn").openConnection().connect()
                        }
                    }
                }
            """.trimIndent(),
            "presentation/IoThenRunOnUiThreadActivity.kt" to """
                class IoThenRunOnUiThreadActivity {
                    fun fetch() {
                        lifecycleScope.launch(Dispatchers.IO) {
                            runOnUiThread {
                                java.net.URL("https://example.cn").openConnection().connect()
                            }
                        }
                    }
                }
            """.trimIndent(),
            "presentation/IoThenMainScopeActivity.kt" to """
                class IoThenMainScopeActivity {
                    suspend fun fetch() = withContext(Dispatchers.IO) {
                        MainScope().launch {
                            java.net.URL("https://example.cn").openConnection().connect()
                        }
                    }
                }
            """.trimIndent(),
            "presentation/DispatchersIoLetActivity.kt" to """
                class DispatchersIoLetActivity {
                    fun fetch() {
                        Dispatchers.IO.let {
                            java.net.URL("https://example.cn").openConnection().connect()
                        }
                    }
                }
            """.trimIndent(),
            "presentation/DispatchersIoAlsoActivity.kt" to """
                class DispatchersIoAlsoActivity {
                    fun fetch() {
                        Dispatchers.IO.also {
                            OkHttpClient().newCall(Request.Builder().url("https://example.cn").build()).execute()
                        }
                    }
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badNestedMainAndScopeSources)
        assertFalse(badReport.passed)
        assertTrue("withContext(IO)->withContext(Main) must fail: ${badReport.violations}", badReport.violations.any { it.contains("IoThenMainActivity.kt") })
        assertTrue("withContext(IO)->Main.immediate must fail: ${badReport.violations}", badReport.violations.any { it.contains("IoThenMainImmediateActivity.kt") })
        assertTrue("launch(IO)->runOnUiThread must fail: ${badReport.violations}", badReport.violations.any { it.contains("IoThenRunOnUiThreadActivity.kt") })
        assertTrue("withContext(IO)->MainScope().launch must fail: ${badReport.violations}", badReport.violations.any { it.contains("IoThenMainScopeActivity.kt") })
        assertTrue("Dispatchers.IO.let must fail: ${badReport.violations}", badReport.violations.any { it.contains("DispatchersIoLetActivity.kt") })
        assertTrue("Dispatchers.IO.also must fail: ${badReport.violations}", badReport.violations.any { it.contains("DispatchersIoAlsoActivity.kt") })

        val validNestedRunInIoSources = mapOf(
            "presentation/ValidRunInsideIoActivity.kt" to """
                class ValidRunInsideIoActivity {
                    suspend fun fetch() = withContext(Dispatchers.IO) {
                        run {
                            java.net.URL("https://example.cn").openConnection().connect()
                        }
                    }
                }
            """.trimIndent()
        )
        val okReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(validNestedRunInIoSources)
        assertTrue("Nested run { ... } inside withContext(Dispatchers.IO) must pass: ${okReport.violations}", okReport.passed)
    }

    @Test
    fun `iteration 5 NEW_A1_QUOTE_COLLISION and generic args handle trailing empty string in template and generic comma in PendingIntent`() {
        val validQuoteAndGenericSources = mapOf(
            "service/ElvisEmptyInTemplateOk.kt" to """
                class ElvisEmptyInTemplateOk {
                    fun build(c: Context, name: String?): PendingIntent =
                        PendingIntent.getActivity(c, 0, Intent("act_${'$'}{name ?: ""}"), PendingIntent.FLAG_IMMUTABLE)
                }
            """.trimIndent(),
            "service/BlockEmptyTemplateShadowOk.kt" to """
                class BlockEmptyTemplateShadowOk {
                    fun build(c: Context, i: Intent): PendingIntent {
                        val flags = PendingIntent.FLAG_IMMUTABLE
                        if (true) {
                            val s = "${'$'}{""}"
                            val flags = 0
                        }
                        return PendingIntent.getActivity(c, 0, i, flags)
                    }
                }
            """.trimIndent(),
            "service/GenericArgCommaOk.kt" to """
                class GenericArgCommaOk {
                    fun build(c: Context): PendingIntent =
                        PendingIntent.getActivity(c, 0, buildIntent<String, Int>(), PendingIntent.FLAG_IMMUTABLE)
                    fun buildNested(c: Context): PendingIntent =
                        PendingIntent.getActivity(
                            c,
                            0,
                            buildMap<String, List<Pair<Int, (Int, String) -> Unit>>>(),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    fun buildComparisonOk(c: Context, a: Int, b: Int, i1: Intent, i2: Intent): PendingIntent =
                        PendingIntent.getActivity(c, if (a < b) 1 else 2, i1, PendingIntent.FLAG_IMMUTABLE)
                }
            """.trimIndent()
        )
        val okReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(validQuoteAndGenericSources)
        assertTrue("Elvis empty string in template, block empty template, and generic arg commas must pass: ${okReport.violations}", okReport.passed)

        val badQuoteAndGenericSources = mapOf(
            "service/ElvisEmptyInTemplateBad.kt" to """
                class ElvisEmptyInTemplateBad {
                    fun build(c: Context, name: String?): PendingIntent =
                        PendingIntent.getActivity(c, 0, Intent("act_${'$'}{name ?: ""}"), 0)
                }
            """.trimIndent(),
            "service/GenericArgCommaBad.kt" to """
                class GenericArgCommaBad {
                    fun build(c: Context): PendingIntent =
                        PendingIntent.getActivity(c, 0, buildIntent<String, Int>(), 0)
                    fun buildComparisonBad(c: Context, a: Int, b: Int, d: Int, i1: Intent, i2: Intent): PendingIntent =
                        PendingIntent.getActivity(c, 0, if (a < b && c > d) i1 else i2, 0)
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badQuoteAndGenericSources)
        assertFalse(badReport.passed)
        assertTrue("Elvis empty template with flags=0 must still fail: ${badReport.violations}", badReport.violations.any { it.contains("ElvisEmptyInTemplateBad.kt") })
        assertEquals("GenericArgCommaBad must detect both unsafe calls", 2, badReport.violations.count { it.contains("GenericArgCommaBad.kt") })
    }
}
