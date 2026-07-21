package com.whj.music.ui

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * 缓解「列表竖滑」被 ViewPager2 当成横滑翻页。
 *
 * 1. 增大 ViewPager2 内部 touchSlop，降低横滑灵敏度
 * 2. 子 RecyclerView 在竖直意图时 requestDisallowIntercept，优先列表滚动
 */
object ViewPager2GestureFix {

    /**
     * @param slopFactor 内部 touchSlop 倍数，越大越不容易误翻页（建议 3–5）
     */
    fun reduceSwipeSensitivity(pager: ViewPager2, slopFactor: Int = 4) {
        val factor = slopFactor.coerceIn(2, 8)
        runCatching {
            val rvField = ViewPager2::class.java.getDeclaredField("mRecyclerView")
            rvField.isAccessible = true
            val rv = rvField.get(pager) as RecyclerView
            val slopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            slopField.isAccessible = true
            val base = slopField.getInt(rv)
            // 避免重复乘
            val scaled = ViewConfiguration.get(pager.context).scaledTouchSlop
            if (base <= scaled * 2) {
                slopField.setInt(rv, base * factor)
            }
        }
    }

    /**
     * 挂到纵向列表上：竖向主导时禁止父级（ViewPager2）拦截。
     */
    fun preferVerticalScroll(list: RecyclerView) {
        val touchSlop = ViewConfiguration.get(list.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var decided = false

        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        decided = false
                        // 先占住，等方向明确后再放行横滑
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (decided) return false
                        val dx = abs(e.x - downX)
                        val dy = abs(e.y - downY)
                        if (dx > touchSlop || dy > touchSlop) {
                            decided = true
                            // 竖直优先：dy 不小于 dx 时继续拦截父级；明显横滑才交给 ViewPager
                            val vertical = dy >= dx * 0.85f
                            rv.parent?.requestDisallowInterceptTouchEvent(vertical)
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> {
                        decided = false
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return false
            }
        })
    }
}
