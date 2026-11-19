package com.mockloc.hook

import android.location.Location
import android.os.Bundle
import com.mockloc.data.config.MockConfig
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.testing.InMemorySharedPreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.lang.reflect.Member

class FakeHookInvocation(
    override val method: Member? = null,
    override val thisObject: Any? = null,
    override val args: Array<Any?> = emptyArray(),
    initialResult: Any? = null
) : HookInvocation {
    override var isEarlyReturnRequested: Boolean = false
        private set

    override var result: Any? = initialResult
        set(value) {
            field = value
            if (isBeforePhase) {
                isEarlyReturnRequested = true
            }
        }

    override var throwable: Throwable? = null
    internal var isBeforePhase: Boolean = true
}

class FakeHookBackend : HookBackend {
    data class RecordedHook(
        val clazz: Class<*>,
        val methodName: String,
        val before: MethodHookHandler?,
        val after: MethodHookHandler?
    )

    val classRegistry = LinkedHashMap<String, Class<*>>()
    val recordedHooks = mutableListOf<RecordedHook>()
    val recordedConstructorHooks = mutableListOf<Triple<Class<*>, MethodHookHandler?, MethodHookHandler?>>()
    val logs = mutableListOf<String>()

    override fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? {
        classRegistry[name]?.let { return it }
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    override fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Int {
        recordedHooks.add(RecordedHook(clazz, methodName, before, after))
        return 1
    }

    override fun hookAllConstructors(
        clazz: Class<*>,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Int {
        recordedConstructorHooks.add(Triple(clazz, before, after))
        return 1
    }

    override fun hookMethod(
        method: Member,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Boolean {
        recordedHooks.add(RecordedHook(method.declaringClass, method.name, before, after))
        return true
    }

    override fun log(message: String, throwable: Throwable?) {
        logs.add(message)
    }

    fun hookCountFor(clazz: Class<*>, methodName: String): Int {
        return recordedHooks.count { it.clazz == clazz && it.methodName == methodName }
    }

    fun invokeSimulated(
        target: Any?,
        clazz: Class<*>,
        methodName: String,
        vararg args: Any?,
        originalCall: ((Array<Any?>) -> Any?)? = null
    ): FakeHookInvocation {
        val matching = recordedHooks.filter {
            (it.clazz == clazz || it.clazz.isAssignableFrom(clazz)) && it.methodName == methodName
        }
        val argArray = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) argArray[i] = args[i]

        val inv = FakeHookInvocation(thisObject = target, args = argArray)
        inv.isBeforePhase = true
        for (h in matching) {
            h.before?.handle(inv)
        }
        if (!inv.isEarlyReturnRequested && originalCall != null) {
            inv.result = originalCall(inv.args)
        }
        inv.isBeforePhase = false
        for (h in matching) {
            h.after?.handle(inv)
        }
        return inv
    }

    fun invokeConstructors(target: Any?, clazz: Class<*>, vararg args: Any?): FakeHookInvocation {
        val matching = recordedConstructorHooks.filter { it.first == clazz }
        val argArray = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) argArray[i] = args[i]

        val inv = FakeHookInvocation(thisObject = target, args = argArray)
        inv.isBeforePhase = true
        for (h in matching) h.second?.handle(inv)
        inv.isBeforePhase = false
        for (h in matching) h.third?.handle(inv)
        return inv
    }
}

/**
 * 模拟真实 XposedBridge.hookAllMethods 仅检索 clazz.declaredMethods 的语义（不包含父类继承方法），
 * 用于对抗验证继承父类但未在子类重写回调方法的 Hook 链路。
 */
class DeclaredOnlyHookBackend : HookBackend {
    data class RecordedHook(
        val declaringClass: Class<*>,
        val methodName: String,
        val before: MethodHookHandler?,
        val after: MethodHookHandler?
    )

    val classRegistry = LinkedHashMap<String, Class<*>>()
    val recordedHooks = mutableListOf<RecordedHook>()

