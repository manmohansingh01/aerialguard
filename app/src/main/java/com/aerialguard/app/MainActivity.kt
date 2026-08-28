package com.aerialguard.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aerialguard.app.detector.DetectorConfig

class MainActivity : AppCompatActivity() {

      private lateinit var statusText: TextView
      private lateinit var thresholdLabel: TextView

      // SeekBar progress 0..45 maps to a 50%..95% confidence threshold.
      private val minThresholdPercent = 50

      private val notificationPermissionLauncher =
          registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

              private val screenCaptureLauncher =
          registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                                          val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                                                                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                                                                                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                                          }
                                                          ContextCompat.startForegroundService(this, serviceIntent)
                                                                          updateStatus("Running - switch to your drone app now. Boxes will appear on top of it.")
                        } else {
                                          updateStatus("Screen capture permission was not granted - tap Start to try again.")
                        }
          }

              override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                                setContentView(R.layout.activity_main)

                                        statusText = findViewById(R.id.statusText)
                                                thresholdLabel = findViewById(R.id.thresholdLabel)
                                                        findViewById<Button>(R.id.startButton).setOnClickListener { onStartClicked() }
                                                                findViewById<Button>(R.id.stopButton).setOnClickListener { onStopClicked() }

                                                                        val seek = findViewById<SeekBar>(R.id.thresholdSeek)
                                                                                seek.progress = (DetectorConfig.minConfidence * 100).toInt() - minThresholdPercent
                        applyThreshold(seek.progress)
                                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                              override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                                                                applyThreshold(progress)
                                              }

                                                          override fun onStartTrackingTouch(bar: SeekBar?) {}
                                                                      override fun onStopTrackingTouch(bar: SeekBar?) {}
                                })

                                        // Every toggle takes effect immediately, even mid-session, because the
                                                // analyser re-reads DetectorConfig on every frame.
                                                        val militaryBox = findViewById<CheckBox>(R.id.militaryCheck)
                                                                militaryBox.isChecked = DetectorConfig.militaryEnabled
                        militaryBox.setOnCheckedChangeListener { _, checked -> DetectorConfig.militaryEnabled = checked }

                                val aerialBox = findViewById<CheckBox>(R.id.aerialCheck)
                                        aerialBox.isChecked = DetectorConfig.aerialEnabled
                        aerialBox.setOnCheckedChangeListener { _, checked -> DetectorConfig.aerialEnabled = checked }

                                val groundBox = findViewById<CheckBox>(R.id.groundCheck)
                                        groundBox.isChecked = DetectorConfig.groundEnabled
                        groundBox.setOnCheckedChangeListener { _, checked -> DetectorConfig.groundEnabled = checked }

                                val allBox = findViewById<CheckBox>(R.id.allClassesCheck)
                                        allBox.isChecked = DetectorConfig.showAllClasses
                        allBox.setOnCheckedChangeListener { _, checked -> DetectorConfig.showAllClasses = checked }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                              notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
              }

                  private fun applyThreshold(progress: Int) {
                            val percent = minThresholdPercent + progress
                            DetectorConfig.minConfidence = percent / 100f
                            thresholdLabel.text = "Only show detections above " + percent + "% confidence"
                  }

                      private fun onStartClicked() {
                                if (!Settings.canDrawOverlays(this)) {
                                              updateStatus("Step 1: allow 'Display over other apps' for NS Netra, then come back and tap Start again.")
                                                          startActivity(
                                                                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
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
