package com.aerialguard.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.aerialguard.app.detector.Detection

/**
 * Adds/removes the full-screen, touch-through detection overlay via
  * WindowManager. Requires the "Display over other apps" permission
   * (Settings.canDrawOverlays), which MainActivity walks the user through.
    */
class OverlayController(private val context: Context) {

      private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
      private var boxView: OverlayBoxView? = null

      fun show() {
                if (boxView != null) return

                val view = OverlayBoxView(context)
                        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                                      @Suppress("DEPRECATION")
                                                  WindowManager.LayoutParams.TYPE_PHONE
                        }

                                val params = WindowManager.LayoutParams(
                                              WindowManager.LayoutParams.MATCH_PARENT,
                                              WindowManager.LayoutParams.MATCH_PARENT,
                                              overlayType,
                                              WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                  WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                                  WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                              PixelFormat.TRANSLUCENT
                                          )
                                        params.gravity = Gravity.TOP or Gravity.START

                windowManager.addView(view, params)
                        boxView = view
      }

          fun update(detections: List<Detection>, sourceWidth: Int, sourceHeight: Int) {
                    boxView?.updateDetections(detections, sourceWidth, sourceHeight)
          }

              fun hide() {
                        boxView?.let {
                                      windowManager.removeView(it)
                                                  boxView = null
                        }
              }
}
