package com.mockloc.presentation.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 支持精确最大高度约束的 RecyclerView，用于搜索结果悬浮列表与底部弹窗内弹性限高滚动。
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = (220f * context.resources.displayMetrics.density).toInt()
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val boundedHeightSpec = if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightSpec)
            val size = MeasureSpec.getSize(heightSpec)
            when (mode) {
                MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(
                    minOf(size, maxHeightPx),
                    MeasureSpec.AT_MOST
                )
                MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(
                    minOf(size, maxHeightPx),
                    MeasureSpec.EXACTLY
                )
                else -> MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            }
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, boundedHeightSpec)
    }
}
