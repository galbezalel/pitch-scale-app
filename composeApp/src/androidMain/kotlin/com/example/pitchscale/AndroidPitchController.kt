package com.example.pitchscale

import android.content.pm.PackageManager
import androidx.compose.runtime.mutableStateOf
import com.example.pitchscale.service.PitchShifterService
import rikka.shizuku.Shizuku

class AndroidPitchController(
    private val onStartStandard: () -> Unit,
    private val onStartShizuku: () -> Unit,
    private val onRequestShizukuPermission: () -> Unit,
    private val onStopService: () -> Unit,
    private val onSemitonesChanged: (Int) -> Unit,
    private val onShowMessage: (String) -> Unit
) : PitchEngineController {

    val isRunningState = mutableStateOf(false)
    val semitonesState = mutableStateOf(0)
    val amplitudeState = mutableStateOf(0.0f)
    val statusTextState = mutableStateOf("System Audio Engine Idle")
    val selectedModeState = mutableStateOf(PitchShifterService.MODE_STANDARD)

    override val isRunning = isRunningState
    override val semitones = semitonesState
    override val amplitude = amplitudeState
    override val statusText = statusTextState
    override val selectedModeId = selectedModeState
    override val hasEmbeddedPlayer = false

    override val modeOptions: List<ModeOption> = listOf(
        ModeOption(
            id = PitchShifterService.MODE_STANDARD,
            label = "Standard",
            subtitle = "No root • MediaProjection"
        ),
        ModeOption(
            id = PitchShifterService.MODE_SHIZUKU,
            label = "Shizuku (Media)",
            subtitle = "Seamless Media stream"
        )
    )

    override val startRoutingNotice: RoutingNotice?
        get() = if (selectedModeState.value == PitchShifterService.MODE_STANDARD) {
            RoutingNotice(
                title = "Audio Routing Notice",
                message = "In Standard mode, Android cannot mute other apps on the Media stream directly. Your Media stream will be muted while pitch-shifted audio routes over Accessibility (headphones) or Alarm (speakers).\n\nFor true Media-stream routing, switch to Shizuku mode."
            )
        } else {
            null
        }

    override fun selectMode(modeId: String) {
        if (isRunningState.value) {
            onShowMessage("Stop the engine before switching routing mode")
            return
        }
        selectedModeState.value = modeId
        statusTextState.value = if (modeId == PitchShifterService.MODE_SHIZUKU) {
            "Shizuku Media-Stream Mode Ready"
        } else {
            "System Audio Engine Idle"
        }
    }

    override fun startEngine() {
        if (selectedModeState.value == PitchShifterService.MODE_SHIZUKU) {
            if (!Shizuku.pingBinder()) {
                onShowMessage("Shizuku is not running. Start Shizuku via Wireless Debugging first.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                onStartShizuku()
            } else {
                onRequestShizukuPermission()
            }
        } else {
            onStartStandard()
        }
    }

    override fun stopEngine() {
        onStopService()
    }

    override fun setSemitones(value: Int) {
        val clamped = PitchConfig.clampSemitones(value)
        semitonesState.value = clamped
        onSemitonesChanged(clamped)
    }
}
