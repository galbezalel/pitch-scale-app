#import <Foundation/Foundation.h>
#import <CoreAudio/CoreAudio.h>
#import <CoreAudio/CATapDescription.h>
#import <CoreAudio/AudioHardwareTapping.h>
#import <AudioToolbox/AudioToolbox.h>
#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>
#include "../soundtouch/soundtouch_c_api.h"

namespace {

// Lock-free single-producer / single-consumer ring buffer for stereo float32 samples
struct FloatRingBuffer {
    std::vector<float> buf;
    size_t mask = 0;
    std::atomic<size_t> readIdx{0};
    std::atomic<size_t> writeIdx{0};

    void init(size_t powerOfTwoSize) {
        buf.assign(powerOfTwoSize, 0.0f);
        mask = powerOfTwoSize - 1;
        readIdx.store(0, std::memory_order_relaxed);
        writeIdx.store(0, std::memory_order_relaxed);
    }

    size_t availableRead() const {
        return writeIdx.load(std::memory_order_acquire) - readIdx.load(std::memory_order_relaxed);
    }

    size_t availableWrite() const {
        return buf.size() - (writeIdx.load(std::memory_order_relaxed) - readIdx.load(std::memory_order_acquire));
    }

    void push(const float* src, size_t count) {
        if (buf.empty() || count == 0) return;
        size_t avail = availableWrite();
        if (count > avail) {
            // Drop oldest samples if consumer falls behind to stay low-latency
            size_t drop = count - avail;
            readIdx.fetch_add(drop, std::memory_order_acq_rel);
        }
        size_t w = writeIdx.load(std::memory_order_relaxed);
        for (size_t i = 0; i < count; ++i) {
            buf[(w + i) & mask] = src[i];
        }
        writeIdx.store(w + count, std::memory_order_release);
    }

    size_t pop(float* dst, size_t count) {
        if (buf.empty() || count == 0) return 0;
        size_t avail = availableRead();
        size_t toRead = std::min(count, avail);
        size_t r = readIdx.load(std::memory_order_relaxed);
        for (size_t i = 0; i < toRead; ++i) {
            dst[i] = buf[(r + i) & mask];
        }
        readIdx.store(r + toRead, std::memory_order_release);
        return toRead;
    }
};

struct MacosPitchEngine {
    std::mutex lifecycleMutex;
    std::atomic<bool> running{false};
    std::atomic<float> amplitude{0.0f};
    std::atomic<float> semitones{0.0f};

    AudioUnit outputUnit = nullptr;
    AudioObjectID tapID = kAudioObjectUnknown;
    AudioObjectID tapAggDeviceID = kAudioObjectUnknown;
    AudioDeviceIOProcID tapIOProcID = nullptr;
    SoundTouchHandle stHandle = nullptr;

    double sampleRate = 48000.0;
    FloatRingBuffer outputRing;
    std::vector<int16_t> inI16;
    std::vector<int16_t> outI16;
    std::vector<float> outF32;

    std::string lastError;

    AudioObjectID getOwnProcessObjectID() {
        pid_t pid = getpid();
        AudioObjectPropertyAddress addr = {
            kAudioHardwarePropertyTranslatePIDToProcessObject,
            kAudioObjectPropertyScopeGlobal,
            kAudioObjectPropertyElementMain
        };
        AudioObjectID procObj = kAudioObjectUnknown;
        UInt32 size = sizeof(procObj);
        OSStatus status = AudioObjectGetPropertyData(
            kAudioObjectSystemObject, &addr, sizeof(pid), &pid, &size, &procObj
        );
        return (status == noErr) ? procObj : kAudioObjectUnknown;
    }

    double getTapSampleRate(AudioObjectID tap) {
        AudioObjectPropertyAddress addr = {
            kAudioTapPropertyFormat,
            kAudioObjectPropertyScopeGlobal,
            kAudioObjectPropertyElementMain
        };
        AudioStreamBasicDescription asbd{};
        UInt32 size = sizeof(asbd);
        if (AudioObjectGetPropertyData(tap, &addr, 0, nullptr, &size, &asbd) == noErr &&
            asbd.mSampleRate >= 8000.0 && asbd.mSampleRate <= 192000.0) {
            return asbd.mSampleRate;
        }
        return 48000.0;
    }

