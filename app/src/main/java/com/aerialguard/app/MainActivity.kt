package com.aerialguard.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

      private lateinit var statusText: TextView

      private val notificationPermissionLauncher =
          registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

              private val screenCaptureLauncher =
          registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                                          val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                                                                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                                                                                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                                          }
                                                          ContextCompat.startForegroundService(this, serviceIntent)
                                                                          updateStatus("Running — switch to your drone app now. Boxes will appear on top of it.")
                        } else {
                                          updateStatus("Screen capture permission was not granted — tap Start to try again.")
                        }
          }

              override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                                setContentView(R.layout.activity_main)

                                        statusText = findViewById(R.id.statusText)
                                                findViewById<Button>(R.id.startButton).setOnClickListener { onStartClicked() }
                                                        findViewById<Button>(R.id.stopButton).setOnClickListener { onStopClicked() }

                                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                                              notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                                                }
              }

                  private fun onStartClicked() {
                            if (!Settings.canDrawOverlays(this)) {
                                          updateStatus("Step 1: allow 'Display over other apps' for AerialGuard, then come back and tap Start again.")
                                                      startActivity(
                                                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                                                                    )
                                                                  return
                            }

                                    updateStatus("Step 2: tap 'Start now' on the next screen to allow screen capture.")
                                            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                  }

                      private fun onStopClicked() {
                                stopService(Intent(this, ScreenCaptureService::class.java))
                                        updateStatus("Stopped.")
                      }

                          private fun updateStatus(text: String) {
                                    statusText.text = text
                          }
}
