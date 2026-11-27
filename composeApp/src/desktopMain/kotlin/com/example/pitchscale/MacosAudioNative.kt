package com.example.pitchscale

import java.io.File

object MacosAudioNative {
    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = loadLibrary()
    }

    private fun loadLibrary(): Boolean {
        val composeResDir = System.getProperty("compose.application.resources.dir")
        val javaHome = System.getProperty("java.home")

        val candidates = listOfNotNull(
            System.getProperty("pitchscale.native.lib"),
            // Inside jpackage .app: compose.application.resources.dir is Contents/app -> ../Frameworks
            composeResDir?.let { File(it, "../Frameworks/libpitchscale_macos.dylib").canonicalPath },
            // Inside jpackage .app: java.home is Contents/runtime/Contents/Home -> ../../../Frameworks
            javaHome?.let { File(it, "../../../Frameworks/libpitchscale_macos.dylib").canonicalPath },
            File(System.getProperty("user.dir"), "dist/Pitch Scaler.app/Contents/Frameworks/libpitchscale_macos.dylib").absolutePath,
            File(System.getProperty("user.dir"), "native/macos/build/libpitchscale_macos.dylib").absolutePath
        )

        for (path in candidates) {
            val f = File(path)
            if (f.exists()) {
                try {
                    System.load(f.absolutePath)
                    return true
                } catch (_: UnsatisfiedLinkError) {}
            }
        }

        return try {
            System.loadLibrary("pitchscale_macos")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeSetSemitones(semitones: Float)
    external fun nativeGetAmplitude(): Float
    external fun nativeGetLastError(): String
}
