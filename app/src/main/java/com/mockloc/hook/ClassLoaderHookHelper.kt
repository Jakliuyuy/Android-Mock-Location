package com.mockloc.hook

import android.app.Application
import android.content.Context
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ClassLoaderHookHelper(
    private val backend: HookBackend = XposedHookBackend
) {
    private val installed = AtomicBoolean(false)
    private val contextDelivered = AtomicBoolean(false)
    private val seenLock = Any()
    private val seenClassLoaders = ArrayList<WeakReference<ClassLoader>>()
    private val contextListeners = CopyOnWriteArrayList<(Context) -> Unit>()
    private val classLoaderListeners = CopyOnWriteArrayList<(ClassLoader) -> Unit>()
    private val reentrancyGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    fun install(
        initialClassLoader: ClassLoader?,
        onContextReady: (Context) -> Unit,
        onClassLoaderReady: (ClassLoader) -> Unit
    ) {
        contextListeners.addIfAbsent(onContextReady)
        classLoaderListeners.addIfAbsent(onClassLoaderReady)

        if (initialClassLoader != null) {
            dispatchClassLoader(initialClassLoader) { cl ->
                for (listener in classLoaderListeners) {
                    runCatching { listener(cl) }
                }
            }
        }

        val backendKey = System.identityHashCode(backend)
        if (!installed.compareAndSet(false, true)) return
        if (!installedBackendKeys.add(backendKey)) return

        val compositeContextReady: (Context) -> Unit = { ctx ->
            for (listener in contextListeners) {
                runCatching { listener(ctx) }
            }
        }
        val compositeClassLoaderReady: (ClassLoader) -> Unit = { cl ->
            for (listener in classLoaderListeners) {
                runCatching { listener(cl) }
            }
        }

        hookContextWrapperAttachBaseContext(compositeContextReady, compositeClassLoaderReady)
        hookApplicationOnCreate(compositeContextReady, compositeClassLoaderReady)
        hookBaseDexClassLoaderConstructors(compositeClassLoaderReady)
        hookLoadedApkGetClassLoader(compositeClassLoaderReady)
    }

    private fun hookContextWrapperAttachBaseContext(
        onContextReady: (Context) -> Unit,
        onClassLoaderReady: (ClassLoader) -> Unit
    ) {
        val ctxWrapperCls = backend.findClassIfExists(
            "android.content.ContextWrapper",
            Context::class.java.classLoader
        ) ?: return

        backend.hookAllMethods(
            ctxWrapperCls,
            "attachBaseContext",
            after = MethodHookHandler { inv ->
                val target = inv.thisObject
                if (target !is Application) return@MethodHookHandler
                val ctx = (inv.args.firstOrNull() as? Context) ?: target
                dispatchContext(ctx, onContextReady)
                runCatching { target.classLoader }.getOrNull()?.let {
                    dispatchClassLoader(it, onClassLoaderReady)
                }
                runCatching { ctx.classLoader }.getOrNull()?.let {
                    dispatchClassLoader(it, onClassLoaderReady)
                }
            }
        )
    }

    private fun hookApplicationOnCreate(
        onContextReady: (Context) -> Unit,
        onClassLoaderReady: (ClassLoader) -> Unit
    ) {
        val appCls = backend.findClassIfExists(
            "android.app.Application",
            Application::class.java.classLoader
        ) ?: return

        backend.hookAllMethods(
            appCls,
            "onCreate",
            before = MethodHookHandler { inv ->
                val app = inv.thisObject as? Application ?: return@MethodHookHandler
                val ctx = runCatching { app.applicationContext }.getOrNull() ?: app
                dispatchContext(ctx, onContextReady)
                runCatching { app.classLoader }.getOrNull()?.let {
                    dispatchClassLoader(it, onClassLoaderReady)
                }
                runCatching { ctx.classLoader }.getOrNull()?.let {
                    dispatchClassLoader(it, onClassLoaderReady)
                }
            }
        )
    }

    private fun hookBaseDexClassLoaderConstructors(onClassLoaderReady: (ClassLoader) -> Unit) {
        val baseDexCls = backend.findClassIfExists(
            "dalvik.system.BaseDexClassLoader",
            ClassLoader::class.java.classLoader
        ) ?: return

        backend.hookAllConstructors(
            baseDexCls,
            after = MethodHookHandler { inv ->
                val cl = inv.thisObject as? ClassLoader ?: return@MethodHookHandler
                dispatchClassLoader(cl, onClassLoaderReady)
            }
        )
    }

    private fun hookLoadedApkGetClassLoader(onClassLoaderReady: (ClassLoader) -> Unit) {
        val loadedApkCls = backend.findClassIfExists(
            "android.app.LoadedApk",
            ClassLoader::class.java.classLoader
        ) ?: return

        backend.hookAllMethods(
            loadedApkCls,
            "getClassLoader",
            after = MethodHookHandler { inv ->
                val cl = inv.result as? ClassLoader ?: return@MethodHookHandler
                dispatchClassLoader(cl, onClassLoaderReady)
            }
        )
    }

    internal fun dispatchContext(context: Context, onContextReady: (Context) -> Unit) {
        if (contextDelivered.compareAndSet(false, true)) {
            runCatching { onContextReady(context) }
        }
    }

    internal fun dispatchClassLoader(
        classLoader: ClassLoader,
        onClassLoaderReady: (ClassLoader) -> Unit
    ) {
        if (reentrancyGuard.get() == true) return
        val shouldDispatch = synchronized(seenLock) {
            val iter = seenClassLoaders.iterator()
            var alreadySeen = false
            while (iter.hasNext()) {
                val ref = iter.next().get()
                if (ref == null) {
                    iter.remove()
                } else if (ref === classLoader) {
                    alreadySeen = true
                }
            }
            if (!alreadySeen) {
                seenClassLoaders.add(WeakReference(classLoader))
                true
            } else {
                false
            }
        }
        if (!shouldDispatch) return

        reentrancyGuard.set(true)
        try {
            runCatching { onClassLoaderReady(classLoader) }
        } finally {
            reentrancyGuard.set(false)
        }
    }

    fun resetForTest() {
        installed.set(false)
        contextDelivered.set(false)
        installedBackendKeys.remove(System.identityHashCode(backend))
        contextListeners.clear()
        classLoaderListeners.clear()
        synchronized(seenLock) {
            seenClassLoaders.clear()
        }
    }

    companion object {
        private val installedBackendKeys = ConcurrentHashMap.newKeySet<Int>()

        val shared: ClassLoaderHookHelper = ClassLoaderHookHelper(XposedHookBackend)
    }
}
