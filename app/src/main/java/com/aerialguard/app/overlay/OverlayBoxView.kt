package com.aerialguard.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.aerialguard.app.detector.Detection
import com.aerialguard.app.detector.DetectorStatus
import com.aerialguard.app.detector.ThreatCategory

/**
 * Transparent, touch-through view drawn as a system overlay on top of
  * whatever app is currently on screen (i.e. your drone app). Boxes are
   * supplied in the pixel space of the analyzed frame and rescaled here to
    * the view's actual on-screen size.
     *
      * A small status line is drawn at the top. It exists so it is always obvious
       * whether the model actually loaded, which build is running, and how many
        * objects the last frame produced -- if something is wrong, that line says so
         * instead of the app just silently drawing nothing.
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

             private val statusPaint = Paint().apply {
                      textSize = 30f
                      isAntiAlias = true
                      setShadowLayer(4f, 0f, 0f, Color.BLACK)
             }

                 private fun colorFor(category: ThreatCategory): Int = when (category) {
                          ThreatCategory.HUMAN -> Color.rgb(255, 64, 64)
                                  ThreatCategory.VEHICLE -> Color.rgb(255, 210, 0)
                                          ThreatCategory.UNKNOWN -> Color.rgb(150, 150, 150)
                 }

                     override fun onDraw(canvas: Canvas) {
                              super.onDraw(canvas)
                                      drawStatus(canvas)

                                              if (sourceWidth <= 0 || sourceHeight <= 0) return
                              val scaleX = width.toFloat() / sourceWidth
                              val scaleY = height.toFloat() / sourceHeight

                              for (detection in detections) {
                                           val isPrimary = detection.category != ThreatCategory.UNKNOWN
                                           boxPaint.color = colorFor(detection.category)
                                                       boxPaint.strokeWidth = if (isPrimary) 5f else 2f

                                           val rect = RectF(
                                                            detection.box.left * scaleX,
                                                            detection.box.top * scaleY,
                                                            detection.box.right * scaleX,
                                                            detection.box.bottom * scaleY
                                                        )
                                                       canvas.drawRect(rect, boxPaint)

                                                                   textPaint.color = if (isPrimary) Color.WHITE else Color.rgb(190, 190, 190)
                                                                               textPaint.textSize = if (isPrimary) 34f else 26f
                                           val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"
                                           canvas.drawText(label, rect.left + 4f, (rect.top - 8f).coerceAtLeast(20f), textPaint)
                              }
                     }

                         private fun drawStatus(canvas: Canvas) {
                                  val line = if (DetectorStatus.modelOk) {
                                               "AerialGuard ${DetectorStatus.VERSION}  -  ${DetectorStatus.lastRawCount} objects  -  ${DetectorStatus.lastMs}ms"
                                  } else {
                                               "AerialGuard ${DetectorStatus.VERSION}  -  MODEL NOT LOADED: ${DetectorStatus.note}"
                                  }
                                          statusPaint.color = if (DetectorStatus.modelOk) Color.rgb(102, 255, 153) else Color.rgb(255, 96, 96)
                                                  canvas.drawText(line, 24f, 96f, statusPaint)
                         }

                             fun updateDetections(newDetections: List<Detection>, srcWidth: Int, srcHeight: Int) {
                                      detections = newDetections
                                      sourceWidth = srcWidth
                                      sourceHeight = srcHeight
                                      postInvalidate()
                             }
}
