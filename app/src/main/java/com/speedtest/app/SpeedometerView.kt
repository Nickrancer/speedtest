package com.speedtest.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxSpeed = 1000f
    private var currentSpeed = 0f
    private var animatedAngle = START_ANGLE

    // Colors
    private val colorBackground = Color.parseColor("#0A0E1A")
    private val colorTrack = Color.parseColor("#1A2040")
    private val colorGlow = Color.parseColor("#00B4FF")
    private val colorNeedle = Color.parseColor("#00E5FF")
    private val colorText = Color.WHITE
    private val colorSubText = Color.parseColor("#8899BB")

    // Paints
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        color = colorTrack
        strokeCap = Paint.Cap.ROUND
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = colorNeedle
        strokeCap = Paint.Cap.ROUND
    }

    private val needleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.argb(60, 0, 229, 255)
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorNeedle
    }

    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorSubText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#334466")
        strokeCap = Paint.Cap.ROUND
    }

    private val glowArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 30f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(25f, BlurMaskFilter.Blur.NORMAL)
    }

    private val pulseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var pulseAlpha = 0f
    private var pulseScale = 0.8f
    private var isAnimating = false

    companion object {
        const val START_ANGLE = 150f
        const val SWEEP_ANGLE = 240f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) / 2f) * 0.75f

        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // Draw pulse rings when animating
        if (isAnimating) {
            drawPulseRings(canvas, cx, cy, radius)
        }

        // Draw outer decorative rings
        drawDecorativeRings(canvas, cx, cy, radius)

        // Draw track background
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // Draw speed arc with gradient
        val sweepProgress = (animatedAngle - START_ANGLE).coerceIn(0f, SWEEP_ANGLE)
        if (sweepProgress > 0) {
            val gradient = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.parseColor("#0033FF"),
                    Color.parseColor("#0099FF"),
                    Color.parseColor("#00CCFF"),
                    Color.parseColor("#00E5FF")
                ),
                floatArrayOf(0f, 0.33f, 0.66f, 1f)
            )
            val matrix = Matrix()
            matrix.setRotate(START_ANGLE, cx, cy)
            gradient.setLocalMatrix(matrix)
            arcPaint.shader = gradient

            // Glow effect arc
            val glowGradient = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.argb(80, 0, 51, 255),
                    Color.argb(120, 0, 229, 255)
                ),
                floatArrayOf(0f, 1f)
            )
            glowGradient.setLocalMatrix(matrix)
            glowArcPaint.shader = glowGradient

            canvas.drawArc(arcRect, START_ANGLE, sweepProgress, false, glowArcPaint)
            canvas.drawArc(arcRect, START_ANGLE, sweepProgress, false, arcPaint)
        }

        // Draw tick marks
        drawTickMarks(canvas, cx, cy, radius)

        // Draw needle
        drawNeedle(canvas, cx, cy, radius)

        // Draw speed text
        val textRadius = radius * 0.15f
        val speedFontSize = radius * 0.38f
        speedTextPaint.textSize = speedFontSize
        canvas.drawText(String.format("%.1f", currentSpeed), cx, cy + textRadius, speedTextPaint)

        unitTextPaint.textSize = radius * 0.13f
        canvas.drawText("Mbps", cx, cy + textRadius + radius * 0.18f, unitTextPaint)
    }

    private fun drawDecorativeRings(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#111830")
            strokeWidth = 2f
        }
        for (i in 1..3) {
            val ringRadius = radius + i * 18f
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        }
    }

    private fun drawPulseRings(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val alpha = (pulseAlpha * 255).toInt().coerceIn(0, 255)
        pulseRingPaint.color = Color.argb(alpha, 0, 180, 255)
        pulseRingPaint.strokeWidth = 2f
        val pulseRadius = radius * (0.9f + pulseScale * 0.4f)
        canvas.drawCircle(cx, cy, pulseRadius, pulseRingPaint)
    }

    private fun drawTickMarks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val majorCount = 10
        val minorCount = 5
        val totalTicks = majorCount * minorCount

        for (i in 0..totalTicks) {
            val angle = Math.toRadians((START_ANGLE + i * SWEEP_ANGLE / totalTicks).toDouble())
            val isMajor = i % minorCount == 0
            val innerRadius = if (isMajor) radius - 28f else radius - 18f
            val outerRadius = radius - 10f

            tickPaint.strokeWidth = if (isMajor) 3f else 1.5f
            tickPaint.color = if (isMajor) Color.parseColor("#445577") else Color.parseColor("#223355")

            val startX = cx + innerRadius * cos(angle).toFloat()
            val startY = cy + innerRadius * sin(angle).toFloat()
            val endX = cx + outerRadius * cos(angle).toFloat()
            val endY = cy + outerRadius * sin(angle).toFloat()

            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val angle = Math.toRadians(animatedAngle.toDouble())
        val needleLength = radius * 0.7f
        val endX = cx + needleLength * cos(angle).toFloat()
        val endY = cy + needleLength * sin(angle).toFloat()
        val tailX = cx + radius * 0.12f * cos(angle + Math.PI).toFloat()
        val tailY = cy + radius * 0.12f * sin(angle + Math.PI).toFloat()

        canvas.drawLine(tailX, tailY, endX, endY, needleGlowPaint)
        canvas.drawLine(tailX, tailY, endX, endY, needlePaint)

        // Center pivot dot
        canvas.drawCircle(cx, cy, 12f, centerDotPaint)
        val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorBackground
        }
        canvas.drawCircle(cx, cy, 6f, innerDotPaint)
    }

    fun setSpeed(speed: Float, animate: Boolean = true) {
        currentSpeed = speed.coerceIn(0f, maxSpeed)
        val targetAngle = START_ANGLE + (currentSpeed / maxSpeed) * SWEEP_ANGLE

        if (animate) {
            val animator = ObjectAnimator.ofFloat(this, "animatedAngle", animatedAngle, targetAngle)
            animator.duration = 800
            animator.interpolator = DecelerateInterpolator(1.5f)
            animator.start()
        } else {
            animatedAngle = targetAngle
            invalidate()
        }
    }

    fun setAnimating(animating: Boolean) {
        isAnimating = animating
        if (animating) {
            startPulseAnimation()
        }
    }

    private fun startPulseAnimation() {
        if (!isAnimating) return
        val alphaAnim = ObjectAnimator.ofFloat(this, "pulseAlpha", 0.6f, 0f)
        val scaleAnim = ObjectAnimator.ofFloat(this, "pulseScale", 0f, 1f)
        val set = AnimatorSet()
        set.playTogether(alphaAnim, scaleAnim)
        set.duration = 1500
        set.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (isAnimating) startPulseAnimation()
            }
        })
        set.start()
    }

    @Suppress("unused")
    fun setAnimatedAngle(angle: Float) {
        animatedAngle = angle
        invalidate()
    }

    @Suppress("unused")
    fun setPulseAlpha(alpha: Float) {
        pulseAlpha = alpha
        invalidate()
    }

    @Suppress("unused")
    fun setPulseScale(scale: Float) {
        pulseScale = scale
    }
}
