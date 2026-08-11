package com.aerialguard.app.detector

import android.graphics.Bitmap

/**
 * Combines the trained COCO object detector (person/vehicle/aircraft) with
  * the lightweight quadcopter motion heuristic into one call per frame.
   */
class FrameAnalyzer(
      private val objectDetector: ObjectDetector?,
      private val quadcopterDetector: QuadcopterHeuristicDetector
  ) {
      fun analyze(bitmap: Bitmap): List<Detection> {
                val objectDetections = objectDetector?.detect(bitmap) ?: emptyList()
                        val quadcopterDetections = quadcopterDetector.detect(bitmap)
                                return objectDetections + quadcopterDetections
      }
}
