package com.aerialguard.app.detector

import android.graphics.Bitmap
import android.graphics.RectF

enum class ThreatCategory {
              HUMAN,
              VEHICLE,
              OTHER,
              UNKNOWN
}

/** Which model produced a detection. Shown in the HUD and used when merging. */
enum class DetectorSource {
              GROUND,
              AERIAL
}

/**
 * A detection in the pixel space of the analysed frame. The overlay rescales
  * it to the phone's screen size before drawing.
   */
   data class Detection(
                 val box: RectF,
                 val label: String,
                 val category: ThreatCategory,
                 val confidence: Float,
                 val source: DetectorSource
             )

   /** A detection still in the coordinate space of a single square tile. */
   data class RawDetection(
                 val box: RectF,
                 val label: String,
                 val score: Float
             )

   /**
    * One detection model. Tiling and coordinate mapping are handled once by
     * FrameAnalyzer, so a detector only has to score a single square tile.
      */
      interface Detector {
                    val source: DetectorSource
                    val isAvailable: Boolean
                    val statusNote: String
                    fun detectTile(tile: Bitmap): List<RawDetection>
                    fun close()
      }

      /** Settings the user can change live from the main screen. */
      object DetectorConfig {
                    @Volatile var minConfidence = 0.60f
                    @Volatile var groundEnabled = true
                    @Volatile var aerialEnabled = true
                    @Volatile var showAllClasses = false
      }

      /** Live state, read by the overlay HUD. */
      object DetectorStatus {
                    @Volatile var groundOk = false
                    @Volatile var aerialOk = false
                    @Volatile var note = "starting"
                    @Volatile var groundCount = 0
                    @Volatile var aerialCount = 0
                    @Volatile var lastMs = 0L
                    const val VERSION = "3.0"
      }

      /**
       * Shared class-name to category mapping. It has to cover both COCO names and
        * VisDrone names, which differ: "person" vs "pedestrian"/"people",
         * "motorcycle" vs "motor", plus van / tricycle / awning-tricycle.
          */
          object Taxonomy {

                        private val humanLabels = setOf("person", "pedestrian", "people")

                            private val vehicleLabels = setOf(
                                              "car", "truck", "bus", "motorcycle", "bicycle", "train", "boat",
                                              "van", "motor", "tricycle", "awning-tricycle"
                                          )

                                fun categorise(label: String): ThreatCategory {
                                                  val key = label.trim().lowercase()
                                                          return when {
                                                                                humanLabels.contains(key) -> ThreatCategory.HUMAN
                                                                                vehicleLabels.contains(key) -> ThreatCategory.VEHICLE
                                                                                else -> ThreatCategory.OTHER
                                                          }
                                }
          }
          
