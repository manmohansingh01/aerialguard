package com.aerialguard.app.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Top-down / drone-viewpoint detector: YOLOv8n trained on VisDrone.
  *
   * The Task Library cannot run this model -- it expects SSD-style postprocessed
    * outputs (four tensors: boxes, classes, scores, count), whereas YOLOv8 emits
     * one raw tensor that still needs decoding and NMS. So this class drives the
      * bare TFLite Interpreter and decodes by hand.
       *
        * If aerial.tflite is not bundled in assets the detector reports itself
         * unavailable and returns nothing; the app then runs on the COCO model alone
          * rather than failing.
           *
            * The decoder reads tensor shapes at runtime and sniffs the box coordinate
             * convention, because those differ between Ultralytics export versions and
              * getting either wrong yields silently wrong boxes.
               */
class AerialDetector(context: Context) : Detector {

      companion object {
                private const val MODEL_FILE = "aerial.tflite"
                private const val SCORE_FLOOR = 0.25f
                private const val NMS_IOU = 0.45f
                private const val MAX_OUT = 40

                /** VisDrone class order, as trained. */
                private val LABELS = arrayOf(
                              "pedestrian", "people", "bicycle", "car", "van",
                              "truck", "tricycle", "awning-tricycle", "bus", "motor"
                          )
      }

          override val source = DetectorSource.AERIAL
      override var isAvailable = false
          private set
      override var statusNote = "model not installed"
          private set

      private var interpreter: Interpreter? = null
      private var inputW = 448
      private var inputH = 448
      private var channels = 0
      private var anchors = 0
      private var channelsFirst = true

      private var inputBuffer: ByteBuffer? = null
      private var pixels = IntArray(0)

          init {
                    try {
                                  val model = FileUtil.loadMappedFile(context, MODEL_FILE)
                                              val options = Interpreter.Options().apply { setNumThreads(2) }
                                                          val interp = Interpreter(model, options)

                                                                      val inShape = interp.getInputTensor(0).shape()
                                                                                  if (inShape.size == 4) {
                                                                                                    inputH = inShape[1]
                                                                                                    inputW = inShape[2]
                                                                                  }

                                                                                              val outShape = interp.getOutputTensor(0).shape()
                                                                                                          if (outShape.size != 3) throw IllegalStateException("output rank " + outShape.size)
                                                                                                                      val d1 = outShape[1]
                                  val d2 = outShape[2]
                                  if (d1 <= d2) {
                                                    channelsFirst = true; channels = d1; anchors = d2
                                  } else {
                                                    channelsFirst = false; channels = d2; anchors = d1
                                  }
                                              if (channels < 5) throw IllegalStateException("channels " + channels)

                                                          inputBuffer = ByteBuffer.allocateDirect(inputW * inputH * 3 * 4).apply {
                                                                            order(ByteOrder.nativeOrder())
                                                          }
                                                                      pixels = IntArray(inputW * inputH)

                                                                                  interpreter = interp
                                  isAvailable = true
                                  statusNote = "ok"
                    } catch (e: Exception) {
                                  interpreter = null
                                  isAvailable = false
                                  statusNote = e.message ?: "model not installed"
                    }
          }

              override fun detectTile(tile: Bitmap): List<RawDetection> {
                        val interp = interpreter ?: return emptyList()
                                val buffer = inputBuffer ?: return emptyList()

                                        val scaled = if (tile.width == inputW && tile.height == inputH) {
                                                      tile
                                        } else {
                                                      Bitmap.createScaledBitmap(tile, inputW, inputH, true)
                                        }

                                                buffer.rewind()
                                                        val n = inputW * inputH
                        scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
                                for (i in 0 until n) {
                                              val p = pixels[i]
                                              buffer.putFloat(((p shr 16) and 0xFF) / 255f)
                                                          buffer.putFloat(((p shr 8) and 0xFF) / 255f)
                                                                      buffer.putFloat((p and 0xFF) / 255f)
                                }
                                        buffer.rewind()
                                                if (scaled !== tile) scaled.recycle()

                                                        val output = if (channelsFirst) {
                                                                      Array(1) { Array(channels) { FloatArray(anchors) } }
                                                        } else {
                                                                      Array(1) { Array(anchors) { FloatArray(channels) } }
                                                        }

                                                                try {
                                                                              interp.run(buffer, output)
                                                                } catch (e: Exception) {
                                                                              statusNote = "inference failed"
                                                                              return emptyList()
                                                                }

                                                                        val numClasses = channels - 4
                        val candidates = ArrayList<RawDetection>()
                                var maxCoord = 0f

                        for (a in 0 until anchors) {
                                      var bestScore = 0f
                                      var bestClass = -1
                                      for (c in 0 until numClasses) {
                                                        val s = if (channelsFirst) output[0][4 + c][a] else output[0][a][4 + c]
                                                        if (s > bestScore) { bestScore = s; bestClass = c }
                                      }
                                                  if (bestClass < 0 || bestScore < SCORE_FLOOR) continue

                                      val cx = if (channelsFirst) output[0][0][a] else output[0][a][0]
                                      val cy = if (channelsFirst) output[0][1][a] else output[0][a][1]
                                      val w = if (channelsFirst) output[0][2][a] else output[0][a][2]
                                      val h = if (channelsFirst) output[0][3][a] else output[0][a][3]
                                      if (w <= 0f || h <= 0f) continue

                                      maxCoord = maxOf(maxCoord, cx, cy, w, h)

                                                  val label = if (bestClass < LABELS.size) LABELS[bestClass] else "class" + bestClass
                                      candidates.add(
                                                        RawDetection(RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), label, bestScore)
                                                                    )
                        }

                                if (candidates.isEmpty()) return emptyList()

                                        val scaleUp = if (maxCoord <= 1.5f) tile.width.toFloat() else tile.width.toFloat() / inputW
                        val mapped = candidates.map {
                                      RawDetection(
                                                        RectF(
                                                                              it.box.left * scaleUp, it.box.top * scaleUp,
                                                                              it.box.right * scaleUp, it.box.bottom * scaleUp
                                                                          ),
                                                        it.label, it.score
                                                    )
                        }

                                return nms(mapped)
              }

                  /** Per-class non-max suppression -- YOLOv8 TFLite output has none applied. */
                      private fun nms(items: List<RawDetection>): List<RawDetection> {
                                val sorted = items.sortedByDescending { it.score }
                                        val kept = ArrayList<RawDetection>()
                                                for (cand in sorted) {
                                                              if (kept.size >= MAX_OUT) break
                                                              var drop = false
                                                              for (k in kept) {
                                                                                if (k.label == cand.label && iou(k.box, cand.box) > NMS_IOU) { drop = true; break }
                                                              }
                                                                          if (!drop) kept.add(cand)
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

                              override fun close() {
                                        interpreter?.close()
                                                interpreter = null
                              }
}
