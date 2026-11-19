package com.mockloc.e2e.factory

import android.content.pm.ApplicationInfo
import android.location.Location
import android.os.Bundle
import com.mockloc.hook.HookBackend
import com.mockloc.hook.HookInvocation
import com.mockloc.hook.MethodHookHandler
import com.mockloc.root.RootProviderManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.Serializable
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

// ==================== 1. E2E 可编程 HookBackend（Clean-Slate 自包含） ====================

data class HookInvocationResult(
    val result: Any?,
    val throwable: Throwable?,
    val earlyReturned: Boolean,
    val args: Array<Any?>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HookInvocationResult) return false
        return result == other.result &&
            throwable == other.throwable &&
            earlyReturned == other.earlyReturned &&
            args.contentEquals(other.args)
    }

    override fun hashCode(): Int {
        var code = result?.hashCode() ?: 0
        code = 31 * code + (throwable?.hashCode() ?: 0)
        code = 31 * code + earlyReturned.hashCode()
        code = 31 * code + args.contentHashCode()
        return code
    }
}

data class InstalledHookRecord(
    val targetClass: Class<*>,
    val member: Member?,
    val methodName: String,
    val installKind: String
)

/**
 * 纯 E2E 自研 [HookBackend] 替身。
 *
 * - 支持 [declaredOnly] 模式：开启后 `hookAllMethods` / `hookAllConstructors` / `replaceMethod`
 *   严格只匹配目标类自身声明的方法（与真实 ART/Xposed 语义一致），用于精准验证
 *   `HookMethodUtils` 与具体子类动态 Hook 是否穿透继承链。
 * - 完整记录所有 Hook 安装行为与目标类，用于断言绝不对接口调用 `hookAllMethods`。
 */
