package com.example.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Visual feedback overlay that draws a stylish glowing highlight rectangle
 * over the exact UI element that Jarvis is tapping or typing into.
 */
class ElementHighlightOverlay(context: Context) : View(context) {

    private var targetRect: Rect? = null
    private val rectF = RectF()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#00F0FF") // Neon Cyan
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3300F0FF") // 20% transparent cyan
    }

    fun setHighlight(rect: Rect?) {
        targetRect = rect
        if (rect != null) {
            visibility = VISIBLE
            invalidate()
        } else {
            visibility = GONE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = targetRect ?: return
        if (r.isEmpty) return

        rectF.set(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        // Draw soft inner highlight
        canvas.drawRoundRect(rectF, 12f, 12f, fillPaint)
        // Draw crisp neon stroke
        canvas.drawRoundRect(rectF, 12f, 12f, boxPaint)
    }
}
