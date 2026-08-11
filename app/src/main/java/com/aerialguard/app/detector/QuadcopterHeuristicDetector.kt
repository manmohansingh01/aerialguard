package com.aerialguard.app.detector

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Lightweight, model-free heuristic that flags small, fast-moving aerial
  * blobs as a rough proxy for quadcopters.
   *
    * WHY A HEURISTIC AND NOT A MODEL: there is no free, ready-made, lightweight
     * pretrained TFLite model for "quadcopter" the way there is for
      * person/car/airplane (those come from the standard COCO dataset — drones
       * don't). Training a real one requires a labeled drone-image dataset. This
        * heuristic works today with zero downloads and near-zero CPU cost by
         * comparing consecutive frames on a tiny downsampled grid and looking for
          * small, roughly blob-shaped regions of motion in the upper part of the
           * frame (where quadcopters typically appear against open sky).
            *
             * IT IS NOT A CLASSIFIER. It will also fire on birds, insects near the
              * lens, or fast clutter. Treat "possible quadcopter" as "worth a look",
               * not a confirmed identification.
                *
                 * UPGRADE PATH: once you have a real trained model (e.g. export one for
                  * free from a public "drone detection" project on Roboflow Universe as
                   * TFLite), drop drone.tflite + drone_labels.txt into assets/ and write a
                    * class with the same detect(bitmap): List<Detection> signature as
                     * ObjectDetector, modeled on that class — then swap it in for this one in
                      * ScreenCaptureService.
                       */
class QuadcopterHeuristicDetector(
      private val gridWidth: Int = 80,
      private val gridHeight: Int = 60
  ) {
      private var previousGray: IntArray? = null

      // Tunables — adjust if you get too many/few "possible quadcopter" boxes.
      private val diffThreshold = 28        // per-cell brightness delta counted as "moved"
      private val minBlobCells = 2          // ignore single-pixel noise
      private val maxBlobCells = 40         // ignore big moving regions (cars, whole-frame light changes)
      private val skyRegionFraction = 0.75f // only look in the top 75% of the frame
      private val minAspect = 0.4f
      private val maxAspect = 2.5f
      private val heuristicConfidence = 0.45f

      fun detect(bitmap: Bitmap): List<Detection> {
                val gray = toDownsampledGray(bitmap)
                        val previous = previousGray
                previousGray = gray
                if (previous == null) return emptyList()

                        val visited = BooleanArray(gridWidth * gridHeight)
                                val results = mutableListOf<Detection>()
                                        val skyRows = (gridHeight * skyRegionFraction).toInt()

                                                for (y in 0 until skyRows) {
                                                              for (x in 0 until gridWidth) {
                                                                                val idx = y * gridWidth + x
                                                                                if (visited[idx]) continue
                                                                                if (kotlin.math.abs(gray[idx] - previous[idx]) < diffThreshold) continue

                                                                                val blob = floodFillBlob(x, y, gray, previous, visited)
                                                                                                val cellCount = blob.cellCount
                                                                                if (cellCount < minBlobCells || cellCount > maxBlobCells) continue

                                                                                val w = blob.maxX - blob.minX + 1
                                                                                val h = blob.maxY - blob.minY + 1
                                                                                val aspect = w.toFloat() / h.toFloat()
                                                                                                if (aspect < minAspect || aspect > maxAspect) continue

                                                                                val box = RectF(
                                                                                                      blob.minX.toFloat() / gridWidth * bitmap.width,
                                                                                                      blob.minY.toFloat() / gridHeight * bitmap.height,
                                                                                                      (blob.maxX + 1).toFloat() / gridWidth * bitmap.width,
                                                                                                      (blob.maxY + 1).toFloat() / gridHeight * bitmap.height
                                                                                                  )
                                                                                                results.add(
                                                                                                                      Detection(box, "possible quadcopter", ThreatCategory.QUADCOPTER_HEURISTIC, heuristicConfidence)
                                                                                                                                      )
                                                              }
                                                }
                                                        return results
      }

          private class Blob {
                    var minX = Int.MAX_VALUE
                    var maxX = Int.MIN_VALUE
                    var minY = Int.MAX_VALUE
                    var maxY = Int.MIN_VALUE
                    var cellCount = 0
          }

              private fun floodFillBlob(
                        startX: Int,
                        startY: Int,
                        gray: IntArray,
                        previous: IntArray,
                        visited: BooleanArray
                    ): Blob {
                        val blob = Blob()
                                val stack = ArrayDeque<Int>()
                                        stack.addLast(startY * gridWidth + startX)
                                                visited[startY * gridWidth + startX] = true

                        while (stack.isNotEmpty()) {
                                      val cur = stack.removeLast()
                                                  val cx = cur % gridWidth
                                      val cy = cur / gridWidth
                                      blob.cellCount++
                                      if (cx < blob.minX) blob.minX = cx
                                      if (cx > blob.maxX) blob.maxX = cx
                                      if (cy < blob.minY) blob.minY = cy
                                      if (cy > blob.maxY) blob.maxY = cy

                                      for (dy in -1..1) {
                                                        for (dx in -1..1) {
                                                                              val nx = cx + dx
                                                                              val ny = cy + dy
                                                                              if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue
                                                                              val nIdx = ny * gridWidth + nx
                                                                              if (visited[nIdx]) continue
                                                                              if (kotlin.math.abs(gray[nIdx] - previous[nIdx]) < diffThreshold) continue
                                                                              visited[nIdx] = true
                                                                              stack.addLast(nIdx)
                                                        }
                                      }
                        }
                                return blob
              }

                  private fun toDownsampledGray(bitmap: Bitmap): IntArray {
                            val small = Bitmap.createScaledBitmap(bitmap, gridWidth, gridHeight, true)
                                    val pixels = IntArray(gridWidth * gridHeight)
                                            small.getPixels(pixels, 0, gridWidth, 0, 0, gridWidth, gridHeight)
                                                    val gray = IntArray(gridWidth * gridHeight)
                                                            for (i in pixels.indices) {
                                                                          val p = pixels[i]
                                                                          val r = (p shr 16) and 0xFF
                                                                          val g = (p shr 8) and 0xFF
                                                                          val b = p and 0xFF
                                                                          gray[i] = (r * 299 + g * 587 + b * 114) / 1000
                                                            }
                                                                    if (small !== bitmap) small.recycle()
                                                                            return gray
                  }
}