    static OSStatus outputRenderCallback(
        void* inRefCon,
        AudioUnitRenderActionFlags* /*ioActionFlags*/,
        const AudioTimeStamp* /*inTimeStamp*/,
        UInt32 /*inBusNumber*/,
        UInt32 inNumberFrames,
        AudioBufferList* ioData
    ) {
        auto* self = static_cast<MacosPitchEngine*>(inRefCon);
        if (!ioData || ioData->mNumberBuffers == 0) return noErr;

        float* out = static_cast<float*>(ioData->mBuffers[0].mData);
        if (!out) return noErr;

        size_t totalSamples = static_cast<size_t>(inNumberFrames) * 2;
        if (!self->running.load(std::memory_order_relaxed)) {
            std::memset(out, 0, totalSamples * sizeof(float));
            return noErr;
        }

        size_t popped = self->outputRing.pop(out, totalSamples);
        if (popped < totalSamples) {
            std::memset(out + popped, 0, (totalSamples - popped) * sizeof(float));
        }
        return noErr;
    }

    bool startOutputUnit(double sr) {
        AudioComponentDescription desc{};
        desc.componentType = kAudioUnitType_Output;
        desc.componentSubType = kAudioUnitSubType_DefaultOutput;
        desc.componentManufacturer = kAudioUnitManufacturer_Apple;

        AudioComponent comp = AudioComponentFindNext(nullptr, &desc);
        if (!comp) {
            lastError = "DefaultOutput AudioComponent not found.";
            return false;
        }

        OSStatus status = AudioComponentInstanceNew(comp, &outputUnit);
        if (status != noErr || !outputUnit) {
            lastError = "AudioComponentInstanceNew failed (" + std::to_string(status) + ").";
            return false;
        }

        AudioStreamBasicDescription streamFmt{};
        streamFmt.mSampleRate = sr;
        streamFmt.mFormatID = kAudioFormatLinearPCM;
        streamFmt.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
        streamFmt.mBytesPerPacket = sizeof(float) * 2;
        streamFmt.mFramesPerPacket = 1;
        streamFmt.mBytesPerFrame = sizeof(float) * 2;
        streamFmt.mChannelsPerFrame = 2;
        streamFmt.mBitsPerChannel = 32;

        status = AudioUnitSetProperty(
            outputUnit,
            kAudioUnitProperty_StreamFormat,
            kAudioUnitScope_Input,
            0,
            &streamFmt,
            sizeof(streamFmt)
        );
        if (status != noErr) {
            lastError = "AudioUnitSetProperty(StreamFormat) failed (" + std::to_string(status) + ").";
            return false;
        }

        AURenderCallbackStruct cb{};
        cb.inputProc = outputRenderCallback;
        cb.inputProcRefCon = this;
        status = AudioUnitSetProperty(
            outputUnit,
            kAudioUnitProperty_SetRenderCallback,
            kAudioUnitScope_Input,
            0,
            &cb,
            sizeof(cb)
        );
        if (status != noErr) {
            lastError = "AudioUnitSetProperty(RenderCallback) failed (" + std::to_string(status) + ").";
            return false;
        }

        status = AudioUnitInitialize(outputUnit);
        if (status != noErr) {
            lastError = "AudioUnitInitialize failed (" + std::to_string(status) + ").";
            return false;
        }

        status = AudioOutputUnitStart(outputUnit);
        if (status != noErr) {
            lastError = "AudioOutputUnitStart failed (" + std::to_string(status) + ").";
            return false;
        }
        return true;
    }

    bool start() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (running.load()) return true;
        lastError.clear();

