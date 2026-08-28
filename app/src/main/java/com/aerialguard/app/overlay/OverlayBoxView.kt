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
   * Colour encodes the category. Stroke encodes the source: solid for the
    * ground model, dashed for the aerial model, dotted for the military model.
     * A detection the resolution gate demoted is drawn thinner and its label
      * carries "?", so an uncertain call never looks like a confident one.
       */
class OverlayBoxView(context: Context) : View(context) {

     @Volatile private var detections: List<Detection> = emptyList()
         @Volatile private var sourceWidth: Int = 1
     @Volatile private var sourceHeight: Int = 1

     private val boxPaint = Paint().apply {
              style = Paint.Style.STROKE
              strokeWidth = 5f
              isAntiAlias = true
     }

         private val dashEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
             private val dotEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)

                 private val textPaint = Paint().apply {
                          color = Color.WHITE
                          textSize = 32f
                          isAntiAlias = true
                          setShadowLayer(4f, 0f, 0f, Color.BLACK)
                 }

                     private val statusPaint = Paint().apply {
                              textSize = 27f
                              isAntiAlias = true
                              setShadowLayer(4f, 0f, 0f, Color.BLACK)
                     }

                         private fun colorFor(category: ThreatCategory): Int = when (category) {
                                  ThreatCategory.HUMAN -> Color.rgb(255, 64, 64)
                                          ThreatCategory.VEHICLE -> Color.rgb(255, 210, 0)
                                                  ThreatCategory.MILITARY -> Color.rgb(255, 122, 26)
                                                          ThreatCategory.OTHER -> Color.rgb(90, 200, 255)
                                                                  ThreatCategory.UNKNOWN -> Color.rgb(150, 150, 150)
                         }

                             override fun onDraw(canvas: Canvas) {
                                      super.onDraw(canvas)
                                              drawStatus(canvas)

                                                      if (sourceWidth <= 0 || sourceHeight <= 0) return
                                      val scaleX = width.toFloat() / sourceWidth
                                      val scaleY = height.toFloat() / sourceHeight

                                      for (d in detections) {
                                                   boxPaint.color = colorFor(d.category)
                                                               boxPaint.pathEffect = when (d.source) {
                                                                                DetectorSource.AERIAL -> dashEffect
                                                                                DetectorSource.MILITARY -> dotEffect
                                                                                else -> null
                                                               }
                                                                           boxPaint.strokeWidth = if (d.gated) 2.5f else 5f

                                                   val rect = RectF(
                                                                    d.box.left * scaleX, d.box.top * scaleY,
                                                                    d.box.right * scaleX, d.box.bottom * scaleY
                                                                )
                                                               canvas.drawRect(rect, boxPaint)

                                                                           val tag = when (d.source) {
                                                                                            DetectorSource.AERIAL -> "A"
                                                                                            DetectorSource.MILITARY -> "M"
                                                                                            else -> "G"
                                                                           }
                                                                                       textPaint.color = if (d.gated) Color.rgb(200, 210, 220) else Color.WHITE
                                                   textPaint.textSize = if (d.gated) 26f else 32f
                                                   val label = d.label + " " + (d.confidence * 100).toInt() + "% [" + tag + "]"
                                                   canvas.drawText(label, rect.left + 4f, (rect.top - 8f).coerceAtLeast(20f), textPaint)
                                      }
                             }

                                 private fun drawStatus(canvas: Canvas) {
                                          val minPct = (DetectorConfig.minConfidence * 100).toInt()
                                                  val g = if (DetectorStatus.groundOk) DetectorStatus.groundCount.toString() else "n/a"
                                          val a = if (DetectorStatus.aerialOk) DetectorStatus.aerialCount.toString() else "n/a"
                                          val m = if (DetectorStatus.militaryOk) DetectorStatus.militaryCount.toString() else "n/a"

                                          val line = "AerialGuard " + DetectorStatus.VERSION +
                                              "  -  ground " + g + "  -  aerial " + a + "  -  mil " + m +
                                              "  -  " + DetectorStatus.lastMs + "ms  -  min " + minPct + "%"

                                          statusPaint.color = when {
                                                       DetectorStatus.groundOk && DetectorStatus.aerialOk && DetectorStatus.militaryOk ->
                                                           Color.rgb(102, 255, 153)
                                                                       DetectorStatus.groundOk || DetectorStatus.aerialOk || DetectorStatus.militaryOk ->
                                                           Color.rgb(255, 210, 0)
                                                                       else -> Color.rgb(255, 96, 96)
                                          }
                                                  canvas.drawText(line, 24f, 88f, statusPaint)

                                                          if (DetectorStatus.gatedCount > 0) {
                                                                       statusPaint.color = Color.rgb(255, 122, 26)
                                                                                   canvas.drawText(
                                                                                                    DetectorStatus.gatedCount.toString() +
                                                                                                         " too small to classify - showing coarse label only",
                                                                                                    24f, 124f, statusPaint
                                                                                                )
                                                          } else if (!DetectorStatus.aerialOk || !DetectorStatus.militaryOk) {
                                                                       statusPaint.color = Color.rgb(170, 190, 210)
                                                                                   canvas.drawText(DetectorStatus.note, 24f, 124f, statusPaint)
                                                          }
                                 }

                                     fun updateDetections(newDetections: List<Detection>, srcWidth: Int, srcHeight: Int) {
                                              detections = newDetections
                                              sourceWidth = srcWidth
                                              sourceHeight = srcHeight
                                              postInvalidate()
                                     }
}
