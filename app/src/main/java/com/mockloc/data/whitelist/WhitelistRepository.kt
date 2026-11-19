package com.mockloc.data.whitelist

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.scope.RecommendedScopeRepository
import com.mockloc.domain.model.AppScopeInfo
import com.mockloc.service.MockLocationForegroundService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

class WhitelistRepository(
    private val configRepository: MockConfigRepository,
    private val metaPrefs: SharedPreferences,
    private val packageQuerySource: PackageQuerySource,
    private val recommendedPackagesProvider: () -> Set<String> = {
        RecommendedScopeRepository.DEFAULT_TARGET_RECOMMENDED_PACKAGES
    },
    private val selfPackageName: String = DEFAULT_SELF_PACKAGE_NAME,
    private val onPackagesSynced: ((updatedConfig: MockConfig, affectedPackages: Set<String>) -> Unit)? = null,
    private val prepopulateOnFirstInit: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutationLock = Any()

    @Volatile
    private var initializedCache = false

    val selectedPackagesFlow: Flow<Set<String>> = configRepository.configFlow
        .map { normalizePackages(it.targetPackages) }
        .distinctUntilChanged()

    init {
        ensureInitialized()
    }

    fun ensureInitialized() {
        if (initializedCache) return

        var syncPayload: Pair<MockConfig, Set<String>>? = null
        synchronized(mutationLock) {
            if (initializedCache) return

            val alreadyInitialized = readBooleanSafe(KEY_WHITELIST_INITIALIZED, false)
            if (alreadyInitialized) {
                initializedCache = true
                return
            }

            val current = normalizePackages(configRepository.currentConfig.targetPackages)
            if (current.isNotEmpty() || !prepopulateOnFirstInit) {
                writeInitializedFlagLocked()
                initializedCache = true
                return
            }

            val preset = normalizePackages(DEFAULT_PRESET_PACKAGES)
            if (preset.isNotEmpty()) {
                val updated = configRepository.updateImmediate { it.copy(targetPackages = preset) }
                syncPayload = updated to preset
            }
            writeInitializedFlagLocked()
            initializedCache = true
        }

        syncPayload?.let { (updated, affected) ->
            dispatchSyncCallback(updated, affected)
        }
    }

    suspend fun getInstalledApps(includeSystem: Boolean): List<AppScopeInfo> {
        ensureInitialized()
        val rawRecords = withContext(ioDispatcher) {
            runCatching { packageQuerySource.queryInstalledApps() }.getOrDefault(emptyList())
        }
        val selected = getSelectedPackages()
        val recommended = normalizePackages(recommendedPackagesProvider())

        val items = ArrayList<AppScopeInfo>(rawRecords.size)
        for (record in rawRecords) {
            val pkg = record.packageName.trim()
            if (!isValidTargetPackage(pkg)) continue
            val isSelected = pkg in selected
            if (!includeSystem && record.isSystemApp && !isSelected) continue

            items.add(
                AppScopeInfo(
                    packageName = pkg,
                    appName = record.appName.trim().ifEmpty { pkg },
                    isSystemApp = record.isSystemApp,
                    isSelected = isSelected,
                    isRecommended = pkg in recommended
                )
            )
        }

        val collator = Collator.getInstance(Locale.CHINA).apply {
            strength = Collator.PRIMARY
        }
        items.sortWith(
            Comparator { a, b ->
                val selCmp = b.isSelected.compareTo(a.isSelected)
                if (selCmp != 0) return@Comparator selCmp
                val recCmp = b.isRecommended.compareTo(a.isRecommended)
                if (recCmp != 0) return@Comparator recCmp
                val nameCmp = collator.compare(a.appName, b.appName)
                if (nameCmp != 0) return@Comparator nameCmp
                a.packageName.compareTo(b.packageName)
            }
        )
        return items.toList()
    }

    fun filterApps(
        apps: List<AppScopeInfo>,
        query: String,
        includeSystem: Boolean = true,
        selectedPackages: Set<String> = emptySet()
    ): List<AppScopeInfo> {
        val cleanQuery = query.trim()
        val normalizedSelected = normalizePackages(selectedPackages)
        return apps.filter { app ->
            val keepBySystem = includeSystem ||
                !app.isSystemApp ||
                app.isSelected ||
                app.packageName in normalizedSelected
            if (!keepBySystem) return@filter false

            if (cleanQuery.isEmpty()) {
                true
            } else {
                app.appName.contains(cleanQuery, ignoreCase = true) ||
                    app.packageName.contains(cleanQuery, ignoreCase = true)
            }
        }
    }

    fun getSelectedPackages(): Set<String> {
        ensureInitialized()
        return normalizePackages(configRepository.currentConfig.targetPackages)
    }

    fun isPackageSelected(packageName: String?): Boolean {
        val trimmed = packageName?.trim().orEmpty()
        if (!isValidTargetPackage(trimmed)) return false
        return getSelectedPackages().contains(trimmed)
    }

    fun saveSelectedPackages(packages: Set<String>): Set<String> {
        ensureInitialized()
        val normalizedNext = normalizePackages(packages)
        return mutateTargetPackages { normalizedNext }
    }

    fun togglePackage(packageName: String): Set<String> {
        ensureInitialized()
        val trimmed = packageName.trim()
        if (!isValidTargetPackage(trimmed)) {
            return getSelectedPackages()
        }
        return mutateTargetPackages { current ->
            if (trimmed in current) {
                current - trimmed
            } else {
                current + trimmed
            }
        }
    }

    fun addPackages(packages: Collection<String>): Set<String> {
        ensureInitialized()
        val toAdd = normalizePackages(packages)
        if (toAdd.isEmpty()) return getSelectedPackages()
        return mutateTargetPackages { current -> current + toAdd }
    }

    fun removePackages(packages: Collection<String>): Set<String> {
        ensureInitialized()
        val toRemove = packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (toRemove.isEmpty()) return getSelectedPackages()
        return mutateTargetPackages { current -> current - toRemove }
    }

    fun selectAllFiltered(filteredApps: List<AppScopeInfo>): Set<String> {
        ensureInitialized()
        val filteredPackages = normalizePackages(filteredApps.map { it.packageName })
        if (filteredPackages.isEmpty()) return getSelectedPackages()
        return mutateTargetPackages { current -> current + filteredPackages }
    }

    fun clearFiltered(filteredApps: List<AppScopeInfo>): Set<String> {
        ensureInitialized()
        val toClear = filteredApps.asSequence()
            .map { it.packageName.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (toClear.isEmpty()) return getSelectedPackages()
        return mutateTargetPackages { current -> current - toClear }
    }

    fun clearAll(): Set<String> {
        return saveSelectedPackages(emptySet())
    }

    fun isValidTargetPackage(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == selfPackageName || trimmed == ANDROID_FRAMEWORK_PACKAGE) return false
        return PACKAGE_NAME_REGEX.matches(trimmed)
    }

    private fun normalizePackages(packages: Iterable<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (raw in packages) {
            val trimmed = raw.trim()
            if (isValidTargetPackage(trimmed)) {
                result.add(trimmed)
            }
        }
        return result.toSet()
    }

    private fun mutateTargetPackages(transform: (Set<String>) -> Set<String>): Set<String> {
        var updatedConfig: MockConfig? = null
        var affectedPackages: Set<String> = emptySet()
        var finalPackages: Set<String> = emptySet()

        synchronized(mutationLock) {
            val previous = normalizePackages(configRepository.currentConfig.targetPackages)
            val candidate = normalizePackages(transform(previous))
            if (!initializedCache || !readBooleanSafe(KEY_WHITELIST_INITIALIZED, false)) {
                writeInitializedFlagLocked()
            }
            initializedCache = true

            if (candidate == previous && configRepository.currentConfig.targetPackages == candidate) {
                finalPackages = previous
            } else {
                val updated = configRepository.updateImmediate { current ->
                    val currentNormalized = normalizePackages(current.targetPackages)
                    val nextSet = normalizePackages(transform(currentNormalized))
                    current.copy(targetPackages = nextSet)
                }
                finalPackages = normalizePackages(updated.targetPackages)
                updatedConfig = updated
                affectedPackages = previous union finalPackages
            }
        }

        val configToSync = updatedConfig
        if (configToSync != null && affectedPackages.isNotEmpty()) {
            dispatchSyncCallback(configToSync, affectedPackages)
        }
        return finalPackages
    }

    private fun dispatchSyncCallback(config: MockConfig, affectedPackages: Set<String>) {
        runCatching {
            onPackagesSynced?.invoke(config, affectedPackages)
        }
    }

    private fun writeInitializedFlagLocked() {
        runCatching {
            metaPrefs.edit()
                .putBoolean(KEY_WHITELIST_INITIALIZED, true)
                .commit()
        }
    }

    private fun readBooleanSafe(key: String, defaultValue: Boolean): Boolean {
        return try {
            metaPrefs.getBoolean(key, defaultValue)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    companion object {
        const val META_PREFS_NAME = "mockloc_whitelist_meta"
        const val KEY_WHITELIST_INITIALIZED = "whitelist_initialized"
        const val DEFAULT_SELF_PACKAGE_NAME = "com.mockloc"
        const val ANDROID_FRAMEWORK_PACKAGE = "android"

        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

        val DEFAULT_PRESET_PACKAGES: Set<String> = linkedSetOf(
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

        @Volatile
        private var instance: WhitelistRepository? = null

        fun getInstance(context: Context): WhitelistRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext ?: context
                val configRepo = MockConfigRepository.getInstance(appContext)
                val metaPrefs = appContext.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)
                val querySource = AndroidPackageQuerySource(appContext.packageManager)
                WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = metaPrefs,
                    packageQuerySource = querySource,
                    selfPackageName = appContext.packageName.ifBlank { DEFAULT_SELF_PACKAGE_NAME },
                    onPackagesSynced = { updatedConfig, affectedPackages ->
                        MockLocationForegroundService.dispatchExplicitConfigBroadcasts(
                            appContext,
                            updatedConfig,
                            affectedPackages
                        )
                    }
                ).also { instance = it }
            }
        }

        fun setInstanceForTest(repository: WhitelistRepository?) {
            synchronized(this) {
                instance = repository
            }
        }
    }
}
