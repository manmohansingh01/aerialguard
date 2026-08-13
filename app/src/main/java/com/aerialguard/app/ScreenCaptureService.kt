package com.aerialguard.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.aerialguard.app.detector.AerialDetector
import com.aerialguard.app.detector.CocoDetector
import com.aerialguard.app.detector.DetectorStatus
import com.aerialguard.app.detector.FrameAnalyzer
import com.aerialguard.app.overlay.OverlayController

/**
 * Foreground service that owns the MediaProjection screen capture, runs every
  * enabled detector on a background thread at a throttled frame rate, and
   * pushes merged results to the system overlay.
    */
class ScreenCaptureService : Service() {

     companion object {
              const val CHANNEL_ID = "aerial_guard_capture"
              const val NOTIF_ID = 1
              const val EXTRA_RESULT_CODE = "extra_result_code"
              const val EXTRA_RESULT_DATA = "extra_result_data"
              const val ACTION_STOP = "com.aerialguard.app.STOP"

              // Two models over three tiles is roughly 300ms of work per frame, so
              // the interval leaves the CPU room rather than queueing frames up.
              private const val PROCESS_INTERVAL_MS = 300L

              private const val CAPTURE_SCALE = 0.5f
     }

         private var mediaProjection: MediaProjection? = null
     private var virtualDisplay: VirtualDisplay? = null
     private var imageReader: ImageReader? = null
     private var frameAnalyzer: FrameAnalyzer? = null

     private lateinit var overlayController: OverlayController

     private var bgThread: HandlerThread? = null
     private var bgHandler: Handler? = null
     private var lastProcessTime = 0L

     private val projectionCallback = object : MediaProjection.Callback() {
              override fun onStop() {
                           stopSelf()
              }
     }

         override fun onCreate() {
                  super.onCreate()
                          createNotificationChannel()
                                  overlayController = OverlayController(applicationContext)
                                          bgThread = HandlerThread("AerialGuardInference").also { it.start() }
                                                  bgHandler = Handler(bgThread!!.looper)
         }

             override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                      if (intent?.action == ACTION_STOP) {
                                   stopSelf()
                                               return START_NOT_STICKY
                      }

                              startForeground(NOTIF_ID, buildNotification())

                                      val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                                                  ?: Activity.RESULT_CANCELED
                      @Suppress("DEPRECATION")
                              val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

                                      if (resultData == null || resultCode != Activity.RESULT_OK) {
                                                   stopSelf()
                                                               return START_NOT_STICKY
                                      }

                                              val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                      mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                              mediaProjection?.registerCallback(projectionCallback, bgHandler)

                                      // The ground model is the guaranteed baseline. The aerial model is
                                              // additive: if it is missing or fails to load the app carries on with
                                                      // the ground model alone and the HUD says so.
                                                              val ground = CocoDetector(applicationContext)
                                                                      val aerial = AerialDetector(applicationContext)

                                                                              DetectorStatus.groundOk = ground.isAvailable
                      DetectorStatus.aerialOk = aerial.isAvailable
                      DetectorStatus.note = when {
                                   ground.isAvailable && aerial.isAvailable -> "both models loaded"
                                   ground.isAvailable -> "aerial: " + aerial.statusNote
                                   aerial.isAvailable -> "ground: " + ground.statusNote
                                   else -> "no model loaded"
                      }

                              frameAnalyzer = FrameAnalyzer(listOf(ground, aerial))

                                      setupVirtualDisplay()
                                              overlayController.show()

                                                      return START_STICKY
             }

                 private fun setupVirtualDisplay() {
                          val metrics = DisplayMetrics()
                                  val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                          @Suppress("DEPRECATION")
                                  windowManager.defaultDisplay.getRealMetrics(metrics)

                                          val captureWidth = (metrics.widthPixels * CAPTURE_SCALE).toInt().coerceAtLeast(2)
                                                  val captureHeight = (metrics.heightPixels * CAPTURE_SCALE).toInt().coerceAtLeast(2)
                                                          val density = metrics.densityDpi

                          val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
                                  reader.setOnImageAvailableListener({ imgReader ->
                                               val now = SystemClock.elapsedRealtime()
                                                           val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                                               if (now - lastProcessTime < PROCESS_INTERVAL_MS) {
                                                                image.close()
                                                                                return@setOnImageAvailableListener
                                               }
                                                           lastProcessTime = now
                                               processImage(image)
                                  }, bgHandler)
                                          imageReader = reader

                          virtualDisplay = mediaProjection?.createVirtualDisplay(
                                       "AerialGuardCapture",
                                       captureWidth, captureHeight, density,
                                       DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                       reader.surface, null, bgHandler
                                   )
                 }

                     private fun processImage(image: Image) {
                              try {
                                           val bitmap = imageToBitmap(image)
                                                       val detections = frameAnalyzer?.analyze(bitmap) ?: emptyList()
                                                                   overlayController.update(detections, bitmap.width, bitmap.height)
                              } catch (e: Exception) {
                                           // Don't let a single bad frame kill the stream.
                              } finally {
                                           image.close()
                              }
                     }

                         private fun imageToBitmap(image: Image): Bitmap {
                                  val plane = image.planes[0]
                                  val buffer = plane.buffer
                                  val pixelStride = plane.pixelStride
                                  val rowStride = plane.rowStride
                                  val rowPadding = rowStride - pixelStride * image.width

                                  val paddedBitmap = Bitmap.createBitmap(
                                               image.width + rowPadding / pixelStride,
                                               image.height,
                                               Bitmap.Config.ARGB_8888
                                           )
                                          paddedBitmap.copyPixelsFromBuffer(buffer)

                                                  return if (rowPadding == 0) {
                                                               paddedBitmap
                                                  } else {
                                                               val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
                                                                           paddedBitmap.recycle()
                                                                                       cropped
                                                  }
                         }

                             private fun buildNotification(): Notification {
                                      val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
                                              val stopPendingIntent = PendingIntent.getService(
                                                           this, 0, stopIntent,
                                                           PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                                       )
                                                      return NotificationCompat.Builder(this, CHANNEL_ID)
                                                                  .setContentTitle("AerialGuard is watching")
                                                                              .setContentText("Detecting people and vehicles on your drone feed")
                                                                                          .setSmallIcon(android.R.drawable.ic_menu_view)
                                                                                                      .addAction(0, "Stop", stopPendingIntent)
                                                                                                                  .setOngoing(true)
                                                                                                                              .build()
                             }

                                 private fun createNotificationChannel() {
                                          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                       val channel = NotificationChannel(
                                                                        CHANNEL_ID, "AerialGuard Detection", NotificationManager.IMPORTANCE_LOW
                                                                    )
                                                                   val manager = getSystemService(NotificationManager::class.java)
                                                                               manager.createNotificationChannel(channel)
                                          }
                                 }

                                     override fun onDestroy() {
                                              super.onDestroy()
                                                      virtualDisplay?.release()
                                                              imageReader?.close()
                                                                      mediaProjection?.unregisterCallback(projectionCallback)
                                                                              mediaProjection?.stop()
                                                                                      overlayController.hide()
                                                                                              frameAnalyzer?.close()
                                                                                                      frameAnalyzer = null
                                              bgThread?.quitSafely()
                                     }

                                         override fun onBind(intent: Intent?): IBinder? = null
}
