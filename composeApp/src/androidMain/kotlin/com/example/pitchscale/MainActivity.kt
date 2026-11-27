package com.example.pitchscale

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.pitchscale.service.PitchShifterService
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity() {

    private var pitchService: PitchShifterService? = null
    private var isBound = false
    private lateinit var controller: AndroidPitchController

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQ_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startShizukuService()
            } else {
                Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PitchShifterService.PitchShifterBinder
            pitchService = binder.getService()
            isBound = true
            controller.isRunningState.value = true
            pitchService?.setSemitoneShift(controller.semitonesState.value)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pitchService = null
            isBound = false
            controller.isRunningState.value = false
        }
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchMediaProjection()
        } else {
            Toast.makeText(this, "Microphone permission is required to capture audio", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, PitchShifterService::class.java).apply {
                putExtra(PitchShifterService.EXTRA_MODE, PitchShifterService.MODE_STANDARD)
                putExtra(PitchShifterService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(PitchShifterService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            controller.isRunningState.value = true
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.init(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }

        val logFile = File(getExternalFilesDir(null), "crash_log.txt")
        if (logFile.exists()) {
            try {
                val crashContent = logFile.readText()
                logFile.delete()
                showCrashDialog(crashContent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        controller = AndroidPitchController(
            onStartStandard = { checkPermissionsAndStartStandard() },
            onStartShizuku = { startShizukuService() },
            onRequestShizukuPermission = { Shizuku.requestPermission(SHIZUKU_REQ_CODE) },
            onStopService = { stopPitchService() },
            onSemitonesChanged = { st -> pitchService?.setSemitoneShift(st) },
            onShowMessage = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        )

        setContent {
            PitchScalerApp(controller = controller)
        }

        startUiSyncLoop()
    }

    private fun startUiSyncLoop() {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post(object : Runnable {
            override fun run() {
                val svc = pitchService
                if (isBound && svc != null && svc.isRunning) {
                    controller.isRunningState.value = true
                    controller.amplitudeState.value = svc.currentAmplitude
                    controller.statusTextState.value = svc.statusMessage
                } else if (controller.isRunningState.value && svc != null && !svc.isRunning) {
                    controller.isRunningState.value = false
                    controller.amplitudeState.value = 0f
                    controller.statusTextState.value = svc.statusMessage
                } else {
                    controller.amplitudeState.value = 0f
                }
                mainHandler.postDelayed(this, 33)
            }
        })
    }

    private fun checkPermissionsAndStartStandard() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchMediaProjection()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startShizukuService() {
        val serviceIntent = Intent(this, PitchShifterService::class.java).apply {
            putExtra(PitchShifterService.EXTRA_MODE, PitchShifterService.MODE_SHIZUKU)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        controller.isRunningState.value = true
    }

    private fun stopPitchService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, PitchShifterService::class.java))
        controller.isRunningState.value = false
        controller.amplitudeState.value = 0f
        controller.statusTextState.value = "System Audio Engine Idle"
        pitchService = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun showCrashDialog(crashContent: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Previous Crash Detected")
            .setMessage(crashContent.take(1500) + if (crashContent.length > 1500) "\n\n... (truncated)" else "")
            .setPositiveButton("Copy & Close") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", crashContent))
                Toast.makeText(this, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    companion object {
        private const val SHIZUKU_REQ_CODE = 4201
    }
}
