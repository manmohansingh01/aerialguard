package com.aerialguard.app.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TfliteObjectDetector

/**
 * Live status of the detector, read by the overlay so the on-screen HUD can
  * show whether the model actually loaded and how many objects the last frame
   * produced. Deliberately global (one detector, one process) so no extra
    * plumbing is needed between the service and the overlay view.
     */
object DetectorStatus {
     @Volatile var modelOk = false
     @Volatile var note = "starting"
     @Volatile var lastRawCount = 0
     @Volatile var lastMs = 0L
     const val VERSION = "2.0"
}

/**
 * Object detector built on the TensorFlow Lite Task Library.
  *
   * Why the Task Library instead of a raw Interpreter: the model file ships with
    * embedded metadata that includes its own label map, so class ids are resolved
     * to names by the library itself. The previous hand-rolled version indexed a
      * separate labelmap.txt by hand, which is what made every car report as
       * "bicycle"/"motorcycle" -- that whole class of bug is now impossible.
        *
         * Two other accuracy fixes live here:
          *
           *  - Square tiling instead of squashing. A phone screen is very tall (e.g.
            *    1080x2400). Resizing that straight into the model's square input
             *    stretched everything horizontally by more than 2x, which wrecks
              *    detection. Instead the frame is cut into overlapping square tiles, each
               *    fed to the model at its native aspect ratio, and results are merged.
                *  - A stronger model (EfficientDet-Lite2) and a lower score threshold, since
                 *    screen-captured, re-compressed video is harder than clean photos.
                  */
                  class ObjectDetector(context: Context) {

                       companion object {
                                private const val TILE_PX = 448
                                private const val SCORE_THRESHOLD = 0.30f
                                private const val MAX_RESULTS_PER_TILE = 25
                                private const val MERGE_IOU = 0.55f
                       }

                           private val detector: TfliteObjectDetector

                       private val tileBitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
                           private val tileCanvas = Canvas(tileBitmap)
                               private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
                                   private val destRect = Rect(0, 0, TILE_PX, TILE_PX)

                                       private val vehicleLabels = setOf(
                                                "car", "truck", "bus", "motorcycle", "bicycle", "train", "boat"
                                            )

                                           init {
                                                    val baseOptions = BaseOptions.builder()
                                                                .setNumThreads(4)
                                                                            .build()
                                                                                    val options = TfliteObjectDetector.ObjectDetectorOptions.builder()
                                                                                                .setBaseOptions(baseOptions)
                                                                                                            .setMaxResults(MAX_RESULTS_PER_TILE)
                                                                                                                        .setScoreThreshold(SCORE_THRESHOLD)
                                                                                                                                    .build()
                                                                                                                                            detector = TfliteObjectDetector.createFromFileAndOptions(context, "detect.tflite", options)
                                                                                                                                                    DetectorStatus.modelOk = true
                                                    DetectorStatus.note = "model loaded"
                                           }

                                               fun detect(bitmap: Bitmap): List<Detection> {
                                                        val startedAt = SystemClock.elapsedRealtime()

                                                                val width = bitmap.width
                                                        val height = bitmap.height
                                                        if (width < 2 || height < 2) return emptyList()

                                                                val shortSide = minOf(width, height)
                                                                        val longSide = maxOf(width, height)
                                                                                val landscape = width >= height

                                                        val tileCount = maxOf(1, (longSide + shortSide - 1) / shortSide)
                                                                val step = if (tileCount == 1) 0 else (longSide - shortSide) / (tileCount - 1)

                                                                        val collected = mutableListOf<Detection>()

                                                                                for (i in 0 until tileCount) {
                                                                                             val offset = if (tileCount == 1) 0 else i * step
                                                                                             val srcRect = if (landscape) {
                                                                                                              Rect(offset, 0, offset + shortSide, shortSide)
                                                                                             } else {
                                                                                                              Rect(0, offset, shortSide, offset + shortSide)
                                                                                             }

                                                                                                         tileCanvas.drawBitmap(bitmap, srcRect, destRect, tilePaint)
                                                                                                         
                                                                                                                     val raw = try {
                                                                                                                                      detector.detect(TensorImage.fromBitmap(tileBitmap))
                                                                                                                     } catch (e: Exception) {
                                                                                                                                      DetectorStatus.note = "detect failed: ${e.message}"
                                                                                                                                      emptyList()
                                                                                                                     }
                                                                                                                     
                                                                                                                                 val backScale = shortSide.toFloat() / TILE_PX

                                                                                             for (result in raw) {
                                                                                                              val category = result.categories.maxByOrNull { it.score } ?: continue
                                                                                                              val label = category.label ?: continue
                                                                                                              val box = result.boundingBox

                                                                                                              val mapped = RectF(
                                                                                                                                   box.left * backScale + srcRect.left,
                                                                                                                                   box.top * backScale + srcRect.top,
                                                                                                                                   box.right * backScale + srcRect.left,
                                                                                                                                   box.bottom * backScale + srcRect.top
                                                                                                                               )
                                                                                                              
                                                                                                                              val threat = when {
                                                                                                                                                   label == "person" -> ThreatCategory.HUMAN
                                                                                                                                                   vehicleLabels.contains(label) -> ThreatCategory.VEHICLE
                                                                                                                                                   else -> ThreatCategory.UNKNOWN
                                                                                                                              }
                                                                                                                              
                                                                                                                                              collected.add(Detection(mapped, label, threat, category.score))
                                                                                             }
                                                                                }

                                                                                        val merged = mergeOverlapping(collected)

                                                                                                DetectorStatus.lastRawCount = merged.size
                                                        DetectorStatus.lastMs = SystemClock.elapsedRealtime() - startedAt
                                                        return merged
                                               }

                                                   private fun mergeOverlapping(items: List<Detection>): List<Detection> {
                                                            if (items.size < 2) return items
                                                            val sorted = items.sortedByDescending { it.confidence }
                                                                    val kept = mutableListOf<Detection>()
                                                                            for (candidate in sorted) {
                                                                                         val duplicate = kept.any { existing ->
                                                                                                          existing.label == candidate.label && iou(existing.box, candidate.box) > MERGE_IOU
                                                                                         }
                                                                                                     if (!duplicate) kept.add(candidate)
                                                                            }
                                                                                    return kept
                                                   }

                                                       private fun iou(a: RectF, b: RectF): Float {
                                                                val left = maxOf(a.left, b.left)
                                                                        val top = maxOf(a.top, b.top)
                                                                                val right = minOf(a.right, b.right)
                                                                                        val bottom = minOf(a.bottom, b.bottom)
                                                                                                if (right <= left || bottom <= top) return 0f
                                                                val overlap = (right - left) * (bottom - top)
                                                                        val areaA = (a.right - a.left) * (a.bottom - a.top)
                                                                                val areaB = (b.right - b.left) * (b.bottom - b.top)
                                                                                        val union = areaA + areaB - overlap
                                                                return if (union <= 0f) 0f else overlap / union
                                                       }

                                                           fun close() {
                                                                    detector.close()
                                                                            tileBitmap.recycle()
                                                           }
                  }
                  
