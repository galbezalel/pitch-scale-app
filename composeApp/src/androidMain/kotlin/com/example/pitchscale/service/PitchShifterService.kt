package com.example.pitchscale.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.pitchscale.MainActivity
import com.example.pitchscale.dsp.SoundTouchNative
import com.example.pitchscale.shizuku.IShizukuAudioService
import com.example.pitchscale.shizuku.ShizukuAudioService
import rikka.shizuku.Shizuku
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class PitchShifterService : Service() {

    private val binder = PitchShifterBinder()

    private var sampleRate = 48000
    private var pitchShifter: SoundTouchNative? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var dspThread: Thread? = null

    private var mediaSession: MediaSession? = null
    private var shizukuService: IShizukuAudioService? = null
    private var shizukuUserServiceArgs: Shizuku.UserServiceArgs? = null

    private var activeMode: String = MODE_STANDARD
    private var originalMediaVolume = -1
    private var currentUsage = AudioAttributes.USAGE_ALARM
    private var currentSemitones = 0

    @Volatile
    private var requiresTrackRecreation = false

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var currentAmplitude = 0.0f
        private set

    @Volatile
    var statusMessage: String = "System Audio Engine Idle"
        private set

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopShifting()
            stopSelf()
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (activeMode == MODE_STANDARD) checkAndUpdateRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (activeMode == MODE_STANDARD) checkAndUpdateRouting()
        }
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuService = IShizukuAudioService.Stub.asInterface(service)
            startShizukuDspLoop()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            if (isRunning && activeMode == MODE_SHIZUKU) {
                statusMessage = "Shizuku disconnected"
                stopShifting()
                stopSelf()
            }
        }
    }

    companion object {
        private const val TAG = "PitchShifterService"
        private const val CHANNEL_ID = "pitch_scaler_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MODE = "extra_mode"

        const val MODE_STANDARD = "standard"
        const val MODE_SHIZUKU = "shizuku"
    }

    inner class PitchShifterBinder : Binder() {
        fun getService(): PitchShifterService = this@PitchShifterService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_STANDARD
        activeMode = mode

        if (mode == MODE_SHIZUKU) {
            startShizukuMode()
        } else {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != 0 && resultData != null) {
                startStandardMode(resultCode, resultData)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Queries the device's native mixer sample rate (fixing Android 16+ high-res
     * AHAL_ModuleRemoteSubmix patch mismatch failures when 44100Hz is hardcoded).
     */
    private fun resolveOptimalSampleRate(): Int {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val nativeRate = audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        return when {
            nativeRate == null -> 48000
            nativeRate in 44100..48000 -> nativeRate
            else -> 48000
        }
    }

    private fun initSoundTouch() {
        pitchShifter?.dispose()
        sampleRate = resolveOptimalSampleRate()
        pitchShifter = SoundTouchNative().apply {
            setSampleRate(sampleRate)
            setChannels(1)
            setPitchSemiTones(currentSemitones.toFloat())
        }
    }

    private fun startShizukuMode() {
        if (isRunning) return
        isRunning = true
        statusMessage = "Connecting Shizuku Media Routing..."
        initSoundTouch()

        val notification = createNotification("Seamless Media Stream (Shizuku)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, ShizukuAudioService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_audio")
            .debuggable(false)
            .version(1)

        shizukuUserServiceArgs = args
        try {
            Shizuku.bindUserService(args, shizukuConnection)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind Shizuku user service", t)
            statusMessage = "Shizuku bind failed: ${t.message}"
            stopShifting()
            stopSelf()
        }
    }

    private fun startShizukuDspLoop() {
        val remote = shizukuService ?: return
        val started = try {
            remote.startLoopbackCapture(Process.myUid(), sampleRate)
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku startLoopbackCapture threw", t)
            false
        }

        if (!started) {
            statusMessage = "Shizuku AudioPolicy unavailable on this ROM"
            stopShifting()
            stopSelf()
            return
        }

        // True seamless Media stream! Works identically on speakers & headphones with normal volume keys.
        currentUsage = AudioAttributes.USAGE_MEDIA
        audioTrack = createAudioTrack(AudioAttributes.USAGE_MEDIA)
        statusMessage = "Seamless Media Stream (Shizuku)"

        dspThread = ShizukuDspThread(remote).also { it.start() }
    }

    private fun startStandardMode(resultCode: Int, resultData: Intent) {
        if (isRunning) return
        isRunning = true
        initSoundTouch()

        val notification = createNotification("Scaling background playback audio...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            stopShifting()
            stopSelf()
            return
        }

        // Android 14+/16/17 requires registering a MediaProjection.Callback before starting capture
        mediaProjection?.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

        setupStandardAudio()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)

        setupMediaSession()
        statusMessage = "Active Background Shifting (Standard)"

        dspThread = StandardDspThread().also { it.start() }
    }

    private fun areHeadphonesConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun checkAndUpdateRouting() {
        val hasHeadphones = areHeadphonesConnected()
        val targetUsage = if (hasHeadphones) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_ALARM
        }

        if (targetUsage != currentUsage) {
            currentUsage = targetUsage
            applyEchoHackVolumes(hasHeadphones)
            requiresTrackRecreation = true
        }
    }

    private fun applyEchoHackVolumes(hasHeadphones: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (originalMediaVolume == -1) {
            originalMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        val maxMedia = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetStream = if (hasHeadphones) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_ALARM
        val maxTarget = audioManager.getStreamMaxVolume(targetStream)

        val targetVol = if (maxMedia > 0) {
            ((originalMediaVolume.toFloat() / maxMedia.toFloat()) * maxTarget).toInt()
        } else 0

        audioManager.setStreamVolume(targetStream, targetVol, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun restoreMediaVolume() {
        if (originalMediaVolume != -1) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalMediaVolume, 0)
            originalMediaVolume = -1
        }
    }

    private fun setupMediaSession() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        mediaSession = MediaSession(this, "PitchShifterSession").apply {
            val playbackState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .build()
            setPlaybackState(playbackState)

            val currentVol = audioManager.getStreamVolume(
                if (areHeadphonesConnected()) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_ALARM
            )

            val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 15, currentVol) {
                override fun onAdjustVolume(direction: Int) {
                    val targetStream = if (areHeadphonesConnected()) {
                        AudioManager.STREAM_ACCESSIBILITY
                    } else {
                        AudioManager.STREAM_ALARM
                    }
                    audioManager.adjustStreamVolume(targetStream, direction, AudioManager.FLAG_SHOW_UI)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
            }
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }
    }

    private fun setupStandardAudio() {
        val proj = mediaProjection ?: return

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .build()

        val hasHeadphones = areHeadphonesConnected()
        currentUsage = if (hasHeadphones) {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        } else {
            AudioAttributes.USAGE_ALARM
        }
        applyEchoHackVolumes(hasHeadphones)

        audioTrack = createAudioTrack(currentUsage)
    }

    private fun createAudioTrack(usage: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .build()
    }

    fun setSemitoneShift(semitones: Int) {
        currentSemitones = semitones
        pitchShifter?.setPitchSemiTones(semitones.toFloat())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pitch Scaler Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Runs the background audio capture and pitch shift engine."
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pitch Scaler Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopShifting()
    }

    fun stopShifting() {
        isRunning = false
        currentAmplitude = 0f

        try {
            dspThread?.join(500)
        } catch (_: InterruptedException) {}
        dspThread = null

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {}

        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null

        try {
            shizukuService?.stopLoopbackCapture()
        } catch (_: Throwable) {}
        shizukuUserServiceArgs?.let { args ->
            try {
                Shizuku.unbindUserService(args, shizukuConnection, true)
            } catch (_: Throwable) {}
        }
        shizukuService = null
        shizukuUserServiceArgs = null

        try {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        pitchShifter?.dispose()
        pitchShifter = null
        restoreMediaVolume()
        statusMessage = "System Audio Engine Idle"
    }

    private fun updateAmplitude(samples: ShortArray, count: Int) {
        var maxAmp = 0.0f
        for (i in 0 until count) {
            val v = abs(samples[i].toFloat() / 32768.0f)
            if (v > maxAmp) maxAmp = v
        }
        currentAmplitude = currentAmplitude * 0.7f + maxAmp * 0.3f
    }

    private inner class StandardDspThread : Thread("PitchShifterStandardDsp") {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val record = audioRecord ?: return
            val st = pitchShifter ?: return
            var track = audioTrack

            val chunkSize = 512
            val recordBuf = ShortArray(chunkSize)
            val playBuf = ShortArray(chunkSize * 4)
            val emptyBuf = ShortArray(0)

            try {
                record.startRecording()
                track?.play()

                while (isRunning) {
                    if (requiresTrackRecreation) {
                        requiresTrackRecreation = false
                        try {
                            track?.stop()
                            track?.release()
                        } catch (_: Exception) {}
                        track = createAudioTrack(currentUsage)
                        audioTrack = track
                        track?.play()
                    }

                    val readResult = record.read(recordBuf, 0, chunkSize)
                    if (readResult <= 0) continue

                    updateAmplitude(recordBuf, readResult)

                    var received = st.process(recordBuf, readResult, playBuf)
                    while (received > 0 && isRunning) {
                        track?.write(playBuf, 0, received)
                        received = st.process(emptyBuf, 0, playBuf)
                    }
                }

                var received = st.flush(playBuf)
                while (received > 0) {
                    track?.write(playBuf, 0, received)
                    received = st.flush(playBuf)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Standard DSP loop error", e)
            } finally {
                try { record.stop() } catch (_: Exception) {}
                try { record.release() } catch (_: Exception) {}
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }

    private inner class ShizukuDspThread(
        private val remote: IShizukuAudioService
    ) : Thread("PitchShifterShizukuDsp") {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val st = pitchShifter ?: return
            val track = audioTrack ?: return

            val chunkBytes = 1024 // 512 mono PCM16 samples
            val shortBuf = ShortArray(chunkBytes / 2)
            val playBuf = ShortArray(chunkBytes * 2)
            val emptyBuf = ShortArray(0)

            try {
                track.play()
                while (isRunning) {
                    val pcmBytes = remote.readPcmChunk(chunkBytes)
                    if (pcmBytes.isEmpty()) {
                        sleep(2)
                        continue
                    }

                    val numSamples = pcmBytes.size / 2
                    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuf, 0, numSamples)

                    updateAmplitude(shortBuf, numSamples)

                    var received = st.process(shortBuf, numSamples, playBuf)
                    while (received > 0 && isRunning) {
                        track.write(playBuf, 0, received)
                        received = st.process(emptyBuf, 0, playBuf)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Shizuku DSP loop error", e)
            } finally {
                try { remote.stopLoopbackCapture() } catch (_: Throwable) {}
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
        }
    }
}
