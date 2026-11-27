package com.example.pitchscale

import androidx.compose.runtime.mutableStateOf
import java.util.Timer
import java.util.TimerTask

class MacosPitchController : PitchEngineController {

    private val isRunningState = mutableStateOf(false)
    private val semitonesState = mutableStateOf(0)
    private val amplitudeState = mutableStateOf(0.0f)
    private val statusTextState = mutableStateOf("macOS Core Audio Tap Idle")
    private val selectedModeState = mutableStateOf("global")

    override val isRunning = isRunningState
    override val semitones = semitonesState
    override val amplitude = amplitudeState
    override val statusText = statusTextState
    override val selectedModeId = selectedModeState
    override val hasEmbeddedPlayer = false
    override val startRoutingNotice: RoutingNotice? = null

    override val modeOptions: List<ModeOption> = listOf(
        ModeOption(id = "global", label = "All System Audio (Core Audio Tap)")
    )

    private var pollTimer: Timer? = null

    override fun selectMode(modeId: String) {
        selectedModeState.value = modeId
    }

    override fun startEngine() {
        if (!MacosAudioNative.isLoaded) {
            statusTextState.value = "Native macOS audio library not found"
            return
        }

        val ok = MacosAudioNative.nativeStart()
        if (ok) {
            isRunningState.value = true
            statusTextState.value = "Seamless System Audio Tap Active"
            MacosAudioNative.nativeSetSemitones(semitonesState.value.toFloat())
            startPolling()
        } else {
            val err = MacosAudioNative.nativeGetLastError().ifBlank { "Failed to start Core Audio Tap" }
            statusTextState.value = err
            isRunningState.value = false
        }
    }

    override fun stopEngine() {
        stopPolling()
        if (MacosAudioNative.isLoaded) {
            MacosAudioNative.nativeStop()
        }
        isRunningState.value = false
        amplitudeState.value = 0f
        statusTextState.value = "macOS Core Audio Tap Idle"
    }

    override fun setSemitones(value: Int) {
        val clamped = PitchConfig.clampSemitones(value)
        semitonesState.value = clamped
        if (MacosAudioNative.isLoaded) {
            MacosAudioNative.nativeSetSemitones(clamped.toFloat())
        }
    }

    private fun startPolling() {
        stopPolling()
        pollTimer = Timer("PitchScalerMacosUiPoll", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (isRunningState.value && MacosAudioNative.isLoaded) {
                        amplitudeState.value = MacosAudioNative.nativeGetAmplitude()
                    } else {
                        amplitudeState.value = 0f
                    }
                }
            }, 0L, 33L)
        }
    }

    private fun stopPolling() {
        pollTimer?.cancel()
        pollTimer = null
    }
}
