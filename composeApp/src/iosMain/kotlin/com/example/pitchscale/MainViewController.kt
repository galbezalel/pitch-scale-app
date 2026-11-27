package com.example.pitchscale

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    val controller = remember { IosPitchController() }
    PitchScalerApp(controller = controller)
}
