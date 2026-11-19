package com.mockloc.presentation

import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.scope.PackageInstallChecker
import com.mockloc.data.whitelist.InstalledAppRecord
import com.mockloc.data.whitelist.PackageQuerySource
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.testing.InMemorySharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WhitelistViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createRepo(
        records: List<InstalledAppRecord> = defaultApps(),
        prepopulate: Boolean = false
    ): WhitelistRepository {
        val configPrefs = InMemorySharedPreferences()
        val metaPrefs = InMemorySharedPreferences()
        val configRepo = MockConfigRepository(
            prefs = configPrefs,
            isWorldReadable = false,
            ioScope = CoroutineScope(testDispatcher)
        )
        return WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { records },
            prepopulateOnFirstInit = prepopulate,
            ioDispatcher = testDispatcher
        )
    }

    private fun defaultApps(): List<InstalledAppRecord> = listOf(
        InstalledAppRecord("com.autonavi.minimap", "高德地图", isSystemApp = false),
        InstalledAppRecord("com.tencent.mm", "微信", isSystemApp = false),
        InstalledAppRecord("com.example.alpha", "测试[Alpha](1)", isSystemApp = false),
        InstalledAppRecord("com.android.settings", "系统设置", isSystemApp = true),
        InstalledAppRecord("com.android.systemui", "系统界面", isSystemApp = true),
        InstalledAppRecord("com.mockloc", "MockLocation", isSystemApp = false),
        InstalledAppRecord("android", "Android 系统框架", isSystemApp = true)
    )

    @Test
    fun `loads apps asynchronously and excludes self and android framework`() = runTest(testDispatcher) {
        val repo = createRepo()
        val checker = PackageInstallChecker { pkg ->
            pkg == "com.autonavi.minimap" || pkg == "com.mockloc" || pkg == "android"
        }

        val vm = WhitelistViewModel(
            whitelistRepositoryProvider = { repo },
            packageInstallChecker = checker,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.allApps.any { it.packageName == "com.autonavi.minimap" })
        assertFalse(state.allApps.any { it.packageName == "com.mockloc" })
        assertFalse(state.allApps.any { it.packageName == "android" })
        // 默认不显示未选中的系统应用
        assertFalse(state.filteredApps.any { it.packageName == "com.android.settings" })
    }

    @Test
    fun `search supports regex special characters safely and system filter keeps selected system app`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            repo.addPackages(listOf("com.android.settings"))
            val vm = WhitelistViewModel(
                whitelistRepositoryProvider = { repo },
                packageInstallChecker = PackageInstallChecker { false },
                ioDispatcher = testDispatcher
            )
            advanceUntilIdle()

            // 已选中的系统应用即使关闭系统开关也要可见
            assertTrue(vm.uiState.value.filteredApps.any { it.packageName == "com.android.settings" })
            assertFalse(vm.uiState.value.filteredApps.any { it.packageName == "com.android.systemui" })

            // 开启显示系统应用
            vm.setIncludeSystemApps(true)
            assertTrue(vm.uiState.value.filteredApps.any { it.packageName == "com.android.systemui" })

            // 含正则元字符关键字搜索不会抛出 PatternSyntaxException
            vm.updateSearchQuery("[Alpha](")
            val filtered = vm.uiState.value.filteredApps
            assertEquals(1, filtered.size)
            assertEquals("com.example.alpha", filtered.first().packageName)
        }

    @Test
    fun `selectAllFiltered and clearFiltered preserve selections outside filter`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            repo.addPackages(listOf("com.tencent.mm"))
            val vm = WhitelistViewModel(
                whitelistRepositoryProvider = { repo },
                packageInstallChecker = PackageInstallChecker { false },
                ioDispatcher = testDispatcher
            )
            advanceUntilIdle()

            // 筛选高德后全选，应保留原有微信
            vm.updateSearchQuery("高德")
            vm.selectAllFiltered()
            advanceUntilIdle()
            assertEquals(
                setOf("com.tencent.mm", "com.autonavi.minimap"),
                vm.uiState.value.selectedPackages
            )

            // 清空当前筛选结果，不应误删筛选外的微信
            vm.clearFiltered()
            advanceUntilIdle()
            assertEquals(setOf("com.tencent.mm"), vm.uiState.value.selectedPackages)
        }

    @Test
    fun `importRecommendedInstalled skips self and android framework`() =
        runTest(testDispatcher) {
            val repo = createRepo()
            val checker = PackageInstallChecker { pkg ->
                pkg in setOf("com.mockloc", "android", "com.autonavi.minimap", "com.tencent.mm")
            }
            val vm = WhitelistViewModel(
                whitelistRepositoryProvider = { repo },
                packageInstallChecker = checker,
                ioDispatcher = testDispatcher
            )
            advanceUntilIdle()

            vm.importRecommendedInstalled()
            advanceUntilIdle()

            val selected = vm.uiState.value.selectedPackages
            assertEquals(setOf("com.autonavi.minimap", "com.tencent.mm"), selected)
            assertFalse("com.mockloc" in selected)
            assertFalse("android" in selected)
        }

    @Test
    fun `concurrent pre-init operations invoke provider once and stay consistent with repo`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val repo = WhitelistRepository(
                    configRepository = MockConfigRepository(
                        prefs = InMemorySharedPreferences(),
                        isWorldReadable = false,
                        ioScope = scope
                    ),
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { defaultApps() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = ioExecutor
                )
                val providerCalls = AtomicInteger(0)
                val vm = WhitelistViewModel(
                    whitelistRepositoryProvider = {
                        providerCalls.incrementAndGet()
                        delay(40)
                        repo
                    },
                    packageInstallChecker = PackageInstallChecker { pkg -> pkg == "com.autonavi.minimap" },
                    ioDispatcher = ioExecutor
                )

                // 初始化尚未完成前并发触发操作
                vm.togglePackage("com.autonavi.minimap")
                vm.togglePackage("com.tencent.mm")
                vm.loadApps()

                val deadline = System.currentTimeMillis() + 3000L
                while ((vm.uiState.value.isLoading ||
                        vm.uiState.value.selectedPackages != repo.getSelectedPackages() ||
                        vm.uiState.value.selectedPackages.size < 2) &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(15)
                }
                delay(50)

                assertEquals(1, providerCalls.get())
                assertEquals(repo.getSelectedPackages(), vm.uiState.value.selectedPackages)
                assertEquals(setOf("com.autonavi.minimap", "com.tencent.mm"), vm.uiState.value.selectedPackages)
                assertTrue(vm.uiState.value.allApps.first { it.packageName == "com.autonavi.minimap" }.isSelected)
                assertTrue(vm.uiState.value.allApps.first { it.packageName == "com.tencent.mm" }.isSelected)
            }
        } finally {
            scope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `out-of-order togglePackage completions never tear uiState from repository`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val slowSyncGate = AtomicBoolean(true)
                val secondCommittedLatch = CountDownLatch(1)
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = scope
                )
                val repo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { defaultApps() },
                    onPackagesSynced = { _, affected ->
                        if (slowSyncGate.compareAndSet(true, false) && "com.autonavi.minimap" in affected) {
                            // 模拟首个广播/回调耗时慢，后发起的操作同时推进
                            secondCommittedLatch.await(150, TimeUnit.MILLISECONDS)
                        } else if ("com.tencent.mm" in affected) {
                            secondCommittedLatch.countDown()
                        }
                    },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = ioExecutor
                )
                val vm = WhitelistViewModel(
                    whitelistRepositoryProvider = { repo },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor
                )

                val deadlineInit = System.currentTimeMillis() + 2000L
                while (vm.uiState.value.isLoading && System.currentTimeMillis() < deadlineInit) {
                    delay(10)
                }

                vm.togglePackage("com.autonavi.minimap")
                vm.togglePackage("com.tencent.mm")

                val deadline = System.currentTimeMillis() + 3000L
                while ((vm.uiState.value.selectedPackages != setOf("com.autonavi.minimap", "com.tencent.mm") ||
                        repo.getSelectedPackages() != setOf("com.autonavi.minimap", "com.tencent.mm")) &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(15)
                }
                delay(50)

                val expected = repo.getSelectedPackages()
                assertEquals(setOf("com.autonavi.minimap", "com.tencent.mm"), expected)
                assertEquals(expected, vm.uiState.value.selectedPackages)
                assertTrue(vm.uiState.value.filteredApps.first { it.packageName == "com.autonavi.minimap" }.isSelected)
                assertTrue(vm.uiState.value.filteredApps.first { it.packageName == "com.tencent.mm" }.isSelected)
            }
        } finally {
            scope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }
}