class E2eHookBackend(
    var declaredOnly: Boolean = false
) : HookBackend {

    private data class HookEntry(
        val before: MethodHookHandler?,
        val after: MethodHookHandler?
    )

    private data class NameMethodKey(
        val declaringClass: Class<*>,
        val methodName: String
    )

    private class E2eHookInvocation(
        override val method: Member?,
        override val thisObject: Any?,
        override val args: Array<Any?>
    ) : HookInvocation {
        var isBeforePhase: Boolean = true
        override var isEarlyReturnRequested: Boolean = false
            private set

        private var _result: Any? = null
        override var result: Any?
            get() = _result
            set(value) {
                _result = value
                if (isBeforePhase) {
                    isEarlyReturnRequested = true
                }
            }

        private var _throwable: Throwable? = null
        override var throwable: Throwable?
            get() = _throwable
            set(value) {
                _throwable = value
                if (isBeforePhase && value != null) {
                    isEarlyReturnRequested = true
                }
            }
    }

    val classRegistry = ConcurrentHashMap<String, Class<*>>()
    val blockedClassNames = ConcurrentHashMap.newKeySet<String>()
    val hookRecords = CopyOnWriteArrayList<InstalledHookRecord>()
    val logs = CopyOnWriteArrayList<Pair<String, Throwable?>>()

    private val memberHooks = ConcurrentHashMap<Member, CopyOnWriteArrayList<HookEntry>>()
    private val virtualMethodHooks = ConcurrentHashMap<NameMethodKey, CopyOnWriteArrayList<HookEntry>>()
    private val constructorHooks = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<HookEntry>>()

    fun registerClass(name: String, clazz: Class<*>): E2eHookBackend = apply {
        classRegistry[name] = clazz
    }

    fun registerClass(clazz: Class<*>): E2eHookBackend = apply {
        classRegistry[clazz.name] = clazz
    }

    fun clearHooks() {
        hookRecords.clear()
        memberHooks.clear()
        virtualMethodHooks.clear()
        constructorHooks.clear()
        logs.clear()
    }

    fun hookInstallCount(clazz: Class<*>, methodName: String? = null): Int {
        return hookRecords.count { rec ->
            rec.targetClass == clazz && (methodName == null || rec.methodName == methodName)
        }
    }

    fun hookAllMethodsInstallCount(clazz: Class<*>, methodName: String? = null): Int {
        return hookRecords.count { rec ->
            rec.installKind == "hookAllMethods" &&
                rec.targetClass == clazz &&
                (methodName == null || rec.methodName == methodName)
        }
    }

    fun interfaceHookAllMethodsRecords(): List<InstalledHookRecord> {
        return hookRecords.filter { it.installKind == "hookAllMethods" && it.targetClass.isInterface }
    }

    fun hasInterfaceHookAllMethods(): Boolean = interfaceHookAllMethodsRecords().isNotEmpty()

    override fun findClassIfExists(name: String, classLoader: ClassLoader?): Class<*>? {
        if (name in blockedClassNames) return null
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
        if (before == null && after == null) return 0
        hookRecords.add(
            InstalledHookRecord(
                targetClass = clazz,
                member = null,
                methodName = methodName,
                installKind = "hookAllMethods"
            )
        )
        val entry = HookEntry(before, after)
        val matchedMethods = findMethodsForHookAll(clazz, methodName)
        if (matchedMethods.isNotEmpty()) {
            for (m in matchedMethods) {
                memberHooks.computeIfAbsent(m) { CopyOnWriteArrayList() }.add(entry)
            }
            return matchedMethods.size
        }
        if (!declaredOnly) {
            virtualMethodHooks.computeIfAbsent(NameMethodKey(clazz, methodName)) { CopyOnWriteArrayList() }
                .add(entry)
            return 1
        }
        return 0
    }

    override fun hookAllConstructors(
        clazz: Class<*>,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Int {
        if (before == null && after == null) return 0
        hookRecords.add(
            InstalledHookRecord(
                targetClass = clazz,
                member = null,
                methodName = "<init>",
                installKind = "hookAllConstructors"
            )
        )
        val entry = HookEntry(before, after)
        val ctors = runCatching { clazz.declaredConstructors.toList() }.getOrDefault(emptyList())
        for (c in ctors) {
            memberHooks.computeIfAbsent(c) { CopyOnWriteArrayList() }.add(entry)
        }
        if (ctors.isNotEmpty()) {
            return ctors.size
        }
        if (!declaredOnly) {
            constructorHooks.computeIfAbsent(clazz) { CopyOnWriteArrayList() }.add(entry)
            return 1
        }
        return 0
    }

    override fun hookMethod(
        method: Member,
        before: MethodHookHandler?,
        after: MethodHookHandler?
    ): Boolean {
        if (before == null && after == null) return false
        hookRecords.add(
            InstalledHookRecord(
                targetClass = method.declaringClass,
                member = method,
                methodName = method.name,
                installKind = "hookMethod"
            )
        )
        memberHooks.computeIfAbsent(method) { CopyOnWriteArrayList() }.add(HookEntry(before, after))
        return true
    }

    override fun replaceMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        replacement: (HookInvocation) -> Any?
    ): Boolean {
        val method = findExactMethod(clazz, methodName, parameterTypes) ?: return false
        return hookMethod(
            method = method,
            before = MethodHookHandler { inv ->
                inv.result = replacement(inv)
            }
        )
    }

    override fun log(message: String, throwable: Throwable?) {
        logs.add(message to throwable)
    }

    fun invokeMethod(
        clazz: Class<*>,
        methodName: String,
        thisObject: Any? = null,
        args: Array<Any?> = emptyArray(),
        originalImpl: ((Array<Any?>) -> Any?)? = null
    ): HookInvocationResult {
        val dispatchClass = thisObject?.javaClass ?: clazz
        val matchedMember = resolveBestMethodInHierarchy(dispatchClass, clazz, methodName, args)
        val entries = collectMethodHookEntries(dispatchClass, clazz, methodName, matchedMember)
        return executeEntries(
            member = matchedMember,
            thisObject = thisObject,
            args = args,
            entries = entries,
            originalImpl = originalImpl
        )
    }

    fun invokeMethod(
        member: Member,
        thisObject: Any? = null,
        args: Array<Any?> = emptyArray(),
        originalImpl: ((Array<Any?>) -> Any?)? = null
    ): HookInvocationResult {
        val dispatchClass = thisObject?.javaClass ?: member.declaringClass
        val entries = if (member is Constructor<*>) {
            val direct = memberHooks[member].orEmpty()
            val byCls = constructorHooks[member.declaringClass].orEmpty()
            direct + byCls
        } else {
            collectMethodHookEntries(dispatchClass, member.declaringClass, member.name, member)
        }
        return executeEntries(
            member = member,
            thisObject = thisObject,
            args = args,
            entries = entries,
            originalImpl = originalImpl
        )
    }

    fun invokeStatic(
        clazz: Class<*>,
        methodName: String,
        args: Array<Any?> = emptyArray(),
        originalImpl: ((Array<Any?>) -> Any?)? = null
    ): HookInvocationResult = invokeMethod(
        clazz = clazz,
        methodName = methodName,
        thisObject = null,
        args = args,
        originalImpl = originalImpl
    )

    fun invokeConstructor(
        clazz: Class<*>,
        thisObject: Any?,
        args: Array<Any?> = emptyArray(),
        originalImpl: ((Array<Any?>) -> Any?)? = null
    ): HookInvocationResult {
        val ctors = runCatching { clazz.declaredConstructors.toList() }.getOrDefault(emptyList())
        val matchedCtor = ctors.firstOrNull { it.parameterTypes.size == args.size } ?: ctors.firstOrNull()
        val entries = ArrayList<HookEntry>()
        if (matchedCtor != null) {
            memberHooks[matchedCtor]?.let { entries.addAll(it) }
        }
        constructorHooks[clazz]?.let { entries.addAll(it) }
        return executeEntries(
            member = matchedCtor,
            thisObject = thisObject,
            args = args,
            entries = entries,
            originalImpl = originalImpl
        )
    }

    private fun executeEntries(
        member: Member?,
        thisObject: Any?,
        args: Array<Any?>,
        entries: List<HookEntry>,
        originalImpl: ((Array<Any?>) -> Any?)?
    ): HookInvocationResult {
        val inv = E2eHookInvocation(
            method = member,
            thisObject = thisObject,
            args = args.copyOf()
        )

        inv.isBeforePhase = true
        for (entry in entries) {
            val before = entry.before ?: continue
            try {
                before.handle(inv)
            } catch (t: Throwable) {
                log("E2eHookBackend before error in ${member?.name}", t)
            }
        }

        val earlyReturned = inv.isEarlyReturnRequested
        if (!earlyReturned && originalImpl != null) {
            try {
                inv.isBeforePhase = false
                inv.result = originalImpl(inv.args)
            } catch (t: Throwable) {
                inv.throwable = t
            }
        }

        inv.isBeforePhase = false
        for (entry in entries.asReversed()) {
            val after = entry.after ?: continue
            try {
                after.handle(inv)
            } catch (t: Throwable) {
                log("E2eHookBackend after error in ${member?.name}", t)
            }
        }

        return HookInvocationResult(
            result = inv.result,
            throwable = inv.throwable,
            earlyReturned = earlyReturned,
            args = inv.args
        )
    }

    private fun findMethodsForHookAll(clazz: Class<*>, methodName: String): List<Method> {
        val result = ArrayList<Method>()
        val seen = HashSet<Method>()
        val declared = runCatching { clazz.declaredMethods }.getOrNull() ?: emptyArray()
        for (m in declared) {
            if (m.name == methodName && seen.add(m)) {
                result.add(m)
            }
        }
        if (!declaredOnly && !clazz.isInterface) {
            var cursor: Class<*>? = clazz.superclass
            while (cursor != null && cursor !== Any::class.java) {
                val superDeclared = runCatching { cursor.declaredMethods }.getOrNull() ?: emptyArray()
                for (m in superDeclared) {
                    if (m.name == methodName && seen.add(m)) {
                        result.add(m)
                    }
                }
                cursor = cursor.superclass
            }
        }
        return result
    }

    private fun findExactMethod(
        clazz: Class<*>,
        methodName: String,
        parameterTypes: Array<out Class<*>>
    ): Method? {
        runCatching { return clazz.getDeclaredMethod(methodName, *parameterTypes) }
        if (!declaredOnly) {
            var cursor: Class<*>? = clazz.superclass
            while (cursor != null && cursor !== Any::class.java) {
                runCatching { return cursor.getDeclaredMethod(methodName, *parameterTypes) }
                cursor = cursor.superclass
            }
        }
        return null
    }

    private fun resolveBestMethodInHierarchy(
        dispatchClass: Class<*>,
        fallbackClass: Class<*>,
        methodName: String,
        args: Array<Any?>
    ): Method? {
        val candidates = ArrayList<Method>()
        val seenClasses = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        queue.addLast(dispatchClass)
        if (fallbackClass !== dispatchClass) {
            queue.addLast(fallbackClass)
        }
        while (queue.isNotEmpty()) {
            val cls = queue.removeFirst()
            if (!seenClasses.add(cls) || cls === Any::class.java) continue
            val declared = runCatching { cls.declaredMethods }.getOrNull() ?: emptyArray()
            for (m in declared) {
                if (m.name == methodName) {
                    candidates.add(m)
                }
            }
            cls.superclass?.let { queue.addLast(it) }
            cls.interfaces.forEach { queue.addLast(it) }
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { methodMatchesArgs(it, args) }
            ?: candidates.firstOrNull { it.parameterTypes.size == args.size }
            ?: candidates.first()
    }

    private fun collectMethodHookEntries(
        dispatchClass: Class<*>,
        declaredTargetClass: Class<*>,
        methodName: String,
        exactMember: Member?
    ): List<HookEntry> {
        val entries = ArrayList<HookEntry>()
        val addedEntries = LinkedHashSet<HookEntry>()

        fun addAllUnique(list: List<HookEntry>?) {
            if (list == null) return
            for (e in list) {
                if (addedEntries.add(e)) {
                    entries.add(e)
                }
            }
        }

        if (exactMember != null) {
            addAllUnique(memberHooks[exactMember])
        }

        // 同时收集继承链上与方法签名匹配的已 Hook 方法（例如子类未覆写父类方法，但 Hook 挂在父类或接口方法上）
        val classesToCheck = LinkedHashSet<Class<*>>()
        var cursor: Class<*>? = dispatchClass
        while (cursor != null && cursor !== Any::class.java) {
            classesToCheck.add(cursor)
            cursor.interfaces.forEach { classesToCheck.add(it) }
            cursor = cursor.superclass
        }
        classesToCheck.add(declaredTargetClass)

        val exactMethod = exactMember as? Method
        for ((member, memberEntries) in memberHooks) {
            if (member === exactMember) continue
            if (member is Method &&
                member.name == methodName &&
                member.declaringClass in classesToCheck &&
                (exactMethod == null || member.parameterTypes.contentEquals(exactMethod.parameterTypes))
            ) {
                addAllUnique(memberEntries)
            }
        }

        if (!declaredOnly) {
            for (cls in classesToCheck) {
                addAllUnique(virtualMethodHooks[NameMethodKey(cls, methodName)])
            }
        }

        return entries
    }

    private fun methodMatchesArgs(method: Method, args: Array<Any?>): Boolean {
        val paramTypes = method.parameterTypes
        if (paramTypes.size != args.size) return false
        for (i in paramTypes.indices) {
            val arg = args[i]
            val pType = paramTypes[i]
            if (arg == null) {
                if (pType.isPrimitive) return false
            } else {
                val boxed = boxPrimitive(pType)
                if (!boxed.isInstance(arg)) return false
            }
        }
        return true
    }

    private fun boxPrimitive(cls: Class<*>): Class<*> = when (cls) {
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
        Short::class.javaPrimitiveType -> java.lang.Short::class.java
        Int::class.javaPrimitiveType -> java.lang.Integer::class.java
        Long::class.javaPrimitiveType -> java.lang.Long::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        Double::class.javaPrimitiveType -> java.lang.Double::class.java
        Char::class.javaPrimitiveType -> java.lang.Character::class.java
        else -> cls
    }
}

// ==================== 2. Xposed 原生参数反射构造工厂 ====================

object XposedParamFactory {

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateWithoutConstructor(clazz: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateMethod.invoke(unsafe, clazz) as T
    }

    fun createLoadPackageParam(
        packageName: String?,
        classLoader: ClassLoader?,
        processName: String? = packageName,
        appInfo: ApplicationInfo? = null,
        isFirstApplication: Boolean = true
    ): XC_LoadPackage.LoadPackageParam {
        return allocateWithoutConstructor(XC_LoadPackage.LoadPackageParam::class.java).apply {
            this.packageName = packageName
            this.classLoader = classLoader
            this.processName = processName
            this.appInfo = appInfo
            this.isFirstApplication = isFirstApplication
        }
    }

    fun createMethodHookParam(
        method: Member? = null,
        thisObject: Any? = null,
        args: Array<Any?> = emptyArray(),
        result: Any? = null,
        throwable: Throwable? = null
    ): XC_MethodHook.MethodHookParam {
        return allocateWithoutConstructor(XC_MethodHook.MethodHookParam::class.java).apply {
            this.method = method
            this.thisObject = thisObject
            this.args = args
            if (result != null) {
                runCatching {
                    val f = XC_MethodHook.MethodHookParam::class.java.getDeclaredField("result")
                    f.isAccessible = true
                    f.set(this, result)
                }
            }
            if (throwable != null) {
                runCatching {
                    val f = XC_MethodHook.MethodHookParam::class.java.getDeclaredField("throwable")
                    f.isAccessible = true
                    f.set(this, throwable)
                }
            }
        }
    }
}

// ==================== 3. 隐藏字段清洗载体 Stub ====================

open class BaseHiddenFieldsLocationStub(
    @JvmField var mFieldsMask: Int = 0b11111, // bit 4 (16) + bits 0..3 (15)
    @JvmField var mIsFromMockProvider: Boolean = true
)

class ChildHiddenFieldsLocationStub : BaseHiddenFieldsLocationStub()

class HiddenFieldsLocationStub(
    @JvmField var mFieldsMask: Int = 0b11111,
    @JvmField var mIsFromMockProvider: Boolean = true
)

class ByteFieldsMaskLocationStub(
    @JvmField var mFieldsMask: Byte = 0b11111.toByte(),
    @JvmField var mIsFromMockProvider: Boolean = true
)

// ==================== 4. 独立 E2E LBS SDK 替身集 ====================

object E2eLbsSdkDoubles {

    // ---------- 4.1 腾讯 / 微信定位 SDK ----------

    interface TencentLocation {
        fun getLatitude(): Double
        fun getLongitude(): Double
        fun getAltitude(): Double
        fun getAccuracy(): Float
        fun getSpeed(): Float
        fun getBearing(): Float
        fun getTime(): Long
        fun getElapsedRealtime(): Long
        fun getProvider(): String
        fun getSourceProvider(): String
        fun getCoordinateType(): Int
        fun getFakeReason(): Int
        fun getFake(): Int
        fun isMockGps(): Boolean
        fun getAddress(): String
        fun getNation(): String
        fun getProvince(): String
        fun getCity(): String
        fun getDistrict(): String
        fun getTown(): String
        fun getVillage(): String
        fun getStreet(): String
        fun getStreetNo(): String
        fun getName(): String
        fun getCityCode(): String
        fun getCityPhoneCode(): String
        fun getPoiList(): List<Any>
        fun getExtra(): Bundle?
    }

    open class DefaultTencentLocation(
        private var lat: Double = 22.5431,
        private var lon: Double = 114.0579,
        private var alt: Double = 10.0,
        private var acc: Float = 25.0f,
        private var spd: Float = 0.0f,
        private var brg: Float = 0.0f,
        private var timeMs: Long = 1_700_000_000_000L,
        private var elapsedMs: Long = 123_456L,
        private var prov: String = "network",
        private var coordType: Int = 1,
        private var fakeReason: Int = 3,
        private var fakeFlag: Int = 1,
        private var mockGps: Boolean = true,
        private var addr: String = "广东省深圳市真实物理地址",
        private var cityStr: String = "深圳市",
        private var nameStr: String = "真实物理大厦",
        private var poi: List<Any> = listOf("真实POI")
    ) : TencentLocation {
        override fun getLatitude(): Double = lat
        override fun getLongitude(): Double = lon
        override fun getAltitude(): Double = alt
        override fun getAccuracy(): Float = acc
        override fun getSpeed(): Float = spd
        override fun getBearing(): Float = brg
        override fun getTime(): Long = timeMs
        override fun getElapsedRealtime(): Long = elapsedMs
        override fun getProvider(): String = prov
        override fun getSourceProvider(): String = prov
        override fun getCoordinateType(): Int = coordType
        override fun getFakeReason(): Int = fakeReason
        override fun getFake(): Int = fakeFlag
        override fun isMockGps(): Boolean = mockGps
        override fun getAddress(): String = addr
        override fun getNation(): String = "中国"
        override fun getProvince(): String = "广东省"
        override fun getCity(): String = cityStr
        override fun getDistrict(): String = "南山区"
        override fun getTown(): String = "粤海街道"
        override fun getVillage(): String = "虚拟村"
        override fun getStreet(): String = "科技南路"
        override fun getStreetNo(): String = "18号"
        override fun getName(): String = nameStr
        override fun getCityCode(): String = "0755"
        override fun getCityPhoneCode(): String = "0755"
        override fun getPoiList(): List<Any> = poi
        override fun getExtra(): Bundle? = null
    }

    /**
     * 非接口版腾讯定位载体（仅实现标记接口 [Serializable]），用于验证
     * `resolveTencentLocationInterface` 排除标记接口并回退至反射字段/setter 清洗。
     */
    open class NonInterfaceTencentLocation(
        @JvmField var latitude: Double = 22.5431,
        @JvmField var longitude: Double = 114.0579,
        @JvmField var altitude: Double = 10.0,
        @JvmField var accuracy: Float = 25.0f,
        @JvmField var speed: Float = 0.0f,
        @JvmField var bearing: Float = 0.0f,
        @JvmField var provider: String = "network",
        @JvmField var coordinateType: Int = 0,
        @JvmField var fakeReason: Int = 2,
        @JvmField var isMockGps: Boolean = true,
        @JvmField var address: String = "真实物理街道",
        @JvmField var city: String = "深圳市",
        @JvmField var mFieldsMask: Int = 16,
        @JvmField var mIsFromMockProvider: Boolean = true
    ) : Serializable {
        fun setLatitude(v: Double) { latitude = v }
        fun setLongitude(v: Double) { longitude = v }
        fun setAltitude(v: Double) { altitude = v }
        fun setAccuracy(v: Float) { accuracy = v }
        fun setSpeed(v: Float) { speed = v }
        fun setBearing(v: Float) { bearing = v }
        fun setProvider(v: String) { provider = v }
        fun setCoordinateType(v: Int) { coordinateType = v }
        fun setAddress(v: String) { address = v }
        fun setCity(v: String) { city = v }
    }

    interface TencentLocationListener {
        fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?)
        fun onStatusUpdate(name: String?, status: Int, desc: String?)
    }

    open class ConcreteTencentLocationListener : TencentLocationListener {
        var lastLocation: TencentLocation? = null
        var lastError: Int = -1
        var lastReason: String? = null
        var lastStatusName: String? = null

        override fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?) {
            lastLocation = location
            lastError = error
            lastReason = reason
        }

        override fun onStatusUpdate(name: String?, status: Int, desc: String?) {
            lastStatusName = name
        }
    }

    open class NonInterfaceTencentLocationListener {
        var lastLocation: Any? = null
        var lastError: Int = -1
        var lastReason: String? = null

        open fun onLocationChanged(location: Any?, error: Int, reason: String?) {
            lastLocation = location
            lastError = error
            lastReason = reason
        }
    }

    open class TencentLocationManager {
        var registeredListener: Any? = null
        var stubLastKnownLocation: TencentLocation? = DefaultTencentLocation()
        var removedListener: Any? = null

        open fun getCoordinateType(): Int = 0

        open fun requestLocationUpdates(request: Any?, listener: TencentLocationListener?): Int {
            registeredListener = listener
            return 0
        }

        open fun requestLocationUpdates(request: Any?, listener: Any?): Int {
            registeredListener = listener
            return 0
        }

        open fun requestSingleFreshLocation(request: Any?, listener: TencentLocationListener?, looper: Any?): Int {
            registeredListener = listener
            return 0
        }

        open fun removeUpdates(listener: TencentLocationListener?) {
            removedListener = listener
            if (registeredListener === listener) {
                registeredListener = null
            }
        }

        open fun removeUpdates(listener: Any?) {
            removedListener = listener
            if (registeredListener === listener) {
                registeredListener = null
            }
        }

        open fun getLastKnownLocation(): TencentLocation? = stubLastKnownLocation
    }

    // ---------- 4.2 高德定位 SDK ----------

    open class AMapLocationQualityReport {
        var mockAppInstalled: Boolean = true
        var gpsStatus: Int = 2
        var gpsSatellites: Int = 0

        open fun isInstalledHighDangerMockApp(): Boolean = mockAppInstalled
        open fun getGPSStatus(): Int = gpsStatus
        open fun getGPSSatellites(): Int = gpsSatellites
    }

    open class AMapLocation(
        private var providerName: String = "lbs"
    ) {
        constructor() : this("lbs")

        private var lat: Double = 22.5431
        private var lon: Double = 114.0579
        private var alt: Double = 12.0
        private var acc: Float = 30.0f
        private var spd: Float = 0.0f
        private var brg: Float = 0.0f
        private var timeMs: Long = 1_700_000_000_000L
        private var errCode: Int = 7
        private var errInfo: String = "KEY鉴权失败"
        private var locType: Int = 5
        private var coordType: String = "WGS84"
        private var mock: Boolean = true
        @JvmField var mTrustedLevel: Int = 4
        @JvmField var mFieldsMask: Int = 16
        @JvmField var mIsFromMockProvider: Boolean = true
        private var addressStr: String = "真实物理高德街道1号"
        private var countryStr: String = "中国"
        private var provinceStr: String = "广东省"
        private var cityStr: String = "深圳市"
        private var districtStr: String = "福田区"
        private var streetStr: String = "深南大道"
        private var streetNumStr: String = "100号"
        private var aoiNameStr: String = "真实商圈"
        private var poiNameStr: String = "真实地标"
        private var adCodeStr: String = "440304"
        private var cityCodeStr: String = "0755"
        private var buildingIdStr: String = "B1"
        private var floorStr: String = "3F"
        private var descriptionStr: String = "在真实大厦附近"
        private var roadStr: String = "深南大道"
        private var locationDetailStr: String = "真实定位详情"

        open fun setLatitude(v: Double) { lat = v }
        open fun getLatitude(): Double = lat
        open fun setLongitude(v: Double) { lon = v }
        open fun getLongitude(): Double = lon
        open fun setAltitude(v: Double) { alt = v }
        open fun getAltitude(): Double = alt
        open fun setAccuracy(v: Float) { acc = v }
        open fun getAccuracy(): Float = acc
        open fun setSpeed(v: Float) { spd = v }
        open fun getSpeed(): Float = spd
        open fun setBearing(v: Float) { brg = v }
        open fun getBearing(): Float = brg
        open fun setTime(v: Long) { timeMs = v }
        open fun getTime(): Long = timeMs
        open fun setProvider(v: String) { providerName = v }
        open fun getProvider(): String = providerName
        open fun setErrorCode(v: Int) { errCode = v }
        open fun getErrorCode(): Int = errCode
        open fun setErrorInfo(v: String) { errInfo = v }
        open fun getErrorInfo(): String = errInfo
        open fun setLocationType(v: Int) { locType = v }
        open fun getLocationType(): Int = locType
        open fun setCoordType(v: String) { coordType = v }
        open fun getCoordType(): String = coordType
        open fun setMock(v: Boolean) { mock = v }
        open fun isMock(): Boolean = mock
        open fun setTrustedLevel(v: Int) { mTrustedLevel = v }
        open fun getTrustedLevel(): Int = mTrustedLevel
        open fun setAddress(v: String) { addressStr = v }
        open fun getAddress(): String = addressStr
        open fun setCountry(v: String) { countryStr = v }
        open fun getCountry(): String = countryStr
        open fun setProvince(v: String) { provinceStr = v }
        open fun getProvince(): String = provinceStr
        open fun setCity(v: String) { cityStr = v }
        open fun getCity(): String = cityStr
        open fun setDistrict(v: String) { districtStr = v }
        open fun getDistrict(): String = districtStr
        open fun setStreet(v: String) { streetStr = v }
        open fun getStreet(): String = streetStr
        open fun setStreetNum(v: String) { streetNumStr = v }
        open fun getStreetNum(): String = streetNumStr
        open fun setAoiName(v: String) { aoiNameStr = v }
        open fun getAoiName(): String = aoiNameStr
        open fun setPoiName(v: String) { poiNameStr = v }
        open fun getPoiName(): String = poiNameStr
        open fun setAdCode(v: String) { adCodeStr = v }
        open fun getAdCode(): String = adCodeStr
        open fun setCityCode(v: String) { cityCodeStr = v }
        open fun getCityCode(): String = cityCodeStr
        open fun setBuildingId(v: String) { buildingIdStr = v }
        open fun getBuildingId(): String = buildingIdStr
        open fun setFloor(v: String) { floorStr = v }
        open fun getFloor(): String = floorStr
        open fun setDescription(v: String) { descriptionStr = v }
        open fun getDescription(): String = descriptionStr
        open fun setRoad(v: String) { roadStr = v }
        open fun getRoad(): String = roadStr
        open fun setLocationDetail(v: String) { locationDetailStr = v }
        open fun getLocationDetail(): String = locationDetailStr
    }

    interface AMapLocationListener {
        fun onLocationChanged(location: AMapLocation?)
    }

    open class ConcreteAMapLocationListener : AMapLocationListener {
        var lastLocation: AMapLocation? = null
        override fun onLocationChanged(location: AMapLocation?) {
            lastLocation = location
        }
    }

    open class AMapLocationClient {
        var listener: AMapLocationListener? = null
        var stubLastKnownLocation: AMapLocation? = AMapLocation("lbs")

        open fun setLocationListener(listener: AMapLocationListener?) {
            this.listener = listener
        }

        open fun getLastKnownLocation(): AMapLocation? = stubLastKnownLocation
    }

    // ---------- 4.3 百度定位 SDK ----------

    open class LocationClientOption(
        private var coorType: String = "bd09ll"
    ) {
        open fun setCoorType(coorType: String) {
            this.coorType = coorType
        }

        open fun getCoorType(): String = coorType
    }

    open class BDLocation {
        private var lat: Double = 22.5431
        private var lon: Double = 114.0579
        private var coorType: String = "gcj02"
        private var alt: Double = 15.0
        private var radius: Float = 20.0f
        private var speed: Float = 0.0f
        private var direction: Float = 0.0f
        private var locType: Int = 161
        private var timeStr: String = ""
        @JvmField var mMockGpsStrategy: Int = 2
        @JvmField var mMockGpsProbability: Int = 95
        private var mockGps: Boolean = true
        private var disToRealLocation: Float = 250.0f
        private var addrStr: String = "真实百度物理地址"
        private var province: String = "广东省"
        private var city: String = "深圳市"
        private var district: String = "南山区"
        private var street: String = "科苑路"
        private var streetNumber: String = "9号"
        private var cityCode: String = "340"
        private var adCode: String = "440305"
        private var locationDescribe: String = "在真实物理位置附近"
        private var poiList: List<Any> = listOf("真实百度POI")

        open fun setLatitude(v: Double) { lat = v }
        open fun getLatitude(): Double = lat
        open fun setLongitude(v: Double) { lon = v }
        open fun getLongitude(): Double = lon
        open fun setCoorType(v: String) { coorType = v }
        open fun getCoorType(): String = coorType
        open fun setAltitude(v: Double) { alt = v }
        open fun getAltitude(): Double = alt
        open fun setRadius(v: Float) { radius = v }
        open fun getRadius(): Float = radius
        open fun setSpeed(v: Float) { speed = v }
        open fun getSpeed(): Float = speed
        open fun setDirection(v: Float) { direction = v }
        open fun getDirection(): Float = direction
        open fun setLocType(v: Int) { locType = v }
        open fun getLocType(): Int = locType
        open fun setTime(v: String) { timeStr = v }
        open fun getTime(): String = timeStr
        open fun setMockGpsStrategy(v: Int) { mMockGpsStrategy = v }
        open fun getMockGpsStrategy(): Int = mMockGpsStrategy
        open fun setMockGpsProbability(v: Int) { mMockGpsProbability = v }
        open fun getMockGpsProbability(): Int = mMockGpsProbability
        open fun isMockGps(): Boolean = mockGps
        open fun getDisToRealLocation(): Float = disToRealLocation
        open fun setAddrStr(v: String) { addrStr = v }
        open fun getAddrStr(): String = addrStr
        open fun setProvince(v: String) { province = v }
        open fun getProvince(): String = province
        open fun setCity(v: String) { city = v }
        open fun getCity(): String = city
        open fun setDistrict(v: String) { district = v }
        open fun getDistrict(): String = district
        open fun setStreet(v: String) { street = v }
        open fun getStreet(): String = street
        open fun setStreetNumber(v: String) { streetNumber = v }
        open fun getStreetNumber(): String = streetNumber
        open fun setCityCode(v: String) { cityCode = v }
        open fun getCityCode(): String = cityCode
        open fun setAdCode(v: String) { adCode = v }
        open fun getAdCode(): String = adCode
        open fun setLocationDescribe(v: String) { locationDescribe = v }
        open fun getLocationDescribe(): String = locationDescribe
        open fun setPoiList(v: List<Any>) { poiList = v }
        open fun getPoiList(): List<Any> = poiList
    }

    interface BDLocationListener {
        fun onReceiveLocation(location: BDLocation?)
    }

    abstract class BDAbstractLocationListener {
        abstract fun onReceiveLocation(location: BDLocation?)
    }

    open class ConcreteBaiduLocationListener : BDAbstractLocationListener() {
        var lastLocation: BDLocation? = null
        override fun onReceiveLocation(location: BDLocation?) {
            lastLocation = location
        }
    }

    open class ConcreteBDLocationListener : BDLocationListener {
        var lastLocation: BDLocation? = null
        override fun onReceiveLocation(location: BDLocation?) {
            lastLocation = location
        }
    }

    open class LocationClient {
        private var option: LocationClientOption = LocationClientOption()
        var registeredListener: Any? = null
        var stubLastKnownLocation: BDLocation? = BDLocation()

        open fun setLocOption(option: LocationClientOption?) {
            if (option != null) this.option = option
        }

        open fun getLocOption(): LocationClientOption = option

        open fun registerLocationListener(listener: BDAbstractLocationListener?) {
            registeredListener = listener
        }

        open fun registerLocationListener(listener: BDLocationListener?) {
            registeredListener = listener
        }

        open fun registerNotifyLocationListener(listener: BDAbstractLocationListener?) {
            registeredListener = listener
        }

        open fun registerNotifyLocationListener(listener: BDLocationListener?) {
            registeredListener = listener
        }

        open fun getLastKnownLocation(): BDLocation? = stubLastKnownLocation
    }

    // ---------- 4.4 GMS Fused Location SDK ----------

    interface OnSuccessListener<T> {
        fun onSuccess(result: T)
    }

    open class ConcreteGmsSuccessListener : OnSuccessListener<Location> {
        var lastResult: Location? = null
        override fun onSuccess(result: Location) {
            lastResult = result
        }
    }

    open class Task<T>(
        private var value: T? = null
    ) {
        val listeners = CopyOnWriteArrayList<OnSuccessListener<T>>()

        open fun getResult(): T? = value

        open fun addOnSuccessListener(listener: OnSuccessListener<T>): Task<T> {
            listeners.add(listener)
            value?.let { listener.onSuccess(it) }
            return this
        }

        open fun addOnSuccessListener(executor: Executor, listener: OnSuccessListener<T>): Task<T> {
            listeners.add(listener)
            executor.execute {
                value?.let { listener.onSuccess(it) }
            }
            return this
        }
    }

    open class LocationResult(
        private val locations: List<Location>
    ) {
        open fun getLocations(): List<Location> = locations
        open fun getLastLocation(): Location? = locations.lastOrNull()
    }

    abstract class LocationCallback {
        open fun onLocationResult(result: LocationResult) {}
        open fun onLocationAvailability(availability: Any) {}
    }

    open class ConcreteGmsLocationCallback : LocationCallback() {
        var lastResult: LocationResult? = null
        var lastAvailability: Any? = null

        override fun onLocationResult(result: LocationResult) {
            lastResult = result
        }

        override fun onLocationAvailability(availability: Any) {
            lastAvailability = availability
        }
    }

    interface LocationListener {
        fun onLocationChanged(location: Location)
    }

    open class ConcreteGmsLocationListener : LocationListener {
        var lastLocation: Location? = null
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
    }

    open class FusedLocationProviderClient {
        var registeredCallback: LocationCallback? = null
        var registeredListener: Any? = null
        var removedTarget: Any? = null
        var lastLocationTask: Task<Location> = Task(null)

        open fun requestLocationUpdates(request: Any?, callback: LocationCallback, looper: Any?): Task<Void?> {
            registeredCallback = callback
            return Task(null)
        }

        open fun requestLocationUpdates(request: Any?, listener: LocationListener, looper: Any?): Task<Void?> {
            registeredListener = listener
            return Task(null)
        }

        open fun removeLocationUpdates(callback: LocationCallback): Task<Void?> {
            removedTarget = callback
            if (registeredCallback === callback) registeredCallback = null
            return Task(null)
        }

        open fun removeLocationUpdates(listener: LocationListener): Task<Void?> {
            removedTarget = listener
            if (registeredListener === listener) registeredListener = null
            return Task(null)
        }

        open fun getLastLocation(): Task<Location> = lastLocationTask

        open fun getCurrentLocation(request: Any?, cancellationToken: Any?): Task<Location> = lastLocationTask
    }

    object LocationServices {
        @JvmStatic
        fun getFusedLocationProviderClient(context: Any?): FusedLocationProviderClient =
            FusedLocationProviderClient()
    }

    // ---------- 4.5 精简 / 混淆变体 (供 T2-F11-05 等异常韧性测试) ----------

    /**
     * 精简混淆版高德定位：缺少 `setMock`, `setTrustedLevel`, `setCoordType`, `setLocationType`, `setPoiName` 等方法。
     */
    class ObfuscatedAmapLocationStub {
        private var lat: Double = 0.0
        private var lon: Double = 0.0
        private var errCode: Int = 7
        private var addr: String = "真实未清理地址"

        fun setLatitude(v: Double) { lat = v }
        fun getLatitude(): Double = lat
        fun setLongitude(v: Double) { lon = v }
        fun getLongitude(): Double = lon
        fun setErrorCode(v: Int) { errCode = v }
        fun getErrorCode(): Int = errCode
        fun setAddress(v: String) { addr = v }
        fun getAddress(): String = addr
    }

    /**
     * 精简混淆版百度定位：缺少 `setPoiList`, `setMockGpsStrategy`, `setMockGpsProbability`, `setCoorType` 等方法。
     */
    class ObfuscatedBaiduLocationStub {
        private var lat: Double = 0.0
        private var lon: Double = 0.0
        private var locTypeVal: Int = 161
        private var addr: String = "真实百度地址"

        fun setLatitude(v: Double) { lat = v }
        fun getLatitude(): Double = lat
        fun setLongitude(v: Double) { lon = v }
        fun getLongitude(): Double = lon
        fun setLocType(v: Int) { locTypeVal = v }
        fun getLocType(): Int = locTypeVal
        fun setAddrStr(v: String) { addr = v }
        fun getAddrStr(): String = addr
    }

    /**
     * 混淆版 GMS LocationResult：无 `getLocations`/`getLastLocation` 方法，仅暴露混淆字段 `zzb`。
     */
    class ObfuscatedGmsLocationResultStub(
        @JvmField val zzb: List<Location>
    )

    /**
     * 混淆版 GMS LocationAvailability：仅暴露混淆字段 `zza`。
     */
    class ObfuscatedGmsAvailabilityStub(
        @JvmField var zza: Boolean = false
    )

    // ---------- 4.6 快捷注册到 E2eHookBackend ----------

    fun registerTencent(backend: E2eHookBackend): E2eHookBackend = backend.apply {
        registerClass("com.tencent.map.geolocation.TencentLocationManager", TencentLocationManager::class.java)
        registerClass("com.tencent.map.geolocation.TencentLocation", TencentLocation::class.java)
        registerClass("com.tencent.map.geolocation.TencentLocationListener", TencentLocationListener::class.java)
    }

    fun registerAmap(backend: E2eHookBackend): E2eHookBackend = backend.apply {
        registerClass("com.amap.api.location.AMapLocationClient", AMapLocationClient::class.java)
        registerClass("com.amap.api.location.AMapLocationListener", AMapLocationListener::class.java)
        registerClass("com.amap.api.location.AMapLocation", AMapLocation::class.java)
        registerClass("com.amap.api.location.AMapLocationQualityReport", AMapLocationQualityReport::class.java)
    }

    fun registerBaidu(backend: E2eHookBackend): E2eHookBackend = backend.apply {
        registerClass("com.baidu.location.LocationClient", LocationClient::class.java)
        registerClass("com.baidu.location.LocationClientOption", LocationClientOption::class.java)
        registerClass("com.baidu.location.BDLocationListener", BDLocationListener::class.java)
        registerClass("com.baidu.location.BDAbstractLocationListener", BDAbstractLocationListener::class.java)
        registerClass("com.baidu.location.BDLocation", BDLocation::class.java)
    }

    fun registerGms(backend: E2eHookBackend): E2eHookBackend = backend.apply {
        registerClass("com.google.android.gms.location.LocationServices", LocationServices::class.java)
        registerClass("com.google.android.gms.location.FusedLocationProviderClient", FusedLocationProviderClient::class.java)
        registerClass("com.google.android.gms.location.LocationCallback", LocationCallback::class.java)
        registerClass("com.google.android.gms.location.LocationListener", LocationListener::class.java)
        registerClass("com.google.android.gms.location.LocationResult", LocationResult::class.java)
        registerClass("com.google.android.gms.tasks.Task", Task::class.java)
    }

    fun registerAll(backend: E2eHookBackend): E2eHookBackend = backend.apply {
        registerTencent(this)
        registerAmap(this)
        registerBaidu(this)
        registerGms(this)
    }
}

