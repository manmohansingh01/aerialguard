package com.aerialguard.app.detector

import android.graphics.Bitmap

/**
 * Thin wrapper around the trained COCO object detector (person/vehicle).
  */
class FrameAnalyzer(
     private val objectDetector: ObjectDetector?
  ) {
     fun analyze(bitmap: Bitmap): List<Detection> {
              return objectDetector?.detect(bitmap) ?: emptyList()
     }
}
