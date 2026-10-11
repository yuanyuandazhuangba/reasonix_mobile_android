package io.reasonix.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.reasonix.mobile.storage.PreferenceManager
import kotlin.math.abs

/**
 * 浮动菜单按钮（FAB）交互控制器。
 *
 * 背景：FAB 原先固定在右下角，恰好压住电脑端 Studio 页面底部的提示词输入框与发送
 * 按钮，而原生视图永远叠在 WebView 之上，网页既感知不到也无法避让。本控制器用三种
 * 互补手段消除该遮挡：
 *
 * 1. 可拖拽：松手后吸附到更近的屏幕侧边，并把「侧别 + 垂直比例」写入
 *    [PreferenceManager]，下次进入沿用，用户可自行避开发送按钮所在的角落；
 * 2. 键盘避让：软键盘弹出时把 FAB 置为 INVISIBLE，正在输入提示词时让路，
 *    键盘收起后恢复；
 * 3. 默认让位：布局上从右下角改为右侧垂直居中，静止一段时间后降为半透明，
 *    触摸即恢复不透明，弱化对页面内容的干扰。
 */
class FloatingFabController(
    private val fab: FloatingActionButton,
    private val container: View,
    private val preferenceManager: PreferenceManager,
    private val onClick: () -> Unit
) {

    private val context: Context = fab.context
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var dragging = false

    /** 软键盘当前是否可见，可见时 FAB 让位隐藏。 */
    private var imeVisible = false

    /** 静止后淡出的定时任务，交互时取消、结束后重排。 */
    private val fadeOutRunnable = Runnable { applyIdleAlpha() }

    private data class Bounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )

    /** 接线一次即可；随后由 insets 与布局变化驱动位置。 */
    fun bind() {
        fab.setOnClickListener { onClick() }
        fab.setOnTouchListener { _, event -> handleTouch(event) }

        // 旋转、系统栏变化、键盘 resize 都会触发重新布局，需按保存的比例重新对齐
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applySavedPosition(animate = false)
        }

        // 首帧拿到真实尺寸后再定位，并启动静止淡出
        fab.post {
            applySavedPosition(animate = false)
            scheduleFadeOut()
        }
    }

    /**
     * 由 Activity 在 `setOnApplyWindowInsetsListener` 中转发。只关心软键盘的可见性，
     * 用于键盘避让；垂直位置本身由容器高度推导，不叠加系统栏 inset，避免二次避让。
     */
    fun applyWindowInsets(insets: WindowInsetsCompat) {
        val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
        if (visible == imeVisible) return
        imeVisible = visible
        if (visible) {
            cancelFade()
            fab.visibility = View.INVISIBLE
        } else {
            fab.visibility = View.VISIBLE
            fab.alpha = IDLE_ALPHA
            applySavedPosition(animate = false)
            scheduleFadeOut()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startTranslationX = fab.translationX
                startTranslationY = fab.translationY
                dragging = false
                cancelFade()
                fab.alpha = 1f
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    val bounds = translationBounds() ?: return true
                    fab.translationX =
                        (startTranslationX + dx).coerceIn(bounds.minX, bounds.maxX)
                    fab.translationY =
                        (startTranslationY + dy).coerceIn(bounds.minY, bounds.maxY)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // 拖拽结束：吸附最近侧边并记住位置
                    snapToNearestSide()
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // 触摸监听返回 true 会吞掉点击，需要自己派发，保持可访问性与点击涟漪
                    fab.performClick()
                }
                dragging = false
                scheduleFadeOut()
            }
        }
        return true
    }

    /**
     * 当前容器尺寸下，FAB 相对布局位置的平移量可取范围。
     * 容器为 content view 根，坐标原点已在状态栏之下，故仅需保留边距。
     */
    private fun translationBounds(): Bounds? {
        val containerWidth = container.width
        val containerHeight = container.height
        val fabWidth = fab.width
        val fabHeight = fab.height
        if (containerWidth == 0 || containerHeight == 0 || fabWidth == 0 || fabHeight == 0) {
            return null
        }

        val margin = dp(MARGIN_DP)
        val travelX = (containerWidth - fabWidth - 2 * margin).coerceAtLeast(0f)
        val layoutTop = (containerHeight - fabHeight) / 2f
        val minTop = margin
        val maxTop = (containerHeight - margin - fabHeight).coerceAtLeast(margin)

        return Bounds(
            minX = -travelX,
            maxX = 0f,
            minY = minTop - layoutTop,
            maxY = maxTop - layoutTop
        )
    }

    /** 按保存的侧别与垂直比例把 FAB 定位到位；[animate] 为真时平滑吸附。 */
    private fun applySavedPosition(animate: Boolean) {
        if (imeVisible) return
        val containerWidth = container.width
        val containerHeight = container.height
        val fabWidth = fab.width
        val fabHeight = fab.height
        if (containerWidth == 0 || containerHeight == 0 || fabWidth == 0 || fabHeight == 0) {
            return
        }

        val margin = dp(MARGIN_DP)
        val travelX = (containerWidth - fabWidth - 2 * margin).coerceAtLeast(0f)
        val targetX = if (preferenceManager.fabSide == PreferenceManager.FAB_SIDE_LEFT) {
            -travelX
        } else {
            0f
        }

        val minTop = margin
        val maxTop = (containerHeight - margin - fabHeight).coerceAtLeast(margin)
        val layoutTop = (containerHeight - fabHeight) / 2f
        val usable = (maxTop - minTop).coerceAtLeast(1f)
        val ratio = preferenceManager.fabVerticalRatio.coerceIn(0f, 1f)
        val targetY = minTop + ratio * usable - layoutTop

        if (animate) {
            fab.animate()
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(SNAP_DURATION_MS)
                .start()
        } else {
            fab.translationX = targetX
            fab.translationY = targetY
        }
    }

    /** 松手后吸附到更近的一侧，并把位置落到偏好中。 */
    private fun snapToNearestSide() {
        val containerWidth = container.width
        val containerHeight = container.height
        val fabWidth = fab.width
        val fabHeight = fab.height
        if (containerWidth == 0 || containerHeight == 0 || fabWidth == 0 || fabHeight == 0) {
            return
        }

        val margin = dp(MARGIN_DP)
        // 右侧布局左边界为 containerWidth - margin - fabWidth，加上平移即为当前左边界
        val currentLeft = (containerWidth - margin - fabWidth) + fab.translationX
        val centerX = currentLeft + fabWidth / 2f
        preferenceManager.fabSide = if (centerX < containerWidth / 2f) {
            PreferenceManager.FAB_SIDE_LEFT
        } else {
            PreferenceManager.FAB_SIDE_RIGHT
        }

        val layoutTop = (containerHeight - fabHeight) / 2f
        val minTop = margin
        val maxTop = (containerHeight - margin - fabHeight).coerceAtLeast(margin)
        val usable = (maxTop - minTop).coerceAtLeast(1f)
        val ratio = ((layoutTop + fab.translationY) - minTop) / usable
        preferenceManager.fabVerticalRatio = ratio.coerceIn(0f, 1f)

        applySavedPosition(animate = true)
    }

    private fun scheduleFadeOut() {
        fab.removeCallbacks(fadeOutRunnable)
        fab.postDelayed(fadeOutRunnable, IDLE_DELAY_MS)
    }

    private fun cancelFade() {
        fab.removeCallbacks(fadeOutRunnable)
    }

    private fun applyIdleAlpha() {
        if (imeVisible) return
        fab.animate().alpha(IDLE_ALPHA).setDuration(FADE_DURATION_MS).start()
    }

    private fun dp(value: Int): Float = value * context.resources.displayMetrics.density

    private companion object {
        /** FAB 与屏幕边缘的留白。 */
        const val MARGIN_DP = 12

        /** 静止后降到的透明度，弱化对页面内容的干扰。 */
        const val IDLE_ALPHA = 0.35f

        /** 静止多久后开始淡出。 */
        const val IDLE_DELAY_MS = 2000L

        const val FADE_DURATION_MS = 400L
        const val SNAP_DURATION_MS = 180L
    }
}
