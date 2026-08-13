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
 * Transparent, touch-through overlay drawn on top of whatever app is on
  * screen. Colour encodes the category; a dashed outline means the aerial
   * (VisDrone) model found it, solid means the ground (COCO) model did.
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

             private val textPaint = Paint().apply {
                      color = Color.WHITE
                      textSize = 32f
                      isAntiAlias = true
                      setShadowLayer(4f, 0f, 0f, Color.BLACK)
             }

                 private val statusPaint = Paint().apply {
                          textSize = 28f
                          isAntiAlias = true
                          setShadowLayer(4f, 0f, 0f, Color.BLACK)
                 }

                     private fun colorFor(category: ThreatCategory): Int = when (category) {
                              ThreatCategory.HUMAN -> Color.rgb(255, 64, 64)
                                      ThreatCategory.VEHICLE -> Color.rgb(255, 210, 0)
                                              ThreatCategory.OTHER -> Color.rgb(90, 200, 255)
                                                      ThreatCategory.UNKNOWN -> Color.rgb(150, 150, 150)
                     }

                         override fun onDraw(canvas: Canvas) {
                                  super.onDraw(canvas)
                                          drawStatus(canvas)

                                                  if (sourceWidth <= 0 || sourceHeight <= 0) return
                                  val scaleX = width.toFloat() / sourceWidth
                                  val scaleY = height.toFloat() / sourceHeight

                                  for (detection in detections) {
                                               boxPaint.color = colorFor(detection.category)
                                                           boxPaint.pathEffect =
                                                   if (detection.source == DetectorSource.AERIAL) dashEffect else null

                                               val rect = RectF(
                                                                detection.box.left * scaleX,
                                                                detection.box.top * scaleY,
                                                                detection.box.right * scaleX,
                                                                detection.box.bottom * scaleY
                                                            )
                                                           canvas.drawRect(rect, boxPaint)

                                                                       val tag = if (detection.source == DetectorSource.AERIAL) "A" else "G"
                                               val label = detection.label + " " + (detection.confidence * 100).toInt() + "% [" + tag + "]"
                                               canvas.drawText(label, rect.left + 4f, (rect.top - 8f).coerceAtLeast(20f), textPaint)
                                  }
                         }

                             private fun drawStatus(canvas: Canvas) {
                                      val minPct = (DetectorConfig.minConfidence * 100).toInt()
                                              val ground = if (DetectorStatus.groundOk) DetectorStatus.groundCount.toString() else "off"
                                      val aerial = if (DetectorStatus.aerialOk) DetectorStatus.aerialCount.toString() else "n/a"

                                      val line = "AerialGuard " + DetectorStatus.VERSION + "  -  ground " + ground +
                                          "  -  aerial " + aerial + "  -  " + DetectorStatus.lastMs + "ms  -  min " + minPct + "%"

                                      statusPaint.color = when {
                                                   DetectorStatus.groundOk && DetectorStatus.aerialOk -> Color.rgb(102, 255, 153)
                                                               DetectorStatus.groundOk || DetectorStatus.aerialOk -> Color.rgb(255, 210, 0)
                                                                           else -> Color.rgb(255, 96, 96)
                                      }
                                              canvas.drawText(line, 24f, 92f, statusPaint)

                                                      if (!DetectorStatus.aerialOk) {
                                                                   statusPaint.color = Color.rgb(170, 190, 210)
                                                                               canvas.drawText(DetectorStatus.note, 24f, 128f, statusPaint)
                                                      }
                             }

                                 fun updateDetections(newDetections: List<Detection>, srcWidth: Int, srcHeight: Int) {
                                          detections = newDetections
                                          sourceWidth = srcWidth
                                          sourceHeight = srcHeight
                                          postInvalidate()
                                 }
}
