package com.aerialguard.app.detector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock

/**
 * Runs every enabled detector over one frame and merges the results.
  *
   * Tiling lives here so the frame is cut and rendered once, with the same tiles
    * feeding every model. A phone screen is very tall (e.g. 1080x2400); squeezing
     * that into a square model input stretches everything horizontally by more
      * than 2x, so the frame is cut into overlapping square tiles instead.
       */
class FrameAnalyzer(private val detectors: List<Detector>) {

     companion object {
              private const val TILE_PX = 448
              private const val MAX_ASPECT = 4.0f
              private const val MAX_AREA_FRACTION = 0.5f
              private const val CROSS_MODEL_IOU = 0.60f
              private const val SAME_MODEL_IOU = 0.55f
     }

         private val tileBitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
             private val tileCanvas = Canvas(tileBitmap)
                 private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
                     private val destRect = Rect(0, 0, TILE_PX, TILE_PX)

                         fun analyze(bitmap: Bitmap): List<Detection> {
                                  val startedAt = SystemClock.elapsedRealtime()

                                          val width = bitmap.width
                                  val height = bitmap.height
                                  if (width < 2 || height < 2) return emptyList()

                                          val active = detectors.filter {
                                                       it.isAvailable && when (it.source) {
                                                                        DetectorSource.GROUND -> DetectorConfig.groundEnabled
                                                                        DetectorSource.AERIAL -> DetectorConfig.aerialEnabled
                                                                        DetectorSource.MILITARY -> DetectorConfig.militaryEnabled
                                                       }
                                          }
                                                  if (active.isEmpty()) {
                                                               DetectorStatus.groundCount = 0
                                                               DetectorStatus.aerialCount = 0
                                                               DetectorStatus.militaryCount = 0
                                                               DetectorStatus.gatedCount = 0
                                                               return emptyList()
                                                  }

                                                          val shortSide = minOf(width, height)
                                                                  val longSide = maxOf(width, height)
                                                                          val landscape = width >= height

                                  val tileCount = maxOf(1, (longSide + shortSide - 1) / shortSide)
                                          val step = if (tileCount == 1) 0 else (longSide - shortSide) / (tileCount - 1)

                                                  val minConfidence = DetectorConfig.minConfidence
                                  val showAll = DetectorConfig.showAllClasses
                                  val tileArea = (TILE_PX * TILE_PX).toFloat()
                                          val backScale = shortSide.toFloat() / TILE_PX

                                  val collected = ArrayList<Detection>()
                                          var gated = 0

                                  for (i in 0 until tileCount) {
                                               val offset = if (tileCount == 1) 0 else i * step
                                               val srcRect = if (landscape) {
                                                                Rect(offset, 0, offset + shortSide, shortSide)
                                               } else {
                                                                Rect(0, offset, shortSide, offset + shortSide)
                                               }

                                                           tileCanvas.drawBitmap(bitmap, srcRect, destRect, tilePaint)

                                                                       for (detector in active) {
                                                                                        val raw = try {
                                                                                                             detector.detectTile(tileBitmap)
                                                                                        } catch (e: Exception) {
                                                                                                             emptyList()
                                                                                        }

                                                                                                        for (r in raw) {
                                                                                                                             if (r.score < minConfidence) continue
                                                                                                         
                                                                                                                             val rawCategory = Taxonomy.categorise(r.label)
                                                                                                                                                 if (!showAll && rawCategory == ThreatCategory.OTHER) continue
                                                                                                         
                                                                                                                             val boxW = r.box.right - r.box.left
                                                                                                                             val boxH = r.box.bottom - r.box.top
                                                                                                                             if (boxW <= 0f || boxH <= 0f) continue
                                                                                                         
                                                                                                                             val aspect = maxOf(boxW / boxH, boxH / boxW)
                                                                                                                                                 if (aspect > MAX_ASPECT) continue
                                                                                                                             if ((boxW * boxH) / tileArea > MAX_AREA_FRACTION) continue
                                                                                                         
                                                                                                                             val mapped = RectF(
                                                                                                                                                      r.box.left * backScale + srcRect.left,
                                                                                                                                                      r.box.top * backScale + srcRect.top,
                                                                                                                                                      r.box.right * backScale + srcRect.left,
                                                                                                                                                      r.box.bottom * backScale + srcRect.top
                                                                                                                                                  )
                                                                                                                             
                                                                                                                                                 // Resolution gate: only claim a fine-grained class when
                                                                                                                                                                     // there are enough pixels on target to support it.
                                                                                                                                                                                         val heightPx = mapped.bottom - mapped.top
                                                                                                                             val gateResult = Taxonomy.applyResolutionGate(r.label, rawCategory, heightPx)
                                                                                                                                                 val label = gateResult.first
                                                                                                                             val category = gateResult.second
                                                                                                                             val wasGated = gateResult.third
                                                                                                                             if (wasGated) gated++
                                                                                                         
                                                                                                                             collected.add(
                                                                                                                                                      Detection(mapped, label, category, r.score, detector.source, wasGated)
                                                                                                                                                                          )
                                                                                                        }
                                                                       }
                                  }

                                          val merged = merge(collected)

                                                  DetectorStatus.groundCount = merged.count { it.source == DetectorSource.GROUND }
                                                          DetectorStatus.aerialCount = merged.count { it.source == DetectorSource.AERIAL }
                                                                  DetectorStatus.militaryCount = merged.count { it.source == DetectorSource.MILITARY }
                                                                          DetectorStatus.gatedCount = gated
                                  DetectorStatus.lastMs = SystemClock.elapsedRealtime() - startedAt
                                  return merged
                         }

                             /**
                                  * Removes duplicates within one model (overlapping tiles) and across
                                       * models (both finding the same object). Cross-model matching is done on
                                            * category rather than label, because the label sets disagree -- COCO says
                                                 * "car" where VisDrone says "van", for the same vehicle.
                                                      */
                                                          private fun merge(items: List<Detection>): List<Detection> {
                                                                   if (items.size < 2) return items
                                                                   val sorted = items.sortedByDescending { it.confidence }
                                                                           val kept = ArrayList<Detection>()
                                                                                   for (cand in sorted) {
                                                                                                var duplicate = false
                                                                                                for (k in kept) {
                                                                                                                 val overlap = iou(k.box, cand.box)
                                                                                                                                 duplicate = if (k.source == cand.source) {
                                                                                                                                                      k.label == cand.label && overlap > SAME_MODEL_IOU
                                                                                                                                 } else {
                                                                                                                                                      k.category == cand.category && overlap > CROSS_MODEL_IOU
                                                                                                                                 }
                                                                                                                                                 if (duplicate) break
                                                                                                }
                                                                                                            if (!duplicate) kept.add(cand)
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
                                                                           detectors.forEach { it.close() }
                                                                                   tileBitmap.recycle()
                                                                  }
}