// ==================== 5. RootProviderManager TestProviderBackend 替身 ====================

data class RecordedProviderLocation(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val bearing: Float,
    val speed: Float
)

/**
 * 纯 E2E 自研 [RootProviderManager.TestProviderBackend] 替身，
 * 支持记录全操作流、模拟已有或缺失 `android:mock_location` AppOp，以及按 Provider 注入异常验证原子回滚。
 */
class FakeTestProviderBackend(
    initialHasMockAppOp: Boolean = true
) : RootProviderManager.TestProviderBackend {

    val hasMockAppOp = AtomicBoolean(initialHasMockAppOp)
    val operations = CopyOnWriteArrayList<String>()
    val registeredProviders = ConcurrentHashMap<String, Int>()
    val enabledProviders = ConcurrentHashMap<String, Boolean>()
    val lastLocations = ConcurrentHashMap<String, RecordedProviderLocation>()
    val clearedProviders = CopyOnWriteArrayList<String>()

    val throwOnAdd = ConcurrentHashMap<String, Throwable>()
    val throwOnSetEnabled = ConcurrentHashMap<String, Throwable>()
    val throwOnSetLocation = ConcurrentHashMap<String, Throwable>()
    val throwOnRemove = ConcurrentHashMap<String, Throwable>()

    override fun addTestProvider(provider: String, sdkInt: Int) {
        operations.add("addTestProvider:$provider:$sdkInt")
        throwOnAdd[provider]?.let { throw it }
        registeredProviders[provider] = sdkInt
    }

    override fun setTestProviderEnabled(provider: String, enabled: Boolean) {
        operations.add("setTestProviderEnabled:$provider:$enabled")
        throwOnSetEnabled[provider]?.let { throw it }
        enabledProviders[provider] = enabled
    }

    override fun setTestProviderLocation(
        provider: String,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        bearing: Float,
        speed: Float
    ) {
        operations.add("setTestProviderLocation:$provider:$latitude,$longitude")
        throwOnSetLocation[provider]?.let { throw it }
        lastLocations[provider] = RecordedProviderLocation(
            provider = provider,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            accuracy = accuracy,
            bearing = bearing,
            speed = speed
        )
    }

    override fun removeTestProvider(provider: String) {
        operations.add("removeTestProvider:$provider")
        throwOnRemove[provider]?.let { throw it }
        registeredProviders.remove(provider)
        enabledProviders.remove(provider)
    }

    override fun clearTestProviderLocation(provider: String) {
        operations.add("clearTestProviderLocation:$provider")
        clearedProviders.add(provider)
    }

    override fun hasMockLocationAppOp(packageName: String, uid: Int, sdkInt: Int): Boolean {
        operations.add("hasMockLocationAppOp:$packageName:$uid:$sdkInt")
        return hasMockAppOp.get()
    }

    fun reset() {
        operations.clear()
        registeredProviders.clear()
        enabledProviders.clear()
        lastLocations.clear()
        clearedProviders.clear()
        throwOnAdd.clear()
        throwOnSetEnabled.clear()
        throwOnSetLocation.clear()
        throwOnRemove.clear()
    }
}
