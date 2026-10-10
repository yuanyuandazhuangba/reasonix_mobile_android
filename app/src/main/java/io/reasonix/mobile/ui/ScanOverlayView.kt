package io.reasonix.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000") // 60% 黑色遮罩
    }

    private val transparentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44E9B456") // 淡金色边框
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E9B456") // Reasonix 标志性金色
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E9B456")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val frameRect = RectF()
    private val cornerLength = 48f
    private var laserPosition = 0f
    private var laserAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = (w.coerceAtMost(h) * 0.70f)
        val left = (w - size) / 2f
        val top = (h - size) / 2f - 60f
        frameRect.set(left, top, left + size, top + size)

        startLaserAnimation()
    }

    private fun startLaserAnimation() {
        laserAnimator?.cancel()
        laserAnimator = ValueAnimator.ofFloat(frameRect.top, frameRect.bottom).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                laserPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 绘制外部遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // 2. 抠出中间取景透明区
        canvas.drawRoundRect(frameRect, 16f, 16f, transparentPaint)

        // 3. 绘制框线
        canvas.drawRoundRect(frameRect, 16f, 16f, borderPaint)

        // 4. 绘制四个角的装饰角标
        val l = frameRect.left
        val t = frameRect.top
        val r = frameRect.right
        val b = frameRect.bottom

        // 左上角
        canvas.drawLine(l, t + cornerLength, l, t, cornerPaint)
        canvas.drawLine(l, t, l + cornerLength, t, cornerPaint)

        // 右上角
        canvas.drawLine(r - cornerLength, t, r, t, cornerPaint)
        canvas.drawLine(r, t, r, t + cornerLength, cornerPaint)

        // 左下角
        canvas.drawLine(l, b - cornerLength, l, b, cornerPaint)
        canvas.drawLine(l, b, l + cornerLength, b, cornerPaint)

        // 右下角
        canvas.drawLine(r - cornerLength, b, r, b, cornerPaint)
        canvas.drawLine(r, b, r, b - cornerLength, cornerPaint)

        // 5. 绘制扫描激光线
        if (laserPosition in frameRect.top..frameRect.bottom) {
            canvas.drawLine(
                frameRect.left + 10f,
                laserPosition,
                frameRect.right - 10f,
                laserPosition,
                laserPaint
            )
        }
    }

    override fun onDetachedFromWindow() {
        laserAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