        if (@available(macOS 14.2, *)) {
            @autoreleasepool {
                outputRing.init(65536); // power of 2 (~680ms stereo capacity)
                inI16.resize(8192 * 2);
                outI16.resize(8192 * 2);
                outF32.resize(8192 * 2);

                // STEP 1: Start our DefaultOutput AudioUnit FIRST!
                // This registers our PID with coreaudiod so getOwnProcessObjectID() is valid,
                // preventing CATapMutedWhenTapped from muting Pitch Scaler's own shifted output.
                sampleRate = 48000.0;
                if (!startOutputUnit(sampleRate)) {
                    cleanupLocked();
                    return false;
                }

                // Wait briefly for coreaudiod to publish our process object
                AudioObjectID myProcObj = kAudioObjectUnknown;
                for (int attempt = 0; attempt < 20; ++attempt) {
                    myProcObj = getOwnProcessObjectID();
                    if (myProcObj != kAudioObjectUnknown) break;
                    usleep(10000);
                }

                NSArray<NSNumber*>* excluded = (myProcObj != kAudioObjectUnknown)
                    ? @[@(myProcObj)]
                    : @[];

                // STEP 2: Create global stereo tap excluding our own process + muting tapped audio
                CATapDescription* tapDesc = [[CATapDescription alloc] initStereoGlobalTapButExcludeProcesses:excluded];
                tapDesc.muteBehavior = CATapMutedWhenTapped;
                tapDesc.privateTap = YES;
                tapDesc.UUID = [NSUUID UUID];
                tapDesc.name = @"PitchScaler System Tap";

                OSStatus status = AudioHardwareCreateProcessTap(tapDesc, &tapID);
                if (status != noErr || tapID == kAudioObjectUnknown) {
                    lastError = "AudioHardwareCreateProcessTap failed (" + std::to_string(status) +
                                "). Grant System Audio Recording permission in System Settings.";
                    cleanupLocked();
                    return false;
                }

                // Match sample rate to tap if different
                double tapSR = getTapSampleRate(tapID);
                if (std::fabs(tapSR - sampleRate) > 1.0) {
                    sampleRate = tapSR;
                    if (outputUnit) {
                        AudioOutputUnitStop(outputUnit);
                        AudioUnitUninitialize(outputUnit);
                        AudioComponentInstanceDispose(outputUnit);
                        outputUnit = nullptr;
                    }
                    if (!startOutputUnit(sampleRate)) {
                        cleanupLocked();
                        return false;
                    }
                }

                // STEP 3: Create tap-only private Aggregate Device (input comes purely from the tap)
                NSString* aggUID = [NSString stringWithFormat:@"com.example.pitchscale.tapagg.%@", [NSUUID UUID].UUIDString];
                NSDictionary* aggDesc = @{
                    @kAudioAggregateDeviceNameKey: @"PitchScaler Tap Aggregate",
                    @kAudioAggregateDeviceUIDKey: aggUID,
                    @kAudioAggregateDeviceIsPrivateKey: @YES,
                    @kAudioAggregateDeviceIsStackedKey: @NO,
                    @kAudioAggregateDeviceTapAutoStartKey: @YES,
                    @kAudioAggregateDeviceTapListKey: @[
                        @{
                            @kAudioSubTapUIDKey: tapDesc.UUID.UUIDString,
                            @kAudioSubTapDriftCompensationKey: @YES
                        }
                    ]
                };

                status = AudioHardwareCreateAggregateDevice((__bridge CFDictionaryRef)aggDesc, &tapAggDeviceID);
                if (status != noErr || tapAggDeviceID == kAudioObjectUnknown) {
                    lastError = "AudioHardwareCreateAggregateDevice failed (" + std::to_string(status) + ").";
                    cleanupLocked();
                    return false;
                }

                // STEP 4: Initialize shared C++ SoundTouch DSP
                stHandle = st_create();
                st_set_sample_rate(stHandle, static_cast<uint32_t>(sampleRate));
                st_set_channels(stHandle, 2);
                st_set_pitch_semitones(stHandle, semitones.load());

                MacosPitchEngine* self = this;
                status = AudioDeviceCreateIOProcIDWithBlock(
                    &tapIOProcID,
                    tapAggDeviceID,
                    nullptr,
                    ^(const AudioTimeStamp* /*inNow*/,
                      const AudioBufferList* inInputData,
                      const AudioTimeStamp* /*inInputTime*/,
                      AudioBufferList* /*outOutputData*/,
                      const AudioTimeStamp* /*inOutputTime*/) {
                        self->onTapInput(inInputData);
                    }
                );

                if (status != noErr || !tapIOProcID) {
                    lastError = "AudioDeviceCreateIOProcIDWithBlock failed (" + std::to_string(status) + ").";
                    cleanupLocked();
                    return false;
                }

                running.store(true, std::memory_order_release);
                status = AudioDeviceStart(tapAggDeviceID, tapIOProcID);
                if (status != noErr) {
                    lastError = "AudioDeviceStart failed (" + std::to_string(status) + ").";
                    running.store(false);
                    cleanupLocked();
                    return false;
                }

                return true;
            }
        } else {
            lastError = "macOS 14.2 or newer is required for Core Audio Process Taps.";
            return false;
        }
    }

