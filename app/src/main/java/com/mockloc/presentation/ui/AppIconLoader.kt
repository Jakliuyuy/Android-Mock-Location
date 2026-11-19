package com.mockloc.presentation.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.mockloc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 异步应用图标加载器：使用 LruCache + Dispatchers.IO 避免 RecyclerView 滑动掉帧，
 * 通过 View tag 绑定校验杜绝异步回调时的视图复用串位。
 */
class AppIconLoader(
    context: Context,
    private val scope: CoroutineScope
) {
    private val packageManager = context.applicationContext.packageManager
    private val fallbackIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_apps_24)
    private val cache = LruCache<String, Drawable>(96)
    private val pendingJobs = ConcurrentHashMap<ImageView, Job>()

    fun load(packageName: String, target: ImageView) {
        target.tag = packageName
        pendingJobs.remove(target)?.cancel()

        val cached = cache.get(packageName)
        if (cached != null) {
            target.setImageDrawable(cached)
            return
        }

        target.setImageDrawable(fallbackIcon)
        val job = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    packageManager.getApplicationIcon(packageName)
                }.getOrNull()
            }
            if (loaded != null) {
                cache.put(packageName, loaded)
            }
            if (target.tag == packageName && loaded != null) {
                target.setImageDrawable(loaded)
            }
        }
        pendingJobs[target] = job
        job.invokeOnCompletion {
            pendingJobs.remove(target, job)
        }
    }
}
