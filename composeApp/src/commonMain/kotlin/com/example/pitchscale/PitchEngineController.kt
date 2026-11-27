package com.example.pitchscale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

data class RoutingNotice(
    val title: String,
    val message: String,
    val confirmLabel: String = "Proceed"
)

data class ModeOption(
    val id: String,
    val label: String,
    val subtitle: String? = null
)

/**
 * Shared platform-agnostic contract consumed by [PitchScalerApp].
 * Android implements this with MediaProjection / Shizuku + SoundTouch NDK;
 * iOS implements this with WKWebView + AVAudioSession + shared Web Audio DSP.
 */
interface PitchEngineController {
    val isRunning: State<Boolean>
    val semitones: State<Int>
    val amplitude: State<Float>
    val statusText: State<String>

    /** Optional mode chips displayed in the header (e.g. Standard/Shizuku on Android, YT Music/YouTube on iOS). */
    val modeOptions: List<ModeOption>
    val selectedModeId: State<String>

    /** Optional confirmation dialog shown before starting the engine. */
    val startRoutingNotice: RoutingNotice?

    /** When true, the center area shows [PlatformMainContent] alongside a compact waveform instead of a full-height visualizer. */
    val hasEmbeddedPlayer: Boolean

    fun selectMode(modeId: String)
    fun startEngine()
    fun stopEngine()
    fun setSemitones(value: Int)

    fun incrementSemitones() {
        setSemitones(PitchConfig.clampSemitones(semitones.value + 1))
    }

    fun decrementSemitones() {
        setSemitones(PitchConfig.clampSemitones(semitones.value - 1))
    }

    @Composable
    fun PlatformMainContent() {
        // Default no-op for platforms without an embedded media view (Android)
    }
}