    void onTapInput(const AudioBufferList* inData) {
        if (!running.load(std::memory_order_relaxed) || !stHandle || !inData || inData->mNumberBuffers == 0) {
            return;
        }

        const AudioBuffer& inBuf0 = inData->mBuffers[0];
        if (!inBuf0.mData || inBuf0.mDataByteSize == 0) return;

        UInt32 inCh0 = inBuf0.mNumberChannels;
        UInt32 numFrames = (inCh0 > 0) ? (inBuf0.mDataByteSize / (sizeof(float) * inCh0)) : 0;
        if (numFrames == 0 || numFrames > 8192) return;

        float peak = 0.0f;
        const float* src0 = static_cast<const float*>(inBuf0.mData);
        const float* src1 = (inData->mNumberBuffers > 1 && inData->mBuffers[1].mData)
            ? static_cast<const float*>(inData->mBuffers[1].mData)
            : nullptr;

        float stVal = semitones.load(std::memory_order_relaxed);
        bool bypass = (std::fabs(stVal) < 0.01f);

        for (UInt32 i = 0; i < numFrames; ++i) {
            float l = 0.0f;
            float r = 0.0f;
            if (inCh0 >= 2) {
                l = src0[i * inCh0];
                r = src0[i * inCh0 + 1];
            } else if (src1) {
                l = src0[i];
                r = src1[i];
            } else {
                l = src0[i];
                r = src0[i];
            }

            float aL = std::fabs(l);
            float aR = std::fabs(r);
            if (aL > peak) peak = aL;
            if (aR > peak) peak = aR;

            if (bypass) {
                outF32[i * 2] = l;
                outF32[i * 2 + 1] = r;
            } else {
                l = std::fmax(-1.0f, std::fmin(1.0f, l));
                r = std::fmax(-1.0f, std::fmin(1.0f, r));
                inI16[i * 2] = static_cast<int16_t>(l * 32767.0f);
                inI16[i * 2 + 1] = static_cast<int16_t>(r * 32767.0f);
            }
        }

        float prevAmp = amplitude.load(std::memory_order_relaxed);
        amplitude.store(prevAmp * 0.7f + peak * 0.3f, std::memory_order_relaxed);

        if (bypass) {
            outputRing.push(outF32.data(), numFrames * 2);
        } else {
            st_put_samples_i16(stHandle, inI16.data(), numFrames);
            constexpr float invScale = 1.0f / 32768.0f;
            uint32_t recFrames = st_receive_samples_i16(stHandle, outI16.data(), 8192);
            while (recFrames > 0) {
                size_t total = static_cast<size_t>(recFrames) * 2;
                for (size_t i = 0; i < total; ++i) {
                    outF32[i] = outI16[i] * invScale;
                }
                outputRing.push(outF32.data(), total);
                recFrames = st_receive_samples_i16(stHandle, outI16.data(), 8192);
            }
        }
    }

    void setSemitones(float value) {
        semitones.store(value, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        if (stHandle) {
            st_set_pitch_semitones(stHandle, value);
        }
    }

    void stop() {
        std::lock_guard<std::mutex> lock(lifecycleMutex);
        running.store(false, std::memory_order_release);
        amplitude.store(0.0f);
        cleanupLocked();
    }

    void cleanupLocked() {
        if (tapAggDeviceID != kAudioObjectUnknown && tapIOProcID != nullptr) {
            AudioDeviceStop(tapAggDeviceID, tapIOProcID);
            AudioDeviceDestroyIOProcID(tapAggDeviceID, tapIOProcID);
            tapIOProcID = nullptr;
        }
        if (tapAggDeviceID != kAudioObjectUnknown) {
            AudioHardwareDestroyAggregateDevice(tapAggDeviceID);
            tapAggDeviceID = kAudioObjectUnknown;
        }
        if (tapID != kAudioObjectUnknown) {
            if (@available(macOS 14.2, *)) {
                AudioHardwareDestroyProcessTap(tapID);
            }
            tapID = kAudioObjectUnknown;
        }
        if (outputUnit) {
            AudioOutputUnitStop(outputUnit);
            AudioUnitUninitialize(outputUnit);
            AudioComponentInstanceDispose(outputUnit);
            outputUnit = nullptr;
        }
        if (stHandle) {
            st_destroy(stHandle);
            stHandle = nullptr;
        }
    }
};

MacosPitchEngine gEngine;

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_pitchscale_MacosAudioNative_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_MacosAudioNative_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    gEngine.stop();
}

JNIEXPORT void JNICALL
Java_com_example_pitchscale_MacosAudioNative_nativeSetSemitones(JNIEnv* /*env*/, jobject /*thiz*/, jfloat st) {
    gEngine.setSemitones(st);
}

JNIEXPORT jfloat JNICALL
Java_com_example_pitchscale_MacosAudioNative_nativeGetAmplitude(JNIEnv* /*env*/, jobject /*thiz*/) {
    return gEngine.amplitude.load(std::memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_com_example_pitchscale_MacosAudioNative_nativeGetLastError(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(gEngine.lastError.c_str());
}

}
