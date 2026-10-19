package com.mockloc.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.mockloc.data.MockConfigManager

import com.mockloc.data.WhitelistRepository

/**
 * 跨进程配置同步桥接 ContentProvider。
 * 在 Android 7.0+ 及 SELinux Enforcing 模式下，允许被注入 Hook 的目标应用进程
 * 免文件系统权限拉取主控端的定位配置与防检测设置。
 */
class LocationConfigProvider : ContentProvider() {

    companion object {
        val CONTENT_URI: Uri
            get() = Uri.parse("content://${MockConfigManager.AUTHORITY}")

        /**
         * 供 Hook 引擎在目标应用进程内跨进程拉取主控配置
         */
        @JvmStatic
        fun fetchConfig(context: Context, targetPackage: String?): Bundle? {
            return try {
                context.contentResolver.call(
                    CONTENT_URI,
                    MockConfigManager.METHOD_GET_CONFIG,
                    targetPackage,
                    null
                )
            } catch (t: Throwable) {
                t.printStackTrace()
                null
            }
        }
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            try {
                WhitelistRepository.getInstance(ctx)
            } catch (_: Throwable) {}
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        context?.let { ctx ->
            try {
                WhitelistRepository.getInstance(ctx)
            } catch (_: Throwable) {}
        }
        return when (method) {
            MockConfigManager.METHOD_GET_CONFIG -> {
                val bundle = MockConfigManager.toBundle()
                // 如果传入了目标包名，计算针对该包名的专属生效状态
                if (!arg.isNullOrEmpty()) {
                    val enabledForPkg = MockConfigManager.isMockEnabledForPackage(arg)
                    bundle.putBoolean(MockConfigManager.KEY_IS_MOCK_ENABLED, enabledForPkg)
                }
                bundle
            }
            MockConfigManager.METHOD_SET_CONFIG -> {
                if (extras != null) {
                    MockConfigManager.updateFromBundle(extras)
                }
                Bundle().apply { putBoolean("success", true) }
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
