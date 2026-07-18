package com.hwanghj09.sonju.accessibility

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/** 화면 제어 중임을 알리는 터치 불가 다섯 색상 가장자리 안개 효과입니다. */
internal class ScreenControlGlowView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val glowColors = intArrayOf(
        Color.rgb(108, 132, 235), // #6C84EB
        Color.rgb(182, 226, 225), // #B6E2E1
        Color.rgb(214, 245, 181), // #D6F5B5
        Color.rgb(230, 194, 150), // #E6C296
        Color.rgb(242, 231, 129), // #F2E781
    )
    private val glowPositions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val topFogPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomFogPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leftFogPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rightFogPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private var horizontalFogDepth = 0f
    private var verticalFogDepth = 0f
    private var pulse = 0.78f
    private val animator = ValueAnimator.ofFloat(0.7f, 1f).apply {
        duration = 1_350L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == 0 || height == 0) return

        horizontalFogDepth = min(width * 0.34f, 150f * density)
        verticalFogDepth = min(height * 0.22f, 150f * density)
        val horizontalColors = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            0f,
            glowColors,
            glowPositions,
            Shader.TileMode.CLAMP,
        )
        val verticalColors = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            glowColors,
            glowPositions,
            Shader.TileMode.CLAMP,
        )
        topFogPaint.shader = composeFog(
            horizontalColors,
            LinearGradient(
                0f, 0f, 0f, verticalFogDepth,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            ),
        )
        bottomFogPaint.shader = composeFog(
            horizontalColors,
            LinearGradient(
                0f, height.toFloat(), 0f, height - verticalFogDepth,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            ),
        )
        leftFogPaint.shader = composeFog(
            verticalColors,
            LinearGradient(
                0f, 0f, horizontalFogDepth, 0f,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            ),
        )
        rightFogPaint.shader = composeFog(
            verticalColors,
            LinearGradient(
                width.toFloat(), 0f, width - horizontalFogDepth, 0f,
                Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
            ),
        )
        borderPaint.shader = SweepGradient(
            width / 2f,
            height / 2f,
            glowColors + glowColors.first(),
            glowPositions + 1f,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val fogAlpha = (112 * pulse).toInt().coerceIn(0, 255)
        topFogPaint.alpha = fogAlpha
        bottomFogPaint.alpha = fogAlpha
        leftFogPaint.alpha = fogAlpha
        rightFogPaint.alpha = fogAlpha
        borderPaint.alpha = (235 * pulse).toInt().coerceIn(0, 255)

        canvas.drawRect(0f, 0f, width.toFloat(), verticalFogDepth, topFogPaint)
        canvas.drawRect(
            0f,
            height - verticalFogDepth,
            width.toFloat(),
            height.toFloat(),
            bottomFogPaint,
        )
        canvas.drawRect(0f, 0f, horizontalFogDepth, height.toFloat(), leftFogPaint)
        canvas.drawRect(
            width - horizontalFogDepth,
            0f,
            width.toFloat(),
            height.toFloat(),
            rightFogPaint,
        )

        val inset = 2.5f * density
        val border = RectF(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(border, 30f * density, 30f * density, borderPaint)
    }

    private fun composeFog(colorShader: Shader, alphaShader: Shader): Shader =
        ComposeShader(colorShader, alphaShader, PorterDuff.Mode.DST_IN)
}
