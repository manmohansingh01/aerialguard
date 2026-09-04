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
        private const val MERGE_IOU = 0.45f
        private const val CONTAINED = 0.80f
        private const val MAX_DETECTIONS = 15

        /**
         * Minimum luminance variance inside a box for it to be believed.
         * Measured on a real capture: blank page 45, Google logo 2881, actual
         * camera footage 6347. Below this there are no edges and no contrast,
         * so nothing is physically there and a reported vehicle is the model
         * hallucinating on flat pixels.
         */
        private const val MIN_VARIANCE = 120f

        /** Boxes below this height are the size the ghost detections came in at. */
        private const val SMALL_BOX_PX = 48f

        /** How much of a small box must sit on our own label for it to be ours. */
        private const val CHIP_CONTAIN = 0.65f

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

    /** Pixels of whichever 448px view is currently being analysed. */
    private val pixelBuf = IntArray(TILE_PX * TILE_PX)

    /**
     * Where this overlay drew its own label chips last frame.
     *
     * MediaProjection mirrors the whole display, and that includes our own
     * overlay window. A solid coloured chip with text looks a lot like a small
     * vehicle from above, so the labels drawn last frame come back as this
     * frame detections, which draw new labels, which feed back again. The
     * ghosts sit still on a blank page because they sustain themselves.
     */
    @Volatile private var lastChips: List<RectF> = emptyList()

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
            lastChips = emptyList()
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
            frameBitmap.getPixels(pixelBuf, 0, TILE_PX, 0, 0, TILE_PX, TILE_PX)

            val frameArea = (width * height).toFloat()
            for (detector in wholeFrameModels) {
                val raw = try {
                    detector.detectTile(frameBitmap)
                } catch (e: Exception) {
                    emptyList()
                }
                for (r in raw) {
                    if (!hasTexture(r.box)) continue
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
                tileBitmap.getPixels(pixelBuf, 0, TILE_PX, 0, 0, TILE_PX, TILE_PX)

                for (detector in tiledModels) {
                    val raw = try {
                        detector.detectTile(tileBitmap)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    for (r in raw) {
                        if (!hasTexture(r.box)) continue
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
        lastChips = merged.map { chipRectFor(it) }

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

            // Reject small boxes sitting on the label chips drawn last frame:
            // that is this overlay detecting itself, not the scene.
            if (boxH < SMALL_BOX_PX) {
                for (chip in lastChips) {
                    if (containment(mapped, chip) > CHIP_CONTAIN) return null
                }
            }
        }

        val gate = Taxonomy.applyResolutionGate(r.label, rawCategory, boxH)
        return Detection(mapped, gate.first, gate.second, r.score, source, gate.third)
    }

    private fun merge(items: List<Detection>): List<Detection> {
        if (items.size < 2) return items

        // One box per object. Every detection competes with every other one,
        // regardless of which model found it or what it was called -- matching
        // on category was the bug: a tank found by the military model and the
        // same tank called "train" by COCO have different categories, so both
        // survived and you got two boxes on one object.
        //
        // Plain overlap is not enough either. Those two "train" boxes sat
        // entirely inside the tank box yet scored only 0.38 IoU, so they need
        // the containment test. Containment is skipped when either box is a
        // person, because a person standing beside a vehicle is genuinely
        // inside its box and is a separate object, not a duplicate.
        val sorted = items.sortedByDescending { it.confidence }
        val kept = ArrayList<Detection>()
        for (cand in sorted) {
            if (kept.size >= MAX_DETECTIONS) break
            var duplicate = false
            for (k in kept) {
                if (iou(k.box, cand.box) > MERGE_IOU) {
                    duplicate = true
                    break
                }
                val humanInvolved = k.category == ThreatCategory.HUMAN ||
                    cand.category == ThreatCategory.HUMAN
                if (!humanInvolved) {
                    val inside = maxOf(
                        containment(cand.box, k.box),
                        containment(k.box, cand.box)
                    )
                    if (inside > CONTAINED) {
                        duplicate = true
                        break
                    }
                }
            }
            if (!duplicate) kept.add(cand)
        }
        return kept
    }

    /**
     * True when the pixels under [box] carry enough contrast to hold an object
     * at all. Samples a coarse grid rather than every pixel, which is plenty to
     * tell flat colour apart from a real scene.
     */
    private fun hasTexture(box: RectF): Boolean {
        val left = box.left.toInt().coerceIn(0, TILE_PX - 2)
        val top = box.top.toInt().coerceIn(0, TILE_PX - 2)
        val right = box.right.toInt().coerceIn(left + 2, TILE_PX)
        val bottom = box.bottom.toInt().coerceIn(top + 2, TILE_PX)

        val step = maxOf(1, minOf(right - left, bottom - top) / 12)
        var n = 0
        var sum = 0.0
        var sumSq = 0.0

        var y = top
        while (y < bottom) {
            val rowBase = y * TILE_PX
            var x = left
            while (x < right) {
                val p = pixelBuf[rowBase + x]
                val lum = 0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)
                sum += lum
                sumSq += lum * lum
                n++
                x += step
            }
            y += step
        }

        if (n < 9) return true
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        return variance > MIN_VARIANCE
    }

    /** Where the overlay will draw this label chip, in frame pixels. */
    private fun chipRectFor(d: Detection): RectF {
        val estWidth = (d.label.length + 12) * 19f
        return RectF(d.box.left - 8f, d.box.top - 60f, d.box.left + estWidth, d.box.top + 8f)
    }

    /** Fraction of [inner] that lies inside [outer]. */
    private fun containment(inner: RectF, outer: RectF): Float {
        val left = maxOf(inner.left, outer.left)
        val top = maxOf(inner.top, outer.top)
        val right = minOf(inner.right, outer.right)
        val bottom = minOf(inner.bottom, outer.bottom)
        if (right <= left || bottom <= top) return 0f
        val innerArea = (inner.right - inner.left) * (inner.bottom - inner.top)
        if (innerArea <= 0f) return 0f
        return ((right - left) * (bottom - top)) / innerArea
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
