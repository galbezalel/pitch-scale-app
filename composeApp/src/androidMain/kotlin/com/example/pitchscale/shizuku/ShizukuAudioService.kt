package com.example.pitchscale.shizuku

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import androidx.annotation.Keep
import java.lang.reflect.Method

/**
 * Runs inside Shizuku's privileged shell process (UID 2000), which holds
 * android.permission.MODIFY_AUDIO_ROUTING on Android 13+.
 *
 * Registers an AudioPolicy with MIX_ROUTE_FLAG_LOOP_BACK (without MIX_ROUTE_FLAG_RENDER),
 * which exclusively intercepts other apps' USAGE_MEDIA/GAME/UNKNOWN audio so the source app
 * never reaches the hardware speaker/headphone mixer. Meanwhile PitchShifterService outputs
 * the pitch-shifted stream directly on USAGE_MEDIA (STREAM_MUSIC).
 */
@Keep
class ShizukuAudioService : IShizukuAudioService.Stub {

    private var context: Context? = null
    private var audioManager: AudioManager? = null
    private var audioPolicy: Any? = null
    private var audioRecord: AudioRecord? = null

    constructor() {
        Log.i(TAG, "ShizukuAudioService default constructor")
    }

    @Keep
    constructor(ctx: Context) {
        this.context = ctx
        this.audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        Log.i(TAG, "ShizukuAudioService context constructor")
    }

    override fun startLoopbackCapture(excludeUid: Int, sampleRate: Int): Boolean {
        stopLoopbackCapture()
        val ctx = context ?: resolveSystemContext() ?: run {
            Log.e(TAG, "Unable to resolve Context inside Shizuku service")
            return false
        }
        val am = audioManager ?: (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager) ?: return false
        audioManager = am

        return try {
            // Build AudioMixingRule matching USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN and excluding our app UID
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance()

            val addRuleMethod = ruleBuilderClass.getMethod(
                "addRule",
                AudioAttributes::class.java,
                Int::class.javaPrimitiveType
            )
            // RULE_MATCH_ATTRIBUTE_USAGE = 0x1
            val matchUsage = 0x1
            for (usage in intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN
            )) {
                val attrs = AudioAttributes.Builder().setUsage(usage).build()
                addRuleMethod.invoke(ruleBuilder, attrs, matchUsage)
            }

            // Exclude PitchScale's own UID so our USAGE_MEDIA output isn't captured back in a loop
            // RULE_EXCLUDE_UID = 0x8004
            val excludeUidRule = 0x8004
            val addMixRuleMethod = findMethod(ruleBuilderClass, "addMixRule", Int::class.javaPrimitiveType!!, Any::class.java)
            addMixRuleMethod?.invoke(ruleBuilder, excludeUidRule, excludeUid)

            val mixingRule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            // Build AudioMix with MIX_ROUTE_FLAG_LOOP_BACK (0x2) ONLY — no RENDER flag -> silent intercept!
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val mixBuilder = mixBuilderClass.getDeclaredConstructor(mixingRuleClass).newInstance(mixingRule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, audioFormat)
            // MIX_ROUTE_FLAG_LOOP_BACK = 0x2
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, 0x2)
            val audioMix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            // Build & register AudioPolicy
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getDeclaredConstructor(Context::class.java).newInstance(ctx)
            val audioMixClass = Class.forName("android.media.audiopolicy.AudioMix")
            policyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val registerMethod = AudioManager::class.java.getMethod("registerAudioPolicy", policyClass)
            val result = registerMethod.invoke(am, policy) as Int
            if (result != 0) {
                Log.e(TAG, "registerAudioPolicy failed with code=$result")
                return false
            }

            val record = policyClass.getMethod("createAudioRecordSink", audioMixClass)
                .invoke(policy, audioMix) as? AudioRecord
            if (record == null) {
                Log.e(TAG, "createAudioRecordSink returned null")
                if (policy != null) unregisterPolicy(am, policy)
                return false
            }

            audioPolicy = policy
            audioRecord = record
            record.startRecording()
            Log.i(TAG, "Shizuku exclusive loopback capture started at ${sampleRate}Hz (excluding uid=$excludeUid)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Shizuku loopback capture (API ${Build.VERSION.SDK_INT})", t)
            stopLoopbackCapture()
            false
        }
    }

    override fun readPcmChunk(maxBytes: Int): ByteArray {
        val rec = audioRecord ?: return ByteArray(0)
        val safeLen = maxBytes.coerceIn(256, 16384)
        val buf = ByteArray(safeLen)
        val read = try {
            rec.read(buf, 0, safeLen)
        } catch (t: Throwable) {
            -1
        }
        return if (read > 0) {
            if (read == safeLen) buf else buf.copyOf(read)
        } else {
            ByteArray(0)
        }
    }

    override fun stopLoopbackCapture() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null

        val am = audioManager
        val policy = audioPolicy
        if (am != null && policy != null) {
            unregisterPolicy(am, policy)
        }
        audioPolicy = null
    }

    override fun destroy() {
        stopLoopbackCapture()
        System.exit(0)
    }

    private fun unregisterPolicy(am: AudioManager, policy: Any) {
        try {
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(am, policy)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to unregister AudioPolicy", t)
        }
    }

    private fun findMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        return try {
            clazz.getMethod(name, *params)
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    private fun resolveSystemContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            activityThreadClass.getMethod("getSystemContext").invoke(currentThread) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "ShizukuAudioService"
    }
}
