package com.mockloc.data.config

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.ipc.IpcProtocol

object SafePrefs {
    const val MODE_WORLD_READABLE_COMPAT = 0x0001

    fun interface PrefsProvider {
        fun getSharedPreferences(context: Context, name: String, mode: Int): SharedPreferences
    }

    data class SafePrefsResult(
        val prefs: SharedPreferences,
        val isWorldReadable: Boolean
    )

    private val defaultProvider = PrefsProvider { context, name, mode ->
        context.getSharedPreferences(name, mode)
    }

    fun open(
        context: Context,
        name: String = IpcProtocol.PREFS_NAME,
        provider: PrefsProvider = defaultProvider
    ): SafePrefsResult {
        return try {
            val prefs = provider.getSharedPreferences(context, name, MODE_WORLD_READABLE_COMPAT)
            SafePrefsResult(prefs = prefs, isWorldReadable = true)
        } catch (_: SecurityException) {
            val fallbackPrefs = provider.getSharedPreferences(context, name, Context.MODE_PRIVATE)
            SafePrefsResult(prefs = fallbackPrefs, isWorldReadable = false)
        }
    }
}
