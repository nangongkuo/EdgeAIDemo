package com.zjf.edgeai.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * R：列表内统一手势仲裁器。
 *
 * 设计原则：
 * 1. 在 R 上识别横/竖手势。
 * 2. 竖向手势交给 RecyclerView 自己滚动。
 * 3. 横向手势不再依赖父级拦截，而是由 R 直接驱动 B / A 切页。
 * 4. 这样可以稳定实现：
 *    - Tab1 右滑 -> A 上一页
 *    - Tab1 左滑 -> B 下一页(Tab2)
 *    - Tab2 左右滑 -> B 内部切页
 *    - Tab3 左滑 -> A 下一页
 *    - Tab3 右滑 -> B 上一页(Tab2)
 */
class CustomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    companion object {
        private const val GESTURE_NONE = 0
        private const val GESTURE_HORIZONTAL = 1
        private const val GESTURE_VERTICAL = 2
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pagerTriggerDistance = touchSlop * 4

    private var downX = 0f
    private var downY = 0f
    private var gesture = GESTURE_NONE
    private var pagerSwitched = false

    private var innerViewPager: ViewPager2? = null
    private var outerViewPager: ViewPager2? = null

    fun setViewPagerRefs(inner: ViewPager2?, outer: ViewPager2?) {
        innerViewPager = inner
        outerViewPager = outer
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                gesture = GESTURE_NONE
                pagerSwitched = false
                stopScroll()
                // 先禁止父级抢事件，R 自己做手势判断
                parent?.requestDisallowInterceptTouchEvent(true)
                setPagerUserInputEnabled(false)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                resetGesture()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return super.onInterceptTouchEvent(e)
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY

                if (gesture == GESTURE_NONE && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    gesture = if (abs(dx) > abs(dy)) GESTURE_HORIZONTAL else GESTURE_VERTICAL
                }

                // 横向手势由当前 RecyclerView 完整接管，避免自身竖向滚动介入
                if (gesture == GESTURE_HORIZONTAL) {
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val totalDx = e.x - downX
                val totalDy = e.y - downY

                if (gesture == GESTURE_NONE && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    gesture = if (abs(totalDx) > abs(totalDy)) GESTURE_HORIZONTAL else GESTURE_VERTICAL
                }

                when (gesture) {
                    GESTURE_HORIZONTAL -> {
                        handleHorizontalGesture(totalDx)
                        return true
                    }

                    GESTURE_VERTICAL -> {
                        // 竖向滚动期间，彻底禁止 A/B 横滑，直到手指抬起
                        setPagerUserInputEnabled(false)
                        return super.onTouchEvent(e)
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (gesture == GESTURE_HORIZONTAL) {
                    resetGesture()
                    return true
                }
                resetGesture()
            }
        }
        return super.onTouchEvent(e)
    }

    private fun handleHorizontalGesture(totalDx: Float) {
        // 横向判定后，禁止 RecyclerView 继续竖滚
        stopScroll()

        if (pagerSwitched || abs(totalDx) < pagerTriggerDistance) {
            return
        }

        if (totalDx < 0) {
            switchLeft()
        } else {
            switchRight()
        }
        pagerSwitched = true
    }

    /** 左滑：优先 B 下一页，B 到边界后切 A 下一页 */
    private fun switchLeft() {
        val inner = innerViewPager
        if (inner != null && canScrollToNext(inner)) {
            inner.setCurrentItem(inner.currentItem + 1, true)
            return
        }

        val outer = outerViewPager
        if (outer != null && canScrollToNext(outer)) {
            outer.setCurrentItem(outer.currentItem + 1, true)
        }
    }

    /** 右滑：优先 B 上一页，B 到边界后切 A 上一页 */
    private fun switchRight() {
        val inner = innerViewPager
        if (inner != null && canScrollToPrevious(inner)) {
            inner.setCurrentItem(inner.currentItem - 1, true)
            return
        }

        val outer = outerViewPager
        if (outer != null && canScrollToPrevious(outer)) {
            outer.setCurrentItem(outer.currentItem - 1, true)
        }
    }

    private fun canScrollToNext(viewPager: ViewPager2): Boolean {
        val count = viewPager.adapter?.itemCount ?: 0
        return count > 0 && viewPager.currentItem < count - 1
    }

    private fun canScrollToPrevious(viewPager: ViewPager2): Boolean {
        return viewPager.currentItem > 0
    }

    private fun setPagerUserInputEnabled(enabled: Boolean) {
        innerViewPager?.isUserInputEnabled = enabled
        outerViewPager?.isUserInputEnabled = enabled
    }

    private fun resetGesture() {
        gesture = GESTURE_NONE
        pagerSwitched = false
        setPagerUserInputEnabled(true)
    }
}
