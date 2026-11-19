package com.mockloc.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.favorite.FavoriteRepository
import com.mockloc.data.route.RouteRepository
import com.mockloc.data.scope.AndroidPackageInstallChecker
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.domain.geocoding.DomesticGeocodingService
import com.mockloc.presentation.gateway.ControllerEngineGateway
import com.mockloc.presentation.gateway.EnvironmentDetectorGateway
import com.mockloc.presentation.gateway.PermissionStatusProvider
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.RootProviderManager
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockLocationForegroundService
import com.mockloc.service.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext
    private val broadcastScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val configRepo = MockConfigRepository.getInstance(appContext)
            val engineController = MockEngineController(appContext)
            return MainViewModel(
                configRepository = configRepo,
                engineGateway = ControllerEngineGateway(engineController),
                geocodingService = DomesticGeocodingService(),
                favoriteRepository = FavoriteRepository.getInstance(appContext),
                routeRepository = RouteRepository.getInstance(appContext),
                whitelistRepositoryProvider = { WhitelistRepository.getInstance(appContext) },
                environmentDetector = EnvironmentDetectorGateway {
                    EnvironmentDetector.detectEnvironment(appContext)
                },
                permissionProvider = PermissionStatusProvider {
                    PermissionChecker.check(appContext)
                },
                packageInstallChecker = AndroidPackageInstallChecker(appContext.packageManager),
                onConfigChangedBroadcast = { updated ->
                    broadcastScope.launch {
                        MockLocationForegroundService.dispatchExplicitConfigBroadcasts(
                            context = appContext,
                            config = updated,
                            targetPackages = updated.targetPackages
                        )
                    }
                },
                stopRootProvider = {
                    withContext(Dispatchers.IO) {
                        RootProviderManager.getInstance(appContext).stop()
                    }
                }
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class WhitelistViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WhitelistViewModel::class.java)) {
            return WhitelistViewModel(
                whitelistRepositoryProvider = { WhitelistRepository.getInstance(appContext) },
                packageInstallChecker = AndroidPackageInstallChecker(appContext.packageManager)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
