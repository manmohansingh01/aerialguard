package com.aerialguard.app.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs Google's free, pretrained COCO SSD-MobileNet TFLite model
  * (downloaded automatically by download_model.gradle into
   * app/src/main/assets/detect.tflite + labelmap.txt).
    *
     * Only two threat buckets are kept, on purpose, to stay basic and light:
      * HUMAN ("person") and VEHICLE ("car", "truck", "bus", "motorcycle",
       * "bicycle", "train", "boat"). Every other COCO class the model reports
        * (including "airplane") is ignored.
         */
class ObjectDetector(context: Context) {

     private val interpreter: Interpreter
     private val labels: List<String>
     private val inputSize = 300 // fixed by the SSD-MobileNet-v1 model

     private val vehicleLabels = setOf("car", "truck", "bus", "motorcycle", "bicycle", "train", "boat")
         private val confidenceThreshold = 0.5f

     init {
              val model = FileUtil.loadMappedFile(context, "detect.tflite")
                      val options = Interpreter.Options().apply { setNumThreads(4) }
                              interpreter = Interpreter(model, options)
                                      labels = FileUtil.loadLabels(context, "labelmap.txt")
     }

         fun detect(bitmap: Bitmap): List<Detection> {
                  val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
                          val inputBuffer = bitmapToByteBuffer(resized)
                                  if (resized !== bitmap) resized.recycle()

                                          val outputLocations = Array(1) { Array(10) { FloatArray(4) } }
                                                  val outputClasses = Array(1) { FloatArray(10) }
                                                          val outputScores = Array(1) { FloatArray(10) }
                                                                  val numDetections = FloatArray(1)

                                                                          val outputMap = mapOf(
                                                                                       0 to outputLocations,
                                                                                       1 to outputClasses,
                                                                                       2 to outputScores,
                                                                                       3 to numDetections
                                                                                   )

                                                                                  interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputMap)

                                                                                          val results = mutableListOf<Detection>()
                                                                                                  val count = numDetections[0].toInt().coerceIn(0, 10)

                                                                                                          for (i in 0 until count) {
                                                                                                                       val score = outputScores[0][i]
                                                                                                                       if (score < confidenceThreshold) continue
                                                                                                           
                                                                                                                       val classId = outputClasses[0][i].toInt()
                                                                                                                                   if (classId < 0 || classId >= labels.size) continue
                                                                                                                       val label = labels[classId]
                                                                                                           
                                                                                                                       val category = when {
                                                                                                                                        label == "person" -> ThreatCategory.HUMAN
                                                                                                                                        vehicleLabels.contains(label) -> ThreatCategory.VEHICLE
                                                                                                                                        else -> ThreatCategory.UNKNOWN
                                                                                                                       }
                                                                                                                                   if (category == ThreatCategory.UNKNOWN) continue
                                                                                                           
                                                                                                                       // Output order is [top, left, bottom, right], normalized 0..1.
                                                                                                                       val loc = outputLocations[0][i]
                                                                                                                       val box = RectF(
                                                                                                                                        loc[1] * bitmap.width,
                                                                                                                                        loc[0] * bitmap.height,
                                                                                                                                        loc[3] * bitmap.width,
                                                                                                                                        loc[2] * bitmap.height
                                                                                                                                    )
                                                                                                                                   results.add(Detection(box, label, category, score))
                                                                                                          }
                                                                                                                  return results
         }

             private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
                      // Quantized (uint8) model — raw 0..255 RGB bytes, no normalization needed.
                      val buffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3)
                              buffer.order(ByteOrder.nativeOrder())
                                      val pixels = IntArray(inputSize * inputSize)
                                              bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
                                                      for (pixel in pixels) {
                                                                   buffer.put(((pixel shr 16) and 0xFF).toByte())
                                                                               buffer.put(((pixel shr 8) and 0xFF).toByte())
                                                                                           buffer.put((pixel and 0xFF).toByte())
                                                      }
                                                              buffer.rewind()
                                                                      return buffer
             }

                 fun close() {
                          interpreter.close()
                 }
}