    override fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? {
        classRegistry[name]?.let { return it }
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    override fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Int {
        var count = 0
        for (m in clazz.declaredMethods) {
            if (m.name == methodName) {
                recordedHooks.add(RecordedHook(m.declaringClass, m.name, before, after))
                count++
            }
        }
        return count
    }

    override fun hookAllConstructors(
        clazz: Class<*>,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Int = 0

    override fun hookMethod(
        method: Member,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Boolean {
        recordedHooks.add(RecordedHook(method.declaringClass, method.name, before, after))
        return true
    }

    override fun log(message: String, throwable: Throwable?) = Unit

    fun hookCountFor(clazz: Class<*>, methodName: String): Int {
        return recordedHooks.count { it.declaringClass == clazz && it.methodName == methodName }
    }

    fun invokeSimulated(
        target: Any?,
        clazz: Class<*>,
        methodName: String,
        vararg args: Any?,
        originalCall: ((Array<Any?>) -> Any?)? = null
    ): FakeHookInvocation {
        val matching = recordedHooks.filter {
            (it.declaringClass == clazz || it.declaringClass.isAssignableFrom(clazz)) &&
                it.methodName == methodName
        }
        val argArray = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) argArray[i] = args[i]

        val inv = FakeHookInvocation(thisObject = target, args = argArray)
        inv.isBeforePhase = true
        for (h in matching) h.before?.handle(inv)
        if (!inv.isEarlyReturnRequested && originalCall != null) {
            inv.result = originalCall(inv.args)
        }
        inv.isBeforePhase = false
        for (h in matching) h.after?.handle(inv)
        return inv
    }
}

class FakePrefsReader(initialConfig: MockConfig = MockConfig.DEFAULT) : PrefsReader {
    val prefs = InMemorySharedPreferences()
    var fileChanged: Boolean = true
    var reloadCount: Int = 0
        private set

    init {
        writeConfig(initialConfig)
    }

    fun writeConfig(config: MockConfig, markChanged: Boolean = true) {
        val editor = prefs.edit()
        ConfigSyncSerializer.writeToPrefs(editor, config)
        editor.commit()
        fileChanged = markChanged
    }

    override fun hasFileChanged(): Boolean = fileChanged

    override fun reload() {
        reloadCount++
        fileChanged = false
    }

    override fun readConfig(fallback: MockConfig): MockConfig {
        return ConfigSyncSerializer.fromPrefs(prefs, fallback)
    }
}

class StaticConfigSnapshotProvider(
    @Volatile var config: MockConfig
) : ConfigSnapshotProvider {
    override fun currentConfig(): MockConfig = config
    override fun isActiveFor(packageName: String): Boolean = config.isEnabledForPackage(packageName)
}

fun createStatefulExtrasBundle(initialEntries: Map<String, Any?> = emptyMap()): Pair<Bundle, MutableMap<String, Any?>> {
    val map = LinkedHashMap<String, Any?>(initialEntries)
    val bundle = mock<Bundle>()

    doAnswer { LinkedHashSet(map.keys) }.`when`(bundle).keySet()
    doAnswer { inv -> map.remove(inv.getArgument<String>(0)); Unit }.`when`(bundle).remove(any())
    doAnswer { inv -> map.containsKey(inv.getArgument<String>(0)) }.`when`(bundle).containsKey(any())
    doAnswer { inv -> map[inv.getArgument<String>(0)] = inv.getArgument<Any?>(1); Unit }.`when`(bundle).putString(any(), anyOrNull())
    doAnswer { inv -> map[inv.getArgument<String>(0)] as? String }.`when`(bundle).getString(any())
    doAnswer { inv -> map[inv.getArgument<String>(0)] = inv.getArgument<Boolean>(1); Unit }.`when`(bundle).putBoolean(any(), any())
    doAnswer { inv -> (map[inv.getArgument<String>(0)] as? Boolean) ?: inv.getArgument<Boolean>(1) }.`when`(bundle).getBoolean(any(), any())

    return bundle to map
}

fun createStatefulMockLocation(
    initialProvider: String = "gps",
    initialLat: Double = 0.0,
    initialLon: Double = 0.0,
    initialAlt: Double = 0.0,
    initialAcc: Float = 5.0f,
    initialMock: Boolean = false,
    initialExtras: Bundle? = null
): Location {
    var provider: String? = initialProvider
    var lat = initialLat
    var lon = initialLon
    var alt = initialAlt
    var acc = initialAcc
    var speed = 0.0f
    var bearing = 0.0f
    var time = 0L
    var elapsedNanos = 0L
    var isMock = initialMock
    var extras: Bundle? = initialExtras

    val loc = mock<Location>()
    doAnswer { provider }.`when`(loc).provider
    doAnswer { inv -> provider = inv.getArgument<String?>(0); Unit }.`when`(loc).provider = anyOrNull()

    doAnswer { lat }.`when`(loc).latitude
    doAnswer { inv -> lat = inv.getArgument<Double>(0); Unit }.`when`(loc).latitude = any()

    doAnswer { lon }.`when`(loc).longitude
    doAnswer { inv -> lon = inv.getArgument<Double>(0); Unit }.`when`(loc).longitude = any()

    doAnswer { alt }.`when`(loc).altitude
    doAnswer { inv -> alt = inv.getArgument<Double>(0); Unit }.`when`(loc).altitude = any()

    doAnswer { acc }.`when`(loc).accuracy
    doAnswer { inv -> acc = inv.getArgument<Float>(0); Unit }.`when`(loc).accuracy = any()

    doAnswer { speed }.`when`(loc).speed
    doAnswer { inv -> speed = inv.getArgument<Float>(0); Unit }.`when`(loc).speed = any()

    doAnswer { bearing }.`when`(loc).bearing
    doAnswer { inv -> bearing = inv.getArgument<Float>(0); Unit }.`when`(loc).bearing = any()

    doAnswer { time }.`when`(loc).time
    doAnswer { inv -> time = inv.getArgument<Long>(0); Unit }.`when`(loc).time = any()

    doAnswer { elapsedNanos }.`when`(loc).elapsedRealtimeNanos
    doAnswer { inv -> elapsedNanos = inv.getArgument<Long>(0); Unit }.`when`(loc).elapsedRealtimeNanos = any()

    doAnswer { isMock }.`when`(loc).isMock
    doAnswer { isMock }.`when`(loc).isFromMockProvider
    doAnswer { inv -> isMock = inv.getArgument<Boolean>(0); Unit }.`when`(loc).setMock(any())

    doAnswer { extras }.`when`(loc).extras
    doAnswer { inv -> extras = inv.getArgument<Bundle?>(0); Unit }.`when`(loc).extras = anyOrNull()

    return loc
}
