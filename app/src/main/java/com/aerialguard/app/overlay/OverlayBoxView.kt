package com.aerialguard.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.aerialguard.app.detector.Detection
import com.aerialguard.app.detector.DetectorConfig
import com.aerialguard.app.detector.DetectorSource
import com.aerialguard.app.detector.DetectorStatus
import com.aerialguard.app.detector.ThreatCategory

/**
 * Transparent, touch-through overlay drawn above whatever app is on screen.
 *
 * Boxes are drawn to stay readable over bright, noisy video: a dark outer
 * stroke carries the coloured stroke so the box survives on a white or sandy
 * background, corner brackets mark the extents, and the label sits in a solid
 * chip rather than floating as thin text.
 *
 * Colour encodes the category. Stroke encodes the source: solid for the
 * ground model, dashed for the aerial model, dotted for the military model.
 */
class OverlayBoxView(context: Context) : View(context) {

    @Volatile private var detections: List<Detection> = emptyList()
    @Volatile private var sourceWidth: Int = 1
    @Volatile private var sourceHeight: Int = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val haloPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.argb(150, 0, 0, 0)
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val chipPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dashEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
    private val dotEffect = DashPathEffect(floatArrayOf(6f, 9f), 0f)

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 36f
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val statusPaint = Paint().apply {
        textSize = 27f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun colorFor(category: ThreatCategory): Int = when (category) {
        ThreatCategory.HUMAN -> Color.rgb(255, 45, 45)
        ThreatCategory.VEHICLE -> Color.rgb(255, 215, 0)
        ThreatCategory.MILITARY -> Color.rgb(255, 110, 0)
        ThreatCategory.OTHER -> Color.rgb(90, 200, 255)
        ThreatCategory.UNKNOWN -> Color.rgb(170, 170, 170)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawStatus(canvas)

        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight

        for (d in detections) {
            val accent = colorFor(d.category)

            val rect = RectF(
                d.box.left * scaleX, d.box.top * scaleY,
                d.box.right * scaleX, d.box.bottom * scaleY
            )

            haloPaint.strokeWidth = if (d.gated) 10f else 14f
            canvas.drawRect(rect, haloPaint)

            boxPaint.color = accent
            boxPaint.pathEffect = when (d.source) {
                DetectorSource.AERIAL -> dashEffect
                DetectorSource.MILITARY -> dotEffect
                else -> null
            }
            boxPaint.strokeWidth = if (d.gated) 5f else 8f
            canvas.drawRect(rect, boxPaint)

            drawCorners(canvas, rect, accent)

            val tag = when (d.source) {
                DetectorSource.AERIAL -> "A"
                DetectorSource.MILITARY -> "M"
                else -> "G"
            }
            val label = d.label.uppercase() + "  " + (d.confidence * 100).toInt() + "%  [" + tag + "]"
            drawLabelChip(canvas, rect, label, accent)
        }
    }

    private fun drawCorners(canvas: Canvas, rect: RectF, accent: Int) {
        val arm = minOf(rect.width(), rect.height()) * 0.28f
        if (arm < 6f) return
        cornerPaint.color = accent
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        canvas.drawLine(l, t, l + arm, t, cornerPaint)
        canvas.drawLine(l, t, l, t + arm, cornerPaint)
        canvas.drawLine(r, t, r - arm, t, cornerPaint)
        canvas.drawLine(r, t, r, t + arm, cornerPaint)
        canvas.drawLine(l, b, l + arm, b, cornerPaint)
        canvas.drawLine(l, b, l, b - arm, cornerPaint)
        canvas.drawLine(r, b, r - arm, b, cornerPaint)
        canvas.drawLine(r, b, r, b - arm, cornerPaint)
    }

    private fun drawLabelChip(canvas: Canvas, rect: RectF, label: String, accent: Int) {
        textPaint.textSize = 36f
        val textW = textPaint.measureText(label)
        val chipH = 46f
        val padding = 12f

        var chipTop = rect.top - chipH - 4f
        if (chipTop < 4f) chipTop = rect.top + 4f
        var chipLeft = rect.left
        if (chipLeft + textW + padding * 2 > width) chipLeft = width - textW - padding * 2
        if (chipLeft < 0f) chipLeft = 0f

        val chip = RectF(chipLeft, chipTop, chipLeft + textW + padding * 2, chipTop + chipH)
        chipPaint.color = accent
        canvas.drawRoundRect(chip, 8f, 8f, chipPaint)

        textPaint.color = Color.BLACK
        canvas.drawText(label, chip.left + padding, chip.bottom - 14f, textPaint)
    }

    private fun drawStatus(canvas: Canvas) {
        val minPct = (DetectorConfig.minConfidence * 100).toInt()
        val m = if (DetectorStatus.militaryOk) DetectorStatus.militaryCount.toString() else "n/a"
        val a = if (DetectorStatus.aerialOk) DetectorStatus.aerialCount.toString() else "n/a"
        val g = if (DetectorStatus.groundOk) DetectorStatus.groundCount.toString() else "n/a"

        val line = "NS NETRA " + DetectorStatus.VERSION +
            "  -  mil " + m + "  -  aerial " + a + "  -  ground " + g +
            "  -  " + DetectorStatus.lastMs + "ms  -  min " + minPct + "%"

        statusPaint.color = when {
            DetectorStatus.militaryOk && DetectorStatus.aerialOk && DetectorStatus.groundOk ->
                Color.rgb(102, 255, 153)
            DetectorStatus.militaryOk || DetectorStatus.aerialOk || DetectorStatus.groundOk ->
                Color.rgb(255, 210, 0)
            else -> Color.rgb(255, 96, 96)
        }
        canvas.drawText(line, 24f, 88f, statusPaint)

        if (DetectorStatus.militaryDebug.isNotEmpty()) {
            statusPaint.color = Color.rgb(255, 200, 120)
            canvas.drawText("M: " + DetectorStatus.militaryDebug, 24f, 124f, statusPaint)
        }
        if (DetectorStatus.aerialDebug.isNotEmpty()) {
            statusPaint.color = Color.rgb(150, 210, 255)
            canvas.drawText("A: " + DetectorStatus.aerialDebug, 24f, 158f, statusPaint)
        }
        if (!DetectorStatus.militaryOk || !DetectorStatus.aerialOk) {
            statusPaint.color = Color.rgb(170, 190, 210)
            canvas.drawText(DetectorStatus.note, 24f, 192f, statusPaint)
        }
    }

    fun updateDetections(newDetections: List<Detection>, srcWidth: Int, srcHeight: Int) {
        detections = newDetections
        sourceWidth = srcWidth
        sourceHeight = srcHeight
        postInvalidate()
    }
}
