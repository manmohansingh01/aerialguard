package com.aerialguard.app.detector

import android.graphics.Bitmap
import android.graphics.RectF

enum class ThreatCategory {
      HUMAN,
      VEHICLE,
      MILITARY,
      OTHER,
      UNKNOWN
}

/** Which model produced a detection. */
enum class DetectorSource {
      GROUND,   // COCO EfficientDet - general objects
      AERIAL,   // VisDrone YOLOv8 - drone / top-down viewpoint
      MILITARY  // Military-vehicle YOLOv8 - armour, artillery, military transport
}

data class Detection(
      val box: RectF,
      val label: String,
      val category: ThreatCategory,
      val confidence: Float,
      val source: DetectorSource,
      /** True when the resolution gate demoted this to a coarser label. */
      val gated: Boolean = false
  )

/** A detection still in the coordinate space of a single square tile. */
data class RawDetection(
      val box: RectF,
      val label: String,
      val score: Float
  )

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
      @Volatile var militaryEnabled = true
      @Volatile var showAllClasses = false

      /**
           * Resolution gate, in analysed-frame pixels of box height.
                *
                     * Fine-grained classes need pixels on target to be meaningful. A tank at
                          * 7 m is hundreds of pixels from a drone and resolves fine. A person is
                               * 15-25 px at the same altitude, and anything they are carrying is well
                                    * under one pixel wide -- so a claim like "armed" cannot come from the
                                         * image, only from guesswork. Below this height the detector reports the
                                              * coarse class ("person", "vehicle?") and declines to say more.
                                                   */
      @Volatile var minPixelsForFineClass = 48f
}

/** Live state, read by the overlay HUD. */
object DetectorStatus {
      @Volatile var groundOk = false
      @Volatile var aerialOk = false
      @Volatile var militaryOk = false
      @Volatile var note = "starting"
      @Volatile var groundCount = 0
      @Volatile var aerialCount = 0
      @Volatile var militaryCount = 0
      @Volatile var gatedCount = 0
      @Volatile var lastMs = 0L
      const val VERSION = "4.0"
}

/**
 * Shared label-to-category mapping across all three models. The label sets
  * disagree by design: COCO says "person" and "motorcycle", VisDrone says
   * "pedestrian"/"people" and "motor", and a military model adds armour and
    * artillery classes.
     */
     object Taxonomy {

           private val humanLabels = setOf("person", "pedestrian", "people", "soldier")

               private val vehicleLabels = setOf(
                         "car", "truck", "bus", "motorcycle", "bicycle", "train", "boat",
                         "van", "motor", "tricycle", "awning-tricycle"
                     )

                   private val militaryLabels = setOf(
                             "tank", "armoured-vehicle", "armored-vehicle", "apc",
                             "artillery", "howitzer", "rocket-launcher",
                             "military-truck", "military-vehicle", "helicopter"
                         )

                       fun categorise(label: String): ThreatCategory {
                                 val key = label.trim().lowercase()
                                         return when {
                                                       militaryLabels.contains(key) -> ThreatCategory.MILITARY
                                                       humanLabels.contains(key) -> ThreatCategory.HUMAN
                                                       vehicleLabels.contains(key) -> ThreatCategory.VEHICLE
                                                       else -> ThreatCategory.OTHER
                                         }
                       }

                           /**
                                * Applies the resolution gate. Returns the label and category that the
                                     * available pixels actually support, plus whether a demotion happened.
                                          *
                                               * Military subtypes fall back to a plain vehicle, flagged with "?" so the
                                                    * operator can see the system is unsure rather than being told "tank".
                                                         * People collapse to "person" -- never to any claim about what they are
                                                              * carrying, which is not resolvable from altitude.
                                                                   */
                                                                       fun applyResolutionGate(
                                                                                 label: String,
                                                                                 category: ThreatCategory,
                                                                                 boxHeightPx: Float
                                                                             ): Triple<String, ThreatCategory, Boolean> {
                                                                                 if (boxHeightPx >= DetectorConfig.minPixelsForFineClass) {
                                                                                               return Triple(label, category, false)
                                                                                 }
                                                                                         return when (category) {
                                                                                                       ThreatCategory.MILITARY -> Triple("vehicle?", ThreatCategory.VEHICLE, true)
                                                                                                                   ThreatCategory.HUMAN -> Triple("person", ThreatCategory.HUMAN, label != "person")
                                                                                                                               else -> Triple(label, category, false)
                                                                                         }
                                                                       }
     }
     
