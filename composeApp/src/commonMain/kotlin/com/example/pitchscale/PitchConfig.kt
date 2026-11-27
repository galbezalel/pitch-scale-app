package com.example.pitchscale

import kotlin.math.pow

object PitchConfig {
    const val MIN_SEMITONES = -12
    const val MAX_SEMITONES = 12

    const val URL_YOUTUBE_MUSIC = "https://music.youtube.com"
    const val URL_YOUTUBE = "https://m.youtube.com"
    const val URL_SOUNDCLOUD = "https://m.soundcloud.com"

    fun clampSemitones(value: Int): Int = value.coerceIn(MIN_SEMITONES, MAX_SEMITONES)

    fun pitchRatio(semitones: Float): Double = 2.0.pow(semitones.toDouble() / 12.0)

    fun formatSemitones(semitones: Int): String = when {
        semitones > 0 -> "+$semitones st"
        semitones < 0 -> "$semitones st"
        else -> "Original Pitch"
    }
}
