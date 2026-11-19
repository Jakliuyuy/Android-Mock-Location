package com.mockloc.hook.cleaner

import android.app.AppOpsManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.mockloc.hook.HookBackend
import com.mockloc.hook.MethodHookHandler
import com.mockloc.hook.XposedHookBackend
import java.lang.reflect.Field
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object MockLocationCleaner {

    private const val HAS_MOCK_PROVIDER_MASK = 16 // bit 4 (1 << 4)
    private const val OP_MOCK_LOCATION_CODE = 58
    private const val OPSTR_MOCK_LOCATION_LITERAL = "android:mock_location"

    private val MOCK_EXTRA_KEYS = setOf(
        "mocklocation",
        "mock",
        "ismock",
        "fake",
        "spoof",
        "isfrommockprovider",
        "mock_location",
        "mockgps",
        "coarselocation",
        "nogpslocation",
        "mockprovider",
        "frommockprovider"
    )

    private val installedLoaders = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var fieldsMaskFieldResolved = false

    @Volatile
    private var fieldsMaskField: Field? = null

    @Volatile
    private var isFromMockFieldResolved = false

    @Volatile
    private var isFromMockField: Field? = null

    fun sanitize(location: Location?): Location? = cleanLocation(location)

    fun cleanLocation(location: Location?): Location? {
        if (location == null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || Build.VERSION.SDK_INT == 0) {
            runCatching { location.setMock(false) }
        }

        cleanHiddenFields(location)
        cleanExtras(location)
        return location
    }

    internal fun cleanHiddenFields(target: Any) {
        val fieldsMask = resolveFieldsMaskField(target.javaClass)
        if (fieldsMask != null) {
            runCatching {
                when (fieldsMask.type) {
                    Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> {
                        val current = (fieldsMask.get(target) as? Number)?.toInt()
                            ?: fieldsMask.getByte(target).toInt()
                        if ((current and HAS_MOCK_PROVIDER_MASK) != 0) {
                            val cleared = (current and HAS_MOCK_PROVIDER_MASK.inv()).toByte()
                            if (fieldsMask.type == Byte::class.javaPrimitiveType) {
                                fieldsMask.setByte(target, cleared)
                            } else {
                                fieldsMask.set(target, cleared)
                            }
                        }
                    }
                    Short::class.javaPrimitiveType, Short::class.javaObjectType -> {
                        val current = (fieldsMask.get(target) as? Number)?.toInt()
                            ?: fieldsMask.getShort(target).toInt()
                        if ((current and HAS_MOCK_PROVIDER_MASK) != 0) {
                            val cleared = (current and HAS_MOCK_PROVIDER_MASK.inv()).toShort()
                            if (fieldsMask.type == Short::class.javaPrimitiveType) {
                                fieldsMask.setShort(target, cleared)
                            } else {
                                fieldsMask.set(target, cleared)
                            }
                        }
                    }
                    Long::class.javaPrimitiveType, Long::class.javaObjectType -> {
                        val current = (fieldsMask.get(target) as? Number)?.toLong()
                            ?: fieldsMask.getLong(target)
                        val mask = HAS_MOCK_PROVIDER_MASK.toLong()
                        if ((current and mask) != 0L) {
                            val cleared = current and mask.inv()
                            if (fieldsMask.type == Long::class.javaPrimitiveType) {
                                fieldsMask.setLong(target, cleared)
                            } else {
                                fieldsMask.set(target, cleared)
                            }
                        }
                    }
                    else -> {
                        val current = fieldsMask.getInt(target)
                        if ((current and HAS_MOCK_PROVIDER_MASK) != 0) {
                            fieldsMask.setInt(target, current and HAS_MOCK_PROVIDER_MASK.inv())
                        }
                    }
                }
            }
        }

        val isFromMock = resolveIsFromMockField(target.javaClass)
        if (isFromMock != null) {
            runCatching {
                if (isFromMock.type == Boolean::class.javaPrimitiveType) {
                    if (isFromMock.getBoolean(target)) {
                        isFromMock.setBoolean(target, false)
                    }
                } else if (isFromMock.get(target) == true) {
                    isFromMock.set(target, false)
                }
            }
        }
    }

    internal fun cleanExtrasBundle(bundle: Bundle?): Bundle? {
        if (bundle == null) return null
        val keys = runCatching { bundle.keySet()?.toList() }.getOrNull() ?: return bundle
        var modified = false
        for (key in keys) {
            if (isMockExtraKey(key)) {
                runCatching {
                    bundle.remove(key)
                    modified = true
                }
            }
        }
        return if (modified) bundle else bundle
    }

    internal fun isMockExtraKey(key: String): Boolean {
        val normalized = key.trim().lowercase(Locale.US)
        if (normalized in MOCK_EXTRA_KEYS) return true
        return normalized.contains("mock") || normalized.contains("spoof") || normalized.contains("fake")
    }

    private fun cleanExtras(location: Location) {
        runCatching {
            val extras = location.extras ?: return@runCatching
            val keys = extras.keySet()?.toList() ?: return@runCatching
            var hasMockKey = false
            for (k in keys) {
                if (isMockExtraKey(k)) {
                    hasMockKey = true
                    break
                }
            }
            if (!hasMockKey) return@runCatching

            val candidateCopy = runCatching { Bundle(extras) }.getOrNull()
            val targetBundle = if (candidateCopy != null && runCatching { candidateCopy.keySet()?.size }.getOrNull() == keys.size) {
                candidateCopy
            } else {
                extras
            }
            for (k in keys) {
                if (isMockExtraKey(k)) {
                    targetBundle.remove(k)
                }
            }
            location.extras = targetBundle
        }
    }

    private fun resolveFieldsMaskField(clazz: Class<*>): Field? {
        if (clazz === Location::class.java && fieldsMaskFieldResolved) {
            return fieldsMaskField
        }
        val found = findDeclaredFieldInHierarchy(clazz, "mFieldsMask")
        if (clazz === Location::class.java) {
            fieldsMaskField = found
            fieldsMaskFieldResolved = true
        }
        return found
    }

    private fun resolveIsFromMockField(clazz: Class<*>): Field? {
        if (clazz === Location::class.java && isFromMockFieldResolved) {
            return isFromMockField
        }
        val found = findDeclaredFieldInHierarchy(clazz, "mIsFromMockProvider")
        if (clazz === Location::class.java) {
            isFromMockField = found
            isFromMockFieldResolved = true
        }
        return found
    }

    private fun findDeclaredFieldInHierarchy(startClass: Class<*>, name: String): Field? {
        var cursor: Class<*>? = startClass
        while (cursor != null && cursor !== Any::class.java) {
            try {
                val field = cursor.getDeclaredField(name)
                field.isAccessible = true
                return field
            } catch (_: Throwable) {
                cursor = cursor.superclass
            }
        }
        return null
    }

    fun installHooks(
        classLoader: ClassLoader?,
        backend: HookBackend = XposedHookBackend,
        sdkInt: Int = Build.VERSION.SDK_INT,
        isMockActiveForCurrentApp: () -> Boolean
    ) {
        val loaderKey = System.identityHashCode(classLoader)
        if (!installedLoaders.add(loaderKey)) return

        hookLocationClass(classLoader, backend, sdkInt, isMockActiveForCurrentApp)
        hookSettingsSecure(classLoader, backend, isMockActiveForCurrentApp)
        hookAppOpsManager(classLoader, backend, isMockActiveForCurrentApp)
    }

    private fun hookLocationClass(
        classLoader: ClassLoader?,
        backend: HookBackend,
        sdkInt: Int,
        isMockActiveForCurrentApp: () -> Boolean
    ) {
        val locationCls = backend.findClassIfExists("android.location.Location", classLoader)
            ?: Location::class.java

        backend.hookAllMethods(
            locationCls,
            "isFromMockProvider",
            after = MethodHookHandler { inv ->
                if (isMockActiveForCurrentApp()) {
                    inv.result = false
                }
            }
        )

        if (sdkInt >= Build.VERSION_CODES.S) {
            backend.hookAllMethods(
                locationCls,
                "isMock",
                after = MethodHookHandler { inv ->
                    if (isMockActiveForCurrentApp()) {
                        inv.result = false
                    }
                }
            )
            backend.hookAllMethods(
                locationCls,
                "setMock",
                before = MethodHookHandler { inv ->
                    if (isMockActiveForCurrentApp() && inv.args.isNotEmpty()) {
                        inv.args[0] = false
                    }
                }
            )
        }
    }

    private fun hookSettingsSecure(
        classLoader: ClassLoader?,
        backend: HookBackend,
        isMockActiveForCurrentApp: () -> Boolean
    ) {
        val secureCls = backend.findClassIfExists("android.provider.Settings\$Secure", classLoader)
            ?: Settings.Secure::class.java

        val mockKey = "mock_location"

        backend.hookAllMethods(
            secureCls,
            "getInt",
            before = MethodHookHandler { inv ->
                if (isMockActiveForCurrentApp() && isMockLocationSettingArg(inv.args, mockKey)) {
                    inv.result = 0
                }
            }
        )
        backend.hookAllMethods(
            secureCls,
            "getLong",
            before = MethodHookHandler { inv ->
                if (isMockActiveForCurrentApp() && isMockLocationSettingArg(inv.args, mockKey)) {
                    inv.result = 0L
                }
            }
        )
        backend.hookAllMethods(
            secureCls,
            "getFloat",
            before = MethodHookHandler { inv ->
                if (isMockActiveForCurrentApp() && isMockLocationSettingArg(inv.args, mockKey)) {
                    inv.result = 0.0f
                }
            }
        )
        backend.hookAllMethods(
            secureCls,
            "getString",
            before = MethodHookHandler { inv ->
                if (isMockActiveForCurrentApp() && isMockLocationSettingArg(inv.args, mockKey)) {
                    inv.result = "0"
                }
            }
        )
    }

    private fun hookAppOpsManager(
        classLoader: ClassLoader?,
        backend: HookBackend,
        isMockActiveForCurrentApp: () -> Boolean
    ) {
        val appOpsCls = backend.findClassIfExists("android.app.AppOpsManager", classLoader)
            ?: AppOpsManager::class.java

        val opCheckMethods = listOf(
            "checkOp",
            "checkOpNoThrow",
            "checkOpRawNoThrow",
            "unsafeCheckOp",
            "unsafeCheckOpNoThrow",
            "unsafeCheckOpRaw",
            "unsafeCheckOpRawNoThrow",
            "noteOp",
            "noteOpNoThrow",
            "startOp",
            "startOpNoThrow"
        )

        val afterHandler = MethodHookHandler { inv ->
            if (!isMockActiveForCurrentApp()) return@MethodHookHandler
            val firstArg = inv.args.firstOrNull()
            if (isMockLocationOpArg(firstArg)) {
                inv.result = AppOpsManager.MODE_IGNORED
            }
        }

        for (methodName in opCheckMethods) {
            backend.hookAllMethods(appOpsCls, methodName, after = afterHandler)
        }

        backend.hookAllMethods(
            appOpsCls,
            "isOpActive",
            after = MethodHookHandler { inv ->
                if (!isMockActiveForCurrentApp()) return@MethodHookHandler
                val firstArg = inv.args.firstOrNull()
                if (isMockLocationOpArg(firstArg)) {
                    inv.result = false
                }
            }
        )
    }

    private fun isMockLocationSettingArg(args: Array<Any?>, mockKey: String): Boolean {
        val key = args.getOrNull(1) as? String ?: return false
        return key == mockKey
    }

    private fun isMockLocationOpArg(arg: Any?): Boolean {
        return when (arg) {
            is String -> arg == OPSTR_MOCK_LOCATION_LITERAL || arg == "mock_location"
            is Int -> arg == OP_MOCK_LOCATION_CODE
            else -> false
        }
    }

    fun clearForTest() {
        installedLoaders.clear()
    }
}
