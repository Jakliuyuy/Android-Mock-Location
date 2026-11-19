package com.mockloc.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.mockloc.data.config.MockConfigRepository

class LocationConfigProvider(
    private val repositoryResolver: ((Context?) -> MockConfigRepository?)? = null,
    private val callingUidProvider: (() -> Int)? = null,
    private val myUidProvider: (() -> Int)? = null,
    private val bundleFactory: () -> Bundle = { Bundle() }
) : ContentProvider() {

    constructor() : this(null, null, null, { Bundle() })

    override fun onCreate(): Boolean {
        return try {
            resolveRepository(context)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return try {
            val repo = resolveRepository(context)

            when (method) {
                IpcProtocol.METHOD_PING -> {
                    val version = repo?.currentConfig?.version ?: 0L
                    bundleFactory().apply {
                        putBoolean(IpcProtocol.RESULT_OK, true)
                        putLong(IpcProtocol.KEY_VERSION, version)
                    }
                }

                IpcProtocol.METHOD_GET_CONFIG -> {
                    val config = repo?.currentConfig
                        ?: return bundleFactory().apply {
                            putBoolean(IpcProtocol.RESULT_OK, false)
                            putString(IpcProtocol.RESULT_ERROR, "REPOSITORY_UNAVAILABLE")
                        }
                    val bundle = ConfigSyncSerializer.toBundle(config, bundleFactory())
                    bundle.putBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, config.enabled)
                    if (!arg.isNullOrBlank()) {
                        bundle.putBoolean(
                            IpcProtocol.KEY_IS_MOCK_ENABLED,
                            config.isEnabledForPackage(arg)
                        )
                    } else {
                        bundle.putBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, config.enabled)
                    }
                    bundle.putBoolean(IpcProtocol.RESULT_OK, true)
                    bundle
                }

                IpcProtocol.METHOD_SET_CONFIG -> {
                    val callingUid = callingUidProvider?.invoke() ?: Binder.getCallingUid()
                    val myUid = myUidProvider?.invoke() ?: Process.myUid()
                    if (callingUid != myUid) {
                        return bundleFactory().apply {
                            putBoolean(IpcProtocol.RESULT_OK, false)
                            putString(
                                IpcProtocol.RESULT_ERROR,
                                "PERMISSION_DENIED_UID: callingUid=$callingUid, expected=$myUid"
                            )
                        }
                    }
                    if (extras == null) {
                        return bundleFactory().apply {
                            putBoolean(IpcProtocol.RESULT_OK, false)
                            putString(IpcProtocol.RESULT_ERROR, "MISSING_EXTRAS")
                        }
                    }
                    if (repo == null) {
                        return bundleFactory().apply {
                            putBoolean(IpcProtocol.RESULT_OK, false)
                            putString(IpcProtocol.RESULT_ERROR, "REPOSITORY_UNAVAILABLE")
                        }
                    }
                    val parsed = ConfigSyncSerializer.fromBundle(extras, repo.currentConfig)
                    val updated = repo.updateImmediate { parsed }
                    bundleFactory().apply {
                        putBoolean(IpcProtocol.RESULT_OK, true)
                        putLong(IpcProtocol.KEY_VERSION, updated.version)
                    }
                }

                else -> bundleFactory().apply {
                    putBoolean(IpcProtocol.RESULT_OK, false)
                    putString(IpcProtocol.RESULT_ERROR, "UNKNOWN_METHOD:$method")
                }
            }
        } catch (t: Throwable) {
            bundleFactory().apply {
                putBoolean(IpcProtocol.RESULT_OK, false)
                putString(IpcProtocol.RESULT_ERROR, t.message ?: "INTERNAL_ERROR")
            }
        }
    }

    private fun resolveRepository(ctx: Context?): MockConfigRepository? {
        repositoryResolver?.let { return it.invoke(ctx) }
        return ctx?.let { MockConfigRepository.getInstance(it) }
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
