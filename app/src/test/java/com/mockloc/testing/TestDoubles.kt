package com.mockloc.testing

import android.content.SharedPreferences
import android.os.Bundle
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

/**
 * 纯 JVM 内存 SharedPreferences 实现，完整支持 get/put/commit/apply/contains/all，
 * 避免依赖 Robolectric 即可在离线单元测试中验证真实持久化行为。
 */
class InMemorySharedPreferences : SharedPreferences {
    private val store = LinkedHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    var commitCount: Int = 0
        private set
    var applyCount: Int = 0
        private set

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(store)

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null || !store.containsKey(key)) return defValue
        return store[key] as String?
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        if (key == null || !store.containsKey(key)) return defValues
        return (store[key] as? Set<String>)?.let { HashSet(it) } ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key == null || !store.containsKey(key)) return defValue
        return store[key] as Int
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key == null || !store.containsKey(key)) return defValue
        return store[key] as Long
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key == null || !store.containsKey(key)) return defValue
        return store[key] as Float
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null || !store.containsKey(key)) return defValue
        return store[key] as Boolean
    }

    override fun contains(key: String?): Boolean = key != null && store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.remove(listener)
    }

    fun putRaw(key: String, value: Any?) {
        store[key] = value
    }

    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = values?.let { HashSet(it) }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = REMOVED_SENTINEL
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            applyChanges()
            commitCount++
            return true
        }

        override fun apply() {
            applyChanges()
            applyCount++
        }

        private fun applyChanges() {
            if (clearRequested) store.clear()
            for ((k, v) in pending) {
                if (v === REMOVED_SENTINEL) {
                    store.remove(k)
                } else {
                    store[k] = v
                }
            }
        }
    }

    private object REMOVED_SENTINEL
}

/**
 * 构建带状态内存 Map 的 Mockito Bundle 桩，用于在无 Android Framework 运行时的 JVM 单元测试中
 * 精确验证 ConfigSyncSerializer 与 LocationConfigProvider 的真实键值往返行为。
 */
fun createStatefulMockBundle(): Bundle {
    val map = LinkedHashMap<String, Any?>()
    val bundle = mock<Bundle>()

    doAnswer { inv -> map.containsKey(inv.getArgument<String>(0)) }
        .`when`(bundle).containsKey(any())

    doAnswer { inv -> map[inv.getArgument<String>(0)] = inv.getArgument<Boolean>(1); Unit }
        .`when`(bundle).putBoolean(any(), any())
    doAnswer { inv ->
        val k = inv.getArgument<String>(0)
        val def = inv.getArgument<Boolean>(1)
        (map[k] as? Boolean) ?: def
    }.`when`(bundle).getBoolean(any(), any())

    doAnswer { inv -> map[inv.getArgument<String>(0)] = inv.getArgument<Double>(1); Unit }
        .`when`(bundle).putDouble(any(), any())
    doAnswer { inv ->
        val k = inv.getArgument<String>(0)
        val def = inv.getArgument<Double>(1)
        (map[k] as? Double) ?: def
    }.`when`(bundle).getDouble(any(), any())

    doAnswer { inv -> map[inv.getArgument<String>(0)] = inv.getArgument<Float>(1); Unit }
        .`when`(bundle).putFloat(any(), any())
    doAnswer { inv ->
        val k = inv.getArgument<String>(0)
        val def = inv.getArgument<Float>(1)
        (map[k] as? Float) ?: def
    }.`when`(bundle).getFloat(any(), any())

    doAnswer { inv -> map[inv.getArgument<String>(0)] = inv.getArgument<Long>(1); Unit }
        .`when`(bundle).putLong(any(), any())
    doAnswer { inv ->
        val k = inv.getArgument<String>(0)
        val def = inv.getArgument<Long>(1)
        (map[k] as? Long) ?: def
    }.`when`(bundle).getLong(any(), any())

    doAnswer { inv -> map[inv.getArgument<String>(0)] = inv.getArgument<String?>(1); Unit }
        .`when`(bundle).putString(any(), anyOrNull())
    doAnswer { inv -> map[inv.getArgument<String>(0)] as? String }
        .`when`(bundle).getString(any())
    doAnswer { inv ->
        val k = inv.getArgument<String>(0)
        val def = inv.getArgument<String?>(1)
        (map[k] as? String) ?: def
    }.`when`(bundle).getString(any(), anyOrNull())

    doAnswer { inv ->
        val list = inv.getArgument<ArrayList<String>?>(1)
        map[inv.getArgument<String>(0)] = list?.let { ArrayList(it) }
        Unit
    }.`when`(bundle).putStringArrayList(any(), anyOrNull())

    @Suppress("UNCHECKED_CAST")
    doAnswer { inv ->
        (map[inv.getArgument<String>(0)] as? ArrayList<String>)?.let { ArrayList(it) }
    }.`when`(bundle).getStringArrayList(any())

    return bundle
}
