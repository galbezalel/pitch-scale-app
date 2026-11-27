package com.example.pitchscale

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSBundle
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class IosPitchController : PitchEngineController {

    private val isRunningState = mutableStateOf(false)
    private val semitonesState = mutableStateOf(0)
    private val amplitudeState = mutableStateOf(0.0f)
    private val statusTextState = mutableStateOf("Play music below, then tap Start")
    private val selectedModeState = mutableStateOf(MODE_YT_MUSIC)

    override val isRunning = isRunningState
    override val semitones = semitonesState
    override val amplitude = amplitudeState
    override val statusText = statusTextState
    override val selectedModeId = selectedModeState
    override val hasEmbeddedPlayer = true
    override val startRoutingNotice: RoutingNotice? = null

    override val modeOptions: List<ModeOption> = listOf(
        ModeOption(id = MODE_YT_MUSIC, label = "YT Music"),
        ModeOption(id = MODE_YOUTUBE, label = "YouTube"),
        ModeOption(id = MODE_SOUNDCLOUD, label = "SoundCloud")
    )

    private var webView: WKWebView? = null

    private val bridgeHandler = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage
        ) {
            val body = didReceiveScriptMessage.body as? Map<*, *> ?: return
            when (body["type"] as? String) {
                "amplitude" -> {
                    val amp = (body["amplitude"] as? NSNumber)?.floatValue ?: 0f
                    amplitudeState.value = amplitudeState.value * 0.65f + amp * 0.35f
                }
                "attached" -> {
                    if (isRunningState.value) {
                        statusTextState.value = "Active Media Pitch Shifting (Lock-Screen Ready)"
                    }
                }
            }
        }
    }

    init {
        configureBackgroundAudioSession()
    }

    private fun configureBackgroundAudioSession() {
        try {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } catch (_: Throwable) {}
    }

    private fun loadBundledResource(name: String, ext: String): String {
        val path = NSBundle.mainBundle.pathForResource(name, ofType = ext) ?: return ""
        return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) ?: ""
    }

    private fun urlForMode(modeId: String): String = when (modeId) {
        MODE_YOUTUBE -> PitchConfig.URL_YOUTUBE
        MODE_SOUNDCLOUD -> PitchConfig.URL_SOUNDCLOUD
        else -> PitchConfig.URL_YOUTUBE_MUSIC
    }

    override fun selectMode(modeId: String) {
        selectedModeState.value = modeId
        val url = NSURL.URLWithString(urlForMode(modeId)) ?: return
        webView?.loadRequest(NSURLRequest.requestWithURL(url))
    }

    override fun startEngine() {
        configureBackgroundAudioSession()
        isRunningState.value = true
        statusTextState.value = "Active Media Pitch Shifting"
        evalJs("window.__PitchScalerEngine && window.__PitchScalerEngine.setEnabled(true);")
        evalJs("window.__PitchScalerEngine && window.__PitchScalerEngine.setSemitones(${semitonesState.value});")
    }

    override fun stopEngine() {
        isRunningState.value = false
        amplitudeState.value = 0f
        statusTextState.value = "Play music below, then tap Start"
        evalJs("window.__PitchScalerEngine && window.__PitchScalerEngine.setEnabled(false);")
    }

    override fun setSemitones(value: Int) {
        val clamped = PitchConfig.clampSemitones(value)
        semitonesState.value = clamped
        evalJs("window.__PitchScalerEngine && window.__PitchScalerEngine.setSemitones($clamped);")
    }

    private fun evalJs(script: String) {
        webView?.evaluateJavaScript(script, completionHandler = null)
    }

    @Composable
    override fun PlatformMainContent() {
        UIKitView(
            modifier = Modifier.fillMaxSize(),
            properties = UIKitInteropProperties(
                isInteractive = true,
                isNativeAccessibilityEnabled = true
            ),
            factory = {
                val contentController = WKUserContentController()
                contentController.addScriptMessageHandler(bridgeHandler, name = "pitchScalerBridge")

                val workletLoader = loadBundledResource("worklet-loader", "js")
                val engineJs = loadBundledResource("pitch-engine", "js")

                if (workletLoader.isNotEmpty()) {
                    contentController.addUserScript(
                        WKUserScript(
                            source = workletLoader,
                            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                            forMainFrameOnly = false
                        )
                    )
                }
                if (engineJs.isNotEmpty()) {
                    contentController.addUserScript(
                        WKUserScript(
                            source = engineJs,
                            injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                            forMainFrameOnly = false
                        )
                    )
                }

                val config = WKWebViewConfiguration().apply {
                    userContentController = contentController
                    allowsInlineMediaPlayback = true
                    allowsPictureInPictureMediaPlayback = true
                    mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
                }

                val wv = WKWebView(frame = CGRectZero.readValue(), configuration = config)
                wv.allowsBackForwardNavigationGestures = true
                webView = wv

                NSURL.URLWithString(urlForMode(selectedModeState.value))?.let { url ->
                    wv.loadRequest(NSURLRequest.requestWithURL(url))
                }
                wv
            }
        )
    }

    companion object {
        private const val MODE_YT_MUSIC = "yt_music"
        private const val MODE_YOUTUBE = "youtube"
        private const val MODE_SOUNDCLOUD = "soundcloud"
    }
}
