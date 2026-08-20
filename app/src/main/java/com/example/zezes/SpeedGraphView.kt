package com.example.zezes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList
import kotlin.math.max

class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val speedPoints = LinkedList<Double>()
    private val maxPoints = 50
    private var accentColor = -16711681
    private var gridColor = -7829368

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        pathEffect = DashPathEffect(floatArrayOf(10.0f, 10.0f), 0.0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24.0f
        typeface = Typeface.MONOSPACE
    }

    private val path = Path()
    private val fillPath = Path()

    fun addSpeedPoint(speed: Double) {
        speedPoints.add(speed)
        if (speedPoints.size > maxPoints) {
            speedPoints.removeFirst()
        }
        invalidate()
    }

    fun setGraphColor(accent: Int, grid: Int) {
        accentColor = accent
        gridColor = grid
        linePaint.color = accent
        textPaint.color = grid
        gridPaint.color = Color.argb(100, Color.red(grid), Color.green(grid), Color.blue(grid))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (speedPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val maxSpeed = max(speedPoints.maxOrNull() ?: 1.0, 1.0)
        val avgSpeed = speedPoints.average()
        val padding = 40.0f
        val drawH = h - (padding * 2.0f)
        val drawW = w - (padding * 2.0f)

        drawGridLine(canvas, maxSpeed, maxSpeed, String.format("Peak: %.1f MB/s", maxSpeed), padding, drawH, drawW)
        drawGridLine(canvas, avgSpeed, maxSpeed, String.format("Avg: %.1f MB/s", avgSpeed), padding, drawH, drawW)

        path.reset()
        fillPath.reset()
        val stepX = drawW / (maxPoints - 1)

        speedPoints.forEachIndexed { i, speed ->
            val x = (i * stepX) + padding
            val y = (padding + drawH) - (drawH * (speed / maxSpeed)).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h - padding)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }

            if (i == speedPoints.size - 1) {
                fillPath.lineTo(x, h - padding)
                fillPath.close()
            }
        }

        val gradient = LinearGradient(
            0.0f,
            padding,
            0.0f,
            h - padding,
            Color.argb(100, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
            0,
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = gradient
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }

    private fun drawGridLine(
        canvas: Canvas,
        value: Double,
        max: Double,
        label: String,
        padding: Float,
        drawH: Float,
        drawW: Float
    ) {
        val y = (padding + drawH) - ((value / max) * drawH).toFloat()
        canvas.drawLine(padding, y, padding + drawW, y, gridPaint)
        canvas.drawText(label, padding + 10.0f, y - 10.0f, textPaint)
    }
}
