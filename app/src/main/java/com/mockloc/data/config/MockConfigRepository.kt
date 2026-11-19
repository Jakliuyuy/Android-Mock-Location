package com.mockloc.data.config

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.ipc.ConfigSyncSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MockConfigRepository(
    private val prefs: SharedPreferences,
    val isWorldReadable: Boolean = false,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val persistLock = Any()
    private val _configFlow = MutableStateFlow(safeLoadFromPrefs(MockConfig.DEFAULT))
    val configFlow: StateFlow<MockConfig> = _configFlow.asStateFlow()

    val currentConfig: MockConfig
        get() = _configFlow.value

    private var debounceJob: Job? = null
    private var lastPersistedVersion: Long = _configFlow.value.version

    @Volatile
    var lastStopOrEngineDisabledVersion: Long = 0L
        private set

    @Volatile
    var engineDisableSeq: Long = 0L
        private set

    fun updateDebounced(transform: (MockConfig) -> MockConfig): MockConfig {
        val updated = synchronized(lock) {
            val current = _configFlow.value
            val now = timeProvider().coerceAtLeast(0L)
            val nextVersion = nextVersion(current.version)
            val next = transform(current)
                .copy(
                    updatedAtMs = now,
                    version = nextVersion
                )
                .sanitized(current)
            if (next.version < current.version) {
                synchronized(persistLock) {
                    lastPersistedVersion = 0L
                }
            }
            recordDisableAuditLocked(current, next)
            _configFlow.value = next

            debounceJob?.cancel()
            debounceJob = ioScope.launch {
                if (debounceMs > 0L) {
                    delay(debounceMs)
                }
                persistSnapshot(next) { !isActive }
            }
            next
        }
        return updated
    }

    fun updateImmediate(transform: (MockConfig) -> MockConfig): MockConfig {
        val updated = synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
            val current = _configFlow.value
            val now = timeProvider().coerceAtLeast(0L)
            val nextVersion = nextVersion(current.version)
            val next = transform(current)
                .copy(
                    updatedAtMs = now,
                    version = nextVersion
                )
                .sanitized(current)
            if (next.version < current.version) {
                synchronized(persistLock) {
                    lastPersistedVersion = 0L
                }
            }
            recordDisableAuditLocked(current, next)
            _configFlow.value = next
            persistSnapshot(next)
            next
        }
        return updated
    }

    fun setConfig(config: MockConfig, immediate: Boolean = true): MockConfig {
        return if (immediate) {
            updateImmediate { config }
        } else {
            updateDebounced { config }
        }
    }

    fun updateTargetPackages(packages: Set<String>): MockConfig {
        return updateImmediate { it.copy(targetPackages = packages) }
    }

    fun reloadFromPrefs(): MockConfig {
        return synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
            val current = _configFlow.value
            val loaded = safeLoadFromPrefs(current)
            if (!loaded.enabled && loaded != current && (current.enabled || loaded.targetPackages == current.targetPackages)) {
                lastStopOrEngineDisabledVersion = loaded.version
                engineDisableSeq += 1L
            }
            _configFlow.value = loaded
            synchronized(persistLock) {
                lastPersistedVersion = loaded.version
            }
            loaded
        }
    }

    private fun safeLoadFromPrefs(fallback: MockConfig = MockConfig.DEFAULT): MockConfig {
        return try {
            clampLoadedVersion(ConfigSyncSerializer.fromPrefs(prefs, fallback))
        } catch (_: Throwable) {
            fallback.sanitized()
        }
    }

    private fun recordDisableAuditLocked(current: MockConfig, next: MockConfig) {
        if (!next.enabled && next.targetPackages == current.targetPackages) {
            lastStopOrEngineDisabledVersion = next.version
            engineDisableSeq += 1L
        }
    }

    suspend fun flushPendingSync() {
        val pending = synchronized(lock) { debounceJob }
        pending?.join()
    }

    private fun clampLoadedVersion(config: MockConfig): MockConfig {
        return if (config.version >= MAX_SAFE_LOADED_VERSION) {
            config.copy(version = 1L)
        } else {
            config
        }
    }

    private fun nextVersion(currentVersion: Long): Long {
        return if (currentVersion >= Long.MAX_VALUE - 1L) 1L else currentVersion + 1L
    }

    private fun persistSnapshot(
        config: MockConfig,
        shouldAbort: () -> Boolean = { false }
    ) {
        synchronized(persistLock) {
            if (shouldAbort()) return
            if (config.version < _configFlow.value.version || config.version <= lastPersistedVersion) {
                return
            }
            try {
                val editor = prefs.edit()
                ConfigSyncSerializer.writeToPrefs(editor, config)
                if (editor.commit()) {
                    lastPersistedVersion = config.version
                }
            } catch (_: Throwable) {
                // 保护后台线程或受限进程不因存储异常崩溃
            }
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
        private const val MAX_SAFE_LOADED_VERSION = Long.MAX_VALUE - 1_000_000L

        @Volatile
        private var instance: MockConfigRepository? = null

        fun getInstance(context: Context): MockConfigRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext ?: context
                val safePrefs = SafePrefs.open(appContext)
                MockConfigRepository(
                    prefs = safePrefs.prefs,
                    isWorldReadable = safePrefs.isWorldReadable
                ).also { instance = it }
            }
        }

        fun setInstanceForTest(repository: MockConfigRepository?) {
            synchronized(this) {
                instance = repository
            }
        }
    }
}
