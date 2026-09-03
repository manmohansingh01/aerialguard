package com.aerialguard.app.detector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.ceil

/**
 * Runs every enabled detector over one frame and merges the results.
 *
 * Two passes, because the models want different things:
 *
 *  - The ground and aerial models look for small objects in a wide scene, so
 *    the frame is cut into overlapping square tiles. A phone screen is very
 *    tall or very wide; squeezing it into a square input would stretch
 *    everything, so square crops are used instead.
 *
 *  - The military model looks for large hardware -- tanks, ships, aircraft --
 *    which is big in frame and does not need slicing. It gets one letterboxed
 *    view of the whole frame. That is what it was trained on, and it costs a
 *    third of what three tiles cost, which matters because it is by far the
 *    most expensive model here.
 */
class FrameAnalyzer(private val detectors: List<Detector>) {

    companion object {
        private const val TILE_PX = 448
        private const val MAX_ASPECT = 4.0f
        private const val MAX_AREA_FRACTION = 0.5f
        private const val CROSS_MODEL_IOU = 0.60f
        private const val SAME_MODEL_IOU = 0.55f

        /** Compute budget: crops per frame, per tiled model. */
        private const val MAX_TILES = 8

        /** Fraction each crop overlaps its neighbour, so seams do not cut targets. */
        private const val TILE_OVERLAP = 0.18f
    }

    private val tileBitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
    private val tileCanvas = Canvas(tileBitmap)
    private val frameBitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frameBitmap)
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
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

        val collected = ArrayList<Detection>()

        val wholeFrameModels = active.filter { it.source == DetectorSource.MILITARY }
        if (wholeFrameModels.isNotEmpty()) {
            val scale = minOf(TILE_PX / width.toFloat(), TILE_PX / height.toFloat())
            val drawW = width * scale
            val drawH = height * scale
            val padX = (TILE_PX - drawW) / 2f
            val padY = (TILE_PX - drawH) / 2f

            frameCanvas.drawColor(Color.BLACK)
            frameCanvas.drawBitmap(
                bitmap, null, RectF(padX, padY, padX + drawW, padY + drawH), scalePaint
            )

            val frameArea = (width * height).toFloat()
            for (detector in wholeFrameModels) {
                val raw = try {
                    detector.detectTile(frameBitmap)
                } catch (e: Exception) {
                    emptyList()
                }
                for (r in raw) {
                    val mapped = RectF(
                        (r.box.left - padX) / scale,
                        (r.box.top - padY) / scale,
                        (r.box.right - padX) / scale,
                        (r.box.bottom - padY) / scale
                    )
                    if (mapped.centerX() < 0f || mapped.centerX() > width) continue
                    if (mapped.centerY() < 0f || mapped.centerY() > height) continue
                    val d = consider(r, mapped, detector.source, frameArea)
                    if (d != null) collected.add(d)
                }
            }
        }

        val tiledModels = active.filter { it.source != DetectorSource.MILITARY }
        if (tiledModels.isNotEmpty()) {
            val tiles = planTiles(width, height)

            for (srcRect in tiles) {
                val srcTile = srcRect.width()
                val backScale = srcTile.toFloat() / TILE_PX
                val tileAreaInFrame = (srcTile * srcRect.height()).toFloat()

                tileCanvas.drawBitmap(bitmap, srcRect, destRect, scalePaint)

                for (detector in tiledModels) {
                    val raw = try {
                        detector.detectTile(tileBitmap)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    for (r in raw) {
                        val mapped = RectF(
                            r.box.left * backScale + srcRect.left,
                            r.box.top * backScale + srcRect.top,
                            r.box.right * backScale + srcRect.left,
                            r.box.bottom * backScale + srcRect.top
                        )
                        val d = consider(r, mapped, detector.source, tileAreaInFrame)
                        if (d != null) collected.add(d)
                    }
                }
            }
        }

        val merged = merge(collected)

        DetectorStatus.groundCount = merged.count { it.source == DetectorSource.GROUND }
        DetectorStatus.aerialCount = merged.count { it.source == DetectorSource.AERIAL }
        DetectorStatus.militaryCount = merged.count { it.source == DetectorSource.MILITARY }
        DetectorStatus.gatedCount = merged.count { it.gated }
        DetectorStatus.lastMs = SystemClock.elapsedRealtime() - startedAt
        return merged
    }

    private fun planTiles(width: Int, height: Int): List<Rect> {
        var srcTile = TILE_PX
        var tiles = layoutTiles(width, height, srcTile)
        val limit = maxOf(width, height)
        while (tiles.size > MAX_TILES && srcTile < limit) {
            srcTile = (srcTile * 1.2f).toInt().coerceAtLeast(srcTile + 1)
            tiles = layoutTiles(width, height, srcTile)
        }
        return tiles
    }

    private fun layoutTiles(width: Int, height: Int, requested: Int): List<Rect> {
        val tile = minOf(requested, minOf(width, height))
        val stride = maxOf(1, (tile * (1f - TILE_OVERLAP)).toInt())
        val xs = axisOffsets(width, tile, stride)
        val ys = axisOffsets(height, tile, stride)
        val out = ArrayList<Rect>(xs.size * ys.size)
        for (y in ys) {
            for (x in xs) {
                out.add(Rect(x, y, x + tile, y + tile))
            }
        }
        return out
    }

    private fun axisOffsets(total: Int, tile: Int, stride: Int): List<Int> {
        if (tile >= total) return listOf(0)
        val steps = ceil((total - tile).toFloat() / stride).toInt() + 1
        val out = ArrayList<Int>(steps)
        for (i in 0 until steps) {
            out.add(minOf(i * stride, total - tile))
        }
        return out
    }

    private fun consider(
        r: RawDetection,
        mapped: RectF,
        source: DetectorSource,
        referenceArea: Float
    ): Detection? {
        val showAll = DetectorConfig.showAllClasses
        val minConfidence = DetectorConfig.minConfidence

        val threshold = if (source == DetectorSource.MILITARY) {
            minConfidence * DetectorConfig.militaryConfidenceScale
        } else {
            minConfidence
        }
        if (r.score < threshold) return null

        val rawCategory = Taxonomy.categorise(r.label)
        if (!showAll && rawCategory == ThreatCategory.OTHER) return null

        val boxW = mapped.right - mapped.left
        val boxH = mapped.bottom - mapped.top
        if (boxW <= 0f || boxH <= 0f) return null

        if (!showAll) {
            val aspect = maxOf(boxW / boxH, boxH / boxW)
            if (aspect > MAX_ASPECT) return null
            if ((boxW * boxH) / referenceArea > MAX_AREA_FRACTION) return null

            if (rawCategory == ThreatCategory.MILITARY &&
                boxH < DetectorConfig.minPixelsForFineClass
            ) {
                return null
            }
        }

        val gate = Taxonomy.applyResolutionGate(r.label, rawCategory, boxH)
        return Detection(mapped, gate.first, gate.second, r.score, source, gate.third)
    }

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
        frameBitmap.recycle()
    }
}
