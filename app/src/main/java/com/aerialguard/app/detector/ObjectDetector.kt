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
 * User-tunable detection settings. Exposed as a slider in MainActivity so the
  * strictness can be changed live, while the overlay is running, without
   * rebuilding the app.
    */
object DetectorConfig {
     @Volatile var minConfidence = 0.90f
}

/**
 * Live status of the detector, read by the overlay HUD.
  */
  object DetectorStatus {
       @Volatile var modelOk = false
       @Volatile var note = "starting"
       @Volatile var lastRawCount = 0
       @Volatile var lastMs = 0L
       const val VERSION = "2.1"
  }

  /**
   * Object detector built on the TensorFlow Lite Task Library, using a model
    * whose labels come from its own embedded metadata.
     *
      * The frame is cut into overlapping square tiles so nothing is stretched, each
       * tile is run through the model, and the results are merged.
        *
         * Everything below the keep bar is discarded, in this order:
          *
           *  1. Class: only person and vehicle classes are kept at all. The model can
            *     name 90 COCO classes and on empty desert it happily reports book,
             *     knife and airplane -- none of those are ever drawn now.
              *  2. Shape: a box more than 4x longer than it is wide (or vice versa) is not
               *     a person or a car, it is the horizon, a road edge or a dune ridge.
                *     Those long thin bands were the bulk of the false positives.
                 *  3. Size: a box covering more than half the tile is background.
                  *  4. Confidence: must be at least DetectorConfig.minConfidence.
                   */
                   class ObjectDetector(context: Context) {

                        companion object {
                                 private const val TILE_PX = 448
                                 private const val MODEL_SCORE_FLOOR = 0.25f
                                 private const val MAX_RESULTS_PER_TILE = 25
                                 private const val MERGE_IOU = 0.55f
                                 private const val MAX_ASPECT = 4.0f
                                 private const val MAX_AREA_FRACTION = 0.5f
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
                                                                                                                         .setScoreThreshold(MODEL_SCORE_FLOOR)
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

                                                                         val minConfidence = DetectorConfig.minConfidence
                                                         val tileArea = (TILE_PX * TILE_PX).toFloat()
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
                                                                                                                               DetectorStatus.note = "detect failed"
                                                                                                                               emptyList()
                                                                                                              }
                                                                                                              
                                                                                                                          val backScale = shortSide.toFloat() / TILE_PX

                                                                                      for (result in raw) {
                                                                                                       val category = result.categories.maxByOrNull { it.score } ?: continue
                                                                                                       val label = category.label ?: continue

                                                                                                       val threat = when {
                                                                                                                            label == "person" -> ThreatCategory.HUMAN
                                                                                                                            vehicleLabels.contains(label) -> ThreatCategory.VEHICLE
                                                                                                                            else -> ThreatCategory.UNKNOWN
                                                                                                       }
                                                                                                                       if (threat == ThreatCategory.UNKNOWN) continue

                                                                                                       if (category.score < minConfidence) continue

                                                                                                       val box = result.boundingBox
                                                                                                       val boxW = box.right - box.left
                                                                                                       val boxH = box.bottom - box.top
                                                                                                       if (boxW <= 0f || boxH <= 0f) continue

                                                                                                       val aspect = maxOf(boxW / boxH, boxH / boxW)
                                                                                                                       if (aspect > MAX_ASPECT) continue

                                                                                                       if ((boxW * boxH) / tileArea > MAX_AREA_FRACTION) continue

                                                                                                       val mapped = RectF(
                                                                                                                            box.left * backScale + srcRect.left,
                                                                                                                            box.top * backScale + srcRect.top,
                                                                                                                            box.right * backScale + srcRect.left,
                                                                                                                            box.bottom * backScale + srcRect.top
                                                                                                                        )
                                                                                                       
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
                   
