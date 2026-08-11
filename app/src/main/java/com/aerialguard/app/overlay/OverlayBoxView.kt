package com.aerialguard.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.aerialguard.app.detector.Detection
import com.aerialguard.app.detector.ThreatCategory

/**
 * Transparent, touch-through view drawn as a system overlay on top of
  * whatever app is currently on screen (i.e. your drone app). Boxes are
   * supplied in the pixel space of the analyzed frame and rescaled here to
    * the view's actual on-screen size.
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

         private val textPaint = Paint().apply {
                  color = Color.WHITE
                  textSize = 34f
                  isAntiAlias = true
                  setShadowLayer(4f, 0f, 0f, Color.BLACK)
         }

             private fun colorFor(category: ThreatCategory): Int = when (category) {
                      ThreatCategory.HUMAN -> Color.rgb(255, 64, 64)
                              ThreatCategory.VEHICLE -> Color.rgb(255, 210, 0)
                                      ThreatCategory.UNKNOWN -> Color.GRAY
             }

                 override fun onDraw(canvas: Canvas) {
                          super.onDraw(canvas)
                                  if (sourceWidth <= 0 || sourceHeight <= 0) return
                          val scaleX = width.toFloat() / sourceWidth
                          val scaleY = height.toFloat() / sourceHeight

                          for (detection in detections) {
                                       boxPaint.color = colorFor(detection.category)
                                                   val rect = RectF(
                                                                    detection.box.left * scaleX,
                                                                    detection.box.top * scaleY,
                                                                    detection.box.right * scaleX,
                                                                    detection.box.bottom * scaleY
                                                                )
                                                               canvas.drawRect(rect, boxPaint)
                                                                           val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"
                                       canvas.drawText(label, rect.left + 4f, (rect.top - 8f).coerceAtLeast(20f), textPaint)
                          }
                 }

                     fun updateDetections(newDetections: List<Detection>, srcWidth: Int, srcHeight: Int) {
                              detections = newDetections
                              sourceWidth = srcWidth
                              sourceHeight = srcHeight
                              postInvalidate()
                     }
}
