package com.example.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.persona.PersonaType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class FloatingOrbCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isListening: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var isProcessing: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var isAsleep: Boolean = false
        set(value) {
            field = value
            alpha = if (value) 0.55f else 1.0f
            invalidate()
        }

    var persona: PersonaType = PersonaType.GIRLFRIEND
        set(value) {
            field = value
            updateColors()
            invalidate()
        }

    private var rotationAngle = 0f
    private var pulseRadius = 1f

    private var primaryColor = Color.parseColor("#EC4899") // Pink default
    private var glowColor = Color.parseColor("#F472B6")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#090D16")
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val centerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var rotationAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        updateColors()
        startAnimations()
    }

    private fun updateColors() {
        when (persona) {
            PersonaType.GIRLFRIEND -> {
                primaryColor = Color.parseColor("#EC4899") // Pink
                glowColor = Color.parseColor("#F472B6")
            }
            PersonaType.PROFESSIONAL -> {
                primaryColor = Color.parseColor("#00F0FF") // Cyan
                glowColor = Color.parseColor("#38BDF8")
            }
            PersonaType.BOLD -> {
                primaryColor = Color.parseColor("#F97316") // Orange
                glowColor = Color.parseColor("#FB923C")
            }
        }
        if (isListening) {
            primaryColor = Color.parseColor("#EF4444") // Red active mic
            glowColor = Color.parseColor("#F87171")
        }
        ringPaint.color = primaryColor
        arcPaint.color = primaryColor
        centerCorePaint.color = Color.WHITE
    }

    private fun startAnimations() {
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        pulseAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulseRadius = it.animatedValue as Float
                if (isListening || isProcessing) {
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateColors()

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(centerX, centerY) * 0.85f

        // Outer glow
        val glowRad = if (isListening) radius * pulseRadius else radius
        glowPaint.shader = RadialGradient(
            centerX, centerY, glowRad,
            intArrayOf(glowColor, Color.TRANSPARENT),
            floatArrayOf(0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, glowRad, glowPaint)

        // Dark circular backing
        canvas.drawCircle(centerX, centerY, radius * 0.8f, bgPaint)

        // Outer Tech Ring
        ringPaint.alpha = if (isListening) 255 else 180
        canvas.drawCircle(centerX, centerY, radius * 0.72f, ringPaint)

        // Rotating Arc segments
        canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)
        val rectF = RectF(
            centerX - radius * 0.62f,
            centerY - radius * 0.62f,
            centerX + radius * 0.62f,
            centerY + radius * 0.62f
        )
        arcPaint.color = primaryColor
        canvas.drawArc(rectF, 0f, 80f, false, arcPaint)
        canvas.drawArc(rectF, 120f, 80f, false, arcPaint)
        canvas.drawArc(rectF, 240f, 80f, false, arcPaint)
        canvas.restore()

        // Inner pulsing core
        val coreRad = (radius * 0.32f) * if (isListening) pulseRadius else 1.0f
        centerCorePaint.shader = RadialGradient(
            centerX, centerY, coreRad,
            intArrayOf(Color.WHITE, primaryColor),
            floatArrayOf(0.2f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, coreRad, centerCorePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotationAnimator?.cancel()
        pulseAnimator?.cancel()
    }
}
