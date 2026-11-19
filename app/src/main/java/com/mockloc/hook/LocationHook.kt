package com.mockloc.hook

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mockloc.data.config.MockConfig
import com.mockloc.hook.cleaner.MockLocationCleaner
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.roundToLong

class LocationHook(
    private val backend: HookBackend = XposedHookBackend,
    private val configProvider: ConfigSnapshotProvider = HookConfigStore.shared,
    private val contextProvider: () -> Context? = { currentAppContext },
    private val locationFactory: (String, MockConfig) -> Location = ::defaultBuildMockLocation,
    private val schedulerFactory: () -> ScheduledExecutorService = {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "MockLoc-Pulse").apply { isDaemon = true }
        }
    }
) {
    private val installedLoaders = ConcurrentHashMap.newKeySet<Int>()
    private val hookedConcreteClasses = ConcurrentHashMap.newKeySet<String>()

    private val registryLock = Any()
    private val proxyRefQueue = ReferenceQueue<LocationListener>()
    private val activeRefQueue = ReferenceQueue<LocationListener>()
    private val listenerProxies = HashMap<IdentityWeakKey, ProxyEntry>()
    private val activeListeners = HashMap<IdentityWeakKey, ListenerSubscription>()
    private val activePendingIntents = LinkedHashMap<PendingIntent, PendingIntentSubscription>()

    private val schedulerLock = Any()
    @Volatile
    private var scheduler: ScheduledExecutorService? = null
    private var pulseTask: ScheduledFuture<*>? = null

    private class IdentityWeakKey(
        referent: LocationListener,
        queue: ReferenceQueue<LocationListener>? = null
    ) : WeakReference<LocationListener>(referent, queue) {
        private val identityHash: Int = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityWeakKey) return false
            val mine = get()
            val theirs = other.get()
            return mine != null && mine === theirs
        }
    }

    private data class ProxyEntry(
        val proxy: LocationListener,
        val handler: ListenerProxyHandler
    )

    private data class ListenerSubscription(
        val originalRef: WeakReference<LocationListener>,
        val proxy: LocationListener,
        val proxyHandler: ListenerProxyHandler,
        val provider: String,
        val intervalMs: Long,
        val executor: Executor?,
        val looper: Looper?,
        val singleShot: Boolean,
        @Volatile var lastDeliveredAtMs: Long = 0L
    )

    private data class PendingIntentSubscription(
        val pendingIntent: PendingIntent,
        val provider: String,
        val intervalMs: Long,
        val singleShot: Boolean,
        @Volatile var lastDeliveredAtMs: Long = 0L
    )

    fun installSystemHooks(classLoader: ClassLoader?, targetPackage: String) {
        val loaderKey = System.identityHashCode(classLoader)
        if (!installedLoaders.add(loaderKey)) return

        val lmClass = backend.findClassIfExists("android.location.LocationManager", classLoader)
            ?: LocationManager::class.java

        hookLocationRequests(lmClass, targetPackage)
        hookSingleAndCurrentLocation(lmClass, targetPackage)
        hookLastKnownLocation(lmClass, targetPackage)
        hookRemoveAndFlush(lmClass, targetPackage)
        hookProviderAndStatusQueries(lmClass, targetPackage)
        hookGnssAndNmea(lmClass, targetPackage)
    }

    fun onConfigChanged(newConfig: MockConfig, targetPackage: String? = null) {
        val pkg = targetPackage ?: currentTargetPackage
        if (!newConfig.isEnabledForPackage(pkg)) {
            // 停用时仅暂停脉冲任务，绝不清空已登记监听器，保障停用再恢复 & 先开后开场景
            stopPulseTask()
            return
        }
        // 配置更新（坐标、速度或恢复启用）时立即调度一次首帧脉冲并确保周期调度运行
        val hasActive = synchronized(registryLock) {
            expungeStaleEntriesLocked()
            activeListeners.isNotEmpty() || activePendingIntents.isNotEmpty()
        }
        if (hasActive) {
            dispatchPulseTick(pkg, forceImmediate = true, overrideConfig = newConfig)
            ensurePulseSchedulerRunning(pkg)
        }
    }

    private fun isActiveFor(pkg: String): Boolean = configProvider.isActiveFor(pkg)

    private fun hookLocationRequests(lmClass: Class<*>, targetPackage: String) {
        backend.hookAllMethods(
            lmClass,
            "requestLocationUpdates",
            before = MethodHookHandler { inv ->
                val parsed = parseRequestArgs(inv.args)
                val listener = parsed.listener
                if (listener != null) {
                    val entry = getOrCreateListenerProxyEntry(
                        original = listener,
                        targetPackage = targetPackage,
                        singleShot = false
                    )
                    if (parsed.listenerIndex >= 0) {
                        inv.args[parsed.listenerIndex] = entry.proxy
                    }
                    synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        activeListeners[IdentityWeakKey(listener, activeRefQueue)] = ListenerSubscription(
                            originalRef = WeakReference(listener),
                            proxy = entry.proxy,
                            proxyHandler = entry.handler,
                            provider = parsed.provider,
                            intervalMs = parsed.intervalMs,
                            executor = parsed.executor,
                            looper = parsed.looper,
                            singleShot = false
                        )
                    }
                }

                val pi = parsed.pendingIntent
                if (pi != null) {
                    synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        activePendingIntents[pi] = PendingIntentSubscription(
                            pendingIntent = pi,
                            provider = parsed.provider,
                            intervalMs = parsed.intervalMs,
                            singleShot = false
                        )
                    }
                }

                if (listener != null || pi != null) {
                    val cfg = configProvider.currentConfig()
                    if (cfg.isEnabledForPackage(targetPackage)) {
                        ensurePulseSchedulerRunning(targetPackage)
                    }
                }
            },
            after = MethodHookHandler { inv ->
                val cfg = configProvider.currentConfig()
                if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                val parsed = parseRequestArgs(inv.args)
                val listener = parsed.listener
                if (listener != null) {
                    val sub = synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        findActiveListenerLocked(listener)
                    }
                    if (sub != null) {
                        deliverToListenerAsync(sub, cfg)
                    }
                }
                val pi = parsed.pendingIntent
                if (pi != null) {
                    val sub = synchronized(registryLock) { activePendingIntents[pi] }
                    if (sub != null) {
                        deliverToPendingIntent(sub, cfg)
                    }
                }
            }
        )
    }

    private fun hookSingleAndCurrentLocation(lmClass: Class<*>, targetPackage: String) {
        backend.hookAllMethods(
            lmClass,
            "requestSingleUpdate",
            before = MethodHookHandler { inv ->
                val parsed = parseRequestArgs(inv.args)
                val listener = parsed.listener
                if (listener != null) {
                    val entry = getOrCreateListenerProxyEntry(
                        original = listener,
                        targetPackage = targetPackage,
                        singleShot = true
                    )
                    if (parsed.listenerIndex >= 0) {
                        inv.args[parsed.listenerIndex] = entry.proxy
                    }
                    synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        activeListeners[IdentityWeakKey(listener, activeRefQueue)] = ListenerSubscription(
                            originalRef = WeakReference(listener),
                            proxy = entry.proxy,
                            proxyHandler = entry.handler,
                            provider = parsed.provider,
                            intervalMs = MIN_PULSE_INTERVAL_MS,
                            executor = parsed.executor,
                            looper = parsed.looper,
                            singleShot = true
                        )
                    }
                }
                val pi = parsed.pendingIntent
                if (pi != null) {
                    synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        activePendingIntents[pi] = PendingIntentSubscription(
                            pendingIntent = pi,
                            provider = parsed.provider,
                            intervalMs = MIN_PULSE_INTERVAL_MS,
                            singleShot = true
                        )
                    }
                }
            },
            after = MethodHookHandler { inv ->
                val cfg = configProvider.currentConfig()
                if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                val parsed = parseRequestArgs(inv.args)
                val listener = parsed.listener
                if (listener != null) {
                    val sub = synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        removeActiveListenerLocked(listener)
                    }
                    if (sub != null && sub.proxyHandler.tryConsumeSingleShot()) {
                        deliverToListenerAsync(sub, cfg)
                    }
                }
                val pi = parsed.pendingIntent
                if (pi != null) {
                    val sub = synchronized(registryLock) { activePendingIntents.remove(pi) }
                    if (sub != null) {
                        deliverToPendingIntent(sub, cfg)
                    }
                }
            }
        )

        backend.hookAllMethods(
            lmClass,
            "getCurrentLocation",
            before = MethodHookHandler { inv ->
                val cfg = configProvider.currentConfig()
                if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler

                val provider = (inv.args.firstOrNull() as? String)?.takeIf { it.isNotBlank() }
                    ?: LocationManager.GPS_PROVIDER
                val cancelSignal = inv.args.firstOrNull { it is CancellationSignal } as? CancellationSignal
                val executor = inv.args.firstOrNull { it is Executor } as? Executor
                @Suppress("UNCHECKED_CAST")
                val consumer = inv.args.firstOrNull { it is Consumer<*> } as? Consumer<Location>
                    ?: return@MethodHookHandler

                if (cancelSignal?.isCanceled == true) {
                    inv.result = null
                    return@MethodHookHandler
                }

                val delivered = AtomicBoolean(false)
                val mockLoc = buildCleanedLocation(provider, cfg)
                val task = Runnable {
                    if (cancelSignal?.isCanceled != true && delivered.compareAndSet(false, true)) {
                        runCatching { consumer.accept(mockLoc) }
                    }
                }
                dispatchOnExecutorOrMain(executor, null, task)
                inv.result = null
            }
        )
    }

    private fun hookLastKnownLocation(lmClass: Class<*>, targetPackage: String) {
        backend.hookAllMethods(
            lmClass,
            "getLastKnownLocation",
            after = MethodHookHandler { inv ->
                val cfg = configProvider.currentConfig()
                if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                val provider = (inv.args.firstOrNull() as? String)?.takeIf { it.isNotBlank() }
                    ?: LocationManager.GPS_PROVIDER
                inv.result = buildCleanedLocation(provider, cfg)
            }
        )
    }

    private fun hookRemoveAndFlush(lmClass: Class<*>, targetPackage: String) {
        backend.hookAllMethods(
            lmClass,
            "removeUpdates",
            before = MethodHookHandler { inv ->
                val first = inv.args.firstOrNull() ?: return@MethodHookHandler
                if (first is LocationListener) {
                    val proxy = synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        removeActiveListenerLocked(first)
                        removeProxyEntryLocked(first)
                    }
                    if (proxy != null) {
                        inv.args[0] = proxy
                    }
                    updateSchedulerStateLocked()
                } else if (first is PendingIntent) {
                    synchronized(registryLock) {
                        activePendingIntents.remove(first)
                    }
                    updateSchedulerStateLocked()
                }
            }
        )

        backend.hookAllMethods(
            lmClass,
            "requestFlush",
            before = MethodHookHandler { inv ->
                val cfg = configProvider.currentConfig()
                val targetArg = inv.args.getOrNull(1)
                if (targetArg is LocationListener) {
                    val proxy = synchronized(registryLock) {
                        expungeStaleEntriesLocked()
                        listenerProxies[IdentityWeakKey(targetArg)]?.proxy
                    }
                    if (proxy != null) {
                        inv.args[1] = proxy
                    }
                    if (cfg.isEnabledForPackage(targetPackage)) {
                        val reqCode = (inv.args.getOrNull(2) as? Int) ?: 0
                        val sub = synchronized(registryLock) { findActiveListenerLocked(targetArg) }
                        dispatchOnExecutorOrMain(sub?.executor, sub?.looper) {
                            runCatching { targetArg.onFlushComplete(reqCode) }
                        }
                    }
                }
            }
        )
    }

    private fun hookProviderAndStatusQueries(lmClass: Class<*>, targetPackage: String) {
        backend.hookAllMethods(
            lmClass,
            "isLocationEnabled",
            after = MethodHookHandler { inv ->
                if (isActiveFor(targetPackage)) {
                    inv.result = true
                }
            }
        )

        val providerCheckAfter = MethodHookHandler { inv ->
            if (!isActiveFor(targetPackage)) return@MethodHookHandler
            val provider = inv.args.firstOrNull() as? String ?: return@MethodHookHandler
            if (isSpoofedProvider(provider)) {
                inv.result = true
            }
        }
        backend.hookAllMethods(lmClass, "isProviderEnabled", after = providerCheckAfter)
        backend.hookAllMethods(lmClass, "hasProvider", after = providerCheckAfter)

        val providerListAfter = MethodHookHandler { inv ->
            if (!isActiveFor(targetPackage)) return@MethodHookHandler
            @Suppress("UNCHECKED_CAST")
            val existing = (inv.result as? List<String>)?.toMutableList() ?: mutableListOf()
            for (provider in SPOOFED_PROVIDERS) {
                if (provider !in existing) {
                    existing.add(provider)
                }
            }
            inv.result = existing
        }
        backend.hookAllMethods(lmClass, "getAllProviders", after = providerListAfter)
        backend.hookAllMethods(lmClass, "getProviders", after = providerListAfter)

        backend.hookAllMethods(
            lmClass,
            "getBestProvider",
            after = MethodHookHandler { inv ->
                if (isActiveFor(targetPackage)) {
                    inv.result = LocationManager.GPS_PROVIDER
                }
            }
        )
    }

    private fun hookGnssAndNmea(lmClass: Class<*>, targetPackage: String) {
        backend.hookAllMethods(
            lmClass,
            "registerGnssStatusCallback",
            before = MethodHookHandler { inv ->
                val callback = inv.args.firstOrNull { arg ->
                    arg != null && arg.javaClass.name != "android.os.Handler" && arg !is Executor
                } ?: return@MethodHookHandler

                val implClass = callback.javaClass
                if (!implClass.isInterface &&
                    implClass.name != "android.location.GnssStatus\$Callback"
                ) {
                    HookMethodUtils.hookConcreteMethodHierarchy(
                        backend = backend,
                        hookedMethodKeys = hookedConcreteClasses,
                        implClass = implClass,
                        methodName = "onSatelliteStatusChanged",
                        before = MethodHookHandler { cbInv ->
                            val latest = configProvider.currentConfig()
                            if (latest.isEnabledForPackage(targetPackage) && latest.antiDetectGnssEnabled) {
                                cbInv.result = null
                            }
                        }
                    )
                }

                val cfg = configProvider.currentConfig()
                if (!cfg.isEnabledForPackage(targetPackage) || !cfg.antiDetectGnssEnabled) {
                    return@MethodHookHandler
                }

                val executor = inv.args.firstOrNull { it is Executor } as? Executor
                val handler = inv.args.firstOrNull { it is Handler } as? Handler
                val notifyReady = Runnable {
                    runCatching {
                        implClass.getMethod("onStarted").invoke(callback)
                    }
                    runCatching {
                        implClass.getMethod("onFirstFix", Int::class.javaPrimitiveType)
                            .invoke(callback, 45)
                    }
                }
                if (executor != null) {
                    runCatching { executor.execute(notifyReady) }
                } else if (handler != null) {
                    runCatching { handler.post(notifyReady) }
                } else {
                    dispatchOnExecutorOrMain(null, null, notifyReady)
                }
            }
        )

        backend.hookAllMethods(
            lmClass,
            "addNmeaListener",
            before = MethodHookHandler { inv ->
                val candidate = inv.args.firstOrNull { arg ->
                    arg != null && arg !is Executor && arg !is Handler
                } ?: return@MethodHookHandler

                val implClass = candidate.javaClass
                if (!implClass.isInterface) {
                    HookMethodUtils.hookConcreteMethodHierarchy(
                        backend = backend,
                        hookedMethodKeys = hookedConcreteClasses,
                        implClass = implClass,
                        methodName = "onNmeaMessage",
                        before = MethodHookHandler { nmeaInv ->
                            val latest = configProvider.currentConfig()
                            if (latest.isEnabledForPackage(targetPackage) && latest.antiDetectGnssEnabled) {
                                if (nmeaInv.args.isNotEmpty()) {
                                    nmeaInv.args[0] = buildSyntheticGpggaNmea(latest)
                                }
                            }
                        }
                    )
                    HookMethodUtils.hookConcreteMethodHierarchy(
                        backend = backend,
                        hookedMethodKeys = hookedConcreteClasses,
                        implClass = implClass,
                        methodName = "onNmeaReceived",
                        before = MethodHookHandler { nmeaInv ->
                            val latest = configProvider.currentConfig()
                            if (latest.isEnabledForPackage(targetPackage) && latest.antiDetectGnssEnabled) {
                                if (nmeaInv.args.size >= 2) {
                                    nmeaInv.args[1] = buildSyntheticGpggaNmea(latest)
                                }
                            }
                        }
                    )
                }
            }
        )
    }

    private fun expungeStaleEntriesLocked() {
        while (true) {
            val staleProxyKey = proxyRefQueue.poll() as? IdentityWeakKey ?: break
            listenerProxies.remove(staleProxyKey)
        }
        while (true) {
            val staleActiveKey = activeRefQueue.poll() as? IdentityWeakKey ?: break
            activeListeners.remove(staleActiveKey)
        }
    }

    private fun findActiveListenerLocked(target: LocationListener): ListenerSubscription? {
        activeListeners[IdentityWeakKey(target)]?.let { return it }
        return activeListeners.values.firstOrNull { it.proxy === target }
    }

    private fun removeActiveListenerLocked(target: LocationListener): ListenerSubscription? {
        activeListeners.remove(IdentityWeakKey(target))?.let { return it }
        val matched = activeListeners.entries.firstOrNull { it.value.proxy === target }
            ?: return null
        // 先读取 value，再从映射删除，避免任何 Entry 视图失效
        val sub = matched.value
        activeListeners.remove(matched.key)
        return sub
    }

    private fun removeProxyEntryLocked(target: LocationListener): LocationListener? {
        listenerProxies.remove(IdentityWeakKey(target))?.let { return it.proxy }
        val matched = listenerProxies.entries.firstOrNull { it.value.proxy === target }
            ?: return null
        val proxy = matched.value.proxy
        listenerProxies.remove(matched.key)
        return proxy
    }

    private fun getOrCreateListenerProxyEntry(
        original: LocationListener,
        targetPackage: String,
        singleShot: Boolean
    ): ProxyEntry {
        synchronized(registryLock) {
            expungeStaleEntriesLocked()
            val lookupKey = IdentityWeakKey(original)
            listenerProxies[lookupKey]?.let { existing ->
                existing.handler.configureMode(singleShot)
                return existing
            }
            val loader = original.javaClass.classLoader ?: LocationListener::class.java.classLoader
            val handler = ListenerProxyHandler(original, targetPackage, singleShot)
            val proxy = Proxy.newProxyInstance(
                loader,
                arrayOf(LocationListener::class.java),
                handler
            ) as LocationListener
            val entry = ProxyEntry(proxy, handler)
            listenerProxies[IdentityWeakKey(original, proxyRefQueue)] = entry
            return entry
        }
    }

    private inner class ListenerProxyHandler(
        original: LocationListener,
        private val targetPackage: String,
        initialSingleShot: Boolean
    ) : InvocationHandler {
        private val originalRef = WeakReference(original)
        private val singleShotMode = AtomicBoolean(initialSingleShot)
        private val singleShotConsumed = AtomicBoolean(false)

        fun configureMode(singleShot: Boolean) {
            singleShotMode.set(singleShot)
            singleShotConsumed.set(false)
        }

        fun tryConsumeSingleShot(): Boolean = singleShotConsumed.compareAndSet(false, true)

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val orig = originalRef.get()
            val methodName = method.name
            if (methodName == "equals") {
                val other = args?.firstOrNull()
                return other === proxy || (orig != null && other === orig)
            }
            if (methodName == "hashCode") {
                return if (orig != null) System.identityHashCode(orig) else System.identityHashCode(proxy)
            }
            if (methodName == "toString") {
                return orig?.toString() ?: "MockLocLocationListenerProxy(recycled)"
            }
            if (orig == null) {
                return null
            }

            if (methodName == "onLocationChanged" && args != null && args.isNotEmpty()) {
                val cfg = configProvider.currentConfig()
                val active = cfg.isEnabledForPackage(targetPackage)
                if (active) {
                    if (singleShotMode.get() && !singleShotConsumed.compareAndSet(false, true)) {
                        return null
                    }
                    if (!singleShotMode.get()) {
                        val hasActive = synchronized(registryLock) {
                            expungeStaleEntriesLocked()
                            activeListeners.isNotEmpty() || activePendingIntents.isNotEmpty()
                        }
                        if (hasActive) {
                            ensurePulseSchedulerRunning(targetPackage)
                        }
                    }
                    val first = args[0]
                    if (first is Location) {
                        val provider = first.provider?.takeIf { it.isNotBlank() } ?: LocationManager.GPS_PROVIDER
                        val cleanMock = buildCleanedLocation(provider, cfg)
                        return method.invoke(orig, cleanMock)
                    }
                    if (first is List<*>) {
                        val mockList = listOf(buildCleanedLocation(LocationManager.GPS_PROVIDER, cfg))
                        return method.invoke(orig, mockList)
                    }
                }
            }

            return if (args == null || args.isEmpty()) {
                method.invoke(orig)
            } else {
                method.invoke(orig, *args)
            }
        }
    }

    internal fun dispatchPulseTick(
        targetPackage: String,
        forceImmediate: Boolean = false,
        overrideConfig: MockConfig? = null
    ): Int {
        val cfg = overrideConfig ?: configProvider.currentConfig()
        if (!cfg.isEnabledForPackage(targetPackage)) {
            stopPulseTask()
            return 0
        }

        val nowMs = SystemClock.elapsedRealtime()
        val dueListeners = mutableListOf<ListenerSubscription>()
        val duePendingIntents = mutableListOf<PendingIntentSubscription>()

        synchronized(registryLock) {
            expungeStaleEntriesLocked()
            val listenerIter = activeListeners.entries.iterator()
            while (listenerIter.hasNext()) {
                val entry = listenerIter.next()
                val sub = entry.value
                if (sub.originalRef.get() == null) {
                    listenerIter.remove()
                    continue
                }
                if (forceImmediate || nowMs - sub.lastDeliveredAtMs >= sub.intervalMs) {
                    if (sub.singleShot) {
                        listenerIter.remove()
                        if (!sub.proxyHandler.tryConsumeSingleShot()) {
                            continue
                        }
                    }
                    sub.lastDeliveredAtMs = nowMs
                    dueListeners.add(sub)
                }
            }

            val piIter = activePendingIntents.entries.iterator()
            while (piIter.hasNext()) {
                val entry = piIter.next()
                val sub = entry.value
                if (forceImmediate || nowMs - sub.lastDeliveredAtMs >= sub.intervalMs) {
                    sub.lastDeliveredAtMs = nowMs
                    duePendingIntents.add(sub)
                    if (sub.singleShot) {
                        piIter.remove()
                    }
                }
            }
        }

        var dispatched = 0
        for (sub in dueListeners) {
            if (deliverToListenerAsync(sub, cfg)) {
                dispatched++
            }
        }
        for (sub in duePendingIntents) {
            if (deliverToPendingIntent(sub, cfg)) {
                dispatched++
            }
        }
        updateSchedulerStateLocked()
        return dispatched
    }

    private fun deliverToListenerAsync(sub: ListenerSubscription, config: MockConfig): Boolean {
        if (sub.originalRef.get() == null) return false
        val mockLocation = buildCleanedLocation(sub.provider, config)
        dispatchOnExecutorOrMain(sub.executor, sub.looper) {
            val target = sub.originalRef.get() ?: return@dispatchOnExecutorOrMain
            runCatching { target.onLocationChanged(mockLocation) }
        }
        return true
    }

    private fun deliverToPendingIntent(sub: PendingIntentSubscription, config: MockConfig): Boolean {
        val ctx = contextProvider() ?: return false
        val mockLocation = buildCleanedLocation(sub.provider, config)
        return try {
            val intent = Intent().apply {
                putExtra(LocationManager.KEY_LOCATION_CHANGED, mockLocation)
            }
            sub.pendingIntent.send(ctx, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            synchronized(registryLock) {
                activePendingIntents.remove(sub.pendingIntent)
            }
            updateSchedulerStateLocked()
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensurePulseSchedulerRunning(targetPackage: String) {
        synchronized(schedulerLock) {
            if (pulseTask != null && pulseTask?.isCancelled == false && pulseTask?.isDone == false) {
                return
            }
            val exec = scheduler ?: schedulerFactory().also { scheduler = it }
            pulseTask = exec.scheduleWithFixedDelay(
                { runCatching { dispatchPulseTick(targetPackage, forceImmediate = false) } },
                PULSE_TICK_MS,
                PULSE_TICK_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun stopPulseTask() {
        synchronized(schedulerLock) {
            pulseTask?.cancel(false)
            pulseTask = null
        }
    }

    private fun updateSchedulerStateLocked() {
        val isEmpty = synchronized(registryLock) {
            expungeStaleEntriesLocked()
            activeListeners.isEmpty() && activePendingIntents.isEmpty()
        }
        if (isEmpty) {
            stopPulseTask()
        }
    }

    internal fun isPulseSchedulerActive(): Boolean {
        synchronized(schedulerLock) {
            val task = pulseTask ?: return false
            return !task.isCancelled && !task.isDone
        }
    }

    internal fun trackedProxyCountForTest(): Int {
        synchronized(registryLock) {
            expungeStaleEntriesLocked()
            return listenerProxies.size
        }
    }

    private fun dispatchOnExecutorOrMain(executor: Executor?, looper: Looper?, task: Runnable) {
        if (executor != null) {
            runCatching { executor.execute(task) }.onSuccess { return }
        }
        val targetLooper = looper ?: runCatching { Looper.getMainLooper() }.getOrNull()
        if (targetLooper != null) {
            val posted = runCatching { Handler(targetLooper).post(task) }.getOrDefault(false)
            if (posted) return
        }
        task.run()
    }

    private fun buildCleanedLocation(provider: String, config: MockConfig): Location {
        val raw = locationFactory(provider, config)
        return MockLocationCleaner.cleanLocation(raw) ?: raw
    }

    private data class ParsedRequest(
        val provider: String,
        val intervalMs: Long,
        val listener: LocationListener?,
        val listenerIndex: Int,
        val pendingIntent: PendingIntent?,
        val executor: Executor?,
        val looper: Looper?
    )

    private fun parseRequestArgs(args: Array<Any?>): ParsedRequest {
        var provider: String? = null
        var intervalMs = DEFAULT_PULSE_INTERVAL_MS
        var listener: LocationListener? = null
        var listenerIndex = -1
        var pendingIntent: PendingIntent? = null
        var executor: Executor? = null
        var looper: Looper? = null

        for (i in args.indices) {
            val arg = args[i] ?: continue
            when {
                arg is String && provider == null -> provider = arg
                arg is Long && arg > 0L -> intervalMs = arg
                arg is LocationListener -> {
                    listener = arg
                    listenerIndex = i
                }
                arg is PendingIntent -> pendingIntent = arg
                arg is Executor -> executor = arg
                arg is Looper -> looper = arg
                arg.javaClass.name == "android.location.LocationRequest" -> {
                    val reqInterval = runCatching {
                        arg.javaClass.getMethod("getIntervalMillis").invoke(arg) as? Long
                    }.getOrNull()
                    if (reqInterval != null && reqInterval > 0L) {
                        intervalMs = reqInterval
                    }
                }
            }
        }

        val clampedInterval = intervalMs.coerceIn(MIN_PULSE_INTERVAL_MS, MAX_PULSE_INTERVAL_MS)
        return ParsedRequest(
            provider = provider?.takeIf { it.isNotBlank() } ?: LocationManager.GPS_PROVIDER,
            intervalMs = clampedInterval,
            listener = listener,
            listenerIndex = listenerIndex,
            pendingIntent = pendingIntent,
            executor = executor,
            looper = looper
        )
    }

    private fun isSpoofedProvider(provider: String): Boolean {
        return provider == LocationManager.GPS_PROVIDER ||
            provider == LocationManager.NETWORK_PROVIDER ||
            provider == "fused" ||
            provider == LocationManager.PASSIVE_PROVIDER
    }

    fun buildSyntheticGpggaNmea(config: MockConfig): String {
        val lat = config.latitude
        val lon = config.longitude
        val latHemisphere = if (lat >= 0.0) "N" else "S"
        val lonHemisphere = if (lon >= 0.0) "E" else "W"
        val latStr = formatNmeaCoordinate(abs(lat), degDigits = 2, maxDeg = 90.0)
        val lonStr = formatNmeaCoordinate(abs(lon), degDigits = 3, maxDeg = 180.0)
        val altStr = String.format(Locale.US, "%.1f", config.altitude)
        val body = "GPGGA,120000.00,$latStr,$latHemisphere,$lonStr,$lonHemisphere,1,12,0.8,$altStr,M,0.0,M,,"

        var checksum = 0
        for (ch in body) {
            checksum = checksum xor ch.code
        }
        return String.format(Locale.US, "$%s*%02X\r\n", body, checksum and 0xFF)
    }

    private fun formatNmeaCoordinate(absValue: Double, degDigits: Int, maxDeg: Double): String {
        val safeVal = if (absValue.isFinite()) absValue.coerceIn(0.0, maxDeg) else 0.0
        val totalTenThousandthsMin = (safeVal * 60.0 * 10_000.0).roundToLong()
        val deg = (totalTenThousandthsMin / 600_000L).toInt()
        val min = (totalTenThousandthsMin % 600_000L) / 10_000.0
        return String.format(Locale.US, "%0${degDigits}d%07.4f", deg, min)
    }

    fun clearForTest() {
        installedLoaders.clear()
        hookedConcreteClasses.clear()
        synchronized(registryLock) {
            listenerProxies.clear()
            activeListeners.clear()
            activePendingIntents.clear()
            while (proxyRefQueue.poll() != null) Unit
            while (activeRefQueue.poll() != null) Unit
        }
        synchronized(schedulerLock) {
            pulseTask?.cancel(false)
            pulseTask = null
            scheduler?.shutdownNow()
            scheduler = null
        }
    }

    companion object {
        const val PULSE_TICK_MS = 500L
        const val MIN_PULSE_INTERVAL_MS = 300L
        const val DEFAULT_PULSE_INTERVAL_MS = 1_000L
        const val MAX_PULSE_INTERVAL_MS = 5_000L

        private val SPOOFED_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused"
        )

        @Volatile
        var currentAppContext: Context? = null

        @Volatile
        var currentTargetPackage: String = ""

        val shared: LocationHook = LocationHook()

        fun defaultBuildMockLocation(provider: String, config: MockConfig): Location {
            return Location(provider).apply {
                latitude = config.latitude
                longitude = config.longitude
                altitude = config.altitude
                accuracy = config.accuracy.coerceAtLeast(1.0f)
                speed = config.speed.coerceAtLeast(0f)
                bearing = config.bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                verticalAccuracyMeters = config.accuracy.coerceAtLeast(1.0f)
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 1.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { setMock(false) }
                }
            }
        }
    }
}
