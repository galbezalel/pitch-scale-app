#include "soundtouch_c_api.h"
#include "SoundTouch.h"

using namespace soundtouch;

extern "C" {

SoundTouchHandle st_create(void) {
    return reinterpret_cast<SoundTouchHandle>(new SoundTouch());
}

void st_destroy(SoundTouchHandle handle) {
    auto* st = reinterpret_cast<SoundTouch*>(handle);
    delete st;
}

void st_set_sample_rate(SoundTouchHandle handle, uint32_t sample_rate) {
    if (!handle) return;
    reinterpret_cast<SoundTouch*>(handle)->setSampleRate(sample_rate);
}

void st_set_channels(SoundTouchHandle handle, uint32_t num_channels) {
    if (!handle) return;
    reinterpret_cast<SoundTouch*>(handle)->setChannels(num_channels);
}

void st_set_pitch_semitones(SoundTouchHandle handle, float semitones) {
    if (!handle) return;
    reinterpret_cast<SoundTouch*>(handle)->setPitchSemiTones(semitones);
}

void st_put_samples_i16(SoundTouchHandle handle, const int16_t* samples, uint32_t num_samples) {
    if (!handle || !samples || num_samples == 0) return;
    reinterpret_cast<SoundTouch*>(handle)->putSamples(samples, num_samples);
}

uint32_t st_receive_samples_i16(SoundTouchHandle handle, int16_t* out_buffer, uint32_t max_samples) {
    if (!handle || !out_buffer || max_samples == 0) return 0;
    return reinterpret_cast<SoundTouch*>(handle)->receiveSamples(out_buffer, max_samples);
}

void st_flush(SoundTouchHandle handle) {
    if (!handle) return;
    reinterpret_cast<SoundTouch*>(handle)->flush();
}

void st_clear(SoundTouchHandle handle) {
    if (!handle) return;
    reinterpret_cast<SoundTouch*>(handle)->clear();
}

}
