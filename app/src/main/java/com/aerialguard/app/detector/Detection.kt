package com.aerialguard.app.detector

import android.graphics.RectF

enum class ThreatCategory {
      HUMAN,
      VEHICLE,
      AIRCRAFT,
      QUADCOPTER_HEURISTIC,
      UNKNOWN
}

/**
 * A single detection result. [box] is expressed in the pixel space of the
  * frame that was analyzed (see [FrameAnalyzer]) — the overlay rescales it
   * to the phone's actual screen size before drawing.
    */
    data class Detection(
          val box: RectF,
          val label: String,
          val category: ThreatCategory,
          val confidence: Float
      )
    
