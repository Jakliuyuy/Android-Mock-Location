package com.mockloc.presentation.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Edge-to-Edge WindowInsets 工具：安全处理状态栏、手势导航条、三键导航栏与软键盘遮挡，
 * 并在多次回调时避免重复叠加原始边距。
 */
object EdgeToEdgeHelper {

    fun applyTopMarginInsets(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val initialTop = lp.topMargin
        val initialLeft = lp.leftMargin
        val initialRight = lp.rightMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = initialTop + bars.top
                leftMargin = initialLeft + bars.left
                rightMargin = initialRight + bars.right
            }
            insets
        }
    }

    fun applyTopPaddingInsets(view: View) {
        val initialTop = view.paddingTop
        val initialLeft = view.paddingLeft
        val initialRight = view.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + bars.top,
                right = initialRight + bars.right
            )
            insets
        }
    }

    fun applyBottomPaddingInsets(view: View, includeIme: Boolean = false) {
        val initialBottom = view.paddingBottom
        val initialLeft = view.paddingLeft
        val initialRight = view.paddingRight
        val typeMask = if (includeIme) {
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        } else {
            WindowInsetsCompat.Type.systemBars()
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val system = insets.getInsets(typeMask)
            v.updatePadding(
                left = initialLeft + system.left,
                right = initialRight + system.right,
                bottom = initialBottom + system.bottom
            )
            insets
        }
    }

    fun applyRightMarginInsets(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val initialRight = lp.rightMargin
        val initialBottom = lp.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                rightMargin = initialRight + bars.right
                bottomMargin = initialBottom + bars.bottom
            }
            insets
        }
    }
}
