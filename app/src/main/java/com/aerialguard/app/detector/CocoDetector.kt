package com.aerialguard.app.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TfliteObjectDetector

/**
 * General-purpose detector: EfficientDet-Lite2 trained on COCO, run through
  * the TFLite Task Library. Class labels come from the model's own embedded
   * metadata, so there is no hand-maintained label map to drift out of sync.
    *
     * Strong on ground-level and oblique views; weak straight down, which is what
      * AerialDetector is for.
       */
class CocoDetector(context: Context) : Detector {

      companion object {
                private const val MODEL_FILE = "detect.tflite"
                private const val SCORE_FLOOR = 0.25f
                private const val MAX_RESULTS = 25
      }

          override val source = DetectorSource.GROUND
      override var isAvailable = false
          private set
      override var statusNote = "not loaded"
          private set

      private var detector: TfliteObjectDetector? = null

      init {
                try {
                              val baseOptions = BaseOptions.builder().setNumThreads(2).build()
                                          val options = TfliteObjectDetector.ObjectDetectorOptions.builder()
                                                          .setBaseOptions(baseOptions)
                                                                          .setMaxResults(MAX_RESULTS)
                                                                                          .setScoreThreshold(SCORE_FLOOR)
                                                                                                          .build()
                                                                                                                      detector = TfliteObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
                                                                                                                                  isAvailable = true
                              statusNote = "ok"
                } catch (e: Exception) {
                              isAvailable = false
                              statusNote = e.message ?: e.javaClass.simpleName
                }
      }

          override fun detectTile(tile: Bitmap): List<RawDetection> {
                    val d = detector ?: return emptyList()
                            val raw = try {
                                          d.detect(TensorImage.fromBitmap(tile))
                            } catch (e: Exception) {
                                          statusNote = "detect failed"
                                          return emptyList()
                            }

                                    val out = ArrayList<RawDetection>(raw.size)
                                            for (result in raw) {
                                                          val category = result.categories.maxByOrNull { it.score } ?: continue
                                                          val label = category.label ?: continue
                                                          val b = result.boundingBox
                                                          out.add(RawDetection(RectF(b.left, b.top, b.right, b.bottom), label, category.score))
                                            }
                                                    return out
          }

              override fun close() {
                        detector?.close()
                                detector = null
              }
}
