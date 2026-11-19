package com.mockloc.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mockloc.data.scope.PackageInstallChecker
import com.mockloc.data.scope.RecommendedScopeRepository
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.domain.model.AppScopeInfo
import com.mockloc.presentation.model.WhitelistUiEvent
import com.mockloc.presentation.model.WhitelistUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 白名单分流管理 ViewModel：全部 WhitelistRepository 初始化与写入均调度至 IO 线程，
 * 通过 Mutex 串行化初始化与变更写回，并以最新仓储权威快照 + Flow 驱动状态，杜绝多线程竞态撕裂。
 */
class WhitelistViewModel(
    private val whitelistRepositoryProvider: suspend () -> WhitelistRepository,
    private val packageInstallChecker: PackageInstallChecker,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(WhitelistUiState())
    val uiState: StateFlow<WhitelistUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WhitelistUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<WhitelistUiEvent> = _events.asSharedFlow()

    private val repoMutex = Mutex()
    private val mutationMutex = Mutex()
    @Volatile
    private var repo: WhitelistRepository? = null
    @Volatile
    private var flowObserved = false

    init {
        loadApps()
    }

    private suspend fun requireRepo(): WhitelistRepository {
        repo?.let { return it }
        return repoMutex.withLock {
            repo?.let { return@withLock it }
            val created = withContext(ioDispatcher) {
                whitelistRepositoryProvider()
            }
            repo = created
            startObservingSelectedFlowLocked(created)
            created
        }
    }

    private fun startObservingSelectedFlowLocked(repository: WhitelistRepository) {
        if (flowObserved) return
        flowObserved = true
        viewModelScope.launch {
            repository.selectedPackagesFlow.collect { selected ->
                _uiState.update { state ->
                    val syncedAll = syncSelectedFlag(state.allApps, selected)
                    state.copy(
                        allApps = syncedAll,
                        selectedPackages = selected,
                        filteredApps = applyFilter(
                            repository = repository,
                            apps = syncedAll,
                            query = state.searchQuery,
                            includeSystem = state.includeSystemApps,
                            selected = selected
                        )
                    )
                }
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val repository = requireRepo()
                val scopes = withContext(ioDispatcher) {
                    val rec = RecommendedScopeRepository.getRecommendedScopes(packageInstallChecker)
                    RecommendedScopeRepository.summarizeScopeStats(rec)
                }
                val installed = withContext(ioDispatcher) {
                    repository.getInstalledApps(includeSystem = true)
                }
                Triple(repository, installed, scopes)
            }.onSuccess { (repository, installed, summary) ->
                mutationMutex.withLock {
                    val latestSelected = withContext(ioDispatcher) {
                        repository.getSelectedPackages()
                    }
                    _uiState.update { state ->
                        val syncedAll = syncSelectedFlag(installed, latestSelected)
                        state.copy(
                            isLoading = false,
                            allApps = syncedAll,
                            selectedPackages = latestSelected,
                            filteredApps = applyFilter(
                                repository = repository,
                                apps = syncedAll,
                                query = state.searchQuery,
                                includeSystem = state.includeSystemApps,
                                selected = latestSelected
                            ),
                            recommendedSummary = summary,
                            errorMessage = null
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载应用列表失败：${err.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredApps = applyFilter(
                    repository = repo,
                    apps = state.allApps,
                    query = query,
                    includeSystem = state.includeSystemApps,
                    selected = state.selectedPackages
                )
            )
        }
    }

    fun setIncludeSystemApps(include: Boolean) {
        _uiState.update { state ->
            state.copy(
                includeSystemApps = include,
                filteredApps = applyFilter(
                    repository = repo,
                    apps = state.allApps,
                    query = state.searchQuery,
                    includeSystem = include,
                    selected = state.selectedPackages
                )
            )
        }
    }

    fun togglePackage(packageName: String) {
        viewModelScope.launch {
            mutationMutex.withLock {
                val repository = requireRepo()
                withContext(ioDispatcher) {
                    repository.togglePackage(packageName)
                }
                val authoritative = withContext(ioDispatcher) {
                    repository.getSelectedPackages()
                }
                applyUpdatedSelection(repository, authoritative)
            }
        }
    }

    fun selectAllFiltered() {
        viewModelScope.launch {
            mutationMutex.withLock {
                val currentFiltered = _uiState.value.filteredApps
                if (currentFiltered.isEmpty()) {
                    _events.tryEmit(WhitelistUiEvent.ShowMessage("当前筛选结果为空"))
                    return@withLock
                }
                val repository = requireRepo()
                withContext(ioDispatcher) {
                    repository.selectAllFiltered(currentFiltered)
                }
                val authoritative = withContext(ioDispatcher) {
                    repository.getSelectedPackages()
                }
                applyUpdatedSelection(repository, authoritative)
                _events.tryEmit(WhitelistUiEvent.ShowMessage("已勾选当前筛选的 ${currentFiltered.size} 个应用"))
            }
        }
    }

    fun clearFiltered() {
        viewModelScope.launch {
            mutationMutex.withLock {
                val currentFiltered = _uiState.value.filteredApps
                if (currentFiltered.isEmpty()) {
                    _events.tryEmit(WhitelistUiEvent.ShowMessage("当前筛选结果为空"))
                    return@withLock
                }
                val repository = requireRepo()
                withContext(ioDispatcher) {
                    repository.clearFiltered(currentFiltered)
                }
                val authoritative = withContext(ioDispatcher) {
                    repository.getSelectedPackages()
                }
                applyUpdatedSelection(repository, authoritative)
                _events.tryEmit(WhitelistUiEvent.ShowMessage("已取消勾选当前筛选列表中的应用"))
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            mutationMutex.withLock {
                val repository = requireRepo()
                withContext(ioDispatcher) {
                    repository.clearAll()
                }
                val authoritative = withContext(ioDispatcher) {
                    repository.getSelectedPackages()
                }
                applyUpdatedSelection(repository, authoritative)
                _events.tryEmit(WhitelistUiEvent.ShowMessage("已清空白名单"))
            }
        }
    }

    fun importRecommendedInstalled() {
        viewModelScope.launch {
            mutationMutex.withLock {
                val repository = requireRepo()
                val result = withContext(ioDispatcher) {
                    RecommendedScopeRepository.addAllInstalledToWhitelist(packageInstallChecker, repository)
                }
                val authoritative = withContext(ioDispatcher) {
                    repository.getSelectedPackages()
                }
                applyUpdatedSelection(repository, authoritative)
                val msg = if (result.importedPackages.isEmpty()) {
                    "没有新增可导入的推荐应用（系统框架与模块自身已自动跳过）"
                } else {
                    "已导入 ${result.importedPackages.size} 个已安装推荐应用"
                }
                _events.tryEmit(WhitelistUiEvent.ShowMessage(msg))
            }
        }
    }

    private fun applyUpdatedSelection(repository: WhitelistRepository, selected: Set<String>) {
        _uiState.update { state ->
            val syncedAll = syncSelectedFlag(state.allApps, selected)
            state.copy(
                allApps = syncedAll,
                selectedPackages = selected,
                filteredApps = applyFilter(
                    repository = repository,
                    apps = syncedAll,
                    query = state.searchQuery,
                    includeSystem = state.includeSystemApps,
                    selected = selected
                )
            )
        }
    }

    private fun syncSelectedFlag(
        apps: List<AppScopeInfo>,
        selected: Set<String>
    ): List<AppScopeInfo> {
        return apps.map { app ->
            val isSel = app.packageName in selected
            if (app.isSelected == isSel) app else app.copy(isSelected = isSel)
        }
    }

    private fun applyFilter(
        repository: WhitelistRepository?,
        apps: List<AppScopeInfo>,
        query: String,
        includeSystem: Boolean,
        selected: Set<String>
    ): List<AppScopeInfo> {
        if (repository != null) {
            return repository.filterApps(
                apps = apps,
                query = query,
                includeSystem = includeSystem,
                selectedPackages = selected
            )
        }
        val trimmed = query.trim()
        return apps.filter { app ->
            val systemOk = includeSystem || !app.isSystemApp || app.packageName in selected
            val queryOk = trimmed.isEmpty() ||
                app.appName.contains(trimmed, ignoreCase = true) ||
                app.packageName.contains(trimmed, ignoreCase = true)
            systemOk && queryOk
        }
    }
}
