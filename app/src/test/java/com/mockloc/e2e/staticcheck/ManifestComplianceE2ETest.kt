package com.mockloc.e2e.staticcheck

import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestComplianceE2ETest {

    private fun validAndroid14To17ManifestXml(
        missingPermission: String? = null,
        includeQueries: Boolean = true,
        appName: String = ".MockLocApp",
        omitActivityExported: Boolean = false,
        includeForegroundService: Boolean = true,
        fgServiceExported: String = "false",
        fgServiceType: String = "location",
        providerAuthorities: String = "com.mockloc.configprovider",
        providerExported: String = "true",
        includeXposedSharedPrefs: Boolean = true,
        xposedMinVersion: String = "93",
        xposedScopeRes: String? = "@array/xposed_scope"
    ): String {
        val permissions = StaticManifestAndResourceChecker.REQUIRED_PERMISSIONS
            .filter { it != missingPermission }
            .joinToString("\n    ") { perm ->
                """<uses-permission android:name="$perm" />"""
            }

        val queriesBlock = if (includeQueries) {
            """
            <queries>
                <intent>
                    <action android:name="android.intent.action.MAIN" />
                </intent>
            </queries>
            """.trimIndent()
        } else ""

        val sharedPrefsMeta = if (includeXposedSharedPrefs) {
            """<meta-data android:name="xposedsharedprefs" android:value="true" />"""
        } else ""

        val scopeMeta = if (xposedScopeRes != null) {
            """<meta-data android:name="xposedscope" android:resource="$xposedScopeRes" />"""
        } else ""

        val activityExportedAttr = if (omitActivityExported) "" else """android:exported="true""""

        val fgServiceBlock = if (includeForegroundService) {
            """
            <service
                android:name=".service.MockLocationForegroundService"
                android:exported="$fgServiceExported"
                android:foregroundServiceType="$fgServiceType" />
            """.trimIndent()
        } else ""

        return """
            |<?xml version="1.0" encoding="utf-8"?>
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |    $permissions
            |    $queriesBlock
            |    <application
            |        android:name="$appName"
            |        android:allowBackup="true"
            |        android:label="MockLocation"
            |        android:theme="@style/Theme.MockLoc">
            |        <meta-data android:name="xposedmodule" android:value="true" />
            |        $sharedPrefsMeta
            |        <meta-data android:name="xposeddescription" android:value="MockLocation Xposed and Root module" />
            |        <meta-data android:name="xposedminversion" android:value="$xposedMinVersion" />
            |        $scopeMeta
            |        <activity
            |            android:name=".presentation.MainActivity"
            |            $activityExportedAttr>
            |            <intent-filter>
            |                <action android:name="android.intent.action.MAIN" />
            |                <category android:name="android.intent.category.LAUNCHER" />
            |            </intent-filter>
            |        </activity>
            |        <activity
            |            android:name=".presentation.WhitelistActivity"
            |            android:exported="false" />
            |        $fgServiceBlock
            |        <provider
            |            android:name="com.mockloc.ipc.LocationConfigProvider"
            |            android:authorities="$providerAuthorities"
            |            android:exported="$providerExported" />
            |    </application>
            |</manifest>
        """.trimMargin()
    }

    @Test
    fun `workspace AndroidManifest exists and is well formed XML`() {
        val manifestFile = WorkspacePathResolver.manifestFile()
        assertTrue("Expected AndroidManifest.xml at ${manifestFile.absolutePath}", manifestFile.isFile)
        val xmlContent = manifestFile.readText(Charsets.UTF_8)
        val report = StaticManifestAndResourceChecker.checkManifestXmlWellFormed(xmlContent)
        assertTrue("Manifest XML must be well-formed: ${report.violations}", report.passed)

        // Phase 2 强断言已开启：全量校验真实 AndroidManifest.xml 对 Android 14–17 的合规性
        val fullReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(xmlContent)
        assertTrue("AndroidManifest compliance violations: ${fullReport.violations}", fullReport.passed)
    }

    @Test
    fun `compliant Android 14 to 17 Manifest fixture passes all rules`() {
        val xml = validAndroid14To17ManifestXml()
        val report = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(xml)
        assertTrue("Expected compliant manifest to pass, violations=${report.violations}", report.passed)
        assertTrue("Expected zero violations", report.violations.isEmpty())
    }

    @Test
    fun `compliant Manifest supports multi-type foregroundServiceType containing location`() {
        val xml = validAndroid14To17ManifestXml(
            appName = "com.mockloc.MockLocApp",
            fgServiceType = "location|dataSync"
        )
        val report = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(xml)
        assertTrue("Expected multi-type location service to pass: ${report.violations}", report.passed)
    }

    @Test
    fun `missing FOREGROUND_SERVICE_LOCATION permission is rejected`() {
        val xml = validAndroid14To17ManifestXml(
            missingPermission = "android.permission.FOREGROUND_SERVICE_LOCATION"
        )
        val report = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(xml)
        assertFalse(report.passed)
        assertTrue(report.violations.any { it.contains("FOREGROUND_SERVICE_LOCATION") })
    }

    @Test
    fun `missing POST_NOTIFICATIONS permission is rejected`() {
        val xml = validAndroid14To17ManifestXml(
            missingPermission = "android.permission.POST_NOTIFICATIONS"
        )
        val report = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(xml)
        assertFalse(report.passed)
        assertTrue(report.violations.any { it.contains("POST_NOTIFICATIONS") })
    }

    @Test
    fun `missing QUERY_ALL_PACKAGES or queries block is rejected`() {
        val missingQueryPerm = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(missingPermission = "android.permission.QUERY_ALL_PACKAGES")
        )
        assertFalse(missingQueryPerm.passed)
        assertTrue(missingQueryPerm.violations.any { it.contains("QUERY_ALL_PACKAGES") })

        val missingQueriesNode = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(includeQueries = false)
        )
        assertFalse(missingQueriesNode.passed)
        assertTrue(missingQueriesNode.violations.any { it.contains("<queries>") })
    }

    @Test
    fun `missing xposedsharedprefs or low xposedminversion is rejected`() {
        val noSharedPrefs = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(includeXposedSharedPrefs = false)
        )
        assertFalse(noSharedPrefs.passed)
        assertTrue(noSharedPrefs.violations.any { it.contains("xposedsharedprefs") })

        val lowMinVersion = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(xposedMinVersion = "82")
        )
        assertFalse(lowMinVersion.passed)
        assertTrue(lowMinVersion.violations.any { it.contains("xposedminversion") })
    }

    @Test
    fun `missing explicit android exported attribute on component is rejected`() {
        val xml = validAndroid14To17ManifestXml(omitActivityExported = true)
        val report = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(xml)
        assertFalse(report.passed)
        assertTrue(report.violations.any { it.contains("android:exported") })
    }

    @Test
    fun `missing or misconfigured MockLocationForegroundService is rejected`() {
        val missingService = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(includeForegroundService = false)
        )
        assertFalse(missingService.passed)
        assertTrue(missingService.violations.any { it.contains("MockLocationForegroundService") })

        val exportedService = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(fgServiceExported = "true")
        )
        assertFalse(exportedService.passed)
        assertTrue(exportedService.violations.any { it.contains("exported=\"false\"") })

        val wrongFgType = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(fgServiceType = "dataSync")
        )
        assertFalse(wrongFgType.passed)
        assertTrue(wrongFgType.violations.any { it.contains("foregroundServiceType") })
    }

    @Test
    fun `misconfigured LocationConfigProvider authorities or exported is rejected`() {
        val badAuth = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(providerAuthorities = "com.wrong.provider")
        )
        assertFalse(badAuth.passed)
        assertTrue(badAuth.violations.any { it.contains("authorities") })

        val unexportedProvider = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(providerExported = "false")
        )
        assertFalse(unexportedProvider.passed)
        assertTrue(unexportedProvider.violations.any { it.contains("LocationConfigProvider") && it.contains("exported") })
    }

    @Test
    fun `malformed or empty Manifest XML fails cleanly`() {
        val emptyReport = StaticManifestAndResourceChecker.checkManifestXmlWellFormed("")
        assertFalse(emptyReport.passed)

        val brokenXml = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            "<manifest><application></manifest>"
        )
        assertFalse(brokenXml.passed)
        assertTrue(brokenXml.violations.any { it.contains("not well-formed") })
    }

    @Test
    fun `missing application name or empty queries or invalid xposed metadata is rejected`() {
        val wrongApp = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(
            validAndroid14To17ManifestXml(appName = ".WrongApp")
        )
        assertFalse(wrongApp.passed)
        assertTrue(wrongApp.violations.any { it.contains("MockLocApp") })

        val emptyQueriesXml = validAndroid14To17ManifestXml(includeQueries = false)
            .replace("<application", "<queries/>\n    <application")
        val emptyQueriesReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(emptyQueriesXml)
        assertFalse(emptyQueriesReport.passed)
        assertTrue(emptyQueriesReport.violations.any { it.contains("<queries>") })

        val badXposedModuleXml = validAndroid14To17ManifestXml()
            .replace("""<meta-data android:name="xposedmodule" android:value="true" />""", """<meta-data android:name="xposedmodule" android:value="false" />""")
        val badModuleReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(badXposedModuleXml)
        assertFalse(badModuleReport.passed)
        assertTrue(badModuleReport.violations.any { it.contains("xposedmodule") })

        val emptyDescXml = validAndroid14To17ManifestXml()
            .replace("""<meta-data android:name="xposeddescription" android:value="MockLocation Xposed and Root module" />""", """<meta-data android:name="xposeddescription" android:value="" />""")
        val emptyDescReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(emptyDescXml)
        assertFalse(emptyDescReport.passed)
        assertTrue(emptyDescReport.violations.any { it.contains("xposeddescription") })
    }

    @Test
    fun `xposedscope using android value instead of android resource is rejected as violation`() {
        val xml = validAndroid14To17ManifestXml(xposedScopeRes = null)
            .replace(
                """<meta-data android:name="xposedminversion" android:value="93" />""",
                """<meta-data android:name="xposedminversion" android:value="93" />
                <meta-data android:name="xposedscope" android:value="@array/xposed_scope" />"""
            )
        val report = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(xml)
        assertFalse("xposedscope with android:value must fail compliance", report.passed)
        assertTrue(report.violations.any { it.contains("xposedscope") && it.contains("android:resource") })
    }

    @Test
    fun `required permission with maxSdkVersion or critical component enabled false is rejected`() {
        val maxSdkXml = validAndroid14To17ManifestXml().replace(
            """<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />""",
            """<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />"""
        )
        val maxSdkReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(maxSdkXml)
        assertFalse("maxSdkVersion on ACCESS_FINE_LOCATION must be rejected", maxSdkReport.passed)
        assertTrue(maxSdkReport.violations.any { it.contains("maxSdkVersion") && it.contains("ACCESS_FINE_LOCATION") })

        val disabledServiceXml = validAndroid14To17ManifestXml().replace(
            """android:name=".service.MockLocationForegroundService"""",
            """android:name=".service.MockLocationForegroundService" android:enabled="false""""
        )
        val disabledSvcReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(disabledServiceXml)
        assertFalse(disabledSvcReport.passed)
        assertTrue(disabledSvcReport.violations.any { it.contains("enabled=\"false\"") && it.contains("MockLocationForegroundService") })

        val disabledProviderXml = validAndroid14To17ManifestXml().replace(
            """android:name="com.mockloc.ipc.LocationConfigProvider"""",
            """android:name="com.mockloc.ipc.LocationConfigProvider" android:enabled="false""""
        )
        val disabledPrvReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(disabledProviderXml)
        assertFalse(disabledPrvReport.passed)
        assertTrue(disabledPrvReport.violations.any { it.contains("enabled=\"false\"") && it.contains("LocationConfigProvider") })
    }

    @Test
    fun `XML comments containing fake permissions do not satisfy compliance and custom namespace prefix works`() {
        val commentedPermXml = validAndroid14To17ManifestXml(
            missingPermission = "android.permission.FOREGROUND_SERVICE_LOCATION"
        ).replace(
            "<queries>",
            "<!-- <uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_LOCATION\" /> -->\n    <queries>"
        )
        val commentedReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(commentedPermXml)
        assertFalse("Commented-out permission must not satisfy requirement", commentedReport.passed)
        assertTrue(commentedReport.violations.any { it.contains("FOREGROUND_SERVICE_LOCATION") })

        val customNsXml = validAndroid14To17ManifestXml(fgServiceType = "location | dataSync")
            .replace("xmlns:android=", "xmlns:a=")
            .replace("android:", "a:")
        val customNsReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(customNsXml)
        assertTrue("Custom XML namespace prefix should be supported: ${customNsReport.violations}", customNsReport.passed)
    }
}
