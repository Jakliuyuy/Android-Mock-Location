package com.mockloc.hook

import com.mockloc.data.config.MockConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

interface HookInvocation {
    val method: Member?
    val thisObject: Any?
    val args: Array<Any?>
    var result: Any?
    var throwable: Throwable?
    val isEarlyReturnRequested: Boolean
}

fun interface MethodHookHandler {
    fun handle(invocation: HookInvocation)
}

interface ConfigSnapshotProvider {
    fun currentConfig(): MockConfig
    fun isActiveFor(packageName: String): Boolean
}

interface HookBackend {
    fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>?
    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        before: MethodHookHandler? = null,
        after: MethodHookHandler? = null
    ): Int

    fun hookAllConstructors(
        clazz: Class<*>,
        before: MethodHookHandler? = null,
        after: MethodHookHandler? = null
    ): Int

    fun hookMethod(
        method: Member,
        before: MethodHookHandler? = null,
        after: MethodHookHandler? = null
    ): Boolean

    fun replaceMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        replacement: (HookInvocation) -> Any?
    ): Boolean {
        val method: Method = try {
            clazz.getDeclaredMethod(methodName, *parameterTypes)
        } catch (_: Throwable) {
            return false
        }
        return hookMethod(
            method = method,
            before = MethodHookHandler { inv ->
                inv.result = replacement(inv)
            }
        )
    }

    fun log(message: String, throwable: Throwable? = null)
}

object XposedHookBackend : HookBackend {

    private class XposedInvocationAdapter(
        private val param: XC_MethodHook.MethodHookParam,
        private val isBeforePhase: Boolean
    ) : HookInvocation {
        override val method: Member?
            get() = param.method

        override val thisObject: Any?
            get() = param.thisObject

        override val args: Array<Any?>
            get() = param.args ?: emptyArray()

        override var isEarlyReturnRequested: Boolean = false
            private set

        override var result: Any?
            get() = param.result
            set(value) {
                param.result = value
                if (isBeforePhase) {
                    isEarlyReturnRequested = true
                }
            }

        override var throwable: Throwable?
            get() = param.throwable
            set(value) {
                param.throwable = value
            }
    }

    override fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? {
        return try {
            XposedHelpers.findClassIfExists(name, classLoader)
        } catch (_: Throwable) {
            try {
                Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Int {
        if (before == null && after == null) return 0
        return try {
            val callback = buildCallback(before, after)
            XposedBridge.hookAllMethods(clazz, methodName, callback).size
        } catch (t: Throwable) {
            log("hookAllMethods failed: ${clazz.name}#$methodName", t)
            0
        }
    }

    override fun hookAllConstructors(
        clazz: Class<*>,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Int {
        if (before == null && after == null) return 0
        return try {
            val callback = buildCallback(before, after)
            XposedBridge.hookAllConstructors(clazz, callback).size
        } catch (t: Throwable) {
            log("hookAllConstructors failed: ${clazz.name}", t)
            0
        }
    }

    override fun hookMethod(
        method: Member,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Boolean {
        if (before == null && after == null) return false
        return try {
            val callback = buildCallback(before, after)
            XposedBridge.hookMethod(method, callback)
            true
        } catch (t: Throwable) {
            log("hookMethod failed: ${method.declaringClass.name}#${method.name}", t)
            false
        }
    }

    override fun replaceMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        replacement: (HookInvocation) -> Any?
    ): Boolean {
        return try {
            val argsAndCallback = arrayOfNulls<Any>(parameterTypes.size + 1)
            for (i in parameterTypes.indices) {
                argsAndCallback[i] = parameterTypes[i]
            }
            argsAndCallback[parameterTypes.size] = object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    return replacement(XposedInvocationAdapter(param, isBeforePhase = true))
                }
            }
            XposedHelpers.findAndHookMethod(clazz, methodName, *argsAndCallback)
            true
        } catch (t: Throwable) {
            log("replaceMethod failed: ${clazz.name}#$methodName", t)
            false
        }
    }

    override fun log(message: String, throwable: Throwable?) {
        runCatching {
            XposedBridge.log("[MockLoc] $message")
            if (throwable != null) {
                XposedBridge.log(throwable)
            }
        }
    }

    private fun buildCallback(
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (before == null) return
                try {
                    before.handle(XposedInvocationAdapter(param, isBeforePhase = true))
                } catch (t: Throwable) {
                    log("beforeHookedMethod error in ${param.method?.name}", t)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (after == null) return
                try {
                    after.handle(XposedInvocationAdapter(param, isBeforePhase = false))
                } catch (t: Throwable) {
                    log("afterHookedMethod error in ${param.method?.name}", t)
                }
            }
        }
    }
}

internal object HookMethodUtils {
    private val EXCLUDED_BASE_CLASS_NAMES = setOf(
        "java.lang.Object",
        "android.location.LocationListener",
        "android.location.GnssStatus\$Callback",
        "android.location.OnNmeaMessageListener",
        "android.location.GpsStatus\$NmeaListener",
        "android.telephony.TelephonyCallback",
        "android.telephony.TelephonyCallback\$CellInfoListener",
        "android.telephony.PhoneStateListener",
        "android.telephony.TelephonyManager\$CellInfoCallback",
        "android.net.wifi.WifiManager\$ScanResultsCallback",
        "com.tencent.map.geolocation.TencentLocationListener",
        "com.amap.api.location.AMapLocationListener",
        "com.baidu.location.BDLocationListener",
        "com.baidu.location.BDAbstractLocationListener",
        "com.google.android.gms.location.LocationListener",
        "com.google.android.gms.location.LocationCallback",
        "java.util.function.Consumer"
    )

    fun findDeclaredMethodsInHierarchy(
        implClass: Class<*>,
        methodName: String,
        extraExcludedClassNames: Set<String> = emptySet()
    ): List<Method> {
        if (implClass.isInterface) return emptyList()
        val result = ArrayList<Method>()
        val seenSignatures = HashSet<String>()
        var cursor: Class<*>? = implClass
        while (cursor != null && cursor !== Any::class.java) {
            val clsName = cursor.name
            if (clsName in EXCLUDED_BASE_CLASS_NAMES || clsName in extraExcludedClassNames) {
                break
            }
            if (!cursor.isInterface) {
                val declared = runCatching { cursor.declaredMethods }.getOrNull() ?: emptyArray()
                for (m in declared) {
                    if (m.name == methodName && !Modifier.isAbstract(m.modifiers)) {
                        val sig = "${m.name}(${m.parameterTypes.joinToString(",") { it.name }})"
                        if (seenSignatures.add(sig)) {
                            result.add(m)
                        }
                    }
                }
            }
            cursor = cursor.superclass
        }
        return result
    }

    fun hookConcreteMethodHierarchy(
        backend: HookBackend,
        hookedMethodKeys: MutableSet<String>,
        implClass: Class<*>,
        methodName: String,
        before: MethodHookHandler? = null,
        after: MethodHookHandler? = null,
        extraExcludedClassNames: Set<String> = emptySet()
    ): Int {
        val methods = findDeclaredMethodsInHierarchy(implClass, methodName, extraExcludedClassNames)
        var hookedCount = 0
        for (method in methods) {
            val declClass = method.declaringClass
            val sig = method.parameterTypes.joinToString(",") { it.name }
            val key = "${System.identityHashCode(declClass)}#${declClass.name}#${method.name}($sig)"
            if (hookedMethodKeys.add(key)) {
                if (backend.hookMethod(method, before = before, after = after)) {
                    hookedCount++
                }
            }
        }
        return hookedCount
    }
}
